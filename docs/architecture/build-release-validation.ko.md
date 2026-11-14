# 빌드·릴리스·검증 구조 상세 설계

상태: **설계 제안. 신규 구현·빌드·배포·기기 검증 완료를 뜻하지 않는다.**

- 작업: `ARCH-REL-01`, 2026-11-14 착수 / 2026-11-15 검토(KST). 아래1060 기준 증거와 문서 검토 날짜는 구분한다. 이 작업의 편집 범위는 본 문서 한 파일이다.
- 소스 기준: `8c3ed07f4c6ee7e7df3b1d62b486f309fac9757a` 위의 미커밋 통합 소스. 실행 담당이1061 보완을 진행하므로 현재 폴더 전체를1060 동결본으로 취급하지 않는다.
- 제품 방향: 스마트폰의 한 대화에서 기본 비서 기능과 필요한 PC·폰 제어, PC의 외부 에이전트 위임을 연결한다. 모델이 현재 요청을 해석하고 다음 행동을 고르며, 호스트가 권한·범위·취소·실제 효과를 검사한다.
- 일반 AI의 현행 기본은 paired-PC 로컬 추론이며 PC가 없으면 중단한다. 여기서 '스마트폰 비서'는 요청·대화·승인·자료·결과의 소유 주체를 뜻한다. 이 설계가 폰 추론 자동 전환을 되살리거나 기본 유료 API 의존성을 추가하지 않는다.
- 내부 베타와 운영 배포의 통과 기준을 구분한다. 스토어 심사, 전 기종 호환, 모든 PC 에이전트·MCP의 지원 완료를 판정하는 문서가 아니다.

## 1. 현재 근거와 다음 후보의 경계

| 항목 | 현재 근거 | 아직 주장할 수 없는 것 |
|---|---|---|
|1060 main|로컬 서명 APK SHA-256 `12c80a9464bbd9395cd1d487722febba72d9e187c779b2c2b0ef62f3c1fee063`가 보존된1060 설치 영수증과 일치|이 문서 작성자가 현재 폰을 다시 조회했거나1061을 설치했다는 주장|
|1060 test rev2|로컬 테스트 APK SHA-256 `3e7a028ef1e8dad7a1bd27679d608d09e503b9598d9f896e813039e2f8bd589a`가 같은 설치 영수증과 일치|같은1060 숫자의 최초 test APK와 동일 바이트라는 주장|
|1060 검사|실행 담당 인계: core718/app603=1321 JVM, lint 오류0/경고144/hint1, 신규 Android 직접 도구·provider 검사18 및 기존 검사24 PASS|새로운 실제 모델6종 워크플로우의 성공, 전체 도구의 의미 품질. 이18건을 새 JNI 모델 추론 검사라고 확대하지 않음|
|실제 모델 폰 근거|기존1059의5종×2회: 목록·정확 메모·시각 완료6회, 질문·문자 준비 확인 대기4회|10회 모두 전체 목표 달성, 실제 SMS 발송,1060/1061의 새 모델 평가|
|새 실제 모델 검사 소스|`TeamModelWorkflowDeviceValidation`에 계산→메모, 메모 정정, 알림, 파일 오타 수정, 저장소 보고, 능력 조회6종이 있음|소스·테스트 APK 포함만으로 실행 PASS. 인계 시점에는6종 미실행|
|공개 상태 문서|읽기 시 PROJECT_STATE/device-validation 첫머리는1059|그 문서의 과거 수치를 현재 이동 중인 통합 소스 전체에 승계|

1060 수치는 담당자의 완료 인계와 보존 영수증을 기준으로 표시했다. 본 작업은 로컬 APK 해시 대조와 소스 읽기를 수행했으며 Gradle·서명·CI·ADB·기기·모델·클라우드 호출을 실행하지 않았다.1061은 해당 후보의 별도 동결·빌드·설치·검사 영수증으로만 승격한다. 검사 메서드 수, 내부 반복 수, 시나리오 유형 수는 각각 기록하고 더해서 성공률을 만들지 않는다.

## 2. 보존할 제품·보안 불변조건

| 조건 | 구현·배포에서 보존할 계약 |
|---|---|
|앱 정체성|브랜드 ZipHap.Agent, 기존 `com.user.localagent` 앱ID와 서명 키를 유지한다. 정식 배포를 이유로 새 앱ID를 요구하지 않는다.|
|대화의 권한|현재 불변 요청, thread/branch/project와 revision, 허용 도구·출처, 읽기 전용, scope, 취소 상태를 실행 직전에 확인한다. 새 화면·스킬·에이전트가 권한을 만들지 않는다.|
|추론 위치|기본 로컬 PC 경로와 별도 외부 엔진 사용 권한을 분리한다. 기존 큐·goal·steer·resume는 캡처한 권한을 유지하고 전역 설정을 뒤늦게 상속하지 않는다.|
|모델의 역할|선계획/직접 다음 행동 비교는 허용하되 자연어 키워드별 고정 라우팅을 추가하지 않는다. 추출·모듈 분리가 필수 전역 분류나 작업 모드 선택을 새로 요구하지 않는다.|
|도구 경계|등록·스키마·지원 기능·scope·동의·대상 버전·중복 효과를 검사한다. 잘못되거나 알 수 없는 호출은 실행하지 않는다.|
|관찰과 완료|외부 에이전트의 설명, 프로세스 종료0, Intent 전달, 모델의 '완료' 문장은 실제 저장·발송·예약 도착의 증거가 아니다. 호스트의 결과 관찰과 목표 충족을 구분한다.|
|자료와 지시|문서·웹·메시지·도구 결과·외부 에이전트 응답은 비신뢰 자료다. 그 안의 지시로 추가 자료·다른 채팅·다른 MCP를 사용하지 않는다.|
|기기 경계|Android 특별 접근은 시스템 동의를 사용한다. 업무 프로필·잠금·보호 화면 경계를 우회하지 않는다. 개발용 ADB를 제품의 일반 제어 API로 노출하지 않는다.|
|자료 보존|원본·기억·대화·승인·완성 파일의 기본 소유자는 폰이다. PC 이미지 복구와 별도 승인된 브리핑의 보관 예외를 범용 PC 에이전트 저장 허가로 확장하지 않는다.|
|증거의 비공개성|키·페어링·실제 주소·장치ID·원문·스크린샷·기기 로그·모델 파일은 Git에 넣지 않는다. 공개 문서는 합성 사례·집계·소스/산출물 해시만 사용한다.|

## 3. 실제 빌드 구조

현재 [settings.gradle.kts](../../settings.gradle.kts)는 `:app`, `:core` 두 모듈만 포함한다. 아래 경로의 존재와 현행 연결을 확인한 것이며, 제안 모듈이 이미 생겼다는 뜻은 아니다.

```mermaid
flowchart LR
    Core[core: JVM 계약·검증·도구 정의] --> App[app: Compose·Room·Android 도구·추론 facade]
    Native[CMake: llama·ASR·image JNI] --> App
    Assets[public trust·번들 skill assets] --> App
    App --> APK[서명 Android APK]
    App -->|페어링·인증서 pin·요청 owner| Worker[PC Python worker]
    Worker --> Runtime[별도 native runtime·모델]
    DevAgent[외부 개발 에이전트] --> MCP[stdio local-llm MCP]
    MCP --> Worker
    Briefing[별도 승인 briefing companion] --> External[제한된 외부 분석 실행]
```

| 단위 | 현행 구성·빌드 계약 | 배포·검증상의 주의 |
|---|---|---|
|Gradle|wrapper9.5.0+배포 ZIP SHA pin, AGP9.3.3, Kotlin2.4.21, KSP2.3.6, JDK21; JVM 산출물 target17|로컬 도구 버전이 CI와 같은지 영수증에 기록. 캐시가 있다는 이유로 출처·입력 검사를 생략하지 않음|
|core|JVM Kotlin, serialization/coroutines, 도구 정의·계획 계약·TaskHarness·evidence·연속 작업 노트, JUnit|Android를 실행하지 않는 계약 검사의 중심. 모델 결과를 Python에서 재해석해 보안 검사를 대체하지 않음|
|app|Compose, Room3 스키마/SQLCipher, Keystore, WorkManager, Android provider/Intent, PC transport, 모델 관리|minSdk31/target37, arm64-v8a. 선언된 지원 범위와 실제 검증 기기를 구분|
|Android native|`ziphap_llama`, `ziphap_asr`, `ziphap_image`, CMake3.22.1/NDK29.0.14206865|ABI·컴파일 정의·16KiB page-size·CPU feature 검사와 라이브러리 해시를 pin|
|native 원천|폰 llama commit `8642541…`, 폰 image `e239e4b…`, PC 복구 native SD `37bc88b…`/GGML `7b2b435…`|폰 image와 PC image의 다른 pin을 혼용하지 않음. 상세 원천/아카이브 SHA는 각 source-pin을 따른다. 가중치는 별도|
|PC worker|`pc-worker/deployed/`의 server/engines/owned/recovery/media/security/pairing/supervisor 등 Python 스냅샷|상위 `pc-worker/` 후보와 실제 설치 파일이 다를 수 있음. 배포 시 파일별 manifest와 실제 설치 해시가 필요|
|개발 MCP|`pc-worker/mcp/local_llm_mcp.py`, 표준 라이브러리 기반 stdio JSON-RPC→기존 PC worker|외부 개발 에이전트가 로컬 모델에 위임하는 방향. 스마트폰의 PC 에이전트 제어 기능과 별개|
|평가기|Python eval→core test runtime의 ScenarioBridge. `exportScenarioRuntime`가 classpath를 내보냄|bridge는 test runtime이며 APK에 넣지 않는다. 실제 모델+합성 도구 결과를 실제 Android 효과로 세지 않음|
|PC 외부 분석|`scripts/briefing_companion.py`의 별도 opt-in 분석 경로|현재 명령/파일 변경/웹/MCP 사용 이벤트를 거부하는 제한 adapter. 일반 PC 에이전트 broker로 재사용하면 권한 계약이 달라짐|
|CI|main/PR에서 core/app unit·lint·release 조립, main 조건의 서명·manifest·GitHub release 게시|현재 Python/MCP·기기·canary·전체 실제 모델 gate는 CI 단계로 구현돼 있지 않음|

현재 PC worker는 별도 버전 고정 설치 패키지·전체 dependency lock·팀원 installer가 완성된 것으로 보지 않는다. `scripts/ci-requirements.txt`의 cryptography pin은 manifest 서명용 CI 의존성이며 PC의 전체 실행 환경 lock이 아니다. PC의 개인 경로가 박힌 supervisor를 그대로 팀원 배포 프로그램이라고 포장하지 않는다.

## 4. 재활용 가능한 추출 경계

아래는 **점진적 추출 제안**이다. 먼저 현재 두 Gradle 모듈 안에 interface/facade 경계를 만들고 같은 회귀를 통과시킨다. build graph 분리는 별도 변경으로 진행한다. DB entity·앱ID·직렬화 계약과 동작 변경을 한 번에 섞지 않는다.

| 제안 경계 | 재사용할 현행 원천 | 밖으로 내보낼 것 | 남길 의존성·조건 |
|---|---|---|---|
|agent-contract|core Tools/ToolCallSchema/VerificationState/StepEvidence/Capability DTO|버전 있는 호출·권한 참조·결과·오류 schema|개인 원문·Android Context·전역 설정을 포함하지 않음|
|agent-runtime|TaskHarness/WorkSegments/WorkNote/계획·검토 계약|bounded loop, 취소·진행·중복·효과 확인 interface|선계획 방식과 다음 행동 방식 모두 같은 host guard를 통과|
|Android 실행 host|ToolExecutor/ProjectWorkspace/대상 검토·read-delivery|ToolExecutorPort, WorkspacePort, ApprovalPort|실제 권한·SAF·provider·Intent·scope 검사는 Android에 유지|
|Android persistence|LocalRepository/ChatRecords/QueueStore/goal·work trace|transaction/CAS/원문·영수증 조회 interface|SQLCipher·Keystore·Room migration·기존 DB 이름/키 alias 보존|
|compute client|PcTransport/AutomaticComputeEngine/ComputeExecutionEngine|로컬 PC 실행·상태·취소·cleanup 계약|Android network/암호화 자격과 호스트별 resource broker 유지|
|UI|Compose chat/approval/run activity|호스트 상태와 근거의 표시|화면의 버튼 상태가 실행 권한의 유일한 검사가 되지 않음|
|PC compute runtime|OwnedEngines/RecoveryEngines/media/native progress|명시 owner의 load/run/cancel/unload 및 근거|큰 모델1개, cleanup 확인 전 자원 반환 금지|
|PC agent broker|아직 범용 구현 없음. 기존 pairing/transport/owner 개념을 재사용|JobPort/AdapterPort/ArtifactPort, scope·예산·취소|외부 agent의 로그인·도구 전체를 자동 상속하지 않음|
|평가·fixture|ScenarioBridge, 상태형 problem-solving fixtures|schema golden·fault injection·채점 interface|기대 정답/도구 순서를 모델 입력에 노출하지 않음|

`ChatRunCoordinator`의 거대 suspend 함수에서 계획·실행·후속 검토·최종 표시·자료 정리를 단계 객체로 분리한다. 각 단계는 같은 불변 request snapshot과 evidence journal을 받으며 `ContinuationSnapshot`/기존 큐를 바꾸지 않는다. 추출 commit마다 기존 도구 정의, 허용 도구 투영, 권한 거부·취소·저장 결과를 동일 입력으로 비교한다. 프롬프트 최적화나 모델 교체는 구조 추출의 효과와 분리해 평가한다.

## 5. PC 에이전트 adapter의 별도 빌드·배포 설계

**현행:** 범용 '폰→PC 외부 에이전트' broker는 구현·검증 전이다. local-llm MCP 네 도구와 제한된 briefing companion은 그 기능의 완성 근거가 아니다.

**제안:** PC 패키지를 `api/auth`, `compute`, `agent_broker`, `adapters`, `artifact_verifier`, `supervisor`, `diagnostics` 논리 단위로 나눈다. Python 실행 환경과 플랫폼별 의존성을 lock하고, signed worker bundle에 코드·의존성·schema·native helper의 manifest를 넣는다. 모델·vendor agent 로그인·토큰·PC 연결 정보는 bundle에 포함하지 않는다.

| adapter 계약 | 필수 입력·제약 | 통과 근거 |
|---|---|---|
|probe|실행 파일의 출처/버전/해시, 지원 protocol, 허용된 profile·workspace|설치됨, 인증됨, 모델 준비됨, 도구 실행 가능을 각각 보고. 단순 파일 존재를 준비 완료로 표시하지 않음|
|submit|폰 request/run/branch/project revision의 host binding, 선택 workspace handle, 허용 도구·자료, 정책 digest, 시간/출력/연산 예산|실행 전에 모든 항목을 검증하고 예약된 자원·job identity를 기록|
|events|job/attempt, 단조 증가 sequence, host-observed 단계·효과 참조|외부 agent의 private reasoning을 전송하지 않음. 계획·도구 시도·파일 변경·검증·오류를 구분|
|cancel/status|같은 인증 주체·job·epoch, 취소 revision|취소 요청 수신과 실제 프로세스/compute 정리를 구분. 다른 job/사용자 프로세스 종료0|
|collect|선택 workspace 안의 실제 산출물, 상대 경로/해시/크기/형식/생성 주체|경로 이탈·symlink·변경된 bytes 거부, 폰 저장·재조회 후 수신 ACK|
|resume|원래 request·권한·workspace·adapter/runtime/model/profile의 동일성|다른 원문/버전/권한으로 이어붙이지 않음. 효과 불명확 시 상태 확인부터 하며 write를 재전송하지 않음|

vendor agent는 이름만으로 활성화하지 않는다. 지원할 agent의 특정 버전·실행 방식·기능만 adapter compatibility 표에 올린다. 초기 canary는 한 adapter의 선택 workspace 읽기/초안 산출부터 시작한다. PC 제어·쓰기·외부 발송·다른 MCP 호출은 별도 capability로 추가한다. 기존 사용자 전역 agent 설정, memories, shell, 브라우저, 다른 MCP가 함께 켜지는 실행 profile은 그대로 휴대폰에 제공하지 않는다. 필요한 접근을 제한할 수 없는 adapter는 해당 기능을 미지원으로 둔다.

외부 에이전트가 대화/trace를 자체 보관하는 경우 그 보관을 막거나 명시된 별도 자료 처리 권한이 있어야 한다. 현재 이미지 복구의24시간/4작업 예외로 이를 허용하지 않는다. 외부 모델·계정·과금 조건은 별도 선택이며 일상 PC 로컬 추론의 필수 자격으로 만들지 않는다. vendor별 정확한 실행 flag와 실제 로그인·취소·보관 경계는 adapter 구현 시 원천 문서/해당 버전/실행 증거로 확정한다.

## 6. 앱·DB·PC protocol 호환성 원장

버전 숫자 하나로 앱·DB·worker·모델의 호환을 대신하지 않는다. 아래 항목은 release evidence에 각각 고정한다.

| 축 | 현행 | 변경·거부 규칙 |
|---|---|---|
|Android package/signing|`com.user.localagent`, 기존 인증서|변경 금지. 새로운 배포 이름이나 모듈 추출을 이유로 데이터 이전을 강제하지 않음|
|Android versionCode|개발 지정값, CI는1000+run number|팀/CI/canary/복구가 공유하는 단일 단조 증가 원장 제안. fleet 최고 버전보다 낮은 후보는 승격하지 않음|
|Room schema|3,1→2와2→3 migration, DB명 유지|파괴적 fallback 금지. 스키마와 직렬화 payload 호환성을 별도로 확인|
|update envelope|schema1, capabilityApi1, databaseSchema3, channel stable|현행 verifier의 정확 허용값을 보존. 지원하지 않는 새 값은 거부하며 임의 호환 처리하지 않음|
|PC service/discovery/pairing|서비스 version1, `/v1/` routes, pairing protocol1|새 기능은 인증된 health의 지원 feature를 확인. discovery의 버전만으로 실행 권한이나 전체 API 호환을 인정하지 않음|
|compute request|owner header, requestId, modelId/hash prefix·문맥 등 검증|기존 strict 요청에 모르는 필드를 조용히 추가하지 않음. requestId 재사용 거부와 새 broker의 idempotency를 구별|
|개발 MCP|협상 버전 `2024-11-05`, 제한된 네 도구|이 구현이 광고하는 버전만 지원. 최신 MCP 전체·실행 중 취소·workspace sandbox를 지원한다고 표시하지 않음|
|이미지 checkpoint|`ZHCP0001`, 원래 runtime/model/request/sampler/shape binding|불일치·손상·만료는 재개 거부. 옛 RAM-only 요청에 저장 권한 부여 금지|
|PC DataStore|모듈은 있으나 확인한 server 경로에 연결되지 않음, health의 durable store=false|현행 개인 자료 원장을 PC로 옮긴 기능이라고 표시하지 않음. 활성화는 별도 보관/복원 설계·권한 필요|
|agent job/event/evidence|아래 새 schema는 제안|현재 service v1 구현의 기능으로 승격하지 않음. 지원 feature와 adapter policy 검증 뒤에만 사용|

새 `agentJobSchema=1` 같은 broker 계약은 명시 feature와 새 전용 endpoint에서 시작하는 안을 사용한다. 예를 들어 `/v1/agent-jobs`는 **제안 경로이며 현재 server route가 아니다.** 구형 worker가 지원하지 않으면 기능 미지원으로 멈춘다. 실패한 새 API를 기존 범용 shell/MCP로 우회하지 않는다. health에 선택 필드를 추가하는 것과 strict 실행 요청의 schema 변경을 구분한다.

새 DB4 또는 capabilityApi2를 도입하려면 기존 verifier가 그 manifest를 거부한다는 점부터 해결해야 한다. 먼저 DB3를 유지하면서 새 버전을 명시적으로 검증할 수 있는 호환 client를 배포·관찰한 뒤 실제 migration 후보를 진행한다. manifest의 DB3 표기를 유지한 채 실제 APK만 DB4로 바꾸는 방식은 금지한다. 현재 envelope에 알려지지 않은 필드를 추가할 때도 기존 parser의 허용 여부를 검증한다. 상세 compatibility 확장은 별도 protocol 변경이다.

## 7. 원문·권한·job·산출물 pin

| pin | 필수 내용 | 재확인 시점 |
|---|---|---|
|SourcePin|정확 commit, dirty 여부, 실제 포함 입력 목록과 각 SHA, 정렬된 source-tree digest, build 설정·generated schema·native pin|빌드 전/후, 검사 종료. 빠진 새 소스/asset와 목록 변화도 검출|
|ArtifactPin|APK unsigned/aligned/signed 구분, main/test 각각 SHA·versionCode·certificate·ABI, worker bundle/schema/runtime hash|서명 후, 설치 후 독립 readback, 검사 후|
|RequestPin|불변 원문 참조, thread/branch/project revision, allowTools·mode·출처, PC revision·allowCloudText, cancellation revision|submit/steer/goal/resume와 각 실제 효과 직전|
|AdapterPin|agent executable/version/hash, 허용 profile·도구·workspace 정책, result parser/schema|job 시작·재개. 실행 중 파일·정책 변경은 기존 승인 승계 금지|
|ModelPin|가중치·projector/encoder/VAE의 전체 SHA·크기, tokenizer/template, 양자화, runtime/가속 backend·문맥/출력/샘플링|다운로드/로드·resident 재사용·재개. 별칭만으로 동일 모델이라 판단하지 않음|
|EffectPin|도구명·canonical 인자, 실제 대상ID/버전·body/file hash, 승인 binding, 호스트 관찰/readback|승인 시와 실행 직전/후. 메시지/대상/본문이 바뀌면 기존 승인 폐기|

공개 update manifest의 commitSha만으로 미커밋 소스에서 만든 APK가 해당 commit과 같다고 증명할 수 없다. 개발 후보에는 실제 source-tree manifest를 함께 보존하고, 운영 승격 시에는 서명 대상이 검토한 정확 commit의 clean 입력과 일치해야 한다. 이 요구가 현재 무단 commit/push를 허가하는 것은 아니다.

빌드 입력과 문서 입력을 구분한다. APK와 관계없는 문서 정정만으로 기존 APK를 새로 설치했다고 기록하지 않는다. 반대로 app/core/asset/native/build 설정·테스트 APK가 달라졌으면 해당 evidence pin을 갱신한다. test rev2가 main을 바꾸지 않은 경우 두 해시와 검사 적용 범위를 명시해 main의 재빌드 여부를 구분한다.

## 8. 빌드·서명·후보 승격 pipeline

아래 단계는 목표 pipeline이다. 현재 CI에 없는 기기/worker/canary gate를 이미 자동화했다고 주장하지 않는다.

| 단계 | 입력·수행 | 통과·정지 조건 | 보존할 결과 |
|---|---|---|---|
|B0 범위/소유권|공통 변경 기록, 실행 lease, dirty 변경·파일별 소유권 확인|타 작업·사용자 연산을 침범하지 않음. 동시 서명/설치/모델 평가 금지|task/owner, 읽기 기준, 변경 파일 목록|
|B1 입력 동결|실제 code/assets/Gradle/manifest/CMake/native 원천·schema pin|목록/해시 완전성. 비밀·모델·runtime 원문 제외|SourcePin, dependency/native manifest|
|B2 순수 검사|core 보안/범위/파서·app unit, Python worker/MCP/eval mock 계약|필수 검사 실패/오류/skip0, 기대 입력과 효과 일치|JUnit/Python 집계와 실패 분류|
|B3 Android 조립|release lint, release APK와 Android test APK, 필요 native/ABI 검사|lint 오류0, 새 경고 검토, installable unsigned 결과, 동결 입력 불변|빌드·lint 보고, unsigned/main/test 해시|
|B4 PC bundle|고정 Python/runtime/의존성·worker/adapter bundle 조립, offline import·schema 검사|비밀/가중치 미포함, 출처·라이선스·호환 범위·payload 제한 확인|bundle manifest/SBOM·서명·해시|
|B5 서명|기존 Android key, update Ed25519 key, worker 신뢰키 각각의 승인된 경로|APK 실제 certificate와 update trust 일치. 키는 코드/일반 artifact/로그에 없음|signed APK/update envelope/SHA256SUMS|
|B6 선행 설치|사용자 작업·idle/cleanup 확인, 정확 전신 버전, 데이터 보존 설치|설치 요청 반환만으로 통과하지 않음. main/test/worker 실제 bytes와 identity 일치|독립 설치 readback, 호환 migration 결과|
|B7 실제 효과|격리 native/provider·scripted coordinator·실제 local model/phone·필요 외부 adapter|각 층위의 효과를 분리. wrong target/허위 완료/권한확대0|버전·모델·fixture·결과·cleanup이 묶인 private receipt|
|B8 canary|검증된 좁은 대상에 같은 signed bytes, 관찰·fault·복구 검증|내부 베타 gate 충족. 개인 원문 지원 로그 업로드0|비개인 집계·장애/복구 결과|
|B9 운영 승격|정확 같은 artifact를 stable에 승격하는 절차|운영 gate 충족, version 원장/호환/업데이트 검증, 공개 자료 검사|signed manifest·정확 commit/source-tree·승격 이력|

JVM CI와 mock Python 검사는 독립 실행할 수 있다. Android 설치/실모델/같은 PC의 native 실험은 resource lease에 따라 직렬화한다. 무거운 Gradle과 모델 부하가 성능 측정을 오염시키지 않게 별도 창을 사용한다. artifact 승격을 위해 APK를 다시 조립하면 새 바이트이므로 B5 이후 검사도 새 후보에 맞춰야 한다.

## 9. 내부 canary와 update pin의 운영 설계

**현행:** UPDATE_URL은 GitHub `latest`이며 verifier/manifest는 stable 하나다. main CI는 빌드 뒤 release를 게시한다. 현재 'beta 채널 선택', 버전 denylist, cohort rollout, 별도 worker updater가 구현된 것은 아니다.

1. 초기 canary는 지정한 signed APK/worker bytes를 통제된 내부 전달로 설치한다. 공개 stable/latest에 미검증 canary manifest를 올리지 않는다. 이 직접 설치도 앱ID·서명·해시·원장·데이터 보존 검사를 거친다.
2. canary와 안정 버전, CI run number, 개발 버전, 긴급 복구 버전의 번호를 한 원장에서 예약한다. 더 높은 번호의 불량 후보 때문에 정상 버전으로 내려갈 수 없는 상황을 계획에 포함한다. 버전 이름은 manifest 생성기의 현재 dotted numeric 제약과 APK metadata를 함께 확인한다.
3. 같은 검증 artifact를 운영으로 승격할 때 exact signed bytes를 유지한다. 채널 suffix나 설정을 APK에 다시 넣어 바이트가 바뀌면 새 후보로 검사한다.
4. cohort/채널 pin/denylist를 구현할 경우 별도 client 호환 변경으로 개발한다. 설정은 서명된 release descriptor에 binding하고, 더 넓은 권한·새 publisher·새 Android permission은 기존 확인 경로를 거친다. 원격 기능 차단이 기존 실행의 승인·자료를 넓히는 수단이 되지 않게 한다.
5. 설치 대기 중 사용자 작업이 시작되거나 동의 화면이 남으면 기존 busy/lease 정책을 따른다. 새 일을 무기한 막거나 현재 작업을 강제로 취소해 업데이트하지 않는다. 실패한 설치는 기존 앱과 자료를 유지하고 다음 상태를 정확히 표시한다.

팀 공유 PC의 다중 폰은 별도 지원 범위다. 현행 compute owner UUID 검사만으로 기기별 인증 주체·권한 격리가 모두 증명됐다고 보지 않는다. 초기 내부 베타는 검증한 폰-PC 페어 범위로 제한한다. 공유 PC 지원 전에는 페어별 자격/철회·job principal·자료/산출물·취소 격리를 두 독립 페어로 검증한다.

## 10. 전진 복구와 특수 데이터 호환

기본 복구는 **같은 앱ID·서명, 더 높은 Android versionCode에서 정상 동작을 회복하는 전진 복구**다. package downgrade나 앱 삭제/데이터 초기화를 일반 복구 방법으로 삼지 않는다. 기존 verifier는 이전/같은 version을 거부한다.

| 자료·상태 | 복구 설계 | 필요한 검증 |
|---|---|---|
|Room/SQLCipher/Keystore|기존 DB·키 alias·암호화 wrapper를 보존. schema 변경은 명시 migration. 키 복호화 실패를 새 빈 DB 생성으로 숨기지 않음|schema1/2→3 합성 DB, 실제 키 기반 process restart, 손상·키 부재 시 비파괴 중단|
|대화/분기/목표/기억|row id/원문·소유 scope·삭제 tombstone·기준·증거를 보존|보관/이동/삭제/재시작 뒤 원문·기준 유지, 지운 채팅 부활0|
|큐·continuation|기존 mode/allowTools/출처·revision, cloud 기본false 및 취소를 보존. 새 필드의 default가 기존 권한을 넓히지 않음|구형 payload golden, 잘못된 타입/unknown version, 이전 read-only/취소 요청의 재개 거부|
|알림|DB record·scope·revision과 실제 Alarm/Work 큐를 함께 정합성 확인|업데이트 중 due 도달/권한 철회/재예약 실패/재부팅/프로젝트 삭제, 중복·외국 scope 전달0|
|SAF 연결·파일|URI 권한·workspace binding·read-delivery·원본 SHA의 실제 유효성 재검사|철회/이동/다른 프로젝트/중간 수정. 감사용 해시를 쓰기 권한으로 승격하지 않음|
|PC pairing|원래 서버·인증서 pin·권한 revision 유지, 재등록은 이전 요청을 새 권한으로 부활시키지 않음|동일 PC 재접속, 다른 서버·인증서/권한 변경의 거부|
|이미지 복구|원래24h/4작업·RAM-only 제한·model/runtime/sampler binding 유지|같은 checkpoint 복원, mismatch/만료/손상 거부, 수신 파일 검증 뒤 ACK, 명시 취소와 단절 분리|
|agent job|폰이 원 요청·정책·마지막 관찰·결과 원장을 보존. PC의 일시 상태가 사라지면 완료로 추측하지 않음|restart/부분 결과/취소·ACK 유실 시 재조회, 실제 write/외부 발송 자동 재시도0|
|외부 앱 효과|앱 복구와 이미 보낸 메일/작성 화면/일정/파일을 되돌리는 것은 별개|실제 관찰한 대상만 대조. 불확실한 발송을 복구 과정에서 재전송하지 않음|

소스만 이전으로 바꿔도 데이터는 이미 새 형식일 수 있다. 따라서 '이전 정상 코드의 재빌드'는 자동 안전하지 않다. 필요한 신규 보안 보완을 유지하고, 새 DB/payload를 읽거나 안전하게 중단할 수 있는 복구 버전을 시험한다. schema를 되돌릴 수 없다면 전진 migration/수정으로 회복하며, 새 schema를 옛 manifest 값으로 위장하지 않는다.

Android backup/device transfer는 현재 차단되어 있다. 암호화 DB 복사만으로 키가 복원된다고 안내하지 않는다. 대화 내보내기도 전체 기억·목표·프로젝트·grant·queue 복원과 다르다. 기기 교체용 암호화 백업/키 복구/선택 복원은 별도 설계·명시 동의·다른 기기 실증이 필요하다. 소유자의 현재 자료를 지우는 방식으로 복구를 시험하지 않는다.

## 11. 팀원 온보딩과 지원 환경

초기 지원 단위는 **검증한 Android 기기/OS/개인 프로필 + 특정 PC OS/runtime/model + 특정 adapter 버전**의 조합이다. minSdk31은 모든 Android12 이상 기기의 기능·속도를 보증하지 않는다. 초기 팀 베타에서 검증하지 않은 PC 공유·업무 프로필·다른 OS를 자동 지원 대상으로 넣지 않는다.

| 단계 | 팀원에게 필요한 동작 | 확인할 결과·실패 처리 |
|---|---|---|
|설치|지정 signed APK의 버전·해시 확인, 기존 사용자는 같은 앱으로 업데이트|앱ID·서명·기존 자료 유지. 출처가 다른 APK나 더 낮은 버전은 안내된 복구 절차로 전환|
|PC 준비|검증 worker/runtime 설치, 모델·지원 파일 별도 다운로드 및 출처/사용 조건·해시 확인|모델 목록·다운로드·검증·로드·실제 추론을 구분. 메모리/디스크 부족이면 준비 미완료|
|첫 연결|같은 LAN에서 Android 로컬 네트워크 동의, PC 탐색·정확한 PC의 코드 입력|현행12자리/5분 유효 코드의 의도한 PC, nonce/서버 identity·certificate pin 결합. 실패/만료/취소는 등록하지 않음|
|연결 확인|오픈소스·PC 엔진 선택, 인증된 health 및 필요한 모델 준비|health 성공을 전체 모델 요청 성공으로 표시하지 않음. PC가 없으면 기본 AI는 중단|
|최소 양성 요청|현재 시각 조회, 합성 임시 메모 저장·검색·정정, 자기 fixture 정리|도구 기록·실제 저장값과 최종 답변 일치. 설명만 하거나 질문으로 끝나면 양성 완료 아님|
|기능 접근|필요한 연락처/SMS/일정·파일·계정 범위만 선택|시스템 동의와 프로젝트 도구 허용을 각각 확인. 권한 거부 이유·가능한 다음 동작 표시|
|특별 접근|필요한 알림 접근/접근성/사용 기록/정확 알람/설치 권한을 Android 화면에서 결정|미허용·철회·채널 꺼짐을 구분. 알림 예약 저장만으로 정확 시각 도착을 약속하지 않음|
|집 밖 연결|두 장치의 승인된 Tailscale 연결 준비, 기존 identity/pin으로 재확인|외부망 전체 inference와 Wi-Fi↔이동통신 fault를 별도 확인. 연결이 새 자료 권한을 주지 않음|
|외부 agent|지원 adapter의 별도 설치·인증·workspace/도구·외부 전달 조건 확인|설정/로그인만으로 실행 준비·정확성 인증을 하지 않음. 기본 로컬 기능은 해당 자격 없이도 유지|
|장애 지원|버전·실패 단계·코드·허용 여부·후보 evidence ID만 선택해 전달|원문·토큰·주소·device/server ID·화면은 기본 진단 묶음에서 제외. 전체 대화 내보내기를 지원 로그로 요구하지 않음|

업무 프로필은 별도 실제 지원 시험이 필요하다. 개인 프로필의 URI·계정·Intent·접근성 동의가 업무 프로필로 전달된다고 가정하지 않는다. MDM/정책상 거부는 안전하게 표시하고 개인 앱으로 자료를 복사하거나 경계를 우회하지 않는다. 공유 폰/PC·퇴사자/분실 기기의 자격 철회·자료 귀속도 팀 운영 gate에서 확인한다.

새 설치와 기존 설치 업그레이드는 다른 시험이다. 재설치로 owner 데이터를 지워 '깨끗한 설치'를 만들지 않는다. 별도 시험 환경에서 clean first-run, 동의 거부, PC 꺼짐, 잘못된 코드, 필요한 모델 없음, 정상 재연결, 첫 실제 요청을 관찰한다.

## 12. 모델·RAM·연산 자원의 설계

**현행:** Android `ComputeResourceBroker`는 PHONE/PC 각각 하나의 소유 lease를 관리한다. 일반 Android facade는 PC-only다. worker의 native lock/owner와 cleanup 검사가 추가로 작동한다. 개발 MCP의 busy 확인은 한 시점의 관찰이며 자원 예약이 아니다.

| 자원·상태 | 설계 규칙 | 측정·검증 |
|---|---|---|
|큰 모델 resident|호스트마다 큰 inference model1개. LLM/ASR/사진 helper/diffusion 전환 전에 기존 모델 정리 확인|model별 load/unload·PID/host memory·GPU resident·정리 결과. 폰과 PC의 서로 다른 자원 lane이 같은 호스트에 두 모델을 적재할 권한을 주지 않음|
|PC single compute|로컬 heavy inference/native image와 이를 쓰는 external adapter는 같은 PC compute broker를 거침|외부 adapter가 자체 llama/GPU 모델을 별도로 띄워 lock을 우회하지 않는지 관찰|
|폰 연산|직접 선택한 폰 image 등 지원 경로만 별도 PHONE lease 사용|일반 요청의 PC 실패를 이유로 PHONE lane을 자동 획득하지 않음|
|병렬 작업|서로 독립된 읽기/전송/검사와 heavy model 연산을 구분. 초기 agent canary는 동시 자식1개, 대기 자식 최대4개, 자식의 추가 재위임0회 제안|부모 예산을 자식마다 초기화하지 않음. 동일 workspace 쓰기·서명·설치·모델 평가는 직렬화|
|문맥·단계|현행 PC context4096/8192, WorkSegments는12round×3/최대18tool steps. 실제 입력 토큰과 출력 한도 검사 유지|MCP 문자 한도나 긴 파일 요약 성공을 모델 토큰 적합성 증거로 보지 않음|
|부모/자식 예산|실행 전 예약한 시간·토큰·도구 수·산출물 한도를 부모와 공유. 중복·재시도·검토도 소비|예산 초과는 남은 결과/미처리 범위를 보존하고 paused. 새 goal/새 run으로 숨겨 무한 반복하지 않음|
|idle 정리|worker의 현재300초 idle unload를 유지. 제안 관찰 허용창330초|마지막 작업 종료와 실제 unload/VRAM 회수 시각을 기록. 실패 시 free라고 보고하지 않음|
|메모리 제한|기기/모델 profile별 실제 RAM·commit·VRAM 여유를 검사, 부족하면 작업 시작 전 보류|Windows working-set 상한을 전체 allocation/VRAM cap이라고 주장하지 않음. 전체 다른 앱의 우선순위·프로세스 변경0|
|가중치 배포|전체 SHA·크기·license/source/runtime compatibility로 별도 관리. 모델/projector/encoder/VAE를 APK·Git·worker 코드 bundle에 넣지 않음|다운로드 단절·변조·disk full에서 기존 검증 모델 유지, 자동 모델 바꿈0|

일반 대화 모델, 사진 이해/영문 프롬프트 helper, 최종 이미지 생성 모델을 다른 준비 항목으로 보여준다. 모델 선택·메모리 관리·adapter의 추론 backend를 바꾸는 실험은 같은 fixture·예산의 통제 비교로 평가한다. 현재 source pin이나 과거 폰 벤치마크로 다른 양자화·PC 모델의 품질/속도를 보증하지 않는다.

## 13. 장기 실행·취소·재연결의 상태 계약

다음 agent job 상태는 **새 broker 설계**이며 현행 모든 Android 실행이 이 enum을 영속 저장한다는 뜻은 아니다. 폰 원장이 사용자에게 보이는 상태의 기준이다.

```mermaid
stateDiagram-v2
    [*] --> Captured
    Captured --> Validated
    Validated --> Queued
    Queued --> Running
    Running --> AwaitingApproval
    AwaitingApproval --> Running
    Running --> Verifying
    Verifying --> ResultStored
    ResultStored --> Completed
    Running --> Paused
    Verifying --> Paused
    Queued --> CancelRequested
    Running --> CancelRequested
    CancelRequested --> Cancelled: effect/compute cleanup 확인
    CancelRequested --> CleanupUnconfirmed: 응답·정리 확인 실패
    Paused --> Validated: 같은 권한과 원문 재검증
    CleanupUnconfirmed --> Cancelled: 실제 정리 확인
```

- `Completed`는 해당 job의 명시 조건과 산출물 검증을 충족한 경우다. 부모 목표는 모든 필수 조건의 근거를 다시 검사한다. `ResultStored`와 외부 앱의 `PREPARED`는 별개다.
- 취소 버튼은 먼저 폰의 cancellation revision을 저장하고 새 효과를 차단한다. 네트워크 단절로 PC가 취소를 못 받았으면 '취소 요청됨/PC 정리 확인 필요'로 표시한다. 취소 요청이 전송됐다는 이유로 compute lease를 free로 만들지 않는다.
- 실제 쓰기가 이미 commit됐다면 그 결과와 취소 경계를 남긴다. NonCancellable은 짧은 영수증/필수 정리 범위에 한정하고 모델·네트워크·provider 전체를 무제한 감싸지 않는다.
- PC 세션/프로세스 재시작에는 epoch를 바꾸고 이전 owner/job/정책 binding을 확인한다. 새 agent 세션을 예전 job의 계속으로 속이지 않는다. 재페어링/권한 철회/다른 branch로 변경된 요청은 기존 승인을 재사용하지 않는다.
- 재연결 후에는 먼저 상태·실제 파일/레코드/영수증을 조회한다. 중복 request가 같은 canonical input이면 저장된 결과를 대조하고, 같은 id에 다른 input이면 conflict로 거부하는 idempotency ledger를 제안한다. 현재 compute requestId 재사용 거부와는 별도 구현이다.
- 이벤트는 job/attempt/sequence에 묶어 중복을 제거하고 유실 구간을 표시한다. 오래된 진행 이벤트가 최종 파일·영수증을 덮어쓰지 않는다. 진행 요약은 host-observed 내용이며 private reasoning이나 추정 완료율을 내보내지 않는다.
- 기본 PC agent 요청의 원문/trace는 RAM-only를 원칙으로 한다. 원 요청·결과·체크포인트는 폰에 보존하고, PC의 crash-safe 개인 원문 저장이 필요하면 별도 명시 권한·암호화·TTL·삭제 계약부터 추가한다. 승인된 workspace에 만든 파일도 산출물로 명시한다.

시간 예산은 tool의 실제 계약과 사용자 목표에 맞춰 호스트에서 정한다. 현행 planner total900초는 전체 workflow의900초 상한과 같지 않다. 제안하는 초기 interactive agent run의 wall-clock 기본은15분이며, 긴 작업은 명시 목표/시간 예산 아래 최대60분 단위로 checkpoint·확인한다. 기존 loop/도구 한도를 조용히 늘리거나 자동 영속 goal을 만들지 않는다. 이 wall-clock 정책은 구현·실측 전이다.

## 14. 검증 층위와 재사용할 검사

| 층위 | 검사 내용·재사용 지점 | PASS의 의미 | 증명하지 않는 것 |
|---|---|---|---|
|T0 문서/정적|소유권·diff·링크·schema 표·build graph|설계/문서 정합성|컴파일·동작·설치|
|T1 JVM/순수 Python|core security/TaskHarness/schema, app unit, worker/MCP/eval mock|정해진 입력의 파서·권한·scope·상태·fault 계약|실모델 이해, Android provider, 실제 worker/native|
|T2 빌드/서명|lint/release/test, native·asset/skill pin, bundle 검증|설치 가능한 정확 산출물·서명|설치/로그인/첫 실행·실제 사용자 효과|
|T3 실제 설치/데이터|main/test/worker 독립 해시, migration/restart/권한 보존|그 바이트가 해당 환경에 설치되고 fixture를 보존|모든 도구·기기·모델 품질|
|T4 provider 직접|TeamProblemSolving, ReminderScope, StorageAudit, Authority, Capabilities 등|실제 scoped DB/파일/Alarm·Work·Android 경계의 검사|모델이 자연어 요청을 해석해 그 도구를 선택함|
|T5 scripted coordinator|수신자/허위 완료/권한·취소/후속 단계 오류 주입|생산 coordinator와 저장·승인·화면·효과 경계|실제 모델의 자발적 계획·복구·일반화|
|T6 실제 로컬 모델+합성 효과|ScenarioBridge의20기준/15스마트폰/28문제해결 과제|실제 모델의 선택과 core guard를 고정 fixture로 평가|Android coordinator/provider·외부 앱 실제 효과|
|T7 실제 로컬 모델+실제 폰|TeamModelWorkflow의6종과 기존5종, 실제 scoped provider readback|해당 원문/모델/예산/폰/바이트의 end-to-end 결과|대상 밖 모든98개 기존 도구와 신규 도구의 열린 업무 품질|
|T8 실제 PC agent|인증된 phone→broker→고정 adapter→선택 workspace→폰 결과 검증|그 adapter/버전/도구/효과 범위의 통합|다른 vendor agent·모든 MCP·임의 PC 제어|
|T9 외부 효과|별도로 허용한 실제 앱/계정의 저장·발송·게시·navigation 등 readback|관찰한 외부 효과만|Intent 전달, provider 로컬 저장, 프로세스 성공으로 대신할 수 없음|

현재 문제 해결 eval의28개는 긍정적18, 안전 경계6, 외부 확인 대기4로 집계한다. 이 비율을 실제 폰의 과제 수/성공률로 옮기지 않는다.6종 폰 모델 검사 중 능력 조회는 정보 조회 성공이며 나머지 파일/메모/알림 산출과 구분한다. `capability_gap`, `awaiting_owner_input`, `PREPARED`, `paused`를 양성 작업 완료 분자에 넣지 않는다.

검사 실행 방법은 향후 담당 창에서 확정한다. 현재 Gradle의 core/app unit·lint/release tasks와 Android test 조립을 사용하고, Python의 `mcp`/`eval`/worker 테스트를 별도 lane으로 둔다. `:core:exportScenarioRuntime`의 test classpath와 exported prompt/schema, 실제 evaluator 소스·fixture·model pin을 같은 manifest에 묶는다. 새 시나리오가 있다는 이유로 결과표를 채우지 않는다.

실제 모델 비교는 같은 요청·최근 대화·도구 목록·권한·예산·tokenizer/template·샘플링으로 순서를 교차하고 반복한다. compact/strict/최소 다음 행동 loop와 모델 교체를 동시에 바꾸지 않는다. 첫 도구명만 맞히거나 불필요한 질문으로 멈춘 것을 전체 문제 해결로 인정하지 않는다. 자동 점수는 실제 transcript/호스트 근거와 사람이 대조하고 오탐을 보존한다.

## 15. 보안·장애 fault matrix

아래는 **실행할 검사 설계**다. 행의 존재는 PASS가 아니다. 모든 주입은 자기 QA fixture·별도 환경에서 수행하고 실제 소유자 발송/자료 삭제·모델 다운로드·외부 과금·보호 프로필 우회를 만들지 않는다.

| ID | 주입 위치/실패 | 기대 상태·효과 불변 | 필요한 근거·층위 |
|---|---|---|---|
|F01|unknown/malformed/중복 JSON key·타입·oversize 호출|도구/provider/agent 실행0, 정해진 오류·수정 가능성 표시|T1+T5, 호출 counter·fixture 불변|
|F02|문서/웹/메시지의 권한·다른 MCP 사용 지시|새 권한·scope 확대0, 인용 자료로만 사용|T1/T6/T7/T8, 제공 도구·실제 접근 비교|
|F03|같은 id·다른 project/thread/branch 또는 revision|읽기/수정/삭제/예약취소0, 이전 승인 재사용0|T4/T5, 외국 record 보존·도구 시작 전 거부|
|F04|승인 뒤 대상·본문·파일 SHA 변경|stale effect 거부, 최신 실제 자료로 다시 판단|T4/T5/T7, old/new version·정확 인자|
|F05|실행 중 local/cloud 설정 변경·재페어링|옛 요청은 외부 경로·새 PC 권한 획득0|T1/T4/T5, 네트워크 발송 전 거부·captured revision|
|F06|PC 미등록/꺼짐/모델 없음·busy|준비 필요/paused, 폰 자동 추론0, 모델 자동 다운로드0|T4/T7, 선택 경로·실제 load/generation 기록|
|F07|서버 identity/인증서 pin 불일치·redirect|자격·원문 전송0, 기존 pairing 유지|T1+격리 TLS T3/T8, payload 이전 거부|
|F08|LAN→Tailscale 또는 Wi-Fi→이동통신, 전송 중 단절|동일 identity/pin만 허용, 부분 응답을 완료로 표시0|T7/T8 실제 네트워크, health와 전체 실행 별도|
|F09|쓰기 완료 뒤 응답 유실·ACK 중복|먼저 실제 상태 대조, write/발송 중복0|T4/T5/T8, effect ledger·산출물 bytes|
|F10|취소 전/도구 중/쓰기 commit 직후·PC 취소 응답 유실|취소 이후 새 효과0, 이미 발생한 효과는 사실대로 보존, cleanup 미확인 때 slot 반환0|T5/T7/T8, 취소 revision·attempt/receipt 순서|
|F11|Android process death·worker/supervisor 재시작|원문·scope·취소·목표 보존, 죽은 세션을 completed로 만들지 않음|T3/T4/T8, restart 전후 checkpoint/epoch·자기 child만 정리|
|F12|native OOM/발열·GPU 부족/idle unload 실패|보류/실패·원인 표시, 두 번째 큰 모델 load0|T4/T7/T8·host memory/PID/VRAM·lease 관찰|
|F13|알림 DB save 뒤 schedule 실패·오래된 at/revision Work|미검증/복구 필요, 다른scope 취소0, stale queue를 새 예약으로 인증0|T4+fault seam, DB/Work/Alarm의 동일 id/version 대조|
|F14|알림 권한·채널 철회, due 경계/시각 변경/재부팅|예약과 실제 게시 구분, 부적절한 중복·삭제 scope 게시0|T4 실제 NotificationManager/Work 관찰, 열람 여부 별도|
|F15|SAF 권한 철회/외부 경로/symlink/중간 file edit|범위 밖 접근·부분 원문 전체 덮어쓰기0|T4 실제 SAF와 private-directory 별도, read-delivery·SHA|
|F16|disk full·부분 APK/파일 다운로드·artifact 변조|미완료 파일을 확정/설치/완료로 표시0, 기존 검증본 보존|T2/T3, 임시→원자 확정·hash/size/형식|
|F17|서명된 manifest/다른 APK·권한 변경·설치 동의 대기|exact bytes/권한 승인 재검증, 기존 앱 보존, 작업 무기한 차단0|T1/T3, manifest digest·APK certificate·actual install|
|F18|migration 실패·DB/key 손상·구형 payload|원본 삭제/빈 DB 자동 교체/권한확대0|T1/T3 실제 암호화 합성 DB·키 실패·재시작|
|F19|adapter stdout 위조 '완료'·성공 exit/no output·path escape|검증 안 된 외부 효과/산출물은 UNVERIFIED 또는 실패|T1/T8, host file/readback·경로·형식·parent 조건|
|F20|MCP partial/busy/스트림 종료 누락·묶음 중 실패|partial/error·NOT_STARTED 구분, 미실행 항목을 완료로 세지 않음|현재 MCP mock+T8 실제 host 연결, 수량/종료 신호|
|F21|다른 폰의 job·owner/epoch 재사용·기기 자격 철회|타 job 조회/취소/산출물 읽기0, 새 자격으로 옛 job 승격0|T8 두 독립 페어/principal, 교차 token·job 테스트|
|F22|업무 프로필·잠금·보호 화면·MDM 거부|시스템 보호 경계 유지·우회0, 정확한 미지원/권한 안내|지원 대상 profile의 실제 앱·Intent/URI 시험|
|F23|장기 loop·동일 실패 반복·자식 job 재위임|부모 예산을 보존해 paused, 무한 생성/도구 실행0|T1/T5/T7/T8, round/step/time/output·child 합산|
|F24|진단/agent 로그/exception에 본문·토큰·주소를 주입|기본 지원 묶음에 유출0, 보존·전송 opt-in 분리|T1/T3/T8, 합성 secret canary 검사·공개 artifact 검사|

F13에서 `verifyScheduled`의 활성 WorkInfo state만으로 정확한 at/revision 확인을 대체할 수 없다. mutation 잠금·expectedUpdatedAt·scope 보완과 이 예약 정합성/중간 실패 복구 시험은 별개다. 최신1061이 이 경계를 수정하면 실제 변경 해시와 회귀 결과를 새 행에 기록한다.

## 16. 정량 gate

아래 숫자는 **제안하는 최소 수용 기준**이며 현재 측정 성적이 아니다. 실행 전 fixture·표본·지원 조합·timeout·분모를 동결한다. 내부 베타 통과도 제한된 지원 환경의 시범 허용이며 '완벽'이나 전체 고객의 성공률 보장이 아니다.

| 영역 | 내부 베타 gate | 운영 배포 gate |
|---|---|---|
|보안·scope·완료|노출 기능의 F01~F24 관련 필수 사례100% PASS, 각 경합/취소/stale 주입 지점 최소3반복, wrong target·권한확대·허위 완료·비밀유출0|같은 기준에 각 경합 지점 최소10반복과 새 미사용 변형 추가. 미해결 P0=0|
|unit/build|선택 보안 포함 core/app/worker/MCP/eval 필수 실패·오류·skip0, lint errors0, 정확 release/test·bundle 서명 PASS|같은 기준, 재현 가능한 clean CI와 release artifact 연결. 새 경고 검토/승인 기록, 기존144경고를0으로 숨기는 규칙 제외 금지|
|입력·설치 pin|후보 입력 hash/목록 드리프트0, main/test/worker 설치 bytes 불일치0|canary 승격 bytes 동일, 모든 지원 조합의 설치/정책/모델 pin 명시|
|기존6종 모델 폰 suite|6종×2회 전부 실행; 실제 산출/정정5종10회와 능력 조회2회를 별도 집계. 중단·질문을 성공으로 대체하지 않음|미사용 표현·정정·복합 과제20종 이상×3회, 실제 모델/폰의 조건 충족≥95%(60회면57회 이상). 발송/삭제 등 중요 대상·범위 오류0, 핵심 안전 사례는100%|
|양성·안전 점수|양성 완료, 정보 조회, 안전 거부, 질문, PREPARED, 실행 실패, 미평가를 별도 분모로 보고|전체/도구군별 분모·지연·실패 유형·사람 검수 오탐 공개. 한 군의 실패를 다른 군의 조회 성공으로 가리지 않음|
|기존 회귀|마지막 정확 버전의 기존 수신자·권한·취소·원문·저장·프로젝트 회귀 모두 통과|지원군별·업데이트/복구 후 같은 회귀. 테스트 수1321 자체를 고정 목표나 안전성 점수로 삼지 않음|
|온보딩|기존 upgrade1 + 별도 clean 폰-PC pair1의 전 과정 성공, 권한 거부/PC 꺼짐/재연결 각3회|지원한다고 표시한 기기/OS/PC/profile 조합 각각 clean/upgrade/recovery PASS. 공유PC 지원은 두 독립 페어 교차 격리 PASS|
|업데이트·복구|정상 업그레이드, 설치 실패/동의 대기, 더 높은 version 복구 각1회 이상. fixture 데이터·권한 보존100%|각 지원 조합·DB 전신 버전별3회, 승인된 rollback 호환 정책과 자료 보존100%. 실제 owner자료 삭제0|
|외부 PC adapter|지원 adapter1개·고정 버전의 읽기/초안 과제 최소3종×2회, 실제 결과 검증100%, 외부 효과는 별도|표시할 adapter/version/도구군마다 정상·취소·restart·격리·필요 실제 효과100%. 미지원 vendor/MCP를 함께 지원한다고 표기하지 않음|
|연결|현재 LAN과 직접 Tailscale의 실제 전체 요청 각각3회, 단절/재연결3회. health-only 제외|지원 경로의 Wi-Fi↔이동통신 전환·응답/ACK 유실·취소 각10회, 권한·pin 유지100%, 중복 효과0|
|취소|폰 UI가 취소를 표시하는 시간 p95≤2초, 네트워크 취소 시도≤2초, 새 효과 차단100%|최소30회 측정, 같은 목표. 정리 완료 목표≤30초; 초과 시 cleanup_unconfirmed·lease 격리로 표시하고 free로 거짓 보고0|
|지연|동일 PC warm 상태의 단순 시각/메모 요청 최소20회, e2e p95≤150초를 초기 목표로 기록; cold/모델전환 별도|실제 사용자에게 약속할 단순 요청 목표 p95≤60초, 최소100회. 미달이면 속도 개선/제품 약속 조정 후 판정. 현재 달성 주장0|
|RAM·idle|20회 load/run/unload에서 host별 heavy active≤1, cleanup 실패 때 새load0, idle release≤330초 또는 명시 실패|동일 모델/profile의30분 연속+20전환; idle 이후 private bytes/PSS가 초기 idle 대비 max(128MiB,10%) 이내 목표, VRAM 잔류/다른owner 종료0|
|canary 관찰|1개 검증 pair에서24시간, 최소20 실제 scoped 작업, P0/데이터 손상/무단효과0|최소3개 지원 사용자 pair로7일·100 scoped 작업, crash/ANR로 필수 자료 손실0, 성공/미완료·성능·지원 이슈를 별도 검토|

전력·발열·메모리·지연은 같은 모델/PC/폰/앱/네트워크와 측정 조건으로 보고한다. 'health53ms', 파일 검사 포함 모델 실행 시간, 오래된 폰 CPU tokens/sec는 서로 다른 값이다. 위 표본은 통계적 모집단 정확도를 인증하지 않는다. 안전 위반1건이라도 있으면 평균 성공률과 관계없이 후보를 멈추고 원인·보완·해당 회귀를 남긴다.

## 17. 증거 원장과 지원 진단 형식

아래 필드는 새 release evidence 형식의 설계다. 기존 영수증 파일이 이미 모두 이 형식을 구현했다는 뜻은 아니다.

| 묶음 | 필드 |
|---|---|
|식별/범위|task, candidate, evidence schema, producer/검증자 역할, 시작/종료 KST/UTC, 검사 단계(T0~T9), 지원 profile|
|source/build|commit·dirty·source-tree SHA, 입력 manifest SHA, 도구chain/의존성/native/asset/schema pin, 정확 build command/profile·결과|
|artifact/install|main/test/worker 각각 SHA·version·certificate/trust key id·ABI, 전신 버전·설치 후 bytes readback, data reset 여부|
|runtime/model|model/프로젝터·runtime 전체 SHA·문맥/출력/샘플링·backend, adapter executable/profile pin, health와실제 model run 구분|
|사례/결과|fixture/case revision·hash, 선택 사례/반복·실행/미실행/skip, 계획·도구·최종 조건·정확한 효과와불확실 범위|
|안전·정리|permission/권한 snapshot의 비개인 참조, approval/effect binding 검사, cancel/cleanup 결과, 자기fixture 잔여, 원래 설정 유지|
|승격|실패/검토/예외, gate 판정, canary 관찰, higher-version recovery·지원 범위·기록 위치|

실제 원문/응답/세부 trace·job identity·장치/서버/주소·계정은 ignored private evidence로 보관한다. 공개 문서와 CI artifact에는 합성 집계/해시/비개인 코드만 둔다. artifact bundle에 `.local`, `.codex-attachments`, build cache, 설정·자격·model weights가 섞이지 않도록 금지 목록과 합성 secret marker 검사로 확인한다. 모델 응답을 지원 진단으로 자동 전송하지 않는다.

테스트 검증자는 product result writer와 분리된 실제 readback을 사용한다. 예를 들어 저장 함수가 success를 반환한 것만 확인하지 않고 DB/파일/예약을 직접 읽는다. 기대값은 모델 입력에 넣지 않고 fixture setup/검증기에만 둔다. 실패 후 cleanup이 실패하면 본 시험과 잔여 자료 정리를 별개로 보고하며 성공 결과로 덮지 않는다.

## 18. 단계별 구현 순서와 인계 경계

| 단계 | 구현/검증 산출 | 다음 단계 전 조건 |
|---|---|---|
|A0 기준 확정|root의1061 hotfix와 정확 후보 소스/바이트/검사 원장, 이전 실패 유지|현재 실행 중인 창을 침범하지 않고 P0·남은 효과 경계 확정|
|A1 기존 안정화|원문/목표/권한·완료·알림/파일 경계 회귀, 새6종 실제 모델/폰 검사|T1~T7 pin이 같은 후보, 미실행/실패/안전대기 분리|
|A2 계약·관찰 분리|기존2모듈 안 facade/DTO 경계, release evidence/schema golden, typed 오류·상태|프롬프트/모델/DB 변경 없이 기존 behavior 회귀 유지|
|A3 PC 패키징|worker/runtime 의존성 lock·서명 bundle·설치/업데이트/지원 진단 설계 구현|개인 경로 제거, 실제 설치 manifest, 원래 pairing/권한·모델 보존|
|A4 broker 최소 경로|인증 principal·request binding·workspace·예산·취소·artifact 수신, adapter1개 읽기/초안|T8 정상·fault·cleanup·비밀 유출0. PC 전체 제어·모든 MCP 완성을 주장하지 않음|
|A5 허용 제어 확장|필요한 PC/폰 제어를 capability별로 추가, 대상·본문·버전·실행 후 readback|실제 앱/버전/프로필·동의·효과가 확인된 adapter만 노출|
|A6 내부 beta|정확 버전 설치·팀원 onboarding·canary·전진복구·기본 실제 업무|내부 gate의 실행 증거, 지원 대상/미지원/장애 대응 안내|
|A7 운영 확대|지원 환경·agent/version 확대, 반복 품질/성능·업데이트/복구·운영 관찰|운영 gate 통과. 서비스/스토어 출시 관련 별도 조건은 별도 검토|

gate 미달 시 소스를 되돌려 지우거나 합격한 것처럼 기록하지 않는다. 좁은 지원 기능으로 내부 베타를 한정하거나 해당 기능을 준비 중/미지원으로 표시할 수 있으나, 사용자 목표가 요구한 긍정적 결과 자체를 안전 중단 수치로 대체하지 않는다. 미지원 도구를 숨은 외부 shell/MCP로 우회하지 않는다.

이 문서 작업은 T0 설계·정적 검토 범위다. 다른 architecture 문서의 저자와 root가 모듈/프로토콜/상태/정량 기준을 조정한 뒤 단계별 구현을 소유한다. 본 작업에서는 README/사용 안내·공통 변경 기록·소스·CI·배포 설정을 수정하지 않았다. root에게 새 문서의 위치·검토 범위·미검증 조건을 전달하고 공통 변경 기록 반영을 요청한다.

## 19. 근거 소스

현행을 확인하는 경로다. 링크한 파일의 존재가 그 기능의 실제 실행 성공을 뜻하지 않는다.

- 빌드: [settings](../../settings.gradle.kts), [root plugins](../../build.gradle.kts), [core](../../core/build.gradle.kts), [app](../../app/build.gradle.kts), [wrapper](../../gradle/wrapper/gradle-wrapper.properties), [CI](../../.github/workflows/android.yml).
- Android native: [CMake](../../app/src/main/cpp/CMakeLists.txt), [llama pin](../../app/src/main/cpp/source-pin.json), [image pin](../../app/src/main/cpp/image/source-pin.json). PC 복구 native는 [별도 pin](../../pc-worker/native/source-pin.json).
- 앱/자료 호환: [LocalRepository](../../app/src/main/java/com/user/localagent/data/LocalRepository.kt), [ChatRecords](../../app/src/main/java/com/user/localagent/data/ChatRecords.kt), [QueuedChatRequest](../../app/src/main/java/com/user/localagent/ChatRunState.kt), [Room queue](../../app/src/main/java/com/user/localagent/data/ChatQueueStore.kt), [manifest](../../app/src/main/AndroidManifest.xml).
- 실행/효과: [ToolExecutor](../../app/src/main/java/com/user/localagent/tools/ToolExecutor.kt), [TaskHarness](../../core/src/main/kotlin/com/user/localagent/core/TaskHarness.kt), [WorkContinuity](../../core/src/main/kotlin/com/user/localagent/core/WorkContinuity.kt), [ReminderScheduler](../../app/src/main/java/com/user/localagent/scheduling/ReminderScheduler.kt).
- PC 계약·자원: [PC endpoint contract](../../core/src/main/kotlin/com/user/localagent/core/PcComputeContract.kt), [pairing](../../app/src/main/java/com/user/localagent/compute/PcPairingClient.kt), [transport](../../app/src/main/java/com/user/localagent/compute/PcTransport.kt), [resource broker](../../app/src/main/java/com/user/localagent/compute/ComputeResourceBroker.kt), [facade](../../app/src/main/java/com/user/localagent/inference/AutomaticComputeEngine.kt).
- PC 실제 스냅샷: [배포 경계](../../pc-worker/deployed/README.ko.md), [server](../../pc-worker/deployed/server.py), [owner lifecycle](../../pc-worker/deployed/owned_engines.py), [recovery](../../pc-worker/deployed/recovery_engines.py), [supervisor](../../pc-worker/deployed/supervisor.py), [memory budget](../../pc-worker/deployed/windows_budget.py).
- 업데이트: [verifier](../../core/src/main/kotlin/com/user/localagent/core/Updates.kt), [manager](../../app/src/main/java/com/user/localagent/updates/UpdateManager.kt), [manifest generator](../../scripts/release_manifest.py).
- 위임 경계: [개발 MCP](../../pc-worker/mcp/README.ko.md), [구현](../../pc-worker/mcp/local_llm_mcp.py), [제한 briefing companion](../../scripts/briefing_companion.py).
- 평가/기기: [scenario 평가](../../pc-worker/eval/README.ko.md), [문제 해결 평가](../../pc-worker/eval/problem-solving-README.ko.md), [6종 실제 모델 폰 검사](../../app/src/androidTest/java/com/user/localagent/TeamModelWorkflowDeviceValidation.kt), [기기 검증 기록](../device-validation.md), [1059 실제 효과 범위](../recipient-completion-validation-2026-11-14.ko.md).
- 제품/공유 계약: [요구사항](../requirements.md), [대화 원칙](../conversation-workflow.ko.md), [의미 해석 연구](../agent-harness-research-2026-10-31.ko.md), [내부 배포 감사](../internal-release-readiness-2026-11-14.ko.md), [공통 변경 기록](../agent-change-log.md).
