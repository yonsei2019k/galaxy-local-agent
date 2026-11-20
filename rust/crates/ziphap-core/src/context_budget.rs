//! Wire-v1 budgets for additional history after the latest dialogue and task metadata are reserved.
//! Costs are UTF-8 byte counts of serialized JSON objects, without array separators.
const MAX_BYTES: u32 = 12_000;
const MAX_TURNS: usize = 8;

/// Returns byte ceilings for the next eligible rows, most recent first. Only the
/// last row may need a literal excerpt. The host verifies the final serialization.
pub fn older_turn_byte_limits(
    reserved_bytes: u32,
    reserved_turns: u32,
    row_bytes: &[u32],
) -> Vec<u32> {
    let slots = MAX_TURNS.saturating_sub(reserved_turns as usize);
    let mut remaining = MAX_BYTES.saturating_sub(reserved_bytes);
    let mut limits = Vec::new();
    for &size in row_bytes.iter().take(slots) {
        // The caller has already placed the latest turn(s) in the JSON array.
        let separator = u32::from(reserved_turns > 0 || !limits.is_empty());
        let available = remaining.saturating_sub(separator);
        if size == 0 || available == 0 {
            break;
        }
        let limit = size.min(available);
        limits.push(limit);
        remaining = available - limit;
        if remaining == 0 {
            break;
        }
    }
    limits
}

/// Choose a second history row to reserve with the newest eligible message.
/// A same-run exchange wins. On return to a paused, unanswered owner request,
/// adjacent owner instructions precede an answer from a different run.
pub fn context_partner(
    is_owner: &[bool],
    same_run_as_newest: &[bool],
    returning_to_paused_request: bool,
) -> Option<u32> {
    let newest = is_owner.len().checked_sub(1)?;
    if same_run_as_newest.len() != is_owner.len() || !same_run_as_newest[newest] {
        return None;
    }
    let opposite = |i: &usize| is_owner[*i] != is_owner[newest];
    let mut earlier = (0..newest).rev();
    let same_run = earlier
        .clone()
        .find(|i| same_run_as_newest[*i] && opposite(i));
    let partner = same_run.or_else(|| {
        let fallback = earlier.find(opposite)?;
        let previous = newest - 1;
        if returning_to_paused_request && is_owner[newest] && is_owner[previous] {
            Some(previous)
        } else {
            Some(fallback)
        }
    })?;
    u32::try_from(partner).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paused_owner_keeps_the_adjacent_owner_before_an_unrelated_reply() {
        let roles = [true, false, true, true];
        let runs = [false, false, false, true];
        assert_eq!(context_partner(&roles, &runs, true), Some(2));
        assert_eq!(context_partner(&roles, &runs, false), Some(1));
        assert_eq!(
            context_partner(&roles, &[false, true, false, true], true),
            Some(1)
        );
        assert_eq!(
            context_partner(&[true, true, false], &[true, false, true], true),
            Some(0)
        );
    }

    #[test]
    fn no_partner_is_invented_from_missing_or_inconsistent_metadata() {
        assert_eq!(context_partner(&[], &[], true), None);
        assert_eq!(context_partner(&[true], &[true], true), None);
        assert_eq!(context_partner(&[true, true], &[false, true], true), None);
        assert_eq!(context_partner(&[false, false], &[false, true], true), None);
        assert_eq!(context_partner(&[true, false], &[true], true), None);
        assert_eq!(context_partner(&[true, false], &[true, false], true), None);
    }

    #[test]
    fn fills_newer_rows_before_the_boundary_and_charges_each_comma() {
        assert_eq!(
            older_turn_byte_limits(11_500, 2, &[100, 200, 500, 1]),
            [100, 200, 197]
        );
        assert_eq!(older_turn_byte_limits(11_899, 1, &[100, 1]), [100]);
        assert_eq!(older_turn_byte_limits(11_900, 0, &[100, 1]), [100]);
    }

    #[test]
    fn exhausted_or_invalid_reservations_and_turn_cap_are_bounded() {
        assert!(older_turn_byte_limits(12_000, 1, &[1]).is_empty());
        assert!(older_turn_byte_limits(u32::MAX, 1, &[1]).is_empty());
        assert!(older_turn_byte_limits(1, u32::MAX, &[1]).is_empty());
        assert!(older_turn_byte_limits(11_999, 1, &[u32::MAX]).is_empty());
        assert_eq!(older_turn_byte_limits(500, 6, &[8; 32]), [8, 8]);
        assert_eq!(older_turn_byte_limits(500, 2, &[10, 0, 3]), [10]);
    }
}
