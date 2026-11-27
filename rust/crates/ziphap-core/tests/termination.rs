//! Owner: Claude (Codex assignment A). Terminal failure and frozen completion criteria.
use ziphap_core::*;

fn binding() -> RunBinding {
    RunBinding { run_id: "run-t".into(), thread_id: "thread-t".into(), project_id: None,
        branch_id: "branch-t".into(), scope_id: "scope-t".into(), authorization_revision: Some(1) }
}
fn profile() -> ExecutionProfile {
    ExecutionProfile { profile_id: "p".into(), provider: ExecutionProvider::CodexCli, model_id: "m".into(),
        reasoning: None, max_tool_calls: 32, deadline_ms: 60_000, cloud_authorized: true }
}
fn action(target: &str) -> ActionRequest {
    ActionRequest { tool: "send_message".into(), target_id: target.into(), arguments_digest: "a".repeat(64) }
}
fn live(b: &RunBinding) -> LiveAuthority<'_> { LiveAuthority { binding: b, active: true, cancelled: false } }
fn write(target: &str) -> CompletionRequirement { CompletionRequirement { action: action(target), requires_effect_readback: true } }
fn read(target: &str) -> CompletionRequirement { CompletionRequirement { action: action(target), requires_effect_readback: false } }
fn started(b: &RunBinding) -> RunMachine {
    let mut m = RunMachine::new(b.clone(), profile(), 32, 100).unwrap(); m.start(&live(b), 100).unwrap(); m
}
fn permit(b: &RunBinding, a: &ActionRequest) -> ActionPermit {
    let mut g = ActionGrant::new(b.clone(), "grant-t".into(), vec![a.clone()], 100, 10_000, 1).unwrap();
    g.approve(HostApproval::after_owner_approval(&g, 100).unwrap()).unwrap();
    g.reserve(a, &live(b), 101, |_| true).unwrap()
}
fn verified() -> HostEffectOutcome { HostEffectOutcome::Verified { evidence_digest: "b".repeat(64), readback_digest: "c".repeat(64) } }

#[test]
fn failed_run_stays_failed_after_reconciliation_and_cannot_resume_or_complete() {
    let b = binding(); let mut m = started(&b);
    m.capture_requirements(vec![write("x")], &live(&b), 100).unwrap();
    m.claim_effect("e1".into(), permit(&b, &action("x")), &live(&b), 102, |_| true).unwrap();
    m.fail(FailureCode::PermissionRevoked, 103).unwrap();
    assert_eq!(RunState::UnknownEffect, m.state());
    assert_eq!(Some(FailureCode::PermissionRevoked), m.failure());
    m.reconcile_effect(&b, "e1", HostEffectOutcome::NotApplied, 104).unwrap();
    assert_eq!(RunState::Failed, m.state());
    assert_eq!(Err(CoreError::InvalidTransition), m.resume(&live(&b), 105));
    assert_eq!(Err(CoreError::InvalidTransition), m.complete(&[write("x")], &live(&b), 105));
    assert_eq!(Err(CoreError::InvalidTransition), m.fail(FailureCode::EngineFailure, 106));
    assert_eq!(Some(FailureCode::PermissionRevoked), m.failure());
}

#[test]
fn failed_run_reconciled_as_verified_still_ends_failed() {
    let b = binding(); let mut m = started(&b);
    m.capture_requirements(vec![write("x")], &live(&b), 100).unwrap();
    m.claim_effect("e1".into(), permit(&b, &action("x")), &live(&b), 102, |_| true).unwrap();
    m.fail(FailureCode::TransportFailure, 103).unwrap();
    m.reconcile_effect(&b, "e1", verified(), 104).unwrap();
    assert_eq!(RunState::Failed, m.state());
    assert_eq!(Some(EffectState::Verified), m.effect_state("e1"));
}

#[test]
fn failure_without_uncertain_effects_is_terminal_immediately() {
    let b = binding(); let mut m = started(&b);
    m.fail(FailureCode::EngineFailure, 101).unwrap();
    assert_eq!(RunState::Failed, m.state());
    assert_eq!(Err(CoreError::InvalidTransition), m.capture_requirements(vec![read("y")], &live(&b), 102));
}

#[test]
fn actions_require_frozen_criteria_first() {
    let b = binding(); let mut m = started(&b);
    assert_eq!(Err(CoreError::IncompleteProof), m.record_observation(action("y"), "d".repeat(64), &live(&b), 101));
    assert_eq!(Err(CoreError::IncompleteProof), m.claim_effect("e1".into(), permit(&b, &action("x")), &live(&b), 101, |_| true));
    assert_eq!(0, m.tool_attempts());
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[read("y")], &live(&b), 102));
}

#[test]
fn criteria_cannot_be_changed_after_freezing_or_after_any_action() {
    let b = binding(); let mut m = started(&b);
    assert_eq!(Err(CoreError::IncompleteProof), m.capture_requirements(vec![], &live(&b), 100));
    assert_eq!(Err(CoreError::ConflictingDuplicate), m.capture_requirements(vec![write("x"), write("x")], &live(&b), 100));
    m.capture_requirements(vec![write("x"), read("y")], &live(&b), 100).unwrap();
    // Identical set (any order) is idempotent; any other set is rejected.
    m.capture_requirements(vec![read("y"), write("x")], &live(&b), 101).unwrap();
    assert_eq!(Err(CoreError::InvalidTransition), m.capture_requirements(vec![read("y")], &live(&b), 101));
    assert_eq!(Err(CoreError::InvalidTransition), m.capture_requirements(vec![read("x"), read("y")], &live(&b), 101));
    assert_eq!(Some(&[write("x"), read("y")][..]), m.requirements());
}

#[test]
fn finish_time_cannot_reduce_or_swap_criteria() {
    let b = binding(); let mut m = started(&b);
    m.capture_requirements(vec![write("x"), read("y")], &live(&b), 100).unwrap();
    m.record_observation(action("y"), "d".repeat(64), &live(&b), 101).unwrap();
    // Only the read was done: neither the reduced set nor a downgraded write may complete.
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[read("y")], &live(&b), 102));
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[read("x"), read("y")], &live(&b), 102));
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[write("x"), read("y")], &live(&b), 102));
    assert_eq!(RunState::Running, m.state());
    m.claim_effect("e1".into(), permit(&b, &action("x")), &live(&b), 103, |_| true).unwrap();
    m.record_effect(&b, "e1", verified(), 104).unwrap();
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[write("x"), read("y"), read("z")], &live(&b), 105));
    m.complete(&[read("y"), write("x")], &live(&b), 105).unwrap();
    assert_eq!(RunState::Completed, m.state());
}

#[test]
fn queued_run_may_freeze_criteria_before_start() {
    let b = binding(); let mut m = RunMachine::new(b.clone(), profile(), 8, 100).unwrap();
    m.capture_requirements(vec![read("y")], &live(&b), 100).unwrap();
    m.start(&live(&b), 101).unwrap();
    m.record_observation(action("y"), "d".repeat(64), &live(&b), 102).unwrap();
    m.complete(&[read("y")], &live(&b), 103).unwrap();
    assert_eq!(RunState::Completed, m.state());
}
