//! Local IPC adapter. This is not a network listener or arbitrary JSON-RPC pass-through.
use serde::Deserialize;
use serde_json::{json, Value};
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use ziphap_pc::{
    strict_json, AppServerClient, AppServerConfig, RunRequest, ToolResultInput, MAX_LINE,
};

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Input {
    Health {},
    Models {},
    Start {
        request: RunRequest,
    },
    ToolResult {
        result: ToolResultInput,
    },
    RefreshPhoneAuthority {
        #[serde(rename = "requestId")]
        request_id: String,
        #[serde(rename = "authorityRevision")]
        authority_revision: u64,
        #[serde(rename = "expiresAtEpochMs")]
        expires_at_epoch_ms: u64,
    },
    Cancel {
        #[serde(rename = "requestId")]
        request_id: String,
    },
}

async fn output(value: Value) -> std::io::Result<()> {
    let mut bytes = serde_json::to_vec(&value)?;
    bytes.push(b'\n');
    let mut out = tokio::io::stdout();
    out.write_all(&bytes).await?;
    out.flush().await
}

#[tokio::main]
async fn main() {
    if let Err(code) = execute().await {
        let _ = output(json!({"type":"error","errorCode":code})).await;
        std::process::exit(1);
    }
}

async fn execute() -> Result<(), &'static str> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() != 3
        || args[0] != "--config"
        || !matches!(args[2].as_str(), "--stdio" | "--models" | "--health")
    {
        return Err("USAGE_CONFIG_AND_STDIO_MODE_REQUIRED");
    }
    let config = AppServerConfig::read(&PathBuf::from(&args[1])).map_err(|e| e.code)?;
    let mut client = AppServerClient::spawn(config).await.map_err(|e| e.code)?;
    if args[2] == "--models" {
        output(json!({"type":"models","catalog":client.models().await.map_err(|e|e.code)?}))
            .await
            .map_err(|_| "IPC_OUTPUT")?;
        client.shutdown().await;
        return Ok(());
    }
    if args[2] == "--health" {
        let blocker = client.isolation_blocker().await.map_err(|e| e.code)?;
        output(json!({"type":"health","account":client.account().await.map_err(|e|e.code)?,"isolationQualified":blocker.is_none(),"isolationBlocker":blocker,"transport":"owned_stdio","inferenceInvoked":false})).await.map_err(|_|"IPC_OUTPUT")?;
        client.shutdown().await;
        return Ok(());
    }
    let mut input = BufReader::new(tokio::io::stdin());
    let mut buffer = Vec::new();
    loop {
        if client.has_pending_events() {
            let event = client.next_event().await.map_err(|e| e.code)?;
            output(serde_json::to_value(event).map_err(|_| "IPC_OUTPUT")?)
                .await
                .map_err(|_| "IPC_OUTPUT")?;
            continue;
        }
        enum Next {
            Input(std::io::Result<usize>),
            Event(Result<ziphap_pc::ProviderEvent, ziphap_pc::ProviderError>),
        }
        // read_until is cancellation-safe: its partial buffer survives event delivery.
        let next = if client.has_active_run() {
            tokio::select! {
                value=read_bounded(&mut input,&mut buffer)=>Next::Input(value),
                event=client.next_event()=>Next::Event(event),
            }
        } else {
            Next::Input(read_bounded(&mut input, &mut buffer).await)
        };
        match next {
            Next::Event(result) => {
                output(
                    serde_json::to_value(result.map_err(|e| e.code)?).map_err(|_| "IPC_OUTPUT")?,
                )
                .await
                .map_err(|_| "IPC_OUTPUT")?;
            }
            Next::Input(result) => {
                if result.map_err(|_| "IPC_INPUT")? == 0 {
                    client.shutdown().await;
                    return Ok(());
                }
                if buffer.len() > MAX_LINE {
                    client.shutdown().await;
                    return Err("IPC_BOUND");
                }
                let value = strict_json(&buffer).map_err(|e| e.code)?;
                buffer.clear();
                let request: Input = serde_json::from_value(value).map_err(|_| "IPC_OPERATION")?;
                match request {
                    Input::Health {} => {
                        let blocker = client.isolation_blocker().await.map_err(|e| e.code)?;
                        output(json!({"type":"health","account":client.account().await.map_err(|e|e.code)?,"isolationQualified":blocker.is_none(),"isolationBlocker":blocker,"transport":"owned_stdio","inferenceInvoked":false})).await.map_err(|_|"IPC_OUTPUT")?;
                    }
                    Input::Models {} => {
                        output(json!({"type":"models","catalog":client.models().await.map_err(|e|e.code)?})).await.map_err(|_|"IPC_OUTPUT")?;
                    }
                    Input::Start { request } => {
                        client.begin(request).await.map_err(|e| e.code)?;
                    }
                    Input::ToolResult { result } => {
                        client
                            .submit_tool_result(result)
                            .await
                            .map_err(|e| e.code)?;
                    }
                    Input::RefreshPhoneAuthority {
                        request_id,
                        authority_revision,
                        expires_at_epoch_ms,
                    } => {
                        client
                            .refresh_phone_authority(
                                &request_id,
                                authority_revision,
                                expires_at_epoch_ms,
                            )
                            .map_err(|e| e.code)?;
                    }
                    Input::Cancel { request_id } => {
                        let event = client.cancel(&request_id).await.map_err(|e| e.code)?;
                        output(serde_json::to_value(event).map_err(|_| "IPC_OUTPUT")?)
                            .await
                            .map_err(|_| "IPC_OUTPUT")?;
                        // A cancelled adapter exits; the host must create a new process and fresh capabilities.
                        return Ok(());
                    }
                }
            }
        }
    }
}

async fn read_bounded<R: tokio::io::AsyncBufRead + Unpin>(
    input: &mut R,
    buffer: &mut Vec<u8>,
) -> std::io::Result<usize> {
    loop {
        let available = input.fill_buf().await?;
        if available.is_empty() {
            return Ok(0);
        }
        let count = available
            .iter()
            .position(|b| *b == b'\n')
            .map_or(available.len(), |i| i + 1);
        if buffer.len().saturating_add(count) > MAX_LINE {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "IPC_BOUND",
            ));
        }
        let ended = available[count - 1] == b'\n';
        buffer.extend_from_slice(&available[..count]);
        input.consume(count);
        if ended {
            return Ok(buffer.len());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn adapter_has_no_arbitrary_rpc_config_or_shell_operation() {
        assert!(serde_json::from_value::<Input>(json!({"op":"health","shell":"whoami"})).is_err());
        assert!(serde_json::from_value::<Input>(
            json!({"op":"models","config":{"mcp_servers":{}}})
        )
        .is_err());
        assert!(
            serde_json::from_value::<Input>(json!({"op":"rpc","method":"command/exec"})).is_err()
        );
        assert!(serde_json::from_value::<Input>(json!({"op":"health"})).is_ok());
    }
    #[tokio::test]
    async fn bounded_input_does_not_allocate_an_unlimited_newline_free_command() {
        let raw = vec![b'x'; MAX_LINE + 1];
        let mut input = BufReader::new(raw.as_slice());
        let mut buffer = Vec::new();
        assert!(read_bounded(&mut input, &mut buffer).await.is_err());
        assert!(buffer.len() <= MAX_LINE);
    }
}
