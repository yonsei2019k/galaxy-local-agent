/// Detects access-check interstitials before treating the response as page evidence.
/// The host provides the already extracted title and a bounded visible-text prefix.
pub fn is_public_page_interstitial(title: &str, text: &str) -> bool {
    if title
        .trim()
        .trim_end_matches(['.', '…'])
        .trim_end()
        .eq_ignore_ascii_case("just a moment")
    {
        return true;
    }
    let compact: String = text
        .chars()
        .take(2048)
        .filter(|character| !character.is_whitespace())
        .collect();
    [
        "자동입력방지문자를입력",
        "보안문자를입력",
        "자동입력방지코드를입력",
    ]
    .iter()
    .any(|prompt| compact.contains(prompt))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_access_check_prompts_but_not_a_general_reference_to_security() {
        assert!(is_public_page_interstitial("Just a moment…", ""));
        assert!(is_public_page_interstitial(
            "확인",
            "자동 입력 방지 문자를 입력해주세요"
        ));
        assert!(!is_public_page_interstitial(
            "보안문자 안내",
            "자동입력 방지 기능의 원리"
        ));
    }
}
