//! Offline process fixture: no Codex/account/network/model dependency.
use std::io::{self,BufRead,Write};
fn id(line:&str)->u64 {line.split("\"id\":").nth(1).unwrap_or("0").chars().take_while(|c|c.is_ascii_digit()).collect::<String>().parse().unwrap_or(0)}
fn send(text:String){println!("{text}");io::stdout().flush().unwrap();}
fn main(){
    if std::env::args().any(|arg|arg=="--owned-descendant") {std::thread::sleep(std::time::Duration::from_secs(60));return;}
    let directory=std::env::current_dir().unwrap();let root=directory.parent().unwrap();
    let mode=std::fs::read_to_string(root.join("mode")).unwrap_or_default();
    let mut thread=0;let mut turn=0;let mut tool_response=false;
    for line in io::stdin().lock().lines(){let line=line.unwrap();let n=id(&line);
        let method=if line.contains("\"method\":\"initialize\"") {"initialize"}
            else if line.contains("\"method\":\"model/list\""){"model/list"}
            else if line.contains("\"method\":\"account/read\""){"account/read"}
            else if line.contains("\"method\":\"config/read\""){"config/read"}
            else if line.contains("\"method\":\"thread/start\""){"thread/start"}
            else if line.contains("\"method\":\"thread/unsubscribe\""){"thread/unsubscribe"}
            else if line.contains("\"method\":\"turn/start\""){"turn/start"}else{""};
        if !method.is_empty(){use std::fs::OpenOptions;let mut trace=OpenOptions::new().create(true).append(true).open(root.join("trace")).unwrap();writeln!(trace,"{method}").unwrap();}
        match method {
            "initialize"=>{
                send(format!("{{\"id\":{n},\"result\":{{\"userAgent\":\"offline-fixture\"}}}}"));
                if mode=="metadata" {
                    send("{\"emittedAtMs\":1,\"method\":\"configWarning\",\"params\":{\"summary\":\"private startup warning\",\"details\":\"MUST_NOT_LEAK\"}}".into());
                    send("{\"emittedAtMs\":2,\"method\":\"remoteControl/status/changed\",\"params\":{\"environmentId\":null,\"installationId\":\"private-instance\",\"serverName\":\"MUST_NOT_LEAK\",\"status\":\"disabled\"}}".into());
                }
            },
            "model/list"=>send(format!("{{\"id\":{n},\"result\":{{\"data\":[{{\"model\":\"fixture-model\",\"displayName\":\"Fixture\",\"isDefault\":true,\"supportedReasoningEfforts\":[{{\"reasoningEffort\":\"medium\"}}]}}],\"nextCursor\":null,\"privateToken\":\"MUST_NOT_LEAK\"}}}}")),
            "account/read"=>send(format!("{{\"id\":{n},\"result\":{{\"account\":{{\"type\":\"chatgpt\",\"email\":\"private@example.invalid\",\"planType\":\"fixture\",\"workspaceName\":\"MUST_NOT_LEAK\"}},\"requiresOpenaiAuth\":true}}}}")),
            "config/read"=>{
                let enabled=if mode=="unsafe_config" {"true"}else{"false"};
                send(format!("{{\"id\":{n},\"result\":{{\"config\":{{\"mcp_servers\":{{}},\"web_search\":\"disabled\",\"model_provider\":\"openai\",\"features\":{{\"shell_tool\":{enabled},\"unified_exec\":false,\"apps\":false,\"plugins\":false,\"browser_use\":false,\"computer_use\":false,\"memories\":false,\"multi_agent\":false,\"code_mode\":false,\"shell_snapshot\":false}}}}}}}}"));
            },
            "thread/start"=>{assert!(line.contains("\"ephemeral\":true"));assert!(line.contains("\"sandbox\":\"read-only\""));assert!(line.contains("\"name\":\"ziphap_device\""));thread+=1;send(format!("{{\"id\":{n},\"result\":{{\"thread\":{{\"id\":\"fixture-thread-{thread}\"}}}}}}"));},
            "thread/unsubscribe"=>send(format!("{{\"id\":{n},\"result\":{{}}}}")),
            "turn/start"=>{
                assert!(line.contains("\"networkAccess\":false"));turn+=1;send(format!("{{\"id\":{n},\"result\":{{\"turn\":{{\"id\":\"fixture-turn-{turn}\"}}}}}}"));
                if mode=="wait" {continue;}
                if mode=="descendant" {
                    let mut command=std::process::Command::new(std::env::current_exe().unwrap());command.arg("--owned-descendant").stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
                    #[cfg(windows)] {use std::os::windows::process::CommandExt;command.creation_flags(0x08000000);}
                    let descendant=command.spawn().unwrap();std::fs::write(root.join("descendant-pid"),descendant.id().to_string()).unwrap();continue;
                }
                if mode=="shell" {send(format!("{{\"method\":\"item/started\",\"params\":{{\"threadId\":\"fixture-thread-{thread}\",\"turnId\":\"fixture-turn-{turn}\",\"item\":{{\"type\":\"commandExecution\"}}}}}}"));continue;}
                if mode=="unknown_rpc" {send("{\"id\":\"hostile\",\"method\":\"item/permissions/requestApproval\",\"params\":{}}".into());continue;}
                if mode=="tool"||mode=="unknown_tool"||mode=="foreign_thread" {
                    let tool=if mode=="unknown_tool" {"delete_everything"}else{"get_fixture"};let target=if mode=="foreign_thread" {"foreign-thread".into()}else{format!("fixture-thread-{thread}")};
                    send(format!("{{\"id\":\"server-call-{turn}\",\"method\":\"item/tool/call\",\"params\":{{\"threadId\":\"{target}\",\"turnId\":\"fixture-turn-{turn}\",\"namespace\":\"ziphap_device\",\"callId\":\"fixture-call-{turn}\",\"tool\":\"{tool}\",\"arguments\":{{}}}}}}"));tool_response=true;continue;
                }
                finish(thread,turn,&mode);
            },
            _ if tool_response&&line.contains("\"contentItems\"")=>{tool_response=false;finish(thread,turn,&mode);},
            _=>{},
        }
    }
}
fn finish(thread:u64,turn:u64,mode:&str){
    if mode=="missing_binding" {send("{\"method\":\"item/agentMessage/delta\",\"params\":{\"delta\":\"stale answer\"}}".into());return;}
    if mode=="nonstring_binding" {send("{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":42,\"turnId\":false,\"delta\":\"stale answer\"}}".into());return;}
    send(format!("{{\"method\":\"item/reasoning/textDelta\",\"params\":{{\"threadId\":\"fixture-thread-{thread}\",\"turnId\":\"fixture-turn-{turn}\",\"delta\":\"PRIVATE_REASONING\"}}}}"));
    send(format!("{{\"method\":\"item/agentMessage/delta\",\"params\":{{\"threadId\":\"fixture-thread-{thread}\",\"turnId\":\"fixture-turn-{turn}\",\"delta\":\"{{\\\"state\\\":\\\"reported_done\\\",\\\"answer\\\":\\\"실제 시험 결과\\\",\\\"question\\\":\\\"\\\"}}\"}}}}"));
    send(format!("{{\"method\":\"item/completed\",\"params\":{{\"threadId\":\"fixture-thread-{thread}\",\"turnId\":\"fixture-turn-{turn}\",\"item\":{{\"type\":\"agentMessage\",\"text\":\"{{\\\"state\\\":\\\"reported_done\\\",\\\"answer\\\":\\\"실제 시험 결과\\\",\\\"question\\\":\\\"\\\"}}\"}}}}}}"));
    send(format!("{{\"method\":\"turn/completed\",\"params\":{{\"threadId\":\"fixture-thread-{thread}\",\"turn\":{{\"id\":\"fixture-turn-{turn}\",\"status\":\"completed\"}}}}}}"));
}
