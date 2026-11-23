//! Local development entry point for a bounded patch to the actual Android media evaluator.
//! Only the generated Rust is compiled. Requests carry neither Rust text nor runtime authority.
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::{ExitCode, Stdio};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::process::Command;

const SOURCE: &str = include_str!("../../../ziphap-android/src/media_rule.rs");
const FALLBACK: &str = "            _ => return Err(()),";
const MAX_PROPOSAL: u64 = 1024;

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Fact {
    Playing,
    Ended,
    Paused,
    Buffering,
    Seeking,
    Advancing,
    DurationKnown,
}

impl Fact {
    fn field(self) -> &'static str {
        match self {
            Self::Playing => "playing",
            Self::Ended => "ended",
            Self::Paused => "paused",
            Self::Buffering => "buffering",
            Self::Seeking => "seeking",
            Self::Advancing => "advancing",
            Self::DurationKnown => "duration_known",
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Proposal {
    base_sha256: String,
    alias: String,
    fact: Fact,
}

fn digest(source: &str) -> String {
    format!("{:x}", Sha256::digest(source.as_bytes()))
}

fn candidate(proposal: &Proposal) -> Result<String, &'static str> {
    if proposal.base_sha256 != digest(SOURCE) {
        return Err("STALE_BASE");
    }
    let alias = &proposal.alias;
    if alias.len() > 40
        || !alias.starts_with(|ch: char| ch.is_ascii_lowercase())
        || !alias.bytes().all(|b| b.is_ascii_lowercase() || b == b'_')
        || matches!(alias.as_str(), "true" | "false" | "null" | "unknown")
    {
        return Err("INVALID_ALIAS");
    }
    let field = proposal.fact.field();
    if SOURCE.matches(FALLBACK).count() != 1
        || !SOURCE.contains(&format!("\"{field}\" => self.facts.{field},"))
    {
        return Err("SOURCE_LAYOUT_CHANGED");
    }
    Ok(SOURCE.replacen(
        FALLBACK,
        &format!("            \"{alias}\" => self.facts.{field},\n{FALLBACK}"),
        1,
    ))
}

fn detached(program: &Path) -> Command {
    let mut command = Command::new(program);
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    // Keep only toolchain/platform discovery variables. The trial has no provider or device credentials.
    command.env_clear();
    for name in [
        "PATH",
        "SystemRoot",
        "WINDIR",
        "TEMP",
        "TMP",
        "TMPDIR",
        "HOME",
        "USERPROFILE",
        "RUSTUP_HOME",
        "LIB",
        "LIBPATH",
        "INCLUDE",
        "ProgramFiles",
        "ProgramFiles(x86)",
        "ProgramData",
        "LOCALAPPDATA",
    ] {
        if let Some(value) = std::env::var_os(name) {
            command.env(name, value);
        }
    }
    command
}

async fn verify(
    rustc: &Path,
    work: &Path,
    source: &str,
    proposal: &Proposal,
    baseline: bool,
) -> Result<(), &'static str> {
    // Remove only the binding attributes for a standalone host build of the evaluator.
    let evaluator = source
        .replace(", uniffi::Enum", "")
        .replace(", uniffi::Record", "")
        .replace("#[uniffi::export]", "");
    let field = proposal.fact.field();
    let alias = &proposal.alias;
    let expectation = if baseline {
        "assert_eq!(observed, NativeMediaRuleResult::Invalid);"
    } else {
        "assert_eq!(observed, native_evaluate_media_rule(canonical.into(), fresh_facts()));"
    };
    let trial = format!(
        r#"
#[test]
fn observed_condition() {{
    for value in [Some(true), Some(false), None] {{
        let fresh_facts = || NativeMediaFacts {{ {field}: value, ..Default::default() }};
        for (rule, canonical) in [("{alias}", "{field}"), ("!{alias}", "!{field}")] {{
            let _ = canonical;
            let observed = native_evaluate_media_rule(rule.into(), fresh_facts());
            {expectation}
        }}
    }}
}}
"#
    );
    fs::write(work.join("media_rule.rs"), source).map_err(|_| "ARTIFACT_WRITE")?;
    fs::write(work.join("trial.rs"), evaluator + &trial).map_err(|_| "ARTIFACT_WRITE")?;
    let binary = work.join(format!("trial{}", std::env::consts::EXE_SUFFIX));
    let build_log = fs::File::create(work.join("compiler.log")).map_err(|_| "ARTIFACT_WRITE")?;
    let mut command = detached(rustc);
    command
        .current_dir(work)
        .stderr(build_log)
        .args([
            "--edition=2021",
            "--test",
            "-D",
            "warnings",
            "trial.rs",
            "-o",
        ])
        .arg(&binary);
    let compiled = ziphap_pc::wait_owned_command(&mut command, Duration::from_secs(30))
        .await
        .map_err(|_| "COMPILER_PROCESS")?
        .ok_or("COMPILER_TIMEOUT")?;
    if !compiled.success() {
        return Err("COMPILE_FAILED");
    }
    let test_log = fs::File::create(work.join("test.log")).map_err(|_| "ARTIFACT_WRITE")?;
    let mut command = detached(&binary);
    command.current_dir(work).stdout(test_log);
    let checked = ziphap_pc::wait_owned_command(&mut command, Duration::from_secs(10))
        .await
        .map_err(|_| "TRIAL_PROCESS")?
        .ok_or("TRIAL_TIMEOUT")?;
    if !checked.success() {
        return Err(if baseline {
            "BASELINE_NOT_REPRODUCED"
        } else {
            "CANDIDATE_FAILED"
        });
    }
    Ok(())
}

async fn run() -> Result<ExitCode, &'static str> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.as_slice() == ["--base"] {
        println!("{}", json!({"base_sha256": digest(SOURCE)}));
        return Ok(ExitCode::SUCCESS);
    }
    let [compiler, root, expected_alias, expected_fact] = args.as_slice() else {
        return Err(
            "USAGE: ziphap-verify-media-repair [--base | RUSTC ARTIFACT_DIRECTORY EXPECTED_ALIAS EXPECTED_FACT]",
        );
    };
    let rustc = PathBuf::from(compiler)
        .canonicalize()
        .map_err(|_| "COMPILER_PATH")?;
    let mut input = Vec::new();
    io::stdin()
        .take(MAX_PROPOSAL + 1)
        .read_to_end(&mut input)
        .map_err(|_| "PROPOSAL_READ")?;
    if input.len() as u64 > MAX_PROPOSAL {
        return Err("PROPOSAL_BOUND");
    }
    let proposal: Proposal = ziphap_pc::strict_json(&input)
        .ok()
        .and_then(|value| serde_json::from_value(value).ok())
        .ok_or("INVALID_PROPOSAL")?;
    let patched = candidate(&proposal)?;
    // The reproduction fixture supplies the expected behavior separately from the model.
    if expected_alias.to_str() != Some(proposal.alias.as_str())
        || expected_fact.to_str() != Some(proposal.fact.field())
    {
        return Err("FIXTURE_MISMATCH");
    }
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "CLOCK")?
        .as_nanos();
    let workspace_id = format!("media-repair-{}-{nonce}", std::process::id());
    let workspace = PathBuf::from(root)
        .canonicalize()
        .map_err(|_| "ARTIFACT_DIRECTORY")?
        .join(&workspace_id);
    let baseline = workspace.join("baseline");
    let repaired = workspace.join("candidate");
    for directory in [&workspace, &baseline, &repaired] {
        fs::create_dir(directory).map_err(|_| "ARTIFACT_DIRECTORY")?;
    }
    let outcome = match verify(&rustc, &baseline, SOURCE, &proposal, true).await {
        Err(error) => Err(("baseline", error)),
        Ok(()) => verify(&rustc, &repaired, &patched, &proposal, false)
            .await
            .map_err(|error| ("candidate", error)),
    };
    if let Err((phase, error)) = outcome {
        let failure = json!({
            "status": "failed", "error": error, "phase": phase, "workspace_id": workspace_id,
            "base_sha256": proposal.base_sha256, "candidate_sha256": digest(&patched)
        });
        fs::write(workspace.join("receipt.json"), failure.to_string())
            .map_err(|_| "ARTIFACT_WRITE")?;
        eprintln!("{failure}");
        return Ok(ExitCode::FAILURE);
    }
    let receipt = json!({
        "status": "verified_trial",
        "base_sha256": proposal.base_sha256,
        "candidate_sha256": digest(&patched),
        "alias": proposal.alias,
        "fact": proposal.fact.field(),
        "baseline_reproduced": true,
        "candidate_verified": true,
        "device_verified": false
    });
    fs::write(workspace.join("receipt.json"), receipt.to_string()).map_err(|_| "ARTIFACT_WRITE")?;
    println!(
        "{}",
        json!({"receipt": receipt, "workspace_id": workspace_id})
    );
    Ok(ExitCode::SUCCESS)
}

#[tokio::main]
async fn main() -> ExitCode {
    match run().await {
        Ok(code) => code,
        Err(error) => {
            eprintln!("{}", json!({"status": "failed", "error": error}));
            ExitCode::FAILURE
        }
    }
}
