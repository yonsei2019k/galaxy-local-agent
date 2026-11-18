use crate::types::identifier;
use crate::{CoreError, RunBinding};
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PauseReason { OwnerInput, Approval, ResourceUnavailable, ConnectionLost, OwnerRequested }
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureCode { EngineFailure, TransportFailure, PermissionRevoked, InvalidInput, BudgetExhausted, HostRejected }
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EffectState { InFlight, Applied, Verified, NotApplied, HandedOff, Unknown }

/// Fixed metadata only. Do not add raw requests, output deltas, arguments, screen text or provider errors.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(tag = "type", deny_unknown_fields, rename_all = "snake_case")]
pub enum EventKind {
    Created,
    Started,
    ApprovalRequested { grant_id: String },
    ApprovalAccepted { grant_id: String },
    ToolStarted { effect_id: String, tool: String },
    ToolFinished { effect_id: String, state: EffectState },
    Observation { tool: String },
    Paused { reason: PauseReason },
    Resumed,
    CancelRequested,
    Cancelled,
    Uncertain,
    Completed,
    Failed { code: FailureCode },
}

// Serde unit variants ignore extra fields even with deny_unknown_fields on an
// internally tagged enum. Empty struct variants enforce the wire boundary.
#[derive(Deserialize)]
#[serde(tag = "type", deny_unknown_fields, rename_all = "snake_case")]
enum StrictEventKind {
    Created {}, Started {},
    ApprovalRequested { grant_id: String }, ApprovalAccepted { grant_id: String },
    ToolStarted { effect_id: String, tool: String }, ToolFinished { effect_id: String, state: EffectState },
    Observation { tool: String }, Paused { reason: PauseReason }, Resumed {},
    CancelRequested {}, Cancelled {}, Uncertain {}, Completed {}, Failed { code: FailureCode },
}
impl<'de> Deserialize<'de> for EventKind {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Ok(match StrictEventKind::deserialize(deserializer)? {
            StrictEventKind::Created {} => Self::Created, StrictEventKind::Started {} => Self::Started,
            StrictEventKind::ApprovalRequested { grant_id } => Self::ApprovalRequested { grant_id },
            StrictEventKind::ApprovalAccepted { grant_id } => Self::ApprovalAccepted { grant_id },
            StrictEventKind::ToolStarted { effect_id, tool } => Self::ToolStarted { effect_id, tool },
            StrictEventKind::ToolFinished { effect_id, state } => Self::ToolFinished { effect_id, state },
            StrictEventKind::Observation { tool } => Self::Observation { tool },
            StrictEventKind::Paused { reason } => Self::Paused { reason },
            StrictEventKind::Resumed {} => Self::Resumed, StrictEventKind::CancelRequested {} => Self::CancelRequested,
            StrictEventKind::Cancelled {} => Self::Cancelled, StrictEventKind::Uncertain {} => Self::Uncertain,
            StrictEventKind::Completed {} => Self::Completed, StrictEventKind::Failed { code } => Self::Failed { code },
        })
    }
}
impl EventKind {
    fn validate(&self) -> Result<(), CoreError> {
        let valid = match self {
            Self::ApprovalRequested { grant_id } | Self::ApprovalAccepted { grant_id } => identifier(grant_id, 128),
            Self::ToolStarted { effect_id, tool } => identifier(effect_id, 128) && identifier(tool, 100),
            Self::ToolFinished { effect_id, .. } => identifier(effect_id, 128),
            Self::Observation { tool } => identifier(tool, 100),
            _ => true,
        };
        if valid { Ok(()) } else { Err(CoreError::InvalidEvidence) }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct RunEvent {
    pub sequence: u64,
    pub timestamp_ms: u64,
    pub kind: EventKind,
}
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct EventPage {
    pub first_retained_sequence: u64,
    pub latest_sequence: u64,
    pub events: Vec<RunEvent>,
    pub has_more: bool,
}

pub struct RunJournal {
    binding: RunBinding,
    capacity: usize,
    latest: u64,
    last_timestamp_ms: u64,
    events: VecDeque<RunEvent>,
}
impl RunJournal {
    pub fn new(binding: RunBinding, capacity: usize) -> Result<Self, CoreError> {
        binding.validate()?;
        if !(1..=4096).contains(&capacity) { return Err(CoreError::InvalidLimits); }
        Ok(Self { binding, capacity, latest: 0, last_timestamp_ms: 0, events: VecDeque::with_capacity(capacity) })
    }
    pub fn binding(&self) -> &RunBinding { &self.binding }
    pub fn latest_sequence(&self) -> u64 { self.latest }
    pub fn first_retained_sequence(&self) -> u64 { self.events.front().map_or(self.latest.saturating_add(1), |e| e.sequence) }
    pub fn append(&mut self, kind: EventKind, now_ms: u64) -> Result<RunEvent, CoreError> {
        kind.validate()?;
        let sequence = self.latest.checked_add(1).ok_or(CoreError::SequenceExhausted)?;
        let event = RunEvent { sequence, timestamp_ms: now_ms.max(self.last_timestamp_ms), kind };
        self.accept(event.clone())?;
        Ok(event)
    }
    /// Replays a trusted host event for this binding; never treat journal replay as permission/effect evidence.
    /// Exact retained duplicates are ignored; missing prefixes/gaps/conflicting sequences fail closed.
    pub fn accept(&mut self, event: RunEvent) -> Result<bool, CoreError> {
        event.kind.validate()?;
        if event.sequence == 0 { return Err(CoreError::SequenceGap); }
        if event.sequence <= self.latest {
            let old = self.events.iter().find(|e| e.sequence == event.sequence).ok_or(CoreError::CursorExpired)?;
            return if old == &event { Ok(false) } else { Err(CoreError::ConflictingDuplicate) };
        }
        if self.latest.checked_add(1) != Some(event.sequence) || event.timestamp_ms < self.last_timestamp_ms {
            return Err(CoreError::SequenceGap);
        }
        self.latest = event.sequence; self.last_timestamp_ms = event.timestamp_ms;
        self.events.push_back(event);
        while self.events.len() > self.capacity { self.events.pop_front(); }
        Ok(true)
    }
    pub fn replay(&self, after_sequence: u64, limit: usize) -> Result<EventPage, CoreError> {
        if limit == 0 || limit > self.capacity { return Err(CoreError::InvalidLimits); }
        if after_sequence > self.latest { return Err(CoreError::CursorAhead); }
        if after_sequence < self.first_retained_sequence().saturating_sub(1) { return Err(CoreError::CursorExpired); }
        let events: Vec<_> = self.events.iter().filter(|e| e.sequence > after_sequence).take(limit).cloned().collect();
        let last = events.last().map_or(after_sequence, |e| e.sequence);
        Ok(EventPage { first_retained_sequence: self.first_retained_sequence(), latest_sequence: self.latest, events, has_more: last < self.latest })
    }
}
