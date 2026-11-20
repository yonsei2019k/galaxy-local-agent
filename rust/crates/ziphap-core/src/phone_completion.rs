use std::collections::BTreeSet;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PhoneVerification {
    Verified,
    Prepared,
    Unverified,
    Failed,
}

pub struct PhoneObservation {
    pub call_id: String,
    pub sequence: u32,
    pub tool: String,
    pub success: bool,
    pub verification: PhoneVerification,
    pub effect_not_applied: bool,
    pub withheld: bool,
}

pub struct PhoneReceipt {
    pub call_id: String,
    pub sequence: u32,
    pub tool: String,
}

/// Only a settled public lookup can continue with its payload withheld.
pub fn can_withhold_public_read(
    tool: &str,
    success: bool,
    verification: PhoneVerification,
    effect_not_applied: bool,
) -> bool {
    is_public_web_read(tool)
        && match verification {
            PhoneVerification::Verified => success,
            PhoneVerification::Failed => !success && effect_not_applied,
            PhoneVerification::Prepared | PhoneVerification::Unverified => false,
        }
}

fn is_public_web_read(tool: &str) -> bool {
    matches!(tool, "search_web" | "read_webpage" | "search_shopping")
}

pub fn opens_phone_screen(tool: &str) -> bool {
    matches!(
        tool,
        "open_tmap"
            | "open_tmap_destination"
            | "open_netflix"
            | "search_netflix"
            | "open_netflix_title"
            | "open_coupang"
            | "search_coupang"
            | "open_coupang_product"
            | "open_coupang_eats"
            | "open_map"
            | "open_url"
    )
}

/// Compare settled receipts with trusted phone observations. No model or result text enters this check.
pub fn phone_completion_proven(
    required_tools: &[String],
    reported: &[PhoneReceipt],
    observed: &[PhoneObservation],
) -> bool {
    phone_evidence_matches(required_tools, reported, observed, false)
}

/// Partial public evidence never widens the plan or permits completion.
pub fn phone_public_partial_proven(
    required_tools: &[String],
    reported: &[PhoneReceipt],
    observed: &[PhoneObservation],
) -> bool {
    phone_evidence_matches(required_tools, reported, observed, true)
}

fn phone_evidence_matches(
    required_tools: &[String],
    reported: &[PhoneReceipt],
    observed: &[PhoneObservation],
    public_only: bool,
) -> bool {
    if reported.len() != observed.len() || observed.len() > 128 || required_tools.len() > 128 {
        return false;
    }
    let required: BTreeSet<_> = required_tools.iter().map(String::as_str).collect();
    if required.len() != required_tools.len()
        || public_only && !required.iter().all(|tool| is_public_web_read(tool))
    {
        return false;
    }
    let mut unique_calls = BTreeSet::new();
    let mut proven = BTreeSet::new();
    for (receipt, observation) in reported.iter().zip(observed) {
        let tool = observation.tool.as_str();
        if receipt.call_id != observation.call_id
            || receipt.sequence != observation.sequence
            || receipt.tool != observation.tool
            || !unique_calls.insert(observation.call_id.as_str())
            || !required.contains(tool)
            || public_only
                && !can_withhold_public_read(
                    tool,
                    observation.success,
                    observation.verification,
                    observation.effect_not_applied,
                )
        {
            return false;
        }
        if observation.success
            && !observation.effect_not_applied
            && !observation.withheld
            && (observation.verification == PhoneVerification::Verified
                || observation.verification == PhoneVerification::Prepared
                    && opens_phone_screen(tool))
        {
            proven.insert(tool);
        }
    }
    if public_only {
        !proven.is_empty()
    } else {
        required == proven
    }
}
