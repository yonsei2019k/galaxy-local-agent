use crate::{CoreError, RunBinding, RunState};
use std::collections::{BTreeMap, VecDeque};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SchedulerLimits {
    pub max_active: usize,
    pub max_queued: usize,
    pub max_terminal: usize,
    pub terminal_ttl_ms: u64,
}
impl SchedulerLimits {
    fn validate(self) -> Result<(), CoreError> {
        if !(1..=64).contains(&self.max_active) || self.max_queued > 1024
            || !(1..=4096).contains(&self.max_terminal) || !(1..=86_400_000).contains(&self.terminal_ttl_ms) {
            return Err(CoreError::InvalidLimits);
        }
        Ok(())
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Admission { Started, Queued, AlreadyActive, AlreadyQueued, AlreadyTerminal }
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TerminalReceipt {
    pub binding: RunBinding,
    pub state: RunState,
    pub finished_at_ms: u64,
}
impl TerminalReceipt {
    fn uncertain(&self) -> bool { self.state == RunState::UnknownEffect }
}

/// Bounded admission/cache, not an autonomous runner. Hosts start only bindings returned by finish/cancel_queued.
/// Unknown-effect tombstones never expire or evict: reconcile explicitly, or admission eventually fails closed.
/// Completed cache entries are bounded retention/idempotency only, not a lifetime replay ledger.
pub struct RunScheduler {
    limits: SchedulerLimits,
    active: BTreeMap<String, RunBinding>,
    queued: VecDeque<RunBinding>,
    terminal: VecDeque<TerminalReceipt>,
    cancelled_queue: VecDeque<TerminalReceipt>,
}
impl RunScheduler {
    pub fn new(limits: SchedulerLimits) -> Result<Self, CoreError> {
        limits.validate()?;
        Ok(Self { limits, active: BTreeMap::new(), queued: VecDeque::new(), terminal: VecDeque::new(), cancelled_queue: VecDeque::new() })
    }
    pub fn active_count(&self) -> usize { self.active.len() }
    pub fn queued_count(&self) -> usize { self.queued.len() }
    pub fn terminal_count(&self) -> usize { self.terminal.len() }
    pub fn terminal(&self, run_id: &str) -> Option<&TerminalReceipt> { self.terminal.iter().chain(self.cancelled_queue.iter()).find(|r| r.binding.run_id == run_id) }
    pub fn gc(&mut self, now_ms: u64) {
        self.terminal.retain(|r| r.uncertain() || now_ms.saturating_sub(r.finished_at_ms) < self.limits.terminal_ttl_ms);
        self.cancelled_queue.retain(|r| now_ms.saturating_sub(r.finished_at_ms) < self.limits.terminal_ttl_ms);
        while self.terminal.len() > self.limits.max_terminal {
            if let Some(index) = self.terminal.iter().position(|r| !r.uncertain()) { self.terminal.remove(index); } else { break; }
        }
    }
    pub fn admit(&mut self, binding: RunBinding, now_ms: u64) -> Result<Admission, CoreError> {
        binding.validate()?; self.gc(now_ms);
        let existing = self.active.get(&binding.run_id).map(|b| (b, Admission::AlreadyActive))
            .or_else(|| self.queued.iter().find(|b| b.run_id == binding.run_id).map(|b| (b, Admission::AlreadyQueued)))
            .or_else(|| self.terminal(&binding.run_id).map(|r| (&r.binding, Admission::AlreadyTerminal)));
        if let Some((old, admission)) = existing {
            if old != &binding { return Err(CoreError::AuthorityChanged); }
            return Ok(admission);
        }
        if self.terminal.len() >= self.limits.max_terminal && self.terminal.iter().all(TerminalReceipt::uncertain) {
            return Err(CoreError::CapacityReached);
        }
        if self.active.len() < self.limits.max_active {
            self.active.insert(binding.run_id.clone(), binding); return Ok(Admission::Started);
        }
        if self.queued.len() + self.cancelled_queue.len() >= self.limits.max_queued { return Err(CoreError::CapacityReached); }
        self.queued.push_back(binding); Ok(Admission::Queued)
    }
    fn room_for_terminal(&mut self) -> Result<(), CoreError> {
        while self.terminal.len() >= self.limits.max_terminal {
            let index = self.terminal.iter().position(|r| !r.uncertain()).ok_or(CoreError::CapacityReached)?;
            self.terminal.remove(index);
        }
        Ok(())
    }
    fn next(&mut self) -> Option<RunBinding> {
        if self.active.len() >= self.limits.max_active { return None; }
        if self.terminal.len() >= self.limits.max_terminal && self.terminal.iter().all(TerminalReceipt::uncertain) { return None; }
        let binding = self.queued.pop_front()?;
        self.active.insert(binding.run_id.clone(), binding.clone()); Some(binding)
    }
    pub fn finish(&mut self, binding: &RunBinding, state: RunState, now_ms: u64) -> Result<Option<RunBinding>, CoreError> {
        if !matches!(state, RunState::Completed | RunState::Cancelled | RunState::Failed | RunState::UnknownEffect) { return Err(CoreError::InvalidTransition); }
        if self.active.get(&binding.run_id) != Some(binding) { return Err(CoreError::AuthorityChanged); }
        self.gc(now_ms); self.room_for_terminal()?;
        self.active.remove(&binding.run_id);
        self.terminal.push_back(TerminalReceipt { binding: binding.clone(), state, finished_at_ms: now_ms });
        Ok(self.next())
    }
    pub fn cancel_queued(&mut self, binding: &RunBinding, now_ms: u64) -> Result<(), CoreError> {
        let index = self.queued.iter().position(|r| r == binding).ok_or(CoreError::UnknownRun)?;
        self.gc(now_ms);
        // Cancellation must make the request non-runnable even when unknown effects
        // occupy every terminal slot. The queued request already reserved this space.
        let terminal_room = self.room_for_terminal().is_ok();
        self.queued.remove(index);
        let receipt = TerminalReceipt { binding: binding.clone(), state: RunState::Cancelled, finished_at_ms: now_ms };
        if terminal_room { self.terminal.push_back(receipt); } else { self.cancelled_queue.push_back(receipt); }
        Ok(())
    }
    /// A host reconciliation receipt is required outside this cache; this method executes no tool/retry.
    pub fn reconcile_terminal(&mut self, binding: &RunBinding, resolved: RunState, now_ms: u64) -> Result<(), CoreError> {
        if !matches!(resolved, RunState::Cancelled | RunState::Failed) { return Err(CoreError::InvalidTransition); }
        let receipt = self.terminal.iter_mut().find(|r| r.binding == *binding).ok_or(CoreError::UnknownRun)?;
        if !receipt.uncertain() { return Err(CoreError::InvalidTransition); }
        receipt.state = resolved; receipt.finished_at_ms = now_ms; Ok(())
    }
}
