//! Small host-owned boundary. No credentials, tool arguments or conversation text cross this ABI.
use std::collections::BTreeSet;
use std::sync::{Arc, Mutex};
use ziphap_core::{CoreError, EffectState, EventKind, ExecutionProfile, RunBinding, RunJournal, RunEvent};
use serde::{Deserialize, Serialize};

uniffi::setup_scaffolding!();
#[uniffi::export]
pub fn native_older_turn_byte_limits(
    reserved_bytes: u32,
    reserved_turns: u32,
    row_bytes: Vec<u32>,
) -> Vec<u32> {
    ziphap_core::older_turn_byte_limits(reserved_bytes, reserved_turns, &row_bytes)
}

#[uniffi::export]
pub fn native_public_page_interstitial(title: String, text: String) -> bool {
    ziphap_core::is_public_page_interstitial(&title, &text)
}

mod screen_snapshot;

#[uniffi::export]
pub fn native_context_partner(
    is_owner: Vec<bool>,
    same_run_as_newest: Vec<bool>,
    returning_to_paused_request: bool,
) -> Option<u32> {
    ziphap_core::context_partner(&is_owner, &same_run_as_newest, returning_to_paused_request)
}

#[derive(uniffi::Enum)]
pub enum NativePhoneVerification {
    Verified,
    Prepared,
    Unverified,
    Failed,
}

impl From<NativePhoneVerification> for ziphap_core::PhoneVerification {
    fn from(value: NativePhoneVerification) -> Self {
        match value {
            NativePhoneVerification::Verified => Self::Verified,
            NativePhoneVerification::Prepared => Self::Prepared,
            NativePhoneVerification::Unverified => Self::Unverified,
            NativePhoneVerification::Failed => Self::Failed,
        }
    }
}

#[uniffi::export]
pub fn native_can_withhold_public_read(
    tool: String,
    success: bool,
    verification: NativePhoneVerification,
    effect_not_applied: bool,
) -> bool {
    ziphap_core::can_withhold_public_read(&tool, success, verification.into(), effect_not_applied)
}

#[derive(uniffi::Record)]
pub struct NativePhoneObservation {
    pub call_id: String,
    pub sequence: u32,
    pub tool: String,
    pub success: bool,
    pub verification: NativePhoneVerification,
    pub effect_not_applied: bool,
    pub withheld: bool,
}

#[derive(uniffi::Record)]
pub struct NativePhoneReceipt {
    pub call_id: String,
    pub sequence: u32,
    pub tool: String,
}

#[uniffi::export]
pub fn native_phone_completion_proven(
    required_tools: Vec<String>,
    reported: Vec<NativePhoneReceipt>,
    observed: Vec<NativePhoneObservation>,
    public_partial: bool,
) -> bool {
    let reported: Vec<_> = reported
        .into_iter()
        .map(|row| ziphap_core::PhoneReceipt {
            call_id: row.call_id,
            sequence: row.sequence,
            tool: row.tool,
        })
        .collect();
    let observed: Vec<_> = observed
        .into_iter()
        .map(|row| ziphap_core::PhoneObservation {
            call_id: row.call_id,
            sequence: row.sequence,
            tool: row.tool,
            success: row.success,
            effect_not_applied: row.effect_not_applied,
            withheld: row.withheld,
            verification: row.verification.into(),
        })
        .collect();
    if public_partial {
        ziphap_core::phone_public_partial_proven(&required_tools, &reported, &observed)
    } else {
        ziphap_core::phone_completion_proven(&required_tools, &reported, &observed)
    }
}

#[derive(uniffi::Enum)]
pub enum NativeGoalBudgetStop {
    Segments,
    Tools,
    Time,
    NoProgress,
}

#[uniffi::export]
pub fn native_goal_budget_stop(
    segments: u32,
    tool_calls: u32,
    started_ms: u64,
    now_ms: u64,
    no_progress: u32,
) -> Option<NativeGoalBudgetStop> {
    use ziphap_core::GoalBudgetStop::*;
    ziphap_core::goal_budget_stop(segments, tool_calls, started_ms, now_ms, no_progress).map(|stop| match stop {
        Segments => NativeGoalBudgetStop::Segments,
        Tools => NativeGoalBudgetStop::Tools,
        Time => NativeGoalBudgetStop::Time,
        NoProgress => NativeGoalBudgetStop::NoProgress,
    })
}
const EVENT_CAPACITY:usize=512; // Created/Started + 128 call pairs + a final uncertain marker.

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("Host execution contract rejected")]
    Rejected,
    #[error("Host execution state unavailable")]
    Unavailable,
}
impl From<CoreError> for EngineError { fn from(_: CoreError) -> Self { Self::Rejected } }

struct State {
    journal: RunJournal,
    calls: BTreeSet<String>,
    in_flight: Option<String>,
    in_flight_tool: Option<String>,
    uncertain: bool,
    closed: bool,
    budget: usize,
    started_at_ms: u64,
    deadline_at_ms: u64,
}

#[derive(Serialize,Deserialize)]
#[serde(deny_unknown_fields,rename_all="camelCase")]
struct Checkpoint {
    binding:RunBinding, calls:Vec<String>, in_flight:Option<String>, in_flight_tool:Option<String>,
    uncertain:bool, closed:bool, budget:usize, started_at_ms:u64, deadline_at_ms:u64, events:Vec<RunEvent>,
}
fn read_only(tool:&str)->bool { matches!(tool,"get_current_datetime"|"search_notes"|"read_note"|"search_contacts"|
    "read_screen"|"read_kakao_screen"|"read_calendar_events"|"list_calendar_events"|"list_reminders"|"read_reminder"|
    "inspect_project_storage"|"read_project_file"|"search_web"|"read_webpage"|"read_app_browser_page"|"read_pc_browser_page") }
use ziphap_core::opens_phone_screen as opens_screen;

#[derive(uniffi::Object)]
pub struct NativeRunJournal { state: Mutex<State> }

#[uniffi::export]
impl NativeRunJournal {
    #[uniffi::constructor]
    pub fn new(binding_json: String, profile_json: String, now_ms: u64) -> Result<Arc<Self>, EngineError> {
        let binding = RunBinding::from_json(&binding_json)?;
        let profile = ExecutionProfile::from_json(&profile_json)?;
        let deadline_at_ms = now_ms.checked_add(profile.deadline_ms).ok_or(EngineError::Rejected)?;
        let mut journal = RunJournal::new(binding, EVENT_CAPACITY)?;
        journal.append(EventKind::Created, now_ms)?;
        journal.append(EventKind::Started, now_ms)?;
        Ok(Arc::new(Self { state: Mutex::new(State { journal, calls: BTreeSet::new(), in_flight: None, in_flight_tool:None,
            uncertain: false, closed: false, budget: profile.max_tool_calls as usize,
            started_at_ms: now_ms, deadline_at_ms }) }))
    }

    /// Restore ONLY from this host's authenticated/encrypted persistence, never model/provider JSON.
    #[uniffi::constructor]
    pub fn restore(binding_json:String,checkpoint_json:String)->Result<Arc<Self>,EngineError>{
        if checkpoint_json.len()>262144 {return Err(EngineError::Rejected)}
        let binding=RunBinding::from_json(&binding_json)?;
        let c:Checkpoint=serde_json::from_str(&checkpoint_json).map_err(|_|EngineError::Rejected)?;
        if c.binding!=binding||c.calls.len()>128||c.budget==0||c.budget>128||c.calls.len()>c.budget||c.events.len()>EVENT_CAPACITY||
            c.deadline_at_ms<=c.started_at_ms||c.deadline_at_ms-c.started_at_ms>1_800_000||c.in_flight.is_some()!=c.in_flight_tool.is_some(){return Err(EngineError::Rejected)}
        let calls:BTreeSet<_>=c.calls.iter().cloned().collect();
        if calls.len()!=c.calls.len()||c.in_flight.as_ref().is_some_and(|id|!calls.contains(id)){return Err(EngineError::Rejected)}
        let mut journal=RunJournal::new(binding,EVENT_CAPACITY)?;
        let mut replay_calls=BTreeSet::new();
        let mut replay_flight:Option<(String,String)>=None;
        let mut replay_uncertain=false;
        for event in c.events {
            match (&event.kind,event.sequence){
                (EventKind::Created,1)|(EventKind::Started,2)=>{},
                (EventKind::ToolStarted{effect_id,tool},n) if n>2&&replay_flight.is_none()=>{
                    if !replay_calls.insert(effect_id.clone()){return Err(EngineError::Rejected)}
                    replay_flight=Some((effect_id.clone(),tool.clone()));
                },
                (EventKind::ToolFinished{effect_id,state},n) if n>2=>{
                    let (id,tool)=replay_flight.take().ok_or(EngineError::Rejected)?;
                    if id!=*effect_id||matches!(state,EffectState::InFlight|EffectState::Applied){return Err(EngineError::Rejected)}
                    replay_uncertain|=*state==EffectState::Unknown||(*state==EffectState::HandedOff&&!opens_screen(&tool));
                },
                (EventKind::Uncertain,n) if n>2&&c.closed=>{replay_uncertain=true;},
                _=>return Err(EngineError::Rejected),
            }
            journal.accept(event)?;
        }
        if journal.latest_sequence()<2||replay_calls!=calls||replay_flight.as_ref().map(|v|&v.0)!=c.in_flight.as_ref()||
            replay_flight.as_ref().map(|v|&v.1)!=c.in_flight_tool.as_ref(){return Err(EngineError::Rejected)}
        Ok(Arc::new(Self{state:Mutex::new(State{journal,calls,in_flight:c.in_flight.clone(),in_flight_tool:c.in_flight_tool,
            uncertain:c.uncertain||replay_uncertain||c.in_flight.is_some(),closed:c.closed,budget:c.budget,
            started_at_ms:c.started_at_ms,deadline_at_ms:c.deadline_at_ms})}))
    }

    pub fn checkpoint(&self)->Result<String,EngineError>{
        let s=self.state.lock().map_err(|_|EngineError::Unavailable)?;
        let c=Checkpoint{binding:s.journal.binding().clone(),calls:s.calls.iter().cloned().collect(),in_flight:s.in_flight.clone(),
            in_flight_tool:s.in_flight_tool.clone(),uncertain:s.uncertain,closed:s.closed,budget:s.budget,
            started_at_ms:s.started_at_ms,deadline_at_ms:s.deadline_at_ms,events:s.journal.replay(0,EVENT_CAPACITY)?.events};
        serde_json::to_string(&c).map_err(|_|EngineError::Unavailable)
    }

    /// The caller must obtain this binding from the current local host after its permission checks.
    pub fn begin_call(&self, binding_json: String, call_id: String, tool: String, now_ms: u64) -> Result<(), EngineError> {
        let binding = RunBinding::from_json(&binding_json)?;
        let mut s = self.state.lock().map_err(|_| EngineError::Unavailable)?;
        if s.closed || s.uncertain || s.in_flight.is_some() || now_ms < s.started_at_ms || now_ms >= s.deadline_at_ms || s.journal.binding() != &binding ||
            s.calls.len() >= s.budget || s.calls.contains(&call_id) { return Err(EngineError::Rejected); }
        s.journal.append(EventKind::ToolStarted { effect_id: call_id.clone(), tool:tool.clone() }, now_ms)?;
        s.calls.insert(call_id.clone()); s.in_flight = Some(call_id); s.in_flight_tool=Some(tool); Ok(())
    }

    /// Metadata from the actual Android executor only; never invoke with a provider's success report.
    /// A closed journal can retain the receipt for its original in-flight call.
    /// This does not reopen dispatch, clear closure uncertainty, or complete the task.
    pub fn finish_call(&self, call_id: String, verification: String, effect_not_applied: bool,now_ms: u64) -> Result<(), EngineError> {
        let mut s = self.state.lock().map_err(|_| EngineError::Unavailable)?;
        if s.in_flight.as_ref() != Some(&call_id) { return Err(EngineError::Rejected); }
        let tool=s.in_flight_tool.as_deref().ok_or(EngineError::Rejected)?;
        let pending_owner=verification=="PREPARED"&&!opens_screen(tool);
        let state = match verification.as_str() {
            "VERIFIED" => EffectState::Verified,
            "PREPARED" => EffectState::HandedOff,
            "FAILED" | "UNVERIFIED" if effect_not_applied || read_only(tool) => EffectState::NotApplied,
            "FAILED" | "UNVERIFIED" => EffectState::Unknown,
            _ => return Err(EngineError::Rejected),
        };
        s.journal.append(EventKind::ToolFinished { effect_id: call_id, state }, now_ms)?;
        s.uncertain |= state == EffectState::Unknown||pending_owner; s.in_flight = None;s.in_flight_tool=None; Ok(())
    }

    pub fn events(&self, after_sequence: u64) -> Result<String, EngineError> {
        let s = self.state.lock().map_err(|_| EngineError::Unavailable)?;
        serde_json::to_string(&s.journal.replay(after_sequence, EVENT_CAPACITY)?).map_err(|_| EngineError::Unavailable)
    }

    /// Closing a subscription to this ledger is not proof of task completion.
    pub fn close(&self, now_ms: u64) -> Result<(), EngineError> {
        let mut s = self.state.lock().map_err(|_| EngineError::Unavailable)?;
        if !s.closed {
            if s.in_flight.is_some() || s.uncertain { s.journal.append(EventKind::Uncertain, now_ms)?; }
            s.closed = true;
        }
        Ok(())
    }
}

#[uniffi::export]
pub fn protocol_version() -> u32 { 2 }

#[cfg(test)]
mod tests {
    use super::*;
    fn native() -> Arc<NativeRunJournal> {
        NativeRunJournal::new(r#"{"runId":"r","threadId":"t","projectId":null,"branchId":"b","scopeId":"scope","authorizationRevision":2}"#.into(),
            r#"{"profileId":"cloud","provider":"codex_cli","modelId":"provider-default","reasoning":null,"maxToolCalls":18,"deadlineMs":330000,"cloudAuthorized":true}"#.into(), 100).unwrap()
    }
    fn binding() -> String { r#"{"runId":"r","threadId":"t","projectId":null,"branchId":"b","scopeId":"scope","authorizationRevision":2}"#.into() }
    #[test] fn exact_bound_dispatch_and_uncertain_write_block_replay() {
        let n = native(); n.begin_call(binding(), "one".into(), "save_note".into(), 101).unwrap();
        n.finish_call("one".into(), "UNVERIFIED".into(), false, 102).unwrap();
        assert!(n.begin_call(binding(), "two".into(), "save_note".into(), 103).is_err());
        assert!(n.events(0).unwrap().contains("unknown"));
    }
    #[test] fn reads_and_prepared_actions_never_become_task_completion() {
        let n = native(); n.begin_call(binding(), "one".into(), "read_note".into(), 101).unwrap();
        n.finish_call("one".into(), "VERIFIED".into(), false, 102).unwrap();
        assert!(n.begin_call(binding(), "one".into(), "read_note".into(), 103).is_err());
        assert!(n.begin_call(binding().replace("scope", "foreign"), "two".into(), "read_note".into(), 103).is_err());
        n.close(104).unwrap(); assert!(!n.events(0).unwrap().contains("completed"));
    }
    #[test] fn dispatch_deadline_is_enforced_but_late_actual_receipt_is_retained() {
        let n = native(); n.begin_call(binding(), "one".into(), "save_note".into(), 101).unwrap();
        n.finish_call("one".into(), "VERIFIED".into(), false, 400_000).unwrap();
        assert!(n.begin_call(binding(), "two".into(), "save_note".into(), 400_001).is_err());
        assert!(n.events(0).unwrap().contains("verified"));
    }
    #[test]
    fn closed_checkpoint_accepts_only_the_original_pending_receipt() {
        let journal = native();
        journal.begin_call(binding(), "pending".into(), "save_note".into(), 101).unwrap();
        journal.close(102).unwrap();
        let restored = NativeRunJournal::restore(binding(), journal.checkpoint().unwrap()).unwrap();
        assert!(restored.finish_call("foreign".into(), "VERIFIED".into(), false, 103).is_err());
        assert!(restored.begin_call(binding(), "new".into(), "save_note".into(), 103).is_err());

        restored.finish_call("pending".into(), "VERIFIED".into(), false, 104).unwrap();
        assert!(restored.finish_call("pending".into(), "VERIFIED".into(), false, 105).is_err());
        restored.close(106).unwrap();
        let sealed = NativeRunJournal::restore(binding(), restored.checkpoint().unwrap()).unwrap();
        assert!(sealed.begin_call(binding(), "new".into(), "save_note".into(), 107).is_err());
        let events = sealed.events(0).unwrap();
        assert!(events.contains("verified"));
        assert!(events.contains("uncertain"));
        assert!(!events.contains("completed"));
    }
    #[test] fn restart_preserves_inflight_uncertainty_and_exact_binding() {
        let n=native();n.begin_call(binding(),"one".into(),"save_note".into(),101).unwrap();
        let checkpoint=n.checkpoint().unwrap();
        let restored=NativeRunJournal::restore(binding(),checkpoint.clone()).unwrap();
        assert!(restored.begin_call(binding(),"two".into(),"save_note".into(),102).is_err());
        assert!(NativeRunJournal::restore(binding().replace("scope","other"),checkpoint).is_err());
    }
    #[test] fn pending_external_effect_and_low_risk_mutation_cannot_become_no_effect() {
        for tool in ["prepare_sms","create_goal"]{
            let n=native();n.begin_call(binding(),"one".into(),tool.into(),101).unwrap();
            n.finish_call("one".into(),if tool=="prepare_sms"{"PREPARED"}else{"UNVERIFIED"}.into(),false,102).unwrap();
            assert!(n.begin_call(binding(),"two".into(),tool.into(),103).is_err());
        }
        let n=native();n.begin_call(binding(),"one".into(),"search_coupang".into(),101).unwrap();
        n.finish_call("one".into(),"PREPARED".into(),false,102).unwrap();
        n.begin_call(binding(),"two".into(),"read_screen".into(),103).unwrap();
        assert!(n.events(0).unwrap().contains("handed_off"));
    }
}
