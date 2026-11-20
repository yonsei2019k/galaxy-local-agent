use ziphap_core::{
    PhoneObservation, PhoneReceipt, PhoneVerification, can_withhold_public_read,
    phone_completion_proven, phone_public_partial_proven,
};

#[test]
fn partial_sources_require_exact_settled_public_receipts_without_completing_the_run() {
    let search = observation("one", 1, "search_web", PhoneVerification::Verified);
    let mut page = observation("two", 2, "read_webpage", PhoneVerification::Failed);
    page.success = false;
    page.effect_not_applied = true;
    let required = ["search_web".into(), "read_webpage".into()];
    let receipts = [receipt(&search), receipt(&page)];
    let mut observations = [search, page];
    assert!(phone_public_partial_proven(
        &required,
        &receipts,
        &observations
    ));
    assert!(!phone_completion_proven(
        &required,
        &receipts,
        &observations
    ));

    observations[1].effect_not_applied = false;
    assert!(!phone_public_partial_proven(
        &required,
        &receipts,
        &observations
    ));
    observations[1].effect_not_applied = true;
    observations[0].withheld = true;
    assert!(!phone_public_partial_proven(
        &required,
        &receipts,
        &observations
    ));
    observations[0].withheld = false;
    let required = [
        "search_web".into(),
        "read_webpage".into(),
        "prepare_sms".into(),
    ];
    assert!(!phone_public_partial_proven(
        &required,
        &receipts,
        &observations
    ));
}

#[test]
fn withholding_only_continues_a_settled_public_read() {
    assert!(can_withhold_public_read(
        "read_webpage",
        true,
        PhoneVerification::Verified,
        false
    ));
    assert!(can_withhold_public_read(
        "search_web",
        false,
        PhoneVerification::Failed,
        true
    ));
    assert!(!can_withhold_public_read(
        "read_webpage",
        false,
        PhoneVerification::Unverified,
        true
    ));
    assert!(!can_withhold_public_read(
        "search_web",
        false,
        PhoneVerification::Failed,
        false
    ));
    assert!(!can_withhold_public_read(
        "prepare_sms",
        true,
        PhoneVerification::Verified,
        false
    ));
    assert!(!can_withhold_public_read(
        "read_note",
        true,
        PhoneVerification::Verified,
        false
    ));
}

fn observation(
    id: &str,
    sequence: u32,
    tool: &str,
    verification: PhoneVerification,
) -> PhoneObservation {
    PhoneObservation {
        call_id: id.into(),
        sequence,
        tool: tool.into(),
        success: true,
        verification,
        effect_not_applied: false,
        withheld: false,
    }
}

fn receipt(observation: &PhoneObservation) -> PhoneReceipt {
    PhoneReceipt {
        call_id: observation.call_id.clone(),
        sequence: observation.sequence,
        tool: observation.tool.clone(),
    }
}

#[test]
fn settled_retry_can_cover_only_its_own_required_tool() {
    let mut first = observation("one", 1, "read_webpage", PhoneVerification::Verified);
    first.withheld = true;
    let clock = observation(
        "two",
        2,
        "get_current_datetime",
        PhoneVerification::Verified,
    );
    let result = observation("three", 3, "read_webpage", PhoneVerification::Verified);
    let required = ["read_webpage".into(), "get_current_datetime".into()];
    let first_receipts = [receipt(&first), receipt(&clock)];
    let all_receipts = [receipt(&first), receipt(&clock), receipt(&result)];
    let observed = [first, clock, result];
    assert!(!phone_completion_proven(
        &required,
        &first_receipts,
        &observed[..2]
    ));
    assert!(phone_completion_proven(&required, &all_receipts, &observed));
}

#[test]
fn a_screen_handoff_is_distinct_from_a_pending_send() {
    for (tool, expected) in [("open_map", true), ("prepare_sms", false)] {
        let local = observation("one", 1, tool, PhoneVerification::Prepared);
        assert_eq!(
            expected,
            phone_completion_proven(&[tool.into()], &[receipt(&local)], &[local])
        );
    }
}

#[test]
fn rejects_mismatched_receipts_duplicates_and_no_effect_results() {
    let required = ["search_web".into()];
    let mut local = observation("one", 1, "search_web", PhoneVerification::Verified);
    let mut wrong_sequence = receipt(&local);
    wrong_sequence.sequence = 2;
    assert!(!phone_completion_proven(
        &required,
        &[wrong_sequence],
        std::slice::from_ref(&local)
    ));
    let duplicate = observation("one", 2, "search_web", PhoneVerification::Verified);
    assert!(!phone_completion_proven(
        &required,
        &[receipt(&local), receipt(&duplicate)],
        &[
            observation("one", 1, "search_web", PhoneVerification::Verified),
            duplicate
        ],
    ));
    local.effect_not_applied = true;
    assert!(!phone_completion_proven(
        &required,
        &[receipt(&local)],
        &[local]
    ));
}
