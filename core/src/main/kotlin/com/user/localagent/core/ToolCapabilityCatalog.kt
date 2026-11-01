package com.user.localagent.core

/** Availability is a prerequisite snapshot, never a grant or proof of a completed action. */
enum class CapabilityAvailability { INSTALLED, REQUIRES_PERMISSION, REQUIRES_MODEL, NOT_IMPLEMENTED, UNVALIDATED }
data class ToolCapability(
    val id:String,
    val title:String,
    val description:String,
    val availability:CapabilityAvailability,
    val requirements:List<String>,
    val requiresConfirmation:Boolean,
    val offline:Boolean,
    val limitation:String
)
data class CapabilityEnvironment(
    val grantedPermissions:Set<String> = emptySet(),
    val installedHandlers:Set<String> = emptySet(),
    val installedModels:Set<String> = emptySet(),
    val validatedConnectors:Set<String> = emptySet(),
    val validatedCapabilities:Set<String> = emptySet(),
    val selectedDocumentAvailable:Boolean=false,
    val selectedOutputAvailable:Boolean=false,
    val apiLevel:Int=37
)

object ToolCapabilityCatalog {
    /** Handler keys: calendar/alarm/sms/email/maps/browser; model keys: text/image/asr.
     * Special access keys: notification_access/usage_access/screen_access.
     * Android runtime permission keys use full android.permission.* names. */
    fun snapshot(environment:CapabilityEnvironment=CapabilityEnvironment()):List<ToolCapability> {
        val env=environment.copy(grantedPermissions=environment.grantedPermissions.toSet(),installedHandlers=environment.installedHandlers.toSet(),installedModels=environment.installedModels.toSet(),validatedConnectors=environment.validatedConnectors.toSet(),validatedCapabilities=environment.validatedCapabilities.toSet())
        require(env.apiLevel>=1)
        val entries=ToolRegistry.definitions.values.map {tool->
            val connection=com.user.localagent.core.connections.ConnectionCatalog.tools[tool.name]
            val pcTool=tool.name in com.user.localagent.core.connections.PcToolCatalog.names
            val consumer=com.user.localagent.core.connections.ConsumerAppCatalog.appForTool[tool.name]
            val goalTool=tool.name in GoalToolCatalog.names
            val permission=if(pcTool)"connector:pc-tools" else connection?.let {"connector:${it.service.id}"+if(it.writes)":write" else ""}?:when(tool.name){
                "get_sms_summary","analyze_sms"->"android.permission.READ_SMS"
                "get_call_log_summary"->"android.permission.READ_CALL_LOG"
                "search_contacts"->"android.permission.READ_CONTACTS"
                "list_calendar_events"->"android.permission.READ_CALENDAR"
                "list_audio_recordings","transcribe_recording"->if(env.apiLevel>=33)"android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
                "list_messenger_notifications","reply_messenger_notification"->"notification_access"
                "read_kakao_screen"->"kakao_screen_access"
                else->null
            }
            val handler=consumer?.let {"consumer:${it.id}"}?:when(tool.name){"prepare_calendar_event"->"calendar";"create_alarm"->"alarm";"prepare_sms"->"sms";"prepare_email"->"email";"open_map"->"maps";"open_url"->"browser";else->null}
            val model=when(tool.name){"generate_image"->"image";"transcribe_recording"->"asr_file";in PrivateAnalysisToolCatalog.analysisNames->"text";else->null}
            val messenger=tool.name in setOf("list_messenger_notifications","reply_messenger_notification")
            val state=when {
                permission!=null&&permission !in env.grantedPermissions->CapabilityAvailability.REQUIRES_PERMISSION
                tool.name=="read_kakao_screen"&&tool.name !in env.validatedCapabilities->CapabilityAvailability.UNVALIDATED
                model!=null&&model !in env.installedModels->CapabilityAvailability.REQUIRES_MODEL
                handler!=null&&handler !in env.installedHandlers->CapabilityAvailability.UNVALIDATED
                messenger&&env.validatedConnectors.intersect(ToolRegistry.supportedMessengerPackages).isEmpty()->CapabilityAvailability.UNVALIDATED
                connection!=null&&connection.service.id !in env.validatedConnectors->CapabilityAvailability.UNVALIDATED
                pcTool&&"pc-tools" !in env.validatedConnectors->CapabilityAvailability.UNVALIDATED
                else->CapabilityAvailability.INSTALLED
            }
            ToolCapability(tool.name,tool.name,tool.description.ifBlank {"기기 내장 도구"},state,listOfNotNull(permission,handler?.let {"Android handler: $it"},model?.let {"Verified local model: $it"})+
                (if(goalTool)listOf("현재 대화의 전체 접근 범위")else emptyList())+
                (if(tool.name in GoalToolCatalog.mutationNames)listOf("사용자가 명시한 목표 관리 요청")else emptyList()),tool.risk!=Risk.LOW,
                connection==null && !pcTool && consumer==null && tool.name !in EvidenceToolCatalog.names && tool.name !in setOf("search_web","search_shopping","read_webpage","open_url","open_map","prepare_sms","prepare_email","reply_messenger_notification"),
                when(tool.name){
                    "read_kakao_screen"->"개별 요청에서 현재 화면만 제한적으로 읽습니다. 전체 대화방·과거 기록·요약 정확도는 별도 실기기 검증이 필요하며 전송·입력·삭제는 지원하지 않습니다."
                    GoalToolCatalog.GET->"이 대화에 저장된 목표와 완료 기준의 일부 페이지를 읽습니다. 목표가 없다는 조회나 지침 읽기는 실제 업무 완료 근거가 아닙니다."
                    GoalToolCatalog.CREATE,GoalToolCatalog.UPDATE->"명시한 요청으로 기기의 대화 목표를 초안으로 저장·수정합니다. 작업 변경은 기존 실행을 정리하고 근거를 초기화하며, 동일한 내용은 실제 근거를 유지합니다. 실행·외부 권한이 추가되지 않습니다."
                    GoalToolCatalog.START,GoalToolCatalog.RESUME->"사용자가 요청한 기존 목표를 한 번의 제한된 실행으로 대기열에 넣습니다. 대기열 저장은 목표 완료·주기 실행·외부 작업 권한이 아닙니다."
                    GoalToolCatalog.PAUSE->"이 목표에 연결된 실행을 중단하고 일시 정지 상태를 저장합니다. 호출한 실행의 중단이 뒤로 미뤄지면 PREPARED로 구분하며 중단 완료라고 표시하지 않습니다."
                    GoalToolCatalog.CLEAR->"명시한 요청과 승인으로 대화 목표만 삭제합니다. 연결 실행을 먼저 정리하고, 이미 수행한 외부 작업이나 대화 기록은 삭제하지 않습니다."
                    "prepare_sms","prepare_email"->"작성 화면만 준비하며 실제 발송 완료를 의미하지 않습니다."
                    "prepare_calendar_event","create_alarm"->"외부 확인 화면 준비와 실제 저장 완료는 별도 검증합니다."
                    "list_messenger_notifications"->"허용한 앱의 수신 세션 알림이며 전체 과거 대화 접근이 아닙니다."
                    "reply_messenger_notification"->"앱별 허용·유효 알림 액션·별도 승인이 필요하며 전송 결과는 별도로 확인합니다."
                    "get_sms_summary"->"권한이 허용한 문자 통계·일부 발췌이며 전체 의미 분석 완료를 뜻하지 않습니다."
                    "get_call_log_summary"->"통화 기록 메타데이터이며 녹음 오디오 분석이 아닙니다."
                    "list_audio_recordings"->"녹음 파일 메타데이터만 조회하며 오디오를 전사하지 않습니다."
                    "transcribe_recording"->"현재 요청에서 실제 목록으로 받은 녹음 ID와 사용 가능한 파일 ASR가 필요합니다. 비공개 전사문·근거를 저장하며 단어 시각·화자·인식 정확도를 보증하지 않습니다."
                    "analyze_sms","analyze_audio_transcript","analyze_conversation_import"->"허용한 원문과 질문을 로컬 모델로 분석해 근거·처리 범위를 저장합니다. 일부 처리·정확 인용과 의미 정확성을 구분하며 자동으로 기억이나 외부 상태를 바꾸지 않습니다."
                    "list_analysis_sources","read_analysis_evidence"->"현재 범위의 자료 목록·저장된 분석 근거만 읽습니다. 목록 수와 정확 인용은 전체 분석 또는 의미 정확성의 보증이 아닙니다."
                    "search_audio_transcripts"->"이미 저장된 전사문만 검색하며 미전사 파일은 분석하지 않습니다."
                    "generate_image"->"검증된 휴대폰 이미지 가중치 또는 소유자가 연결한 사용 가능한 PC가 필요합니다. PC에는 장면·선택 참고 사진만 계산용으로 보내며 작업과 완성 파일은 스마트폰에 저장합니다."
                    "search_publications","verify_reference"->"명시한 공개 주제·서지정보만 전송합니다. 초록·메타데이터 조회는 연구 결론 검증이 아니며 조회 실패·미등록을 허위 인용으로 단정하지 않습니다."
                    "calculate","date_calculate","find_free_time"->"입력한 수식·날짜·시간 구간만 기기에서 계산하며 실제 계정 일정이나 근무 패턴을 추측하지 않습니다."
                    "search_web","search_shopping","read_webpage"->"명시한 공개 질의만 전송합니다. 사이트 접근·가격·재고는 실제 원문으로 확인해야 합니다."
                    else->"실행 권한과 대상 범위는 호출마다 따로 검사하고 결과를 검증합니다."
                })
        }.toMutableList()
        fun file(id:String,title:String,write:Boolean=false,limit:String){
            val selected=if(write)env.selectedOutputAvailable else env.selectedDocumentAvailable
            entries+=ToolCapability(id,title,"사용자가 선택한 파일의 기기 내 처리",if(selected)CapabilityAvailability.INSTALLED else CapabilityAvailability.REQUIRES_PERMISSION,listOf(if(write)"사용자가 선택한 출력 URI" else "사용자가 선택한 입력 URI"),write,true,limit)
        }
        file("document.read","텍스트·Markdown 읽기",limit="UTF-8 텍스트만 지원합니다. DOCX와는 별도 기능입니다.")
        file("document.write","텍스트·Markdown 저장",true,"선택한 출력 파일에 저장하기 전 승인이 필요합니다.")
        file("spreadsheet.read_csv","CSV 읽기",limit="CSV 값만 읽으며 수식과 매크로를 실행하지 않습니다.")
        file("spreadsheet.write_csv","CSV 저장",true,"수식처럼 보이는 셀은 리터럴로 저장합니다.")
        file("pdf.inspect","PDF 조회",limit=if(env.apiLevel>=35)"페이지·내장 텍스트 조회, OCR 없음." else "이 Android 버전에서는 페이지 정보만 지원하며 내장 텍스트 추출은 Android15+가 필요합니다.")
        file("pdf.create","PDF 만들기",true,"기본 텍스트 PDF 출력이며 복잡한 원본 레이아웃 편집은 아닙니다.")
        file("email.read_eml","선택한 이메일 읽기",limit="2MiB EML, 일반 텍스트만 표시. HTML·원격 이미지·첨부 실행 없음.")
        entries+=ToolCapability("email.compose","이메일 초안","기존 Android 메일 작성 화면 준비",if("email" in env.installedHandlers)CapabilityAvailability.INSTALLED else CapabilityAvailability.UNVALIDATED,listOf("Android handler: email"),true,false,"발송은 사용자가 메일 앱에서 최종 확인합니다. 메일함 직접 연결은 별도 연동입니다.")
        entries+=ToolCapability("audio.speak_local","오프라인 음성 읽기","설치된 한국어 로컬 음성으로 읽기·WAV 생성",if("tts" in env.installedHandlers)CapabilityAvailability.INSTALLED else CapabilityAvailability.REQUIRES_MODEL,listOf("설치된 오프라인 한국어 음성"),false,true,"네트워크 음성으로 자동 대체하지 않습니다.")
        entries+=ToolCapability("audio.listen_ondevice","누르고 말하기","화면에서 시작한 기기 내 음성 인식",when {"android.permission.RECORD_AUDIO" !in env.grantedPermissions->CapabilityAvailability.REQUIRES_PERMISSION;"asr" !in env.installedModels->CapabilityAvailability.REQUIRES_MODEL;else->CapabilityAvailability.INSTALLED},listOf("android.permission.RECORD_AUDIO","기기 내 음성 인식 서비스·언어 모델"),true,true,"앱을 벗어나면 마이크 인식을 중단하고 인식 결과는 실행 전에 확인합니다.")
        entries+=ToolCapability("video.edit_local","영상 자르기·사진 영상","선택한 파일을 MP4로 편집·생성",if("video.edit_local" in env.validatedCapabilities)CapabilityAvailability.INSTALLED else CapabilityAvailability.UNVALIDATED,listOf("지원하는 기기 영상 인코더","선택한 입력 URI"),true,true,"720p·최대60초의 기본 편집이며 생성형 텍스트 영상 모델은 아닙니다.")
        for(packageName in ToolRegistry.supportedMessengerPackages.sorted()){
            val available=when {"notification_access" !in env.grantedPermissions->CapabilityAvailability.REQUIRES_PERMISSION;packageName !in env.validatedConnectors->CapabilityAvailability.UNVALIDATED;else->CapabilityAvailability.INSTALLED}
            entries+=ToolCapability("connector.$packageName",packageName,"앱별 알림 세션 연결",available,listOf("notification_access","앱별 수집 허용","실제 해당 앱 버전에서 검증"),true,true,"수신 알림과 허용된 액션만 접근하며 앱의 전체 DB·과거 기록 접근을 보장하지 않습니다.")
        }
        entries+=ToolCapability("usage.local_reflection","앱 사용 시간 분석","사용자가 허용한 앱 사용 시간의 로컬 분석",if("usage_access" in env.grantedPermissions)CapabilityAvailability.INSTALLED else CapabilityAvailability.REQUIRES_PERMISSION,listOf("usage_access"),true,true,"다른 앱의 본문이나 화면을 자동으로 학습하지 않으며 모델 가중치는 고정입니다.")
        entries+=ToolCapability("connector.screen","선택 앱 화면 연결","사용자 주도·버전별 화면 어댑터",when {"screen_access" !in env.grantedPermissions->CapabilityAvailability.REQUIRES_PERMISSION;"screen" !in env.validatedConnectors->CapabilityAvailability.UNVALIDATED;else->CapabilityAvailability.INSTALLED},listOf("screen_access","앱별 허용","어댑터 실기기 검증"),true,true,"모든 앱 자율 제어가 아닙니다. 보호된 앱 저장소·업무 프로필을 우회하지 않습니다.")
        listOf("document.docx" to "DOCX 편집","spreadsheet.xlsx" to "XLSX 편집","audio.transcribe" to "로컬 음성 전사").forEach {(id,title)->
            val state=when {id=="audio.transcribe"&&"asr" !in env.installedModels->CapabilityAvailability.REQUIRES_MODEL;id in env.validatedCapabilities->CapabilityAvailability.INSTALLED;else->CapabilityAvailability.UNVALIDATED}
            entries+=ToolCapability(id,title,"별도 네이티브 구현·모델·파일 권한 확인 필요",state,listOf("해당 기능의 구현 연결 및 실제 파일 검증"),true,true,"코드·모델 존재만으로 전체 형식 호환 또는 실행 성공을 주장하지 않습니다.")
        }
        listOf("video.generate" to "로컬 영상 생성","email.mailbox_sync" to "메일함 자동 동기화","mcp.executable_host" to "실행형 MCP 호스팅","apps.universal_control" to "모든 앱 전체 자율 제어").forEach {(id,title)->
            entries+=ToolCapability(id,title,"완성된 내장 실행 경로가 없음",CapabilityAvailability.NOT_IMPLEMENTED,listOf("별도 검토·구현·권한·실기기 검증"),true,false,"설치됨 또는 사용 가능으로 표시하지 않습니다.")
        }
        return entries.toList()
    }
}
