//! Shared host execution contracts. This crate neither infers intent nor executes tools.
//!
//! Wire profiles/bindings are untrusted until validated. Live authority, approvals,
//! permissions and execution evidence must come from the platform host, never model
//! prose or remote JSON. No request, result, credential or screen payload is stored or
//! logged here. All times are host-supplied epoch milliseconds; callers must use a
//! trusted clock and preserve unknown-effect state durably across process restarts.

mod grant;
mod goal_budget;
mod journal;
mod run;
mod scheduler;
mod types;

pub use grant::*;
pub use goal_budget::*;
pub use journal::*;
pub use run::*;
pub use scheduler::*;
pub use types::*;
