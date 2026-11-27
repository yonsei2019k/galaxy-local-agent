/// A limit decision from trusted, host-validated goal checkpoint metadata.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GoalBudgetStop {
    Segments,
    Tools,
    Time,
    NoProgress,
}

/// `active_elapsed_ms` is the host-measured active segment time carried across segments
/// (owner-approval and queue waits excluded), never a wall-clock difference.
pub fn goal_budget_stop(
    segments: u32,
    tool_calls: u32,
    active_elapsed_ms: u64,
    no_progress: u32,
) -> Option<GoalBudgetStop> {
    if segments >= 8 {
        Some(GoalBudgetStop::Segments)
    } else if tool_calls >= 144 {
        Some(GoalBudgetStop::Tools)
    } else if active_elapsed_ms >= 30 * 60 * 1000 {
        Some(GoalBudgetStop::Time)
    } else if no_progress >= 3 {
        Some(GoalBudgetStop::NoProgress)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn boundary_and_precedence_match_the_existing_host_policy() {
        assert_eq!(goal_budget_stop(7, 143, 1_799_999, 2), None);
        assert_eq!(goal_budget_stop(8, 144, 1_800_000, 3), Some(GoalBudgetStop::Segments));
        assert_eq!(goal_budget_stop(7, 144, 1_800_000, 3), Some(GoalBudgetStop::Tools));
        assert_eq!(goal_budget_stop(7, 143, 1_800_000, 3), Some(GoalBudgetStop::Time));
        assert_eq!(goal_budget_stop(7, 143, u64::MAX, 2), Some(GoalBudgetStop::Time));
        assert_eq!(goal_budget_stop(7, 143, 0, 3), Some(GoalBudgetStop::NoProgress));
        assert_eq!(goal_budget_stop(0, 0, 0, 0), None);
    }
}
