use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CoreError {
    InvalidJson,
    InvalidBinding,
    InvalidProfile,
    InvalidAction,
    InvalidEvidence,
    InvalidLimits,
    InvalidTransition,
    AuthorityChanged,
    PermissionDenied,
    ApprovalMissing,
    GrantExpired,
    GrantExhausted,
    GrantMismatch,
    DeadlineExceeded,
    Cancelled,
    CapacityReached,
    UnknownRun,
    UnknownEffect,
    EffectReplayBlocked,
    IncompleteProof,
    CursorExpired,
    CursorAhead,
    SequenceGap,
    ConflictingDuplicate,
    SequenceExhausted,
}
impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Fixed codes only: no authority, target, model input or provider error payload.
        write!(f, "{:?}", self)
    }
}
impl std::error::Error for CoreError {}

pub(crate) fn identifier(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_-.:/".contains(&c))
}
pub(crate) fn digest(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionProvider {
    LocalPc,
    /// On-device phone inference. Like LocalPc it never carries a cloud prerequisite.
    LocalPhone,
    CodexCli,
    ClaudeCli,
}
impl ExecutionProvider {
    pub fn is_local(self) -> bool {
        matches!(self, Self::LocalPc | Self::LocalPhone)
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReasoningEffort {
    Minimal,
    Low,
    Medium,
    High,
    Xhigh,
    Max,
    Ultra,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ExecutionProfile {
    pub profile_id: String,
    pub provider: ExecutionProvider,
    pub model_id: String,
    pub reasoning: Option<ReasoningEffort>,
    pub max_tool_calls: u16,
    pub deadline_ms: u64,
    /// A captured prerequisite, not an approval to transmit arbitrary data.
    pub cloud_authorized: bool,
}
impl ExecutionProfile {
    pub fn validate_binding(&self, binding: &RunBinding) -> Result<(), CoreError> {
        self.validate()?;
        binding.validate()?;
        if !self.provider.is_local() && binding.authorization_revision.is_none() {
            return Err(CoreError::AuthorityChanged);
        }
        Ok(())
    }

    pub fn validate(&self) -> Result<(), CoreError> {
        if !identifier(&self.profile_id, 100)
            || !identifier(&self.model_id, 100)
            || !(1..=128).contains(&self.max_tool_calls)
            || !(1_000..=1_800_000).contains(&self.deadline_ms)
            || (!self.provider.is_local() && !self.cloud_authorized)
            || (self.provider.is_local() && self.cloud_authorized)
        {
            return Err(CoreError::InvalidProfile);
        }
        Ok(())
    }
    pub fn from_json(raw: &str) -> Result<Self, CoreError> {
        if raw.len() > 4096 {
            return Err(CoreError::InvalidJson);
        }
        let value: Self = serde_json::from_str(raw).map_err(|_| CoreError::InvalidJson)?;
        value.validate()?;
        Ok(value)
    }
}

#[derive(Clone, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct RunBinding {
    pub run_id: String,
    pub thread_id: String,
    pub project_id: Option<String>,
    pub branch_id: String,
    pub scope_id: String,
    /// Captured cloud authorization revision; an explicit null for a local-only run. Never omitted.
    #[serde(deserialize_with = "required_revision")]
    pub authorization_revision: Option<u64>,
}
fn required_revision<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<Option<u64>, D::Error> {
    Option::<u64>::deserialize(deserializer)
}
impl fmt::Debug for RunBinding {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("RunBinding(private host binding)")
    }
}
impl RunBinding {
    pub fn validate(&self) -> Result<(), CoreError> {
        if [&self.run_id, &self.thread_id, &self.branch_id]
            .iter()
            .any(|s| !identifier(s, 128))
            || self.project_id.as_ref().is_some_and(|s| !identifier(s, 128))
            || !identifier(&self.scope_id, 256)
        {
            return Err(CoreError::InvalidBinding);
        }
        Ok(())
    }
    pub fn from_json(raw: &str) -> Result<Self, CoreError> {
        if raw.len() > 2048 {
            return Err(CoreError::InvalidJson);
        }
        let value: Self = serde_json::from_str(raw).map_err(|_| CoreError::InvalidJson)?;
        value.validate()?;
        Ok(value)
    }
}

/// Exact host-issued target token and canonical argument digest, never message/file contents.
#[derive(Clone, Eq, PartialEq, Ord, PartialOrd, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ActionRequest {
    pub tool: String,
    pub target_id: String,
    pub arguments_digest: String,
}
impl fmt::Debug for ActionRequest {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ActionRequest(private exact action)")
    }
}
impl ActionRequest {
    pub fn validate(&self) -> Result<(), CoreError> {
        if !identifier(&self.tool, 100)
            || !identifier(&self.target_id, 256)
            || !digest(&self.arguments_digest)
        {
            return Err(CoreError::InvalidAction);
        }
        Ok(())
    }
}

/// Not deserializable: supplied from current platform state after approval and immediately before effects.
pub struct LiveAuthority<'a> {
    pub binding: &'a RunBinding,
    pub active: bool,
    pub cancelled: bool,
}
impl LiveAuthority<'_> {
    pub(crate) fn check(&self, expected: &RunBinding) -> Result<(), CoreError> {
        if self.cancelled {
            return Err(CoreError::Cancelled);
        }
        if !self.active || self.binding != expected {
            return Err(CoreError::AuthorityChanged);
        }
        self.binding.validate()
    }
}
