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

#[cfg(test)]
mod tests {
    use super::*;

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
