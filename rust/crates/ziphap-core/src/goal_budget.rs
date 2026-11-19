/// A limit decision from trusted, host-validated goal checkpoint metadata.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GoalBudgetStop {
    Segments,
    Tools,
    Time,
    NoProgress,
}

pub fn goal_budget_stop(
    segments: u32,
    tool_calls: u32,
    started_ms: u64,
    now_ms: u64,
    no_progress: u32,
) -> Option<GoalBudgetStop> {
    if segments >= 8 {
        Some(GoalBudgetStop::Segments)
    } else if tool_calls >= 144 {
        Some(GoalBudgetStop::Tools)
    } else if now_ms.checked_sub(started_ms).is_none_or(|elapsed| elapsed >= 30 * 60 * 1000) {
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
        assert_eq!(goal_budget_stop(7, 143, 100, 1_800_099, 2), None);
        assert_eq!(goal_budget_stop(8, 144, 100, 99, 3), Some(GoalBudgetStop::Segments));
        assert_eq!(goal_budget_stop(7, 144, 100, 99, 3), Some(GoalBudgetStop::Tools));
        assert_eq!(goal_budget_stop(7, 143, 100, 99, 3), Some(GoalBudgetStop::Time));
        assert_eq!(goal_budget_stop(7, 143, 100, 1_800_100, 2), Some(GoalBudgetStop::Time));
        assert_eq!(goal_budget_stop(7, 143, 100, 101, 3), Some(GoalBudgetStop::NoProgress));
    }
}
