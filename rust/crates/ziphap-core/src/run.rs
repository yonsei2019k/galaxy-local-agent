use crate::types::{digest, identifier};
use crate::{ActionPermit, ActionRequest, CoreError, EffectState, EventKind, ExecutionProfile, FailureCode,
    LiveAuthority, PauseReason, RunBinding, RunJournal};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::sync::atomic::Ordering;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RunState { Queued, Running, Paused, Completed, Cancelled, Failed, UnknownEffect }

/// Supplied only by the actual platform executor/readback path, never deserialized from a model.
pub enum HostEffectOutcome {
    Applied { evidence_digest: String },
    Verified { evidence_digest: String, readback_digest: String },
    NotApplied,
    Unknown,
}
impl HostEffectOutcome {
    fn validate(&self) -> Result<(), CoreError> {
        let valid = match self {
            Self::Applied { evidence_digest } => digest(evidence_digest),
            Self::Verified { evidence_digest, readback_digest } => digest(evidence_digest) && digest(readback_digest),
            _ => true,
        };
        if valid { Ok(()) } else { Err(CoreError::InvalidEvidence) }
    }
    fn state(&self) -> EffectState {
        match self { Self::Applied { .. } => EffectState::Applied, Self::Verified { .. } => EffectState::Verified,
            Self::NotApplied => EffectState::NotApplied, Self::Unknown => EffectState::Unknown }
    }
}
struct EffectRecord { action: ActionRequest, state: EffectState, evidence_digest: Option<String>, readback_digest: Option<String> }
struct Observation { action: ActionRequest, evidence_digest: String }

/// Completion criteria must be selected from the host-accepted owner task, never the final answer.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct CompletionRequirement {
    pub action: ActionRequest,
    pub requires_effect_readback: bool,
}

pub struct RunMachine {
    binding: RunBinding,
    profile: ExecutionProfile,
    state: RunState,
    journal: RunJournal,
    effects: BTreeMap<String, EffectRecord>,
    observations: Vec<Observation>,
    started_at_ms: Option<u64>,
    deadline_at_ms: Option<u64>,
    cancelled: bool,
    /// Terminal failure. Reconciling its uncertain effects ends in Failed, never Paused/Running.
    failure: Option<FailureCode>,
    /// Owner-task completion criteria, frozen by the host before the first observation or effect.
    requirements: Option<Vec<CompletionRequirement>>,
    tool_attempts: u16,
}
impl RunMachine {
    pub fn new(binding: RunBinding, profile: ExecutionProfile, journal_capacity: usize, now_ms: u64) -> Result<Self, CoreError> {
        profile.validate_binding(&binding)?;
        let mut journal = RunJournal::new(binding.clone(), journal_capacity)?;
        journal.append(EventKind::Created, now_ms)?;
        Ok(Self { binding, profile, state: RunState::Queued, journal, effects: BTreeMap::new(),
            observations: Vec::new(), started_at_ms: None, deadline_at_ms: None, cancelled: false, failure: None, requirements: None, tool_attempts: 0 })
    }
    pub fn binding(&self) -> &RunBinding { &self.binding }
    pub fn profile(&self) -> &ExecutionProfile { &self.profile }
    pub fn state(&self) -> RunState { self.state }
    pub fn journal(&self) -> &RunJournal { &self.journal }
    pub fn tool_attempts(&self) -> u16 { self.tool_attempts }
    pub fn failure(&self) -> Option<FailureCode> { self.failure }
    pub fn requirements(&self) -> Option<&[CompletionRequirement]> { self.requirements.as_deref() }

    /// Freezes the owner-task completion criteria selected by the host from the accepted task (never from a
    /// model's final answer). Allowed once, before any observation or effect; repeating the identical set is a
    /// no-op, any different set afterwards is rejected.
    pub fn capture_requirements(&mut self, requirements: Vec<CompletionRequirement>, live: &LiveAuthority<'_>, now_ms: u64) -> Result<(), CoreError> {
        self.live(live, now_ms)?;
        if !matches!(self.state, RunState::Queued | RunState::Running) || self.failure.is_some() { return Err(CoreError::InvalidTransition); }
        if requirements.is_empty() || requirements.len() > 128 { return Err(CoreError::IncompleteProof); }
        for (index, requirement) in requirements.iter().enumerate() {
            requirement.action.validate()?;
            if requirements[..index].iter().any(|r| r.action == requirement.action) { return Err(CoreError::ConflictingDuplicate); }
        }
        if let Some(frozen) = &self.requirements {
            return if same_requirements(frozen, &requirements) { Ok(()) } else { Err(CoreError::InvalidTransition) };
        }
        if !self.effects.is_empty() || !self.observations.is_empty() { return Err(CoreError::InvalidTransition); }
        self.requirements = Some(requirements); Ok(())
    }
    fn live(&self, live: &LiveAuthority<'_>, now_ms: u64) -> Result<(), CoreError> {
        live.check(&self.binding)?;
        if self.cancelled { return Err(CoreError::Cancelled); }
        if self.deadline_at_ms.is_some_and(|deadline| now_ms >= deadline) { return Err(CoreError::DeadlineExceeded); }
        if self.started_at_ms.is_some_and(|start| now_ms < start) { return Err(CoreError::DeadlineExceeded); }
        Ok(())
    }
    pub fn start(&mut self, live: &LiveAuthority<'_>, now_ms: u64) -> Result<(), CoreError> {
        self.live(live, now_ms)?;
        if self.state != RunState::Queued { return Err(CoreError::InvalidTransition); }
        self.deadline_at_ms = Some(now_ms.checked_add(self.profile.deadline_ms).ok_or(CoreError::InvalidLimits)?);
        self.started_at_ms = Some(now_ms); self.state = RunState::Running;
        self.journal.append(EventKind::Started, now_ms)?;
        Ok(())
    }
    pub fn pause(&mut self, reason: PauseReason, now_ms: u64) -> Result<(), CoreError> {
        if self.state != RunState::Running || self.effects.values().any(|e| e.state == EffectState::InFlight) { return Err(CoreError::InvalidTransition); }
        self.state = RunState::Paused; self.journal.append(EventKind::Paused { reason }, now_ms)?; Ok(())
    }
    pub fn resume(&mut self, live: &LiveAuthority<'_>, now_ms: u64) -> Result<(), CoreError> {
        self.live(live, now_ms)?;
        if self.state != RunState::Paused || self.failure.is_some() || self.has_uncertain_effects() { return Err(CoreError::InvalidTransition); }
        self.state = RunState::Running; self.journal.append(EventKind::Resumed, now_ms)?; Ok(())
    }
    pub fn has_uncertain_effects(&self) -> bool {
        self.effects.values().any(|e| matches!(e.state, EffectState::InFlight | EffectState::Applied | EffectState::Unknown))
    }
    pub fn effect_state(&self, effect_id: &str) -> Option<EffectState> { self.effects.get(effect_id).map(|e| e.state) }

    /// Consumes a move-only permit and rechecks actual permission after any approval/queue wait.
    /// Call immediately before the platform side effect; a claim is uncertain until the executor reports.
    pub fn claim_effect(&mut self, effect_id: String, permit: ActionPermit, live: &LiveAuthority<'_>, now_ms: u64,
        mut permission: impl FnMut(&ActionRequest) -> bool) -> Result<ActionRequest, CoreError> {
        self.live(live, now_ms)?;
        if self.state != RunState::Running || self.failure.is_some() { return Err(CoreError::InvalidTransition); }
        if self.requirements.is_none() { return Err(CoreError::IncompleteProof); }
        if permit.binding != self.binding { return Err(CoreError::GrantMismatch); }
        if !permit.approved.load(Ordering::Acquire) { return Err(CoreError::ApprovalMissing); }
        if now_ms < permit.not_before_ms || now_ms >= permit.expires_at_ms { return Err(CoreError::GrantExpired); }
        if !identifier(&effect_id, 128) { return Err(CoreError::InvalidAction); }
        if self.has_uncertain_effects() { return Err(CoreError::UnknownEffect); }
        if self.effects.contains_key(&effect_id) || self.effects.values().any(|e| e.action == permit.action && e.state != EffectState::NotApplied) { return Err(CoreError::EffectReplayBlocked); }
        if self.tool_attempts >= self.profile.max_tool_calls { return Err(CoreError::CapacityReached); }
        if !permission(&permit.action) { return Err(CoreError::PermissionDenied); }
        let action = permit.action;
        self.journal.append(EventKind::ToolStarted { effect_id: effect_id.clone(), tool: action.tool.clone() }, now_ms)?;
        self.effects.insert(effect_id, EffectRecord { action: action.clone(), state: EffectState::InFlight, evidence_digest: None, readback_digest: None });
        self.tool_attempts += 1; Ok(action)
    }

    /// Actual callbacks remain recordable after authority revocation. After cancellation has marked
    /// the effect Unknown, use explicit same-target reconcile_effect instead. Neither grants completion.
    pub fn record_effect(&mut self, binding: &RunBinding, effect_id: &str, outcome: HostEffectOutcome, now_ms: u64) -> Result<(), CoreError> {
        if binding != &self.binding { return Err(CoreError::AuthorityChanged); }
        outcome.validate()?;
        let previous = self.effects.get(effect_id).ok_or(CoreError::UnknownEffect)?.state;
        if !matches!(previous, EffectState::InFlight | EffectState::Applied)
            || (previous == EffectState::Applied && matches!(outcome, HostEffectOutcome::NotApplied)) { return Err(CoreError::InvalidTransition); }
        self.update_effect(effect_id, outcome, now_ms)
    }
    fn update_effect(&mut self, effect_id: &str, outcome: HostEffectOutcome, now_ms: u64) -> Result<(), CoreError> {
        let state = outcome.state();
        self.journal.append(EventKind::ToolFinished { effect_id: effect_id.into(), state }, now_ms)?;
        let record = self.effects.get_mut(effect_id).ok_or(CoreError::UnknownEffect)?;
        record.state = state;
        match outcome {
            HostEffectOutcome::Applied { evidence_digest } => record.evidence_digest = Some(evidence_digest),
            HostEffectOutcome::Verified { evidence_digest, readback_digest } => { record.evidence_digest = Some(evidence_digest); record.readback_digest = Some(readback_digest); }
            _ => {}
        }
        if state == EffectState::Unknown { self.state = RunState::UnknownEffect; self.journal.append(EventKind::Uncertain, now_ms)?; }
        if !self.has_uncertain_effects() {
            if self.cancelled { self.state = RunState::Cancelled; } else if self.failure.is_some() { self.state = RunState::Failed; }
        }
        Ok(())
    }
    /// Explicit same-target provider reconciliation only; this never launches or retries the effect.
    pub fn reconcile_effect(&mut self, binding: &RunBinding, effect_id: &str, outcome: HostEffectOutcome, now_ms: u64) -> Result<(), CoreError> {
        if binding != &self.binding { return Err(CoreError::AuthorityChanged); }
        outcome.validate()?;
        if self.effect_state(effect_id) != Some(EffectState::Unknown)
            || !matches!(outcome, HostEffectOutcome::Verified { .. } | HostEffectOutcome::NotApplied) { return Err(CoreError::InvalidTransition); }
        self.update_effect(effect_id, outcome, now_ms)?;
        if !self.has_uncertain_effects() {
            self.state = if self.cancelled { RunState::Cancelled } else if self.failure.is_some() { RunState::Failed } else { RunState::Paused };
        }
        Ok(())
    }
    /// Host read/tool observation, already validated by its actual executor. No model/server prose accepted.
    pub fn record_observation(&mut self, action: ActionRequest, evidence_digest: String, live: &LiveAuthority<'_>, now_ms: u64) -> Result<(), CoreError> {
        self.live(live, now_ms)?; action.validate()?;
        if self.state != RunState::Running || self.failure.is_some() { return Err(CoreError::InvalidTransition); }
        if self.requirements.is_none() { return Err(CoreError::IncompleteProof); }
        if !digest(&evidence_digest) { return Err(CoreError::InvalidEvidence); }
        if self.tool_attempts >= self.profile.max_tool_calls { return Err(CoreError::CapacityReached); }
        self.journal.append(EventKind::Observation { tool: action.tool.clone() }, now_ms)?;
        self.observations.push(Observation { action, evidence_digest }); self.tool_attempts += 1; Ok(())
    }
    pub fn cancel(&mut self, now_ms: u64) -> Result<(), CoreError> {
        if matches!(self.state, RunState::Completed | RunState::Failed | RunState::Cancelled) { return Err(CoreError::InvalidTransition); }
        if self.cancelled { return Ok(()); }
        self.cancelled = true; self.journal.append(EventKind::CancelRequested, now_ms)?;
        for effect in self.effects.values_mut() {
            if matches!(effect.state, EffectState::InFlight | EffectState::Applied) { effect.state = EffectState::Unknown; }
        }
        self.state = if self.has_uncertain_effects() { RunState::UnknownEffect } else { RunState::Cancelled };
        self.journal.append(if self.state == RunState::UnknownEffect { EventKind::Uncertain } else { EventKind::Cancelled }, now_ms)?; Ok(())
    }
    pub fn fail(&mut self, code: FailureCode, now_ms: u64) -> Result<(), CoreError> {
        if matches!(self.state, RunState::Completed | RunState::Cancelled | RunState::Failed) || self.failure.is_some() { return Err(CoreError::InvalidTransition); }
        self.failure = Some(code);
        for effect in self.effects.values_mut() { if matches!(effect.state, EffectState::InFlight | EffectState::Applied) { effect.state = EffectState::Unknown; } }
        self.state = if self.has_uncertain_effects() { RunState::UnknownEffect } else { RunState::Failed };
        self.journal.append(EventKind::Failed { code }, now_ms)?; Ok(())
    }
    /// `requirements` must be exactly the set frozen by [`Self::capture_requirements`]; the caller cannot
    /// choose, reduce or replace the completion criteria at finish time.
    pub fn complete(&mut self, requirements: &[CompletionRequirement], live: &LiveAuthority<'_>, now_ms: u64) -> Result<(), CoreError> {
        self.live(live, now_ms)?;
        if self.state != RunState::Running || self.failure.is_some() { return Err(CoreError::InvalidTransition); }
        let frozen = self.requirements.as_ref().ok_or(CoreError::IncompleteProof)?;
        if !same_requirements(frozen, requirements) || self.has_uncertain_effects() { return Err(CoreError::IncompleteProof); }
        for requirement in frozen {
            requirement.action.validate()?;
            let effect = self.effects.values().any(|e| e.action == requirement.action && e.state == EffectState::Verified
                && e.evidence_digest.as_ref().is_some_and(|d| digest(d)) && e.readback_digest.as_ref().is_some_and(|d| digest(d)));
            let observed = !requirement.requires_effect_readback && self.observations.iter().any(|o| o.action == requirement.action && digest(&o.evidence_digest));
            if !effect && !observed { return Err(CoreError::IncompleteProof); }
        }
        self.journal.append(EventKind::Completed, now_ms)?; self.state = RunState::Completed; Ok(())
    }
}

fn same_requirements(frozen: &[CompletionRequirement], other: &[CompletionRequirement]) -> bool {
    frozen.len() == other.len() && other.iter().enumerate().all(|(index, r)| frozen.contains(r) && !other[..index].contains(r))
}
