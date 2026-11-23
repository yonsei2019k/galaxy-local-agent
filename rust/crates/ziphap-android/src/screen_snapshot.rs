use sha2::{Digest, Sha256};
use std::fmt::Write;

const DIGEST_BYTES: usize = 32;
const MAX_VISIBLE_TEXTS: usize = 600;
const MAX_TARGETS: usize = 150;

/// A bounded, center-aligned play glyph recognizer. It deliberately rejects
/// arbitrary drawings and is only used inside an independently identified video.
#[uniffi::export]
pub fn native_screen_play_glyph(sample: Vec<u8>, side: u32, radius: u32) -> bool {
    if !(17..=65).contains(&side)
        || side % 2 == 0
        || !(5..=20).contains(&radius)
        || radius * 2 + 3 > side
        || sample.len() != (side * side) as usize
    {
        return false;
    }
    let center = (side / 2) as i32;
    let radius = radius as i32;
    let mut inside = Vec::new();
    let mut outside = Vec::new();
    for y in -radius..=radius {
        for x in -radius..=radius {
            let boundary = (radius - x) * 4 - 7 * y.abs();
            let value = sample[((center + y) * side as i32 + center + x) as usize];
            if x > -radius + 2 && boundary > 8 {
                inside.push(value);
            } else if (x < -radius + 2 || boundary < -9)
                && x * x + y * y <= radius * radius
            {
                outside.push(value);
            }
        }
    }
    let counts = |high: bool| {
        let good_inside = inside.iter().filter(|&&v| if high { v >= 205 } else { v <= 50 }).count();
        let good_outside = outside.iter().filter(|&&v| if high { v <= 145 } else { v >= 110 }).count();
        good_inside * 100 >= inside.len() * 92 && good_outside * 100 >= outside.len() * 85
    };
    !inside.is_empty() && !outside.is_empty() && (counts(true) || counts(false))
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum NativeScreenRole {
    Tap,
    Edit,
    Scroll,
}

#[derive(Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NativeScreenDispatchDecision {
    Current,
    Stale,
    ApprovalInvalidated,
}

/// The approval snapshot comes from the host's accepted review for this exact call.
#[uniffi::export]
pub fn native_screen_dispatch_decision(
    requested_snapshot: String,
    current_snapshot: String,
    approved_snapshot: Option<String>,
) -> NativeScreenDispatchDecision {
    if let Some(approved) = approved_snapshot {
        if approved != requested_snapshot || approved != current_snapshot {
            return NativeScreenDispatchDecision::ApprovalInvalidated;
        }
    }
    if requested_snapshot == current_snapshot {
        NativeScreenDispatchDecision::Current
    } else {
        NativeScreenDispatchDecision::Stale
    }
}

#[derive(Clone, uniffi::Record)]
pub struct NativeScreenTarget {
    pub role: NativeScreenRole,
    pub label_digest: Vec<u8>,
    pub identity_digest: Vec<u8>,
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
    pub sensitive: bool,
    pub enabled: bool,
}

#[derive(uniffi::Record)]
pub struct NativeScreenRebindEvidence {
    pub snapshot_id: String,
    pub package_digest: String,
    pub window_id: i32,
    pub target_digest: String,
}

/// The Android host supplies two trusted, uniquely resolved observations in order,
/// with a settled stale rejection between them. Neither observation grants approval.
#[uniffi::export]
pub fn native_can_rebind_screen_target(
    rejected_snapshot: String,
    proposed_snapshot: String,
    before: NativeScreenRebindEvidence,
    after: NativeScreenRebindEvidence,
) -> bool {
    fn digest(value: &str) -> bool {
        value.len() == DIGEST_BYTES * 2
            && value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    }
    digest(&before.snapshot_id)
        && digest(&after.snapshot_id)
        && digest(&before.package_digest)
        && digest(&before.target_digest)
        && before.snapshot_id == rejected_snapshot
        && after.snapshot_id == proposed_snapshot
        && before.snapshot_id != after.snapshot_id
        && before.package_digest == after.package_digest
        && before.window_id == after.window_id
        && before.target_digest == after.target_digest
}

/// Bind the bounded accessibility observation. Only digests and structural metadata
/// cross the ABI. The process nonce makes IDs opaque and invalid after a restart.
#[uniffi::export]
pub fn native_screen_snapshot_id(
    process_nonce: Vec<u8>,
    package_digest: Vec<u8>,
    window_id: i32,
    truncated: bool,
    visible_text_digests: Vec<Vec<u8>>,
    targets: Vec<NativeScreenTarget>,
) -> Option<String> {
    if process_nonce.len() != DIGEST_BYTES
        || package_digest.len() != DIGEST_BYTES
        || visible_text_digests.len() > MAX_VISIBLE_TEXTS
        || targets.len() > MAX_TARGETS
        || visible_text_digests
            .iter()
            .any(|text| text.len() != DIGEST_BYTES)
        || targets.iter().any(|target| {
            target.label_digest.len() != DIGEST_BYTES
                || target.identity_digest.len() != DIGEST_BYTES
        })
    {
        return None;
    }

    let mut hasher = Sha256::new();
    hasher.update(b"ziphap.android.screen-snapshot.v2\0");
    hasher.update(process_nonce);
    hasher.update(package_digest);
    hasher.update(window_id.to_le_bytes());
    hasher.update([u8::from(truncated)]);
    hasher.update((visible_text_digests.len() as u32).to_le_bytes());
    for text in visible_text_digests {
        hasher.update(text);
    }

    // Target order binds the implicit e1, e2, ... references issued by Android.
    hasher.update((targets.len() as u32).to_le_bytes());
    for target in targets {
        let role = match target.role {
            NativeScreenRole::Tap => 1,
            NativeScreenRole::Edit => 2,
            NativeScreenRole::Scroll => 3,
        };
        hasher.update([role, u8::from(target.sensitive), u8::from(target.enabled)]);
        hasher.update(target.label_digest);
        hasher.update(target.identity_digest);
        for edge in [target.left, target.top, target.right, target.bottom] {
            hasher.update(edge.to_le_bytes());
        }
    }
    let mut id = String::with_capacity(DIGEST_BYTES * 2);
    for byte in hasher.finalize() {
        write!(&mut id, "{byte:02x}").expect("formatting into String cannot fail");
    }
    Some(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visual_play_glyph_requires_a_right_facing_contrast_shape() {
        let side = 49usize;
        let radius = 13;
        let mut play = vec![21; side * side];
        let mut back = play.clone();
        let mut pause = play.clone();
        for y in -radius..=radius {
            for x in -radius..=radius {
                let index = ((24 + y) * side as i32 + 24 + x) as usize;
                if x >= -radius + 2 && (radius - x) * 4 >= 7 * y.abs() {play[index] = 248;}
                if x <= radius - 2 && (radius + x) * 4 >= 7 * y.abs() {back[index] = 248;}
                if (3..=7).contains(&x.abs()) && y.abs() <= 10 {pause[index] = 248;}
            }
        }
        assert!(native_screen_play_glyph(play.clone(), 49, 13));
        assert!(native_screen_play_glyph(play.into_iter().map(|v|255-v).collect(), 49, 13));
        assert!(!native_screen_play_glyph(back, 49, 13));
        assert!(!native_screen_play_glyph(pause, 49, 13));
        assert!(!native_screen_play_glyph(vec![0; 3], 49, 13));
    }

    #[test]
    fn accepted_approval_is_bound_to_the_requested_and_current_snapshot() {
        use NativeScreenDispatchDecision::*;
        let decide = |requested: &str, current: &str, approved: Option<&str>| {
            native_screen_dispatch_decision(
                requested.into(),
                current.into(),
                approved.map(str::to_string),
            )
        };
        assert_eq!(decide("reviewed", "reviewed", Some("reviewed")), Current);
        assert_eq!(decide("reviewed", "changed", None), Stale);
        assert_eq!(
            decide("reviewed", "changed", Some("reviewed")),
            ApprovalInvalidated
        );
        assert_eq!(
            decide("changed", "changed", Some("reviewed")),
            ApprovalInvalidated
        );
    }

    #[test]
    fn rebinding_requires_fresh_snapshot_for_the_same_physical_target() {
        fn observed(snapshot: &str, window: i32, target: &str) -> NativeScreenRebindEvidence {
            NativeScreenRebindEvidence {
                snapshot_id: snapshot.repeat(64),
                package_digest: "c".repeat(64),
                window_id: window,
                target_digest: target.repeat(64),
            }
        }
        let check = |after| {
            native_can_rebind_screen_target(
                "a".repeat(64),
                "b".repeat(64),
                observed("a", 3, "d"),
                after,
            )
        };
        assert!(check(observed("b", 3, "d")));
        assert!(!check(observed("a", 3, "d")));
        assert!(!check(observed("b", 4, "d")));
        assert!(!check(observed("b", 3, "e")));
    }

    fn target() -> NativeScreenTarget {
        NativeScreenTarget {
            role: NativeScreenRole::Tap,
            label_digest: vec![3; 32],
            identity_digest: vec![4; 32],
            left: 10,
            top: 250,
            right: 100,
            bottom: 280,
            sensitive: false,
            enabled: true,
        }
    }

    fn screen(window: i32, text: Vec<Vec<u8>>, targets: Vec<NativeScreenTarget>) -> Option<String> {
        native_screen_snapshot_id(vec![1; 32], vec![2; 32], window, false, text, targets)
    }

    #[test]
    fn same_button_on_changed_checkout_or_window_invalidates_snapshot() {
        let total = Sha256::digest("Total 32,000").to_vec();
        let changed_total = Sha256::digest("Total 52,000").to_vec();
        let snapshot = screen(20, vec![total.clone()], vec![target()]).unwrap();
        assert_eq!(snapshot.len(), 64);
        assert_eq!(
            screen(20, vec![total.clone()], vec![target()]),
            Some(snapshot.clone())
        );
        assert_ne!(
            screen(20, vec![changed_total], vec![target()]),
            Some(snapshot.clone())
        );
        assert_ne!(screen(21, vec![total], vec![target()]), Some(snapshot));
    }

    #[test]
    fn rebinding_a_ref_or_changing_target_protection_invalidates_snapshot() {
        let original = target();
        let mut replacement = original.clone();
        replacement.identity_digest = vec![5; 32];
        let mut protected = original.clone();
        protected.sensitive = true;
        let mut disabled = original.clone();
        disabled.enabled = false;
        let snapshot = screen(20, vec![], vec![original.clone()]);
        for changed in [replacement.clone(), protected, disabled] {
            assert_ne!(screen(20, vec![], vec![changed]), snapshot);
        }
        assert_ne!(
            screen(20, vec![], vec![original.clone(), replacement.clone()]),
            screen(20, vec![], vec![replacement, original]),
        );
    }

    #[test]
    fn malformed_digests_and_excess_rows_have_no_snapshot_id() {
        assert_eq!(screen(20, vec![vec![1; 31]], vec![target()]), None);
        assert_eq!(screen(20, vec![], vec![target(); MAX_TARGETS + 1]), None);
        assert_eq!(
            screen(20, vec![vec![1; 32]; MAX_VISIBLE_TEXTS + 1], vec![]),
            None
        );
    }
}
