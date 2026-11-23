/// The Android host compares the exact URL and link metadata in memory. Only
/// bounded structural evidence crosses the ABI. Unseen controls are ambiguous.
#[uniffi::export]
pub fn native_browser_navigation_target(
    same_source: bool,
    complete_observation: bool,
    marker_already_visible: bool,
    matched_controls: Vec<bool>,
) -> Option<u32> {
    if !same_source || !complete_observation || marker_already_visible || matched_controls.len() > 120 {
        return None;
    }
    let mut targets = matched_controls.iter().enumerate().filter(|(_, matched)| **matched);
    let (index, _) = targets.next()?;
    targets.next().is_none().then_some(index as u32)
}

#[cfg(test)]
mod tests {
    use super::native_browser_navigation_target;

    #[test]
    fn rebinds_one_observed_control_without_accepting_ambiguous_scopes() {
        assert_eq!(native_browser_navigation_target(true, true, false, vec![false, true]), Some(1));
        assert_eq!(native_browser_navigation_target(true, true, false, vec![true, true]), None);
        assert_eq!(native_browser_navigation_target(false, true, false, vec![true]), None);
        assert_eq!(native_browser_navigation_target(true, false, false, vec![true]), None);
        assert_eq!(native_browser_navigation_target(true, true, true, vec![true]), None);
        assert_eq!(native_browser_navigation_target(true, true, false, vec![false; 121]), None);
    }
}
