//! Collaboration room log for the owner, Codex and Claude. Owner: Claude (Codex assignment X-001).
//!
//! Pure, bounded state transitions with no I/O. The PC host owns storage (DPAPI), file locations and clocks.
//! Every message is data only: `executionAuthority` is always false and nothing here starts a model, grants a
//! tool or approves an action. The sender is assigned by the host: HTTP posts are always `user`; markdown
//! imports take the sender from the host-chosen source file, never from message text.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const SCHEMA_VERSION: u32 = 1;
pub const MAX_TEXT_CHARS: usize = 8000;
pub const MAX_MESSAGES: usize = 500;
pub const MAX_TOTAL_TEXT_BYTES:usize=4*1024*1024;
pub const MAX_LIST: u32 = 50;
pub const MAX_IMPORTED_IDS: usize = 20_000;
pub const MAX_MARKDOWN_BYTES: usize = 2 * 1024 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Party { User, Codex, Claude }

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Recipient { User, Codex, Claude, All }
impl Recipient {
    fn as_str(self) -> &'static str {
        match self { Self::User => "user", Self::Codex => "codex", Self::Claude => "claude", Self::All => "all" }
    }
    fn parse(value: &str) -> Option<Self> {
        match value { "user" => Some(Self::User), "codex" => Some(Self::Codex), "claude" => Some(Self::Claude), "all" => Some(Self::All), _ => None }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CollabError {
    InvalidRequest,
    TextBound,
    Conflict,
    CursorAhead,
    CursorExpired,
    StateInvalid,
    LedgerFull,
}
impl CollabError {
    pub fn code(self) -> &'static str {
        match self {
            Self::InvalidRequest => "COLLAB_REQUEST", Self::TextBound => "COLLAB_TEXT_BOUND",
            Self::Conflict => "COLLAB_CLIENT_ID_CONFLICT", Self::CursorAhead => "COLLAB_CURSOR_AHEAD",
            Self::CursorExpired => "COLLAB_CURSOR_EXPIRED", Self::StateInvalid => "COLLAB_STATE_INVALID",
            Self::LedgerFull => "COLLAB_IMPORT_LEDGER_FULL",
        }
    }
    pub fn status(self) -> u16 {
        match self {
            Self::InvalidRequest | Self::CursorAhead => 400, Self::TextBound => 413, Self::Conflict => 409,
            Self::CursorExpired => 410, Self::StateInvalid | Self::LedgerFull => 500,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Message {
    pub sequence: u64,
    pub message_id: String,
    pub sender: Party,
    pub recipient: Recipient,
    pub text: String,
    pub created_at_ms: u64,
    pub client_message_id: Option<String>,
    pub body_digest: String,
    pub data_only: bool,
    pub execution_authority: bool,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CollabLog {
    pub schema_version: u32,
    pub next_sequence: u64,
    pub messages: Vec<Message>,
    /// "codex:X-001" / "claude:C-001" keys already imported, kept beyond message retention to prevent re-import.
    pub imported: Vec<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Page {
    pub messages: Vec<Message>,
    pub latest_sequence: u64,
    pub first_retained_sequence: u64,
    pub has_more: bool,
    pub data_only: bool,
    pub execution_authority: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Posted { pub message: Message, pub created: bool }

fn digest(recipient: Recipient, text: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(recipient.as_str().as_bytes()); hash.update([0u8]); hash.update(text.as_bytes());
    format!("{:x}", hash.finalize())
}
fn is_uuid(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 36 && bytes.iter().enumerate().all(|(i, b)| match i {
        8 | 13 | 18 | 23 => *b == b'-',
        _ => b.is_ascii_digit() || (b'a'..=b'f').contains(b),
    })
}
fn valid_text(text: &str) -> Result<(), CollabError> {
    if text.trim().is_empty() || text.chars().any(|c| c.is_control() && c != '\n' && c != '\t') { return Err(CollabError::InvalidRequest); }
    if text.chars().count() > MAX_TEXT_CHARS { return Err(CollabError::TextBound); }
    Ok(())
}
fn bounded(text: &str) -> String {
    if text.chars().count() <= MAX_TEXT_CHARS { return text.to_owned(); }
    let mut out: String = text.chars().take(MAX_TEXT_CHARS - 16).collect();
    out.push_str("\n…(이하 생략)"); out
}
fn import_id(value: &str, prefix: char) -> bool {
    let mut chars = value.chars();
    chars.next() == Some(prefix) && chars.next() == Some('-') && {
        let digits: Vec<char> = chars.collect();
        (1..=6).contains(&digits.len()) && digits.iter().all(char::is_ascii_digit)
    }
}

impl Default for CollabLog { fn default() -> Self { Self::new() } }

impl CollabLog {
    pub fn new() -> Self { Self { schema_version: SCHEMA_VERSION, next_sequence: 1, messages: Vec::new(), imported: Vec::new() } }

    /// Host-stored state is re-validated on every load; anything inconsistent fails closed.
    pub fn validate(&self) -> Result<(), CollabError> {
        let bad = Err(CollabError::StateInvalid);
        if self.schema_version != SCHEMA_VERSION || self.next_sequence == 0 || self.next_sequence==u64::MAX || self.messages.len() > MAX_MESSAGES
            || self.imported.len() > MAX_IMPORTED_IDS { return bad; }
        if self.messages.iter().map(|m|m.text.len()).sum::<usize>()>MAX_TOTAL_TEXT_BYTES{return bad;}
        let mut previous = 0;
        let mut message_ids=std::collections::BTreeSet::new();
        let mut client_ids=std::collections::BTreeSet::new();
        for m in &self.messages {
            if m.sequence <= previous || m.sequence >= self.next_sequence || !m.data_only || m.execution_authority
                || previous>0&&m.sequence!=previous+1 || valid_text(&m.text).is_err() || m.created_at_ms>253_402_300_799_999
                || !message_ids.insert(m.message_id.as_str()) || m.client_message_id.as_ref().is_some_and(|id|!client_ids.insert(id.as_str()))
                || m.body_digest != digest(m.recipient, &m.text) { return bad; }
            let id_ok = match m.sender {
                Party::User => m.message_id == format!("U-{:06}", m.sequence) && m.client_message_id.as_deref().is_some_and(is_uuid),
                Party::Codex => import_id(&m.message_id, 'X') && m.client_message_id.is_none(),
                Party::Claude => import_id(&m.message_id, 'C') && m.client_message_id.is_none(),
            };
            if !id_ok { return bad; }
            previous = m.sequence;
        }
        if self.messages.is_empty()&&self.next_sequence!=1||!self.messages.is_empty()&&previous+1!=self.next_sequence{return bad;}
        let mut seen = std::collections::BTreeSet::new();
        if !self.imported.iter().all(|k| seen.insert(k.as_str())&&(k.strip_prefix("codex:").is_some_and(|id|import_id(id,'X'))||k.strip_prefix("claude:").is_some_and(|id|import_id(id,'C')))) { return bad; }
        Ok(())
    }

    fn push(&mut self, mut message: Message) -> Message {
        message.sequence = self.next_sequence; self.next_sequence += 1;
        if message.sender == Party::User { message.message_id = format!("U-{:06}", message.sequence); }
        self.messages.push(message.clone());
        while self.messages.len()>MAX_MESSAGES||self.messages.iter().map(|m|m.text.len()).sum::<usize>()>MAX_TOTAL_TEXT_BYTES {self.messages.remove(0);}
        message
    }

    /// Owner message from the paired phone. Same client ID + same body returns the original; a different body
    /// under a reused client ID is a conflict. Dedup covers retained messages only.
    pub fn post_user(&mut self, client_message_id: &str, text: &str, recipient: &str, now_ms: u64) -> Result<Posted, CollabError> {
        if self.next_sequence>=u64::MAX-1||now_ms>253_402_300_799_999{return Err(CollabError::InvalidRequest);}
        if !is_uuid(client_message_id) { return Err(CollabError::InvalidRequest); }
        let recipient = Recipient::parse(recipient).ok_or(CollabError::InvalidRequest)?;
        valid_text(text)?;
        let body_digest = digest(recipient, text);
        if let Some(old) = self.messages.iter().find(|m| m.client_message_id.as_deref() == Some(client_message_id)) {
            return if old.body_digest == body_digest { Ok(Posted { message: old.clone(), created: false }) } else { Err(CollabError::Conflict) };
        }
        let message = self.push(Message { sequence: 0, message_id: String::new(), sender: Party::User, recipient, text: text.to_owned(),
            created_at_ms: now_ms, client_message_id: Some(client_message_id.to_owned()), body_digest, data_only: true, execution_authority: false });
        Ok(Posted { message, created: true })
    }

    pub fn list(&self, after: u64, limit: u32) -> Result<Page, CollabError> {
        if !(1..=MAX_LIST).contains(&limit) { return Err(CollabError::InvalidRequest); }
        let latest = self.next_sequence - 1;
        let first = self.messages.first().map_or(latest + 1, |m| m.sequence);
        if after > latest { return Err(CollabError::CursorAhead); }
        if after + 1 < first { return Err(CollabError::CursorExpired); }
        let messages: Vec<Message> = self.messages.iter().filter(|m| m.sequence > after).take(limit as usize).cloned().collect();
        let last = messages.last().map_or(after, |m| m.sequence);
        Ok(Page { messages, latest_sequence: latest, first_retained_sequence: first, has_more: last < latest,
            data_only: true, execution_authority: false })
    }

    /// Imports `## [X-001] … | to: …` sections from an agent's own coordination file. The host chooses `source`
    /// from the file path; a section whose ID prefix does not match the source is ignored (cannot impersonate).
    pub fn import_markdown(&mut self, source: Party, markdown: &str, now_ms: u64) -> Result<Vec<Message>, CollabError> {
        if self.next_sequence>u64::MAX-MAX_IMPORTED_IDS as u64||now_ms>253_402_300_799_999{return Err(CollabError::InvalidRequest);}
        let prefix = match source { Party::Codex => 'X', Party::Claude => 'C', Party::User => return Err(CollabError::InvalidRequest) };
        if markdown.len() > MAX_MARKDOWN_BYTES { return Err(CollabError::InvalidRequest); }
        let default_recipient = if source == Party::Codex { Recipient::Claude } else { Recipient::Codex };
        let source_name = if source == Party::Codex { "codex" } else { "claude" };
        let mut created = Vec::new();
        let mut sections: Vec<(String, String, Vec<&str>)> = Vec::new();
        for line in markdown.lines() {
            if let Some(rest) = line.strip_prefix("## [") {
                if let Some((id, header)) = rest.split_once(']') {
                    sections.push((id.to_owned(), header.to_owned(), Vec::new())); continue;
                }
            }
            if line.starts_with("## ") { sections.push((String::new(), String::new(), Vec::new())); continue; }
            if let Some(last) = sections.last_mut() { last.2.push(line); }
        }
        for (id, header, body) in sections {
            if !import_id(&id, prefix) { continue; }
            let key = format!("{source_name}:{id}");
            if self.imported.iter().any(|k| k == &key) { continue; }
            if self.imported.len() >= MAX_IMPORTED_IDS { return Err(CollabError::LedgerFull); }
            let recipient = header.split('|').filter_map(|f| f.trim().strip_prefix("to:")).filter_map(|v| Recipient::parse(v.trim())).next()
                .unwrap_or(default_recipient);
            let text = bounded(format!("[{id}]{header}\n{}", body.join("\n")).trim());
            if text.chars().any(|c| c.is_control() && c != '\n' && c != '\t') { self.imported.push(key); continue; }
            self.imported.push(key);
            let body_digest = digest(recipient, &text);
            created.push(self.push(Message { sequence: 0, message_id: id, sender: source, recipient, text, created_at_ms: now_ms,
                client_message_id: None, body_digest, data_only: true, execution_authority: false }));
        }
        Ok(created)
    }
}

/// Markdown block appended to the host-owned `user-to-agents.md` so the existing v1 watchers see owner messages.
/// Body lines are quoted, so owner text cannot forge a `## [X-…]` header or another sender.
pub fn render_user_markdown(message: &Message) -> Result<String, CollabError> {
    if message.sender != Party::User || message.recipient == Recipient::User { return Err(CollabError::InvalidRequest); }
    let mut out = format!("\n## [{}] {} | type: request | needs-response: yes | priority: normal | to: {}\n",
        message.message_id, utc_minute(message.created_at_ms), message.recipient.as_str());
    for line in message.text.lines() { out.push_str("> "); out.push_str(line); out.push('\n'); }
    Ok(out)
}

/// `YYYY-MM-DD HH:MM UTC` without a date library (civil-from-days, Howard Hinnant).
pub fn utc_minute(ms: u64) -> String {
    let secs = ms / 1000; let days = (secs / 86_400) as i64; let rem = secs % 86_400;
    let z = days + 719_468; let era = z.div_euclid(146_097); let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365; let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153; let day = doy - (153 * mp + 2) / 5 + 1; let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("{year:04}-{month:02}-{day:02} {:02}:{:02} UTC", rem / 3600, (rem % 3600) / 60)
}
