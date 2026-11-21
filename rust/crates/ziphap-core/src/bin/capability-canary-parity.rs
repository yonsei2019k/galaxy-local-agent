//! Offline comparison of Android capability canaries. Outputs only case IDs and typed evidence states.
use serde::Serialize;
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};

const MAX_REPORT_BYTES: u64 = 2 * 1024 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
enum Outcome {
    Verified,
    Unverified,
    Failed,
    WaitingOwner,
    Invalid,
    NotRun,
}

#[derive(Debug, Eq, PartialEq, Serialize)]
struct Evidence {
    outcome: Outcome,
    cleanup_confirmed: bool,
}

fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-' || byte == b'_'
        })
}

// The canary prompts, rather than the model's own plan, define the required observations.
fn required_tools(case: &str) -> Option<&'static [(&'static str, usize)]> {
    match case {
        "date" => Some(&[("get_current_datetime", 1)]),
        "contacts" => Some(&[("search_contacts", 1)]),
        "calendar" => Some(&[("list_calendar_events", 1)]),
        "calculator" => Some(&[("screen_action", 5), ("read_screen", 1)]),
        "browser" => Some(&[("open_app_browser_page", 1), ("read_app_browser_page", 1)]),
        _ => None,
    }
}

fn classify(row: &Value) -> Outcome {
    let status = row["status"].as_str();
    let waited = row["waitedForOwner"].as_bool();
    if row.get("error").is_some()
        || row["projectRemoved"].as_bool().is_none()
        || waited.is_none()
        || !matches!(
            status,
            Some("completed" | "paused" | "failed" | "cancelled")
        )
    {
        return Outcome::Invalid;
    }
    if waited == Some(true) {
        return if status == Some("completed") {
            Outcome::Invalid
        } else {
            Outcome::WaitingOwner
        };
    }
    if status != Some("completed") {
        return Outcome::Failed;
    }
    let Some(steps) = row["steps"].as_array() else {
        return Outcome::Invalid;
    };
    let Some(required) = row["case"].as_str().and_then(required_tools) else {
        return Outcome::Invalid;
    };
    let mut verified = BTreeMap::new();
    for step in steps {
        let Some(tool) = step["tool"].as_str().filter(|name| identifier(name)) else {
            return Outcome::Unverified;
        };
        if step["state"] != "VERIFIED" {
            return Outcome::Unverified;
        }
        *verified.entry(tool).or_insert(0usize) += 1;
    }
    if required
        .iter()
        .all(|(tool, count)| verified.get(tool).is_some_and(|actual| actual >= count))
    {
        Outcome::Verified
    } else {
        Outcome::Unverified
    }
}

fn load(reader: impl Read) -> Result<BTreeMap<String, Evidence>, &'static str> {
    let mut bytes = Vec::new();
    reader
        .take(MAX_REPORT_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "could not read report")?;
    if bytes.len() as u64 > MAX_REPORT_BYTES {
        return Err("report exceeds size limit");
    }
    let rows: Vec<Value> = serde_json::from_slice(&bytes).map_err(|_| "invalid JSON report")?;
    let mut cases = BTreeMap::new();
    for row in rows {
        let case = row["case"]
            .as_str()
            .filter(|id| identifier(id))
            .ok_or("invalid case ID")?;
        let evidence = Evidence {
            outcome: classify(&row),
            cleanup_confirmed: row["projectRemoved"] == true,
        };
        if cases.insert(case.to_owned(), evidence).is_some() {
            return Err("duplicate case ID");
        }
    }
    Ok(cases)
}

#[derive(Serialize)]
struct Comparison<'a> {
    case: &'a str,
    baseline: Option<Outcome>,
    candidate: Outcome,
    cleanup_confirmed: bool,
    known_gap_matched: bool,
    passed: bool,
}

fn compare<'a>(
    case: &'a str,
    baseline: Option<&Evidence>,
    candidate: Option<&Evidence>,
    declared_gap: bool,
) -> Comparison<'a> {
    let previous = baseline.map(|evidence| evidence.outcome);
    let current = candidate.map_or(Outcome::NotRun, |evidence| evidence.outcome);
    let cleanup_confirmed = candidate.is_some_and(|evidence| evidence.cleanup_confirmed);
    let known_gap_matched = declared_gap
        && baseline.is_some_and(|evidence| evidence.cleanup_confirmed)
        && previous == Some(current)
        && matches!(current, Outcome::Failed | Outcome::Unverified);
    Comparison {
        case,
        baseline: previous,
        candidate: current,
        cleanup_confirmed,
        known_gap_matched,
        passed: cleanup_confirmed && (current == Outcome::Verified || known_gap_matched),
    }
}

#[derive(Serialize)]
struct Report<'a> {
    schema_version: u32,
    kind: &'static str,
    passed: bool,
    cases: Vec<Comparison<'a>>,
}

fn requested_cases(value: &str) -> Result<BTreeSet<&str>, &'static str> {
    let mut cases = BTreeSet::new();
    for id in value.split(',') {
        if required_tools(id).is_none() || !cases.insert(id) {
            return Err("invalid or repeated expected case ID");
        }
    }
    Ok(cases)
}

fn run(args: &[String]) -> Result<bool, &'static str> {
    let [candidate_path, baseline_path, cases, output_path, remaining @ ..] = args else {
        return Err("usage: capability-canary-parity CANDIDATE BASELINE_OR_- EXPECTED_CASES OUTPUT [KNOWN_GAPS]");
    };
    let gaps = match remaining {
        [] => BTreeSet::new(),
        [gaps] => requested_cases(gaps)?,
        _ => return Err("only one known-gap list is supported"),
    };
    let expected = requested_cases(cases)?;
    if !gaps.is_subset(&expected) {
        return Err("known gaps must be among expected cases");
    }
    let candidate = load(File::open(candidate_path).map_err(|_| "could not open candidate")?)?;
    let baseline = if baseline_path == "-" {
        BTreeMap::new()
    } else {
        load(File::open(baseline_path).map_err(|_| "could not open baseline")?)?
    };
    let rows: Vec<_> = expected
        .iter()
        .map(|&case| {
            compare(
                case,
                baseline.get(case),
                candidate.get(case),
                gaps.contains(case),
            )
        })
        .collect();
    let passed = rows.iter().all(|case| case.passed);
    let report = Report {
        schema_version: 1,
        kind: "capability-canary-parity",
        passed,
        cases: rows,
    };
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(output_path)
        .map_err(|_| "could not create a new output report")?;
    serde_json::to_writer_pretty(&mut output, &report).map_err(|_| "could not write report")?;
    output
        .write_all(b"\n")
        .map_err(|_| "could not finish report")?;
    Ok(passed)
}

fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    std::process::exit(match run(&args) {
        Ok(true) => 0,
        Ok(false) => 1,
        Err(error) => {
            eprintln!("{error}");
            2
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn completed() -> Value {
        json!({
            "case": "calendar", "status": "completed", "waitedForOwner": false,
            "projectRemoved": true, "planTools": ["list_calendar_events"],
            "steps": [{"tool": "list_calendar_events", "state": "VERIFIED", "summary": "private"}],
            "answer": "private", "receipt": "private"
        })
    }

    #[test]
    fn requires_host_defined_tool_even_if_model_claims_success() {
        let mut row = completed();
        assert_eq!(classify(&row), Outcome::Verified);
        row["planTools"] = Value::Null;
        assert_eq!(classify(&row), Outcome::Verified);
        row["planTools"] = json!(["get_current_datetime"]);
        row["steps"][0]["tool"] = json!("get_current_datetime");
        assert_eq!(classify(&row), Outcome::Unverified);
        row["steps"][0]["tool"] = Value::Null;
        assert_eq!(classify(&row), Outcome::Unverified);
    }

    #[test]
    fn owner_wait_is_not_success_and_cannot_coexist_with_completion() {
        let mut row = completed();
        row["waitedForOwner"] = json!(true);
        assert_eq!(classify(&row), Outcome::Invalid);
        row["status"] = json!("cancelled");
        assert_eq!(classify(&row), Outcome::WaitingOwner);
        let pending = Evidence {
            outcome: Outcome::WaitingOwner,
            cleanup_confirmed: true,
        };
        assert!(!compare("calendar", None, Some(&pending), false).passed);
        assert!(!compare("calendar", None, Some(&pending), true).passed);
        assert!(!compare("calendar", Some(&pending), Some(&pending), true).passed);
    }

    #[test]
    fn requires_all_expected_calculator_interactions() {
        let mut row = completed();
        row["case"] = json!("calculator");
        let steps = row["steps"].as_array_mut().unwrap();
        steps.clear();
        steps.push(json!({"tool": "read_screen", "state": "VERIFIED"}));
        for _ in 0..4 {
            steps.push(json!({"tool": "screen_action", "state": "VERIFIED"}));
        }
        assert_eq!(classify(&row), Outcome::Unverified);
        row["steps"]
            .as_array_mut()
            .unwrap()
            .push(json!({"tool": "screen_action", "state": "VERIFIED"}));
        assert_eq!(classify(&row), Outcome::Verified);
    }

    #[test]
    fn exclusions_cannot_hide_regressions_missing_cases_or_failed_cleanup() {
        let verified = Evidence {
            outcome: Outcome::Verified,
            cleanup_confirmed: true,
        };
        let failed = Evidence {
            outcome: Outcome::Failed,
            cleanup_confirmed: true,
        };
        let no_cleanup = Evidence {
            outcome: Outcome::Failed,
            cleanup_confirmed: false,
        };
        assert!(!compare("calendar", Some(&verified), Some(&failed), true).passed);
        assert!(!compare("calendar", Some(&failed), None, true).passed);
        assert!(!compare("calendar", Some(&failed), Some(&no_cleanup), true).passed);
        assert!(!compare("calendar", Some(&failed), Some(&failed), false).passed);
        assert!(compare("calendar", Some(&failed), Some(&failed), true).passed);
    }

    #[test]
    fn ignores_private_payloads_and_rejects_duplicate_ids() {
        let row = completed();
        let bytes = serde_json::to_vec(&json!([row])).unwrap();
        let evidence = load(bytes.as_slice()).unwrap();
        let comparison = compare("calendar", None, evidence.get("calendar"), false);
        assert!(!serde_json::to_string(&comparison)
            .unwrap()
            .contains("private"));
        let duplicate = serde_json::to_vec(&json!([row, row])).unwrap();
        assert!(load(duplicate.as_slice()).is_err());
        assert!(requested_cases("calendar,calendar").is_err());
    }
}
