use crate::types::identifier;
use crate::{ActionRequest, CoreError, LiveAuthority, RunBinding};
use std::collections::BTreeSet;
use std::sync::{Arc, atomic::{AtomicBool, Ordering}};

/// Host-produced approval receipt. Deliberately not Deserialize/Clone: wire JSON is never approval.
pub struct HostApproval {
    binding: RunBinding,
    grant_id: String,
    generation: Arc<AtomicBool>,
    approved_at_ms: u64,
}
impl HostApproval {
    /// Call only after the platform's exact target/body review returns an affirmative owner decision.
    pub fn after_owner_approval(
        grant: &ActionGrant,
        approved_at_ms: u64,
    ) -> Result<Self, CoreError> {
        grant.binding.validate()?;
        if grant.revoked { return Err(CoreError::ApprovalMissing); }
        Ok(Self { binding: grant.binding.clone(), grant_id: grant.grant_id.clone(),
            generation: Arc::clone(&grant.approved), approved_at_ms })
    }
}

/// A bounded exact-action grant. Construction is not authorization; reserve and claim recheck live state.
pub struct ActionGrant {
    binding: RunBinding,
    grant_id: String,
    actions: BTreeSet<ActionRequest>,
    expires_at_ms: u64,
    not_before_ms: u64,
    remaining: u16,
    approved: Arc<AtomicBool>,
    revoked: bool,
}
impl ActionGrant {
    pub fn new(
        binding: RunBinding,
        grant_id: String,
        actions: Vec<ActionRequest>,
        not_before_ms: u64,
        expires_at_ms: u64,
        max_actions: u16,
    ) -> Result<Self, CoreError> {
        binding.validate()?;
        if !identifier(&grant_id, 128)
            || actions.is_empty()
            || actions.len() > 128
            || !(1..=128).contains(&max_actions)
            || expires_at_ms <= not_before_ms
            || expires_at_ms - not_before_ms > 1_800_000
        {
            return Err(CoreError::InvalidLimits);
        }
        for action in &actions { action.validate()?; }
        let count = actions.len();
        let actions: BTreeSet<_> = actions.into_iter().collect();
        if actions.len() != count { return Err(CoreError::InvalidAction); }
        Ok(Self { binding, grant_id, actions, expires_at_ms, not_before_ms, remaining: max_actions, approved: Arc::new(AtomicBool::new(false)), revoked: false })
    }
    pub fn approve(&mut self, approval: HostApproval) -> Result<(), CoreError> {
        if self.revoked { return Err(CoreError::ApprovalMissing); }
        if approval.binding != self.binding || approval.grant_id != self.grant_id || !Arc::ptr_eq(&approval.generation, &self.approved) {
            return Err(CoreError::GrantMismatch);
        }
        if approval.approved_at_ms < self.not_before_ms || approval.approved_at_ms >= self.expires_at_ms {
            return Err(CoreError::GrantExpired);
        }
        if self.approved.load(Ordering::Acquire) { return Err(CoreError::ConflictingDuplicate); }
        self.approved.store(true, Ordering::Release);
        Ok(())
    }
    pub fn remaining(&self) -> u16 { self.remaining }
    pub fn revoke(&mut self) { self.revoked = true; self.approved.store(false, Ordering::Release); self.remaining = 0; }

    /// Reserves one attempt, not a permission shortcut. Limits are consumed even if the later claim fails.
    pub fn reserve(
        &mut self,
        action: &ActionRequest,
        live: &LiveAuthority<'_>,
        now_ms: u64,
        mut permission: impl FnMut(&ActionRequest) -> bool,
    ) -> Result<ActionPermit, CoreError> {
        live.check(&self.binding)?;
        action.validate()?;
        if !self.approved.load(Ordering::Acquire) { return Err(CoreError::ApprovalMissing); }
        if now_ms < self.not_before_ms || now_ms >= self.expires_at_ms { return Err(CoreError::GrantExpired); }
        if self.remaining == 0 { return Err(CoreError::GrantExhausted); }
        if !self.actions.contains(action) { return Err(CoreError::GrantMismatch); }
        if !permission(action) { return Err(CoreError::PermissionDenied); }
        self.remaining -= 1;
        Ok(ActionPermit { binding: self.binding.clone(), action: action.clone(), not_before_ms: self.not_before_ms, expires_at_ms: self.expires_at_ms, approved: Arc::clone(&self.approved) })
    }
}

/// One move-only permit. Pass by value into RunMachine::claim_effect immediately before the host effect.
pub struct ActionPermit {
    pub(crate) binding: RunBinding,
    pub(crate) action: ActionRequest,
    pub(crate) not_before_ms: u64,
    pub(crate) expires_at_ms: u64,
    pub(crate) approved: Arc<AtomicBool>,
}
impl ActionPermit {
    pub fn action(&self) -> &ActionRequest { &self.action }
}
