#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NativeMediaRuleResult {
    Invalid,
    Unknown,
    Matched,
    Unmatched,
}

#[derive(Default, uniffi::Record)]
pub struct NativeMediaFacts {
    pub playing: Option<bool>,
    pub ended: Option<bool>,
    pub paused: Option<bool>,
    pub buffering: Option<bool>,
    pub seeking: Option<bool>,
    pub advancing: Option<bool>,
    pub duration_known: Option<bool>,
}

/// Evaluates a model-authored expression over observations. The expression has
/// no ability to read other data, call a function or change playback.
#[uniffi::export]
pub fn native_evaluate_media_rule(rule: String, facts: NativeMediaFacts) -> NativeMediaRuleResult {
    if rule.len() > 200 || !rule.is_ascii() {
        return NativeMediaRuleResult::Invalid;
    }
    let mut parser = Parser { input: &rule, facts: &facts };
    match parser.or(0) {
        Ok(value) if parser.input.trim().is_empty() => match value {
            Some(true) => NativeMediaRuleResult::Matched,
            Some(false) => NativeMediaRuleResult::Unmatched,
            None => NativeMediaRuleResult::Unknown,
        },
        _ => NativeMediaRuleResult::Invalid,
    }
}

struct Parser<'a> {
    input: &'a str,
    facts: &'a NativeMediaFacts,
}

impl Parser<'_> {
    fn eat(&mut self, token: &str) -> bool {
        self.input = self.input.trim_start();
        if let Some(rest) = self.input.strip_prefix(token) {
            self.input = rest;
            true
        } else {
            false
        }
    }

    fn or(&mut self, depth: u8) -> Result<Option<bool>, ()> {
        let mut value = self.and(depth)?;
        while self.eat("||") {
            let next = self.and(depth)?;
            value = match (value, next) {
                (Some(true), _) | (_, Some(true)) => Some(true),
                (Some(false), Some(false)) => Some(false),
                _ => None,
            };
        }
        Ok(value)
    }

    fn and(&mut self, depth: u8) -> Result<Option<bool>, ()> {
        let mut value = self.term(depth)?;
        while self.eat("&&") {
            let next = self.term(depth)?;
            value = match (value, next) {
                (Some(false), _) | (_, Some(false)) => Some(false),
                (Some(true), Some(true)) => Some(true),
                _ => None,
            };
        }
        Ok(value)
    }

    fn term(&mut self, depth: u8) -> Result<Option<bool>, ()> {
        if depth >= 12 {
            return Err(());
        }
        if self.eat("!") {
            return Ok(self.term(depth + 1)?.map(|value| !value));
        }
        if self.eat("(") {
            let value = self.or(depth + 1)?;
            return if self.eat(")") { Ok(value) } else { Err(()) };
        }
        let length = self.input.bytes().take_while(|byte| byte.is_ascii_lowercase() || *byte == b'_').count();
        let (word, rest) = self.input.split_at(length);
        self.input = rest;
        Ok(match word {
            "playing" => self.facts.playing,
            "ended" => self.facts.ended,
            "paused" => self.facts.paused,
            "buffering" => self.facts.buffering,
            "seeking" => self.facts.seeking,
            "advancing" => self.facts.advancing,
            "duration_known" => self.facts.duration_known,
            _ => return Err(()),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn authored_rule_cannot_change_playback_and_preserves_missing_evidence() {
        let evaluate = |rule: &str| native_evaluate_media_rule(rule.into(), NativeMediaFacts {
            playing: Some(true),
            advancing: Some(true),
            buffering: Some(false),
            ..Default::default()
        });
        assert_eq!(evaluate("playing && advancing && !buffering"), NativeMediaRuleResult::Matched);
        assert_eq!(evaluate("ended || (advancing && buffering)"), NativeMediaRuleResult::Unknown);
        assert_eq!(evaluate("!ended"), NativeMediaRuleResult::Unknown);
        assert_eq!(evaluate("playing && buffering || advancing"), NativeMediaRuleResult::Matched);
        for rule in ["playing || eval()", "playing; video.play()", "muted", "playing &&", "!!!!!!!!!!!!playing"] {
            assert_eq!(evaluate(rule), NativeMediaRuleResult::Invalid);
        }
    }
}
