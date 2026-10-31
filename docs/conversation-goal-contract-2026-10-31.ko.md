# 같은 대화에서 다루는 목표 — 저장·실행 계약

이 변경은 별도 목표 모드나 입력 양식을 거치지 않고, 현재 사용자의 명시적인 목표 요청을 실제 도구로 처리하기 위한 backend다. 일반 업무를 자동으로 지속 목표로 바꾸지 않는다. 자연어의 뜻은 기존 모델의 현재 요청 해석에 맡기며 새 전역 의도 필드나 어휘/정규식 분기를 추가하지 않았다. 도구 호출·인자·저장 버전·대화 범위·실행 영수증은 호스트가 검사한다.

## 도구와 상태

| 도구 | 실제 효과 |
| --- | --- |
| get_goal | 현재 대화 목표와 기준 페이지 조회. 업무 완료 근거가 아니다. |
| create_goal | 기존 목표가 없을 때 DRAFT/PENDING으로 저장·재조회. 실행하지 않는다. |
| update_goal | actual goalId/version으로 내용 변경. 기준은 ID patch라 언급하지 않은 기준을 보존하며 삭제 ID는 따로 명시한다. |
| start_goal | 호스트 lifecycle에서 bounded 실행 한 건을 실제 queue에 연결·저장한 경우만 시작으로 보고한다. |
| pause_goal | 연결된 실행을 quiesce한 뒤 PAUSED 저장. |
| resume_goal | PAUSED 목표에 bounded 실행 한 건을 실제 연결한다. |
| clear_goal | 연결 실행을 quiesce한 뒤 같은 목표·버전만 CAS 삭제한다. 수행한 외부 작업을 되돌리지 않는다. |

모델은 status, evidence, USER_CONFIRMED, COMPLETE, linkedRunIds, thread/project/scope를 인자로 줄 수 없다. 실제 work가 바뀌면 이전 host/user 완료 근거는 모든 기준에서 PENDING으로 초기화하고 목표를 DRAFT로 바꾼다. literal work가 같은 patch는 기존 VERIFIED/USER_CONFIRMED·버전을 유지한다. UPDATE가 work를 바꾸고 이전 linkedRunIds가 있으면 old work를 먼저 quiesce해야 하며 실패하면 이전 목표를 저장하지 않는다.

START/RESUME는 명시 사용자 요청에 따른 로컬 queue metadata 동작이어서 RiskLOW다. 미래 업무 도구의 별도 권한·승인은 그대로 유지한다. CLEAR는 기존 삭제 정책에 맞춰 RiskHIGH다. 주기 실행·자기시작·추가 외부 권한을 부여하지 않는다.

## actual host API

`ScopedGoalTools(scope, store, authorizedTools: () -> Set<String>, callerRunId, lifecycle, editReadGuard)`의 `execute(call): ToolResult?`를 등록 handler에서 사용한다. factory는 원래 owner 요청의 FULL ChatAccessScope와 run binding을 캡처한다. authorization provider는 그 요청에서 현재 검증된 required/allowed tool을 반환한다. 호출 전에 정적 전체 도구 목록을 승인으로 복사하거나, 문서·지침에 인용된 명령으로 승인하지 않는다. 편집 읽기 guard의 기본값은 거부이며 실제 요청의 GoalReadAuthority를 주입한다.

실제 store는 `GuardedChatGoalStore(ChatGoalStore(repository))`다. ChatGoalStore에는 `loadGuarded(expectedAccess)`, `saveGuarded(expectedAccess,goal)`, `deleteGuarded(expectedAccess,goalId,expectedUpdatedAt)`를 추가했다. static MUTATION 안에서 thread/project/revision뿐 아니라 scopeKey/useOwnerMemory/memoryEnabled를 포함한 FULL access를 전후 비교한다. 삭제도 저장된 실제 goalId/version을 비교한다. 기존 load/save/delete/move API는 유지했다. LocalRepository는 수정하지 않았다.

version은 목표의 실제 updatedAt Long을 복사한 decimal string이다. 모델이 새 시각을 제안하지 않으며 기존 generic integer 검증을 넓히지 않는다. 기존 목표를 지운 뒤 오래된 updatedAt payload로 다시 살리는 것도 거부한다.

호스트가 제공하는 `GoalToolLifecycle.apply(GoalLifecycleRequest)`의 action은 START/RESUME/PAUSE/CLEAR/UPDATE다. START/RESUME callback은 실제 ACTIVE 목표·새 updatedAt·runlink와 bounded queue 한 건을 저장·확인한다. backend도 이를 readback한다. PAUSE/CLEAR/UPDATE callback은 quiesce만 수행하고 metadata save/delete는 backend가 CAS로 한다. 같은 caller가 목표에 연결돼 자신을 join할 수 없으면 `quiesced=true, deferredCallerStop=true`로 나머지 실행의 정지를 알린다. backend는 PREPARED를 반환하며 root가 durable tool receipt 뒤 caller를 중단한다. 이미 중단됐다는 VERIFIED 주장을 하지 않는다.

root는 GoalStore.verifyEvidence뿐 아니라 GoalRunEvidence의 이전 업무 단계 상속에서도 GoalToolCatalog.names를 제외한다. 목표 관리 영수증은 현재 사용자의 생성/수정 요청을 확인할 수 있지만 BUSINESS 목표 완료를 증명하지 않는다.

## 모델 문맥과 paging

`GoalModelContext.build(result,maxChars=2200)`를 Goal 도구 결과에 사용하고 generic1100 축약을 다시 적용하지 않는다. actual goalId/expectedUpdatedAt와 표시한 기준의 full ID는 자르지 않는다. 텍스트·조건·기준 페이지가 일부면 contextPartial/criteriaPartial/totalCriteria/nextCriteriaOffset으로 표시한다. PREPARED/deferredCallerStop도 보존한다.

get_goal은 기본 기준8개, 최대16개다. criteriaOffset>0 조회에는 처음 읽은 goalId+expectedUpdatedAt를 함께 보내 중간 변경을 거부한다. update는 전체 기준 배열 교체가 아니라 기존 ID 수정·ID 없는 항목 추가·명시 removeCriterionIds 삭제이므로 일부 페이지만 보고 보이지 않은 꼬리를 지우지 않는다. metadata lookup은 private read 범위이며 public research나 완료 근거로 바꾸지 않는다.

get_goal의 textField는 outcome/criterion/constraint 중 실제 필드 하나를 goalId·version·기준ID 또는 조건 index로 선택한다. textOffset·textLimit으로 원문 구간을 읽으며, textRead에 실제 offset/totalChars/nextOffset/textComplete를 표시한다. 최대900자는 상한이며 JSON 표시 예산에 맞추어 더 짧은 정확한 구간을 반환할 수 있다. Unicode 문자 중간을 나누거나 formatter에서 원문을 조용히 자르지 않는다. 기존 저장 목표의 범위도 다루도록 offset/index 상한은12000이고 실제 필드 범위를 재검사한다.

update_goal의 textEdits는 실제 필드에 정확히 한 번 존재하는 oldText를 newText로 바꾸고 나머지를 보존한다. 기존 전체 필드를 교체할 때에는 기존 전체 내용, 부분을 수정할 때에는 그 부분의 실제 원문 전달 근거가 있어야 한다. GoalReadAuthority는 같은 방·프로젝트·목표·버전의 실제 host preview가 이번 generationQuery에 포함됐는지 확인하고 매번 교체한다. 과거 요청의 읽기를 누적 권한으로 쓰지 않는다. UPDATE 실행 전 읽기 guard 거부는 실제 쓰기 시도와 분리해, 읽고 다시 수정할 수 있게 한다. 실제 backend도 저장 전에 다시 확인한다. 기존17번째 이상 조건은 typed constraintEdits로 해당 위치만 바꾸며 새 배열의16개 상한 때문에 기존 꼬리를 지우지 않는다.

Core 등록은 GoalToolCatalog.definitions/names, array용 isArrayArgument/validateArray, typed argumentSchema/validate hooks를 사용한다. get_goal은 context-read이고 mutations는 ACT/DEBUG만 허용한다. legacy GOAL 모드는 읽기 전용을 유지한다. registry/ToolExecutor/AppContainer/Co·실제 lifecycle를 연결했다.

## 검증과 인계

초기 staging 기록은 아래와 같다. 이후1016 canonical 통합에서 실제 registry95/호스트 큐/요청별 읽기 근거까지 연결했고 전체 JVM 986개, release lint/build/signature 및 test APK 빌드가 통과했다. 실제 기기 설치·Room/UI/모델 시나리오는 장시간 이미지 작업의 기기 lease 인계 뒤에 검증한다. 최신 상태와 해시는 PROJECT_STATE·공통 변경 기록을 따른다.

담당 source11개(기존1/새10: core main2/app main4/test5)와 ChatGoalStore 기준선은 `goals/source-freeze.json`, `baseline/goals`, `goals/ChatGoalStore.diff`로 고정했다. 기준선 SHA는3678f3d8d014d80c6cbde63375120077010f112fb2cace1e85f3773c8de4a988이다. 이전 private-analysis source package는 변경하지 않았다.

실제 기존 app/core ABI와 새 소스를 독립 컴파일하고 core10+app14=24개 순수/합성 검사가 통과했다. 인자 위조·원래 scope/버전·기준 꼬리 보존·proof reset·queue link readback·deferred caller stop·취소·UPDATE quiesce 순서/실패·2200자 문맥을 검사한다. checks/goals/{core,app}-result.json과 source manifest에 해당 입력 SHA가 있다.

별도 GuardedChatGoalStoreValidation은 독립 합성 SQLCipher DB의3사례(CAS/삭제 후 부활 금지, thread revision 없는 정책 변경, 관리 영수증의 업무 완료 검증 거부)이며 conversationGoalStoreApproved=true gate가 필요하다. 소스 컴파일은 통과했지만 실제 DB/queue/model/기기 실행은 하지 않았다. canonical·Gradle·설치·이전 package 변경도 하지 않았다. 전체 root 통합과 실제 lifecycle/모델/기기 검증은 별도다.
