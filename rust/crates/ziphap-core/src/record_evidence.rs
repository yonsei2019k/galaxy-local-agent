/// Return true only for bounded, verbatim evidence and a named subject visible in it.
pub fn record_evidence_matches(source: &str, quote: &str, label: &str) -> bool {
    source.len() <= 4_800
        && (6..=240).contains(&quote.chars().count())
        && (2..=48).contains(&label.chars().count())
        && quote.trim() == quote
        && label.trim() == label
        && !quote.chars().any(char::is_control)
        && source.contains(quote)
        && quote.contains(label)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checks_exact_quote_and_visible_subject() {
        let source = "팀 회의 금요일 오후 2시에서 4시로 변경했어요";
        assert!(record_evidence_matches(source, source, "팀 회의"));
        assert!(!record_evidence_matches(source, "팀 회의 금요일 오후 3시", "팀 회의"));
        assert!(!record_evidence_matches(source, source, "병원 진료"));
    }
}
