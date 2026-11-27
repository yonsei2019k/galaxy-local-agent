use ziphap_core::*;

fn binding(id: &str) -> RunBinding {
    RunBinding { run_id: id.into(), thread_id: "thread-a".into(), project_id: Some("project-a".into()),
        branch_id: "branch-a".into(), scope_id: "project:project-a".into(), authorization_revision: Some(3) }
}
fn profile() -> ExecutionProfile {
    ExecutionProfile { profile_id: "cloud-fast".into(), provider: ExecutionProvider::CodexCli,
        model_id: "configured-model".into(), reasoning: Some(ReasoningEffort::Low), max_tool_calls: 32,
        deadline_ms: 60_000, cloud_authorized: true }
}
fn action(target: &str) -> ActionRequest {
    ActionRequest { tool: "save_note".into(), target_id: target.into(), arguments_digest: "a".repeat(64) }
}
fn live(binding: &RunBinding) -> LiveAuthority<'_> { LiveAuthority { binding, active: true, cancelled: false } }
fn grant(binding: &RunBinding, action: &ActionRequest, maximum: u16) -> ActionGrant {
    let mut grant = ActionGrant::new(binding.clone(), "grant-a".into(), vec![action.clone()], 100, 10_000, maximum).unwrap();
    grant.approve(HostApproval::after_owner_approval(&grant, 100).unwrap()).unwrap();
    grant
}
fn machine(binding: &RunBinding) -> RunMachine {
    let mut machine = RunMachine::new(binding.clone(), profile(), 16, 100).unwrap();
    machine.start(&live(binding), 100).unwrap();
    // The host freezes the owner-task criteria before any action (default: verified write of note-a).
    machine.capture_requirements(vec![CompletionRequirement { action: action("note-a"), requires_effect_readback: true }], &live(binding), 100).unwrap();
    machine
}
fn permit(binding: &RunBinding, action: &ActionRequest) -> ActionPermit {
    grant(binding, action, 1).reserve(action, &live(binding), 101, |_| true).unwrap()
}
fn verified() -> HostEffectOutcome {
    HostEffectOutcome::Verified { evidence_digest: "b".repeat(64), readback_digest: "c".repeat(64) }
}

#[test]
fn wire_contracts_reject_unknown_duplicate_and_cloud_without_capture() {
    let json = serde_json::to_string(&profile()).unwrap();
    assert_eq!(profile(), ExecutionProfile::from_json(&json).unwrap());
    let unknown = json.replacen('{', "{\"apiKey\":\"never-accepted\",", 1);
    assert_eq!(Err(CoreError::InvalidJson), ExecutionProfile::from_json(&unknown));
    let duplicate = json.replacen('{', "{\"profileId\":\"other\",", 1);
    assert_eq!(Err(CoreError::InvalidJson), ExecutionProfile::from_json(&duplicate));
    let mut no_capture = profile(); no_capture.cloud_authorized = false;
    assert_eq!(Err(CoreError::InvalidProfile), no_capture.validate());
    let mut invalid = binding("run-a"); invalid.scope_id = "".into();
    assert_eq!(Err(CoreError::InvalidBinding), invalid.validate());
    let binding_json = serde_json::to_string(&binding("run-a")).unwrap();
    assert!(RunBinding::from_json(&binding_json.replacen('{', "{\"permission\":true,", 1)).is_err());
}

#[test]
fn action_grants_require_exact_host_approval_and_actual_permission() {
    let b = binding("run-a"); let a = action("note-a");
    let mut g = ActionGrant::new(b.clone(), "grant-a".into(), vec![a.clone()], 100, 1000, 1).unwrap();
    assert!(matches!(g.reserve(&a, &live(&b), 101, |_| true), Err(CoreError::ApprovalMissing)));
    let other = ActionGrant::new(b.clone(), "different-grant".into(), vec![a.clone()], 100, 1000, 1).unwrap();
    assert_eq!(Err(CoreError::GrantMismatch), g.approve(HostApproval::after_owner_approval(&other, 100).unwrap()));
    g.approve(HostApproval::after_owner_approval(&g, 100).unwrap()).unwrap();
    assert!(matches!(g.reserve(&a, &live(&b), 101, |_| false), Err(CoreError::PermissionDenied)));
    assert_eq!(1, g.remaining());
    let mut changed = a.clone(); changed.arguments_digest = "d".repeat(64);
    assert!(matches!(g.reserve(&changed, &live(&b), 101, |_| true), Err(CoreError::GrantMismatch)));
    assert!(matches!(g.reserve(&action("note-b"), &live(&b), 101, |_| true), Err(CoreError::GrantMismatch)));
    assert!(g.reserve(&a, &live(&b), 102, |_| true).is_ok());
    assert!(matches!(g.reserve(&a, &live(&b), 103, |_| true), Err(CoreError::GrantExhausted)));
}

#[test]
fn expiry_revision_scope_and_permission_are_rechecked_after_approval() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    let p = permit(&b, &a);
    assert_eq!(Err(CoreError::PermissionDenied), m.claim_effect("effect-a".into(), p, &live(&b), 102, |_| false));
    assert_eq!(None, m.effect_state("effect-a"));
    let mut changed = b.clone(); changed.authorization_revision = Some(4);
    assert!(matches!(grant(&b, &a, 1).reserve(&a, &live(&changed), 102, |_| true), Err(CoreError::AuthorityChanged)));
    let mut other_project = b.clone(); other_project.project_id = Some("project-b".into());
    assert_eq!(Err(CoreError::AuthorityChanged), m.claim_effect("effect-a".into(), permit(&b, &a), &live(&other_project), 102, |_| true));
    assert_eq!(Err(CoreError::GrantExpired), m.claim_effect("effect-a".into(), permit(&b, &a), &live(&b), 10_000, |_| true));
    assert_eq!(0, m.tool_attempts());
}

#[test]
fn revoking_grant_also_revokes_already_reserved_unused_permits() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    let mut g = grant(&b, &a, 1);
    let p = g.reserve(&a, &live(&b), 101, |_| true).unwrap();
    let late_receipt = HostApproval::after_owner_approval(&g, 101).unwrap();
    g.revoke();
    assert_eq!(Err(CoreError::ApprovalMissing), g.approve(late_receipt));
    assert_eq!(Err(CoreError::ApprovalMissing), m.claim_effect("effect-a".into(), p, &live(&b), 102, |_| true));
    assert_eq!(None, m.effect_state("effect-a"));
}

#[test]
fn reused_grant_label_cannot_move_approval_to_different_actions_or_limits() {
    let b = binding("run-a"); let a = action("note-a");
    let first = ActionGrant::new(b.clone(), "same-label".into(), vec![a], 100, 1000, 1).unwrap();
    let receipt = HostApproval::after_owner_approval(&first, 101).unwrap();
    let mut replacement = ActionGrant::new(b, "same-label".into(), vec![action("other-note")], 100, 2000, 2).unwrap();
    assert_eq!(Err(CoreError::GrantMismatch), replacement.approve(receipt));
}

#[test]
fn cancelling_a_queued_run_while_unknown_receipts_fill_storage_never_dispatches_it() {
    let mut s = RunScheduler::new(SchedulerLimits { max_active:1,max_queued:3,max_terminal:1,terminal_ttl_ms:10000 }).unwrap();
    let a=binding("a"); let b=binding("b"); let c=binding("c");
    assert_eq!(Admission::Started,s.admit(a.clone(),100).unwrap());
    assert_eq!(Admission::Queued,s.admit(b.clone(),101).unwrap());
    assert_eq!(Admission::Queued,s.admit(c.clone(),102).unwrap());
    assert_eq!(None,s.finish(&a,RunState::UnknownEffect,103).unwrap());
    s.cancel_queued(&c,104).unwrap();
    assert_eq!(RunState::Cancelled,s.terminal("c").unwrap().state);
    s.reconcile_terminal(&a,RunState::Failed,105).unwrap();
    let d=binding("d"); assert_eq!(Admission::Started,s.admit(d.clone(),106).unwrap());
    assert_eq!(Some(b.clone()),s.finish(&d,RunState::Completed,107).unwrap());
    assert_eq!(None,s.finish(&b,RunState::Completed,108).unwrap());
    assert_eq!(Admission::AlreadyTerminal,s.admit(c,109).unwrap());
}

#[test]
fn cancelled_inflight_effect_is_unknown_and_never_replays() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    m.claim_effect("effect-a".into(), permit(&b, &a), &live(&b), 102, |_| true).unwrap();
    m.cancel(103).unwrap();
    assert_eq!(RunState::UnknownEffect, m.state()); assert_eq!(Some(EffectState::Unknown), m.effect_state("effect-a"));
    assert_eq!(Err(CoreError::Cancelled), m.claim_effect("effect-b".into(), permit(&b, &a), &live(&b), 104, |_| true));
    let requirement = CompletionRequirement { action: a, requires_effect_readback: true };
    assert_eq!(Err(CoreError::Cancelled), m.complete(&[requirement], &live(&b), 104));
    m.reconcile_effect(&b, "effect-a", verified(), 105).unwrap();
    assert_eq!(RunState::Cancelled, m.state());
    assert_eq!(Some(EffectState::Verified), m.effect_state("effect-a"));
}

#[test]
fn unknown_effect_blocks_other_changes_until_explicit_reconciliation() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    m.claim_effect("effect-a".into(), permit(&b, &a), &live(&b), 102, |_| true).unwrap();
    m.record_effect(&b, "effect-a", HostEffectOutcome::Unknown, 103).unwrap();
    assert_eq!(Err(CoreError::InvalidTransition), m.resume(&live(&b), 104));
    assert!(m.claim_effect("effect-b".into(), permit(&b, &a), &live(&b), 104, |_| true).is_err());
    assert_eq!(Err(CoreError::InvalidTransition), m.record_effect(&b, "effect-a", verified(), 104));
    m.reconcile_effect(&b, "effect-a", verified(), 105).unwrap();
    assert_eq!(RunState::Paused, m.state()); m.resume(&live(&b), 106).unwrap();
    assert_eq!(Err(CoreError::EffectReplayBlocked), m.claim_effect("new-effect-id".into(), permit(&b, &a), &live(&b), 107, |_| true));
}

#[test]
fn confirmed_no_effect_can_be_retried_with_fresh_exact_approval() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    m.claim_effect("effect-a".into(), permit(&b, &a), &live(&b), 102, |_| true).unwrap();
    m.record_effect(&b, "effect-a", HostEffectOutcome::NotApplied, 103).unwrap();
    m.claim_effect("effect-b".into(), permit(&b, &a), &live(&b), 104, |_| true).unwrap();
    m.record_effect(&b, "effect-b", verified(), 105).unwrap();
    m.complete(&[CompletionRequirement { action: a, requires_effect_readback: true }], &live(&b), 106).unwrap();
    assert_eq!(RunState::Completed, m.state());
}

#[test]
fn actual_applied_is_not_completed_without_same_action_readback() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    let requirement = CompletionRequirement { action: a.clone(), requires_effect_readback: true };
    m.record_observation(a.clone(), "f".repeat(64), &live(&b), 101).unwrap();
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[requirement.clone()], &live(&b), 102));
    m.claim_effect("effect-a".into(), permit(&b, &a), &live(&b), 103, |_| true).unwrap();
    m.record_effect(&b, "effect-a", HostEffectOutcome::Applied { evidence_digest: "b".repeat(64) }, 104).unwrap();
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[requirement.clone()], &live(&b), 105));
    m.record_effect(&b, "effect-a", verified(), 106).unwrap();
    let mut wrong = requirement.clone(); wrong.action.target_id = "note-b".into();
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[wrong], &live(&b), 107));
    m.complete(&[requirement], &live(&b), 108).unwrap();
    assert_eq!(RunState::Completed, m.state());
}

#[test]
fn a_model_answer_or_journal_event_is_not_completion_evidence() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[], &live(&b), 101));
    assert_eq!(Err(CoreError::IncompleteProof), m.complete(&[CompletionRequirement { action: a, requires_effect_readback: false }], &live(&b), 102));
    assert_eq!(RunState::Running, m.state());
    let mut journal = RunJournal::new(b, 2).unwrap(); journal.append(EventKind::Completed, 102).unwrap();
    assert_eq!(RunState::Running, m.state());
}

#[test]
fn revoked_authority_does_not_erase_actual_effect_but_cannot_complete() {
    let b = binding("run-a"); let a = action("note-a"); let mut m = machine(&b);
    m.claim_effect("effect-a".into(), permit(&b, &a), &live(&b), 102, |_| true).unwrap();
    let mut revoked = b.clone(); revoked.authorization_revision = Some(4);
    m.record_effect(&b, "effect-a", verified(), 103).unwrap();
    assert_eq!(Some(EffectState::Verified), m.effect_state("effect-a"));
    assert_eq!(Err(CoreError::AuthorityChanged), m.complete(&[CompletionRequirement { action: a, requires_effect_readback: true }], &live(&revoked), 104));
}

#[test]
fn deadlines_and_tool_budgets_include_read_observations() {
    let b = binding("run-a"); let mut p = profile(); p.max_tool_calls = 1; p.deadline_ms = 1000;
    let mut m = RunMachine::new(b.clone(), p, 4, 100).unwrap(); m.start(&live(&b), 100).unwrap();
    m.capture_requirements(vec![CompletionRequirement { action: action("note-a"), requires_effect_readback: false }], &live(&b), 100).unwrap();
    m.record_observation(action("note-a"), "b".repeat(64), &live(&b), 101).unwrap();
    assert_eq!(Err(CoreError::CapacityReached), m.record_observation(action("note-b"), "b".repeat(64), &live(&b), 102));
    assert_eq!(Err(CoreError::DeadlineExceeded), m.complete(&[CompletionRequirement { action: action("note-a"), requires_effect_readback: false }], &live(&b), 1100));
}

#[test]
fn journal_cursors_duplicate_and_gap_checks_are_bounded() {
    let mut journal = RunJournal::new(binding("run-a"), 3).unwrap();
    let first = journal.append(EventKind::Created, 100).unwrap();
    assert!(!journal.accept(first.clone()).unwrap());
    let mut conflicting = first; conflicting.kind = EventKind::Started;
    assert_eq!(Err(CoreError::ConflictingDuplicate), journal.accept(conflicting));
    assert_eq!(Err(CoreError::SequenceGap), journal.accept(RunEvent { sequence: 3, timestamp_ms: 101, kind: EventKind::Started }));
    journal.append(EventKind::Started, 99).unwrap(); // Host timestamps cannot move backwards.
    journal.append(EventKind::Paused { reason: PauseReason::OwnerInput }, 102).unwrap();
    journal.append(EventKind::Resumed, 103).unwrap();
    assert_eq!(2, journal.first_retained_sequence());
    assert_eq!(Err(CoreError::CursorExpired), journal.replay(0, 2));
    assert_eq!(Err(CoreError::CursorAhead), journal.replay(5, 2));
    let page = journal.replay(1, 2).unwrap(); assert_eq!(vec![2, 3], page.events.iter().map(|e| e.sequence).collect::<Vec<_>>()); assert!(page.has_more);
    assert_eq!(100, page.events[0].timestamp_ms);
    assert!(journal.replay(4, 3).unwrap().events.is_empty());
}

#[test]
fn event_wire_never_accepts_or_emits_raw_payload_fields() {
    assert!(serde_json::from_str::<RunEvent>(r#"{"sequence":1,"timestampMs":1,"kind":{"type":"created","request":"private"}}"#).is_err());
    let event = RunEvent { sequence: 1, timestamp_ms: 1, kind: EventKind::Observation { tool: "read_note".into() } };
    let wire = serde_json::to_string(&event).unwrap();
    assert!(!wire.contains("arguments")); assert!(!wire.contains("body")); assert!(!wire.contains("token"));
    assert!(!format!("{:?}", action("secret-target")).contains("secret-target"));
    assert!(!format!("{:?}", binding("secret-run")).contains("secret-run"));
}

#[test]
fn scheduler_finishes_160_sequential_requests_without_capacity_leak() {
    let mut scheduler = RunScheduler::new(SchedulerLimits { max_active: 1, max_queued: 2, max_terminal: 16, terminal_ttl_ms: 60_000 }).unwrap();
    for i in 0..160 {
        let b = binding(&format!("run-{i}"));
        assert_eq!(Admission::Started, scheduler.admit(b.clone(), 100 + i).unwrap());
        assert_eq!(None, scheduler.finish(&b, RunState::Completed, 101 + i).unwrap());
        assert_eq!(0, scheduler.active_count()); assert_eq!(0, scheduler.queued_count()); assert!(scheduler.terminal_count() <= 16);
    }
    assert_eq!(16, scheduler.terminal_count()); scheduler.gc(100_000); assert_eq!(0, scheduler.terminal_count());
}

#[test]
fn scheduler_preserves_binding_and_fifo_and_never_evicts_unknown_effects() {
    let mut scheduler = RunScheduler::new(SchedulerLimits { max_active: 1, max_queued: 2, max_terminal: 2, terminal_ttl_ms: 100 }).unwrap();
    let a = binding("run-a"); let b = binding("run-b"); let c = binding("run-c");
    assert_eq!(Admission::Started, scheduler.admit(a.clone(), 0).unwrap());
    assert_eq!(Admission::AlreadyActive, scheduler.admit(a.clone(), 0).unwrap());
    let mut changed = a.clone(); changed.scope_id = "project:b".into();
    assert_eq!(Err(CoreError::AuthorityChanged), scheduler.admit(changed, 0));
    assert_eq!(Admission::Queued, scheduler.admit(b.clone(), 0).unwrap());
    assert_eq!(Admission::Queued, scheduler.admit(c.clone(), 0).unwrap());
    assert_eq!(Some(b.clone()), scheduler.finish(&a, RunState::UnknownEffect, 1).unwrap());
    assert_eq!(Some(c.clone()), scheduler.finish(&b, RunState::Completed, 2).unwrap());
    scheduler.gc(500); assert!(scheduler.terminal("run-a").is_some()); assert!(scheduler.terminal("run-b").is_none());
    scheduler.finish(&c, RunState::UnknownEffect, 501).unwrap();
    assert_eq!(Err(CoreError::CapacityReached), scheduler.admit(binding("run-d"), 502));
    assert_eq!(Admission::AlreadyTerminal, scheduler.admit(a.clone(), 502).unwrap());
    scheduler.reconcile_terminal(&a, RunState::Failed, 503).unwrap();
    assert_eq!(Admission::Started, scheduler.admit(binding("run-d"), 504).unwrap());
}

#[test]
fn local_phone_profile_never_claims_cloud_and_local_binding_has_explicit_null_revision() {
    let local = r#"{"profileId":"local-goal","provider":"local_phone","modelId":"local-model","reasoning":null,"maxToolCalls":18,"deadlineMs":1800000,"cloudAuthorized":false}"#;
    assert_eq!(ExecutionProvider::LocalPhone, ExecutionProfile::from_json(local).unwrap().provider);
    assert_eq!(Err(CoreError::InvalidProfile), ExecutionProfile::from_json(&local.replace("\"cloudAuthorized\":false", "\"cloudAuthorized\":true")));
    let pc = local.replace("local_phone", "local_pc");
    assert_eq!(ExecutionProvider::LocalPc, ExecutionProfile::from_json(&pc).unwrap().provider);
    let raw = r#"{"runId":"run-a","threadId":"thread-a","projectId":null,"branchId":"branch-a","scopeId":"scope-a","authorizationRevision":null}"#;
    let parsed = RunBinding::from_json(raw).unwrap();
    assert_eq!(None, parsed.authorization_revision);
    assert_eq!(raw, serde_json::to_string(&parsed).unwrap());
    // A missing revision is not silently a local binding.
    assert!(RunBinding::from_json(&raw.replace(",\"authorizationRevision\":null", "")).is_err());
    let mut cloud = parsed.clone(); cloud.authorization_revision = Some(0);
    assert_ne!(parsed, cloud);
}
