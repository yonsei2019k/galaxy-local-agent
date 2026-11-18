//! Bounded one-shot CLI for the PC worker. One JSON request on stdin, one JSON reply on stdout.
//! Takes no arguments, paths, executables or config: the host passes state in and stores what comes out.
use serde::Deserialize;
use serde_json::{json, Value};
use std::io::{Read, Write};
use ziphap_collab::{render_user_markdown, CollabError, CollabLog, Message, Party};

const MAX_INPUT: u64 = 16 * 1024 * 1024;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PostBody { client_message_id: String, text: String, recipient: String }

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Request {
    Init {},
    List { state: CollabLog, after: u64, limit: u32 },
    Post { state: CollabLog, #[serde(rename = "nowMs")] now_ms: u64, message: PostBody },
    Import { state: CollabLog, #[serde(rename = "nowMs")] now_ms: u64, source: String, markdown: String },
    Render { message: Message },
}

fn run(raw: &[u8]) -> Result<Value, CollabError> {
    let request: Request = serde_json::from_slice(raw).map_err(|_| CollabError::InvalidRequest)?;
    let checked = |state: &CollabLog| state.validate();
    Ok(match request {
        Request::Init {} => json!({"state": CollabLog::new()}),
        Request::List { state, after, limit } => { checked(&state)?; json!({"page": state.list(after, limit)?}) }
        Request::Post { mut state, now_ms, message } => {
            checked(&state)?;
            let posted = state.post_user(&message.client_message_id, &message.text, &message.recipient, now_ms)?;
            json!({"state": state, "message": posted.message, "created": posted.created,
                "dataOnly": true, "executionAuthority": false})
        }
        Request::Import { mut state, now_ms, source, markdown } => {
            checked(&state)?;
            let source = match source.as_str() { "codex" => Party::Codex, "claude" => Party::Claude, _ => return Err(CollabError::InvalidRequest) };
            let imported = state.import_markdown(source, &markdown, now_ms)?.len();
            json!({"state": state, "imported": imported})
        }
        Request::Render { message } => json!({"markdown": render_user_markdown(&message)?}),
    })
}

fn main() {
    if std::env::args().len() != 1 { reply(json!({"ok": false, "error": "COLLAB_USAGE", "status": 400})); std::process::exit(2); }
    let mut raw = Vec::new();
    let read = std::io::stdin().take(MAX_INPUT + 1).read_to_end(&mut raw);
    let value = match read {
        Ok(n) if n as u64 <= MAX_INPUT => match run(&raw) {
            Ok(mut value) => { value["ok"] = json!(true); value }
            Err(e) => json!({"ok": false, "error": e.code(), "status": e.status()}),
        },
        _ => json!({"ok": false, "error": "COLLAB_INPUT_BOUND", "status": 413}),
    };
    reply(value);
}

fn reply(value: Value) {
    let mut out = std::io::stdout().lock();
    let _ = serde_json::to_writer(&mut out, &value); let _ = out.write_all(b"\n"); let _ = out.flush();
}
