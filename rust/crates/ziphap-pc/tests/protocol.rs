use serde_json::json;
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use ziphap_pc::*;

struct Fixture {
    root: PathBuf,
    config: AppServerConfig,
}
static FIXTURE_SEQUENCE: AtomicU64 = AtomicU64::new(1);
impl Fixture {
    fn new(mode: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "ziphap-pc-protocol-{}-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            FIXTURE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("mode"), mode).unwrap();
        let exe = root.join(if cfg!(windows) {
            "offline-stub.exe"
        } else {
            "offline-stub"
        });
        let source = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/support/stub_server.rs");
        let mut compiler = std::process::Command::new("rustc");
        compiler
            .arg("--edition=2021")
            .arg(source)
            .arg("-o")
            .arg(&exe);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            compiler.creation_flags(0x08000000);
        }
        assert!(
            compiler.status().unwrap().success(),
            "offline fixture did not compile"
        );
        let digest = format!("{:x}", Sha256::digest(std::fs::read(&exe).unwrap()));
        Self {
            config: AppServerConfig {
                executable: exe,
                executable_sha256: digest,
                runtime_directory: root.clone(),
            },
            root,
        }
    }
    fn trace(&self) -> String {
        std::fs::read_to_string(self.root.join("trace")).unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        assert!(self
            .root
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("ziphap-pc-protocol-"));
        let _ = std::fs::remove_dir_all(&self.root);
    }
}
fn request(id: &str) -> RunRequest {
    RunRequest {
        request_id: id.into(),
        binding: RunBinding {
            owner_id: "owner-fixture".into(),
            project_binding: "project-fixture".into(),
            thread_binding: "thread-fixture".into(),
            authority_revision: 1,
            execution_profile: "pc-only".into(),
        },
        model: "fixture-model".into(),
        effort: Some("medium".into()),
        owner_request: "Synthetic bounded request".into(),
        tools: vec![],
        external_allowed: true,
        phone_authority_expires_at_epoch_ms: None,
        timeout_ms: 10000,
    }
}
#[tokio::test]
async fn stdio_failure_names_only_the_active_host_request() {
    let fault = stop_stdio_with_pending_phone_call(false).await;
    assert_eq!(
        json!({"type":"error","errorCode":"RUN_BINDING","requestId":"active-fixture","pendingPhoneCallId":"fixture-call-1"}),
        fault
    );
}

#[tokio::test]
async fn stdio_cancel_keeps_the_outstanding_phone_call_reference() {
    let notice = stop_stdio_with_pending_phone_call(true).await;
    assert_eq!(
        json!({"type":"cancelled","requestId":"active-fixture","pendingPhoneCallId":"fixture-call-1"}),
        notice
    );
}

async fn stop_stdio_with_pending_phone_call(cancel: bool) -> serde_json::Value {
    use std::process::Stdio;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    let fixture = Fixture::new("tool");
    let config = fixture.root.join("adapter.json");
    std::fs::write(
        &config,
        json!({
            "executable":fixture.config.executable,
            "executableSha256":fixture.config.executable_sha256,
            "runtimeDirectory":fixture.config.runtime_directory
        })
        .to_string(),
    )
    .unwrap();
    let mut child = tokio::process::Command::new(env!("CARGO_BIN_EXE_ziphap-pc"))
        .arg("--config")
        .arg(config)
        .arg("--stdio")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let mut input = child.stdin.take().unwrap();
    let mut lines = BufReader::new(child.stdout.take().unwrap()).lines();
    let deadline = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64 + 20_000;
    let start = json!({"op":"start","request":{
        "requestId":"active-fixture",
        "binding":{"ownerId":"owner-fixture","projectBinding":"project-fixture","threadBinding":"thread-fixture",
            "authorityRevision":1,"executionProfile":"pc-only"},
        "model":"fixture-model","ownerRequest":"Synthetic bounded request","externalAllowed":true,"timeoutMs":10000,
        "phoneAuthorityExpiresAtEpochMs":deadline,
        "tools":[{"name":"get_fixture","description":"Synthetic fixture","location":"phone",
            "inputSchema":{"type":"object","properties":{},"additionalProperties":false}}]
    }});
    input
        .write_all(format!("{start}\n").as_bytes())
        .await
        .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        let call_id = loop {
            let line = lines.next_line().await.unwrap().unwrap();
            let event: serde_json::Value = serde_json::from_str(&line).unwrap();
            if event["type"] == "tool_call" {
                break event["callId"].as_str().unwrap().to_owned();
            }
            assert_ne!(event["type"], "error", "{event}");
        };
        let stop = if cancel {
            json!({"op":"cancel","requestId":"active-fixture"})
        } else {
            json!({"op":"tool_result","result":{
                "requestId":"unbound-fixture","callId":call_id,"success":true,"text":"hidden fixture"
            }})
        };
        input
            .write_all(format!("{stop}\n").as_bytes())
            .await
            .unwrap();
        let line = lines.next_line().await.unwrap().unwrap();
        let notice = serde_json::from_str(&line).unwrap();
        assert_eq!(cancel, child.wait().await.unwrap().success());
        notice
    })
    .await
    .unwrap()
}
async fn complete(client: &mut AppServerClient) -> Vec<ProviderEvent> {
    let mut events = vec![];
    loop {
        let event = client.next_event().await.unwrap();
        let done = matches!(event, ProviderEvent::Report { .. });
        events.push(event);
        if done {
            break;
        }
    }
    events
}

#[test]
fn duplicate_and_deep_json_keys_never_change_authority() {
    assert!(strict_json(br#"{"binding":{"owner":"a","owner":"b"}}"#).is_err());
    assert!(strict_json(br#"{"op":"models","op":"start"}"#).is_err());
    assert!(strict_json(b"{} {}").is_err());
    assert!(strict_json(br#"{"one":1,"nested":{"safe":true}}"#).is_ok());
}

#[test]
fn headless_browser_builder_never_uses_a_visible_or_existing_user_profile() {
    let f = Fixture::new("");
    let browser = headless::HeadlessBrowser::prepare(
        f.config.executable.clone(),
        f.config.executable_sha256.clone(),
        f.root.clone(),
    )
    .unwrap();
    let other = headless::HeadlessBrowser::prepare(
        f.config.executable.clone(),
        f.config.executable_sha256.clone(),
        f.root.clone(),
    )
    .unwrap();
    assert_ne!(browser.profile(), other.profile());
    assert!(browser
        .profile()
        .starts_with(f.root.canonicalize().unwrap()));
    let args = browser.arguments();
    assert!(args.iter().any(|s| s == "--headless=new"));
    assert!(args.iter().any(|s| s == "--remote-debugging-pipe"));
    assert_eq!(args.last().unwrap(), "about:blank");
    assert!(!args
        .iter()
        .any(|s| s.starts_with("--profile-directory") || s.starts_with("--remote-debugging-port")));
    assert_eq!(
        args.iter()
            .filter(|s| s.starts_with("--user-data-dir="))
            .count(),
        1
    );
}

#[tokio::test]
async fn pinned_process_catalog_and_account_are_sanitized_without_inference() {
    let f = Fixture::new("");
    let mut bad = f.config.clone();
    bad.executable_sha256 = "0".repeat(64);
    assert!(AppServerClient::spawn(bad).await.is_err());
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    let models = client.models().await.unwrap();
    let account = client.account().await.unwrap();
    let public = format!("{models}{account}");
    assert!(!public.contains("MUST_NOT_LEAK"));
    assert!(!public.contains("private@"));
    assert_eq!(models["models"][0]["model"], "fixture-model");
    assert_eq!(account["authenticated"], true);
    assert!(!f.trace().contains("thread/start"));
    client.shutdown().await;
}

#[tokio::test]
async fn actual_cli_startup_metadata_shapes_are_discarded_without_leaking_identity_or_authority() {
    let f = Fixture::new("metadata");
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    let models = client.models().await.unwrap();
    let account = client.account().await.unwrap();
    assert!(client.isolation_qualified().await.unwrap());
    let public = format!("{models}{account}");
    assert!(!public.contains("MUST_NOT_LEAK"));
    assert!(!public.contains("private"));
    assert!(!client.has_active_run());
    assert!(!f.trace().contains("thread/start"));
    assert!(!f.trace().contains("turn/start"));
    client.shutdown().await;
}

#[tokio::test]
async fn ephemeral_threads_reuse_only_exact_binding_and_fresh_capabilities() {
    let f = Fixture::new("");
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    client.begin(request("first")).await.unwrap();
    let events = complete(&mut client).await;
    let public = serde_json::to_string(&events).unwrap();
    assert!(!public.contains("PRIVATE_REASONING"));
    assert!(!public.contains("verified"));
    assert!(events
        .iter()
        .any(|v| matches!(v, ProviderEvent::AnswerDelta { .. })));
    client.begin(request("second")).await.unwrap();
    complete(&mut client).await;
    assert_eq!(f.trace().matches("thread/start").count(), 1);
    let mut changed = request("third");
    changed.binding.authority_revision = 2;
    client.begin(changed).await.unwrap();
    complete(&mut client).await;
    assert_eq!(f.trace().matches("thread/start").count(), 2);
    let mut changed = request("fourth");
    changed.tools = vec![ToolCapability {
        name: "get_fixture".into(),
        description: "Read synthetic data".into(),
        input_schema: json!({"type":"object","additionalProperties":false,"properties":{}}),
        location: ToolLocation::Pc,
    }];
    client.begin(changed).await.unwrap();
    complete(&mut client).await;
    assert_eq!(f.trace().matches("thread/start").count(), 3);
    assert_eq!(
        client.begin(request("first")).await.err().unwrap().code,
        "REQUEST_REPLAY"
    );
}

#[tokio::test]
async fn bounded_dynamic_tool_proposals_need_exact_owned_result_and_are_never_effect_receipts() {
    let f = Fixture::new("tool");
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    let mut req = request("tool-run");
    req.tools = vec![ToolCapability {
        name: "get_fixture".into(),
        description: "Read synthetic".into(),
        input_schema: json!({"type":"object","properties":{},"additionalProperties":false}),
        location: ToolLocation::Pc,
    }];
    client.begin(req).await.unwrap();
    let proposal = loop {
        if let event @ ProviderEvent::ToolCall { .. } = client.next_event().await.unwrap() {
            break event;
        }
    };
    let ProviderEvent::ToolCall { call_id, .. } = proposal else {
        panic!()
    };
    assert!(client
        .submit_tool_result(ToolResultInput {
            request_id: "foreign-run".into(),
            call_id: call_id.clone(),
            success: true,
            text: "not delivered".into()
        })
        .await
        .is_err());
    client
        .submit_tool_result(ToolResultInput {
            request_id: "tool-run".into(),
            call_id: call_id.clone(),
            success: true,
            text: "synthetic observed host result".into(),
        })
        .await
        .unwrap();
    assert!(client
        .submit_tool_result(ToolResultInput {
            request_id: "tool-run".into(),
            call_id,
            success: true,
            text: "duplicate".into()
        })
        .await
        .is_err());
    let events = complete(&mut client).await;
    assert!(
        matches!(events.last(),Some(ProviderEvent::Report{state,calls:1,..}) if state=="reported_done")
    );
    client.shutdown().await;
}

#[tokio::test]
async fn shell_unknown_rpc_foreign_binding_and_unoffered_tools_fail_closed() {
    for mode in [
        "shell",
        "unknown_rpc",
        "unknown_tool",
        "foreign_thread",
        "missing_binding",
        "nonstring_binding",
    ] {
        let f = Fixture::new(mode);
        let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
        client.begin(request(mode)).await.unwrap();
        let mut failure = None;
        for _ in 0..5 {
            match client.next_event().await {
                Ok(_) => {}
                Err(e) => {
                    failure = Some(e.code);
                    break;
                }
            }
        }
        assert!(failure.is_some(), "{mode} was not rejected");
        assert!(!client.has_active_run());
    }
}

#[tokio::test]
async fn unsafe_ambient_config_is_rejected_before_creating_a_thread_or_starting_inference() {
    let f = Fixture::new("unsafe_config");
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    assert_eq!(
        client.begin(request("unsafe")).await.err().unwrap().code,
        "ISOLATION_UNCONFIRMED"
    );
    assert!(!f.trace().contains("thread/start"));
    assert!(!f.trace().contains("turn/start"));
}

#[tokio::test]
async fn queued_phone_proposal_and_result_are_rechecked_against_absolute_authority_and_run_deadlines(
) {
    fn phone_request(id: &str) -> RunRequest {
        let mut req = request(id);
        req.phone_authority_expires_at_epoch_ms = Some(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64
                + 500,
        );
        req.tools = vec![ToolCapability {
            name: "get_fixture".into(),
            description: "Fixture phone read".into(),
            input_schema: json!({"type":"object","properties":{},"additionalProperties":false}),
            location: ToolLocation::Phone,
        }];
        req
    }
    let f = Fixture::new("tool");
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    client.begin(phone_request("queued")).await.unwrap();
    assert!(matches!(
        client.next_event().await.unwrap(),
        ProviderEvent::Progress { .. }
    ));
    assert!(matches!(
        client.next_event().await.unwrap(),
        ProviderEvent::Progress { .. }
    ));
    tokio::time::sleep(std::time::Duration::from_millis(550)).await;
    assert_eq!(
        client.next_event().await.err().unwrap().code,
        "PHONE_AUTHORITY_EXPIRED"
    );
    assert!(!client.has_active_run());
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    client.begin(phone_request("late-result")).await.unwrap();
    let call = loop {
        if let ProviderEvent::ToolCall { call_id, .. } = client.next_event().await.unwrap() {
            break call_id;
        }
    };
    tokio::time::sleep(std::time::Duration::from_millis(550)).await;
    assert_eq!(
        client
            .submit_tool_result(ToolResultInput {
                request_id: "late-result".into(),
                call_id: call,
                success: true,
                text: "private late result must not reach model".into()
            })
            .await
            .err()
            .unwrap()
            .code,
        "PHONE_AUTHORITY_EXPIRED"
    );
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    let mut req = phone_request("run-timeout");
    req.tools[0].location = ToolLocation::Pc;
    req.timeout_ms = 100;
    client.begin(req).await.unwrap();
    let call = loop {
        if let ProviderEvent::ToolCall { call_id, .. } = client.next_event().await.unwrap() {
            break call_id;
        }
    };
    tokio::time::sleep(std::time::Duration::from_millis(150)).await;
    assert_eq!(
        client
            .submit_tool_result(ToolResultInput {
                request_id: "run-timeout".into(),
                call_id: call,
                success: true,
                text: "late".into()
            })
            .await
            .err()
            .unwrap()
            .code,
        "RUN_TIMEOUT"
    );
}

#[tokio::test]
async fn cancellation_kills_process_and_revokes_every_cached_thread_without_replay() {
    let f = Fixture::new("wait");
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    client.begin(request("cancel-me")).await.unwrap();
    assert!(client.cancel("wrong-run").await.is_err());
    assert!(client.has_active_run());
    assert!(matches!(
        client.cancel("cancel-me").await.unwrap(),
        ProviderEvent::Cancelled { .. }
    ));
    assert!(!client.has_active_run());
    assert_eq!(
        client
            .begin(request("new-after-cancel"))
            .await
            .err()
            .unwrap()
            .code,
        "PROCESS_REVOKED"
    );
    assert_eq!(f.trace().matches("turn/start").count(), 1);
}

#[tokio::test]
async fn owned_tree_cancellation_terminates_an_actual_offline_descendant() {
    let f = Fixture::new("descendant");
    let mut client = AppServerClient::spawn(f.config.clone()).await.unwrap();
    client.begin(request("owned-tree")).await.unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    let pid = loop {
        if let Ok(raw) = std::fs::read_to_string(f.root.join("descendant-pid")) {
            break raw.parse::<u32>().unwrap();
        }
        assert!(std::time::Instant::now() < deadline);
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    };
    #[cfg(windows)]
    {
        use std::ffi::c_void;
        #[link(name = "kernel32")]
        extern "system" {
            fn OpenProcess(access: u32, inherit: i32, pid: u32) -> *mut c_void;
            fn WaitForSingleObject(handle: *mut c_void, timeout: u32) -> u32;
            fn CloseHandle(handle: *mut c_void) -> i32;
        }
        let handle = unsafe { OpenProcess(0x100000 | 0x1000, 0, pid) };
        assert!(!handle.is_null());
        client.cancel("owned-tree").await.unwrap();
        let status = unsafe { WaitForSingleObject(handle, 5000) };
        unsafe {
            CloseHandle(handle);
        }
        assert_eq!(status, 0, "owned descendant survived job termination");
    }
    #[cfg(unix)]
    {
        client.cancel("owned-tree").await.unwrap();
        let stat = PathBuf::from(format!("/proc/{pid}/stat"));
        for _ in 0..100 {
            if !stat.exists()
                || std::fs::read_to_string(&stat)
                    .map(|v| v.split_whitespace().nth(2) == Some("Z"))
                    .unwrap_or(true)
            {
                return;
            }
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
        panic!("owned descendant survived process group termination");
    }
}
