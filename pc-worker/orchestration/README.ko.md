# 오케스트레이션 v1 오프라인 계약·상태기계

작업 `ARCH-P1-01`, 기준2026-11-15 / HEAD `f25a2e2b0382c72012e9a853dea841f50c519c93`에서 시작한 별도 신규 파일이다. [통합 설계의 단계1](../../docs/architecture/README.ko.md)에 따른 작은 시제품이며 **제품 broker·인증·실행 서비스가 아니다.** 기존 Android/worker/Gradle·설계3문서·MCP는 바꾸지 않는다.

## 구현한 범위

| 파일 | 실제 구현 |
|---|---|
|`contracts.py`|3종 메시지의 strict JSON·작은 schema subset 검사, frozen DTO, canonical task digest. 네트워크·파일·프로세스·모델 접근 없음|
|`state.py`|합성 host authority와 intent의 분리, 현재 binding·subset/CAS, 중복 key 충돌, 단회 승인 consume, 취소/ACK·unknown effect·명시 safe retry의 bounded in-memory 상태|
|`test_contracts.py`|공개 schema/합성 wire golden 일치, malformed/unknown/중복키/NaN/수형·byte/depth/node·원문 비노출 검사|
|`test_state.py`|합성 상태 golden, legacy 외부 불허·다른scope/epoch/revision·승인 재사용·취소·효과 불명·host provenance·용량/재시도 한도 검사|

공개 형식과 합성 자료는 [protocol/orchestration/v1](../../protocol/orchestration/v1/README.md)에 있다. 이 단계는 `inspect_workspace`, `propose_artifact`, `write_artifact`라는 제안 operation과 capability3개만 모델링한다. 실제 기존102도구의 등록/dispatch나 파일 작업을 구현한 것이 아니다. 별도 Gradle 모듈·큰 coordinator 교체도 없다.

## API와 신뢰 경계

1. `parse_message(raw)`는 `TaskRequest`, `Control`, `HostObservation` 중 하나를 반환한다. shape 통과는 동의·host 서명·실제 관찰을 뜻하지 않는다. 알 수 없는 binding/grant/source/VERIFIED 필드는 거부한다.
2. `HostAuthority`는 trusted caller가 제공하는 **합성 host 상태**다. owner/project/branch/scope/target/epoch/authority revision과 capability subset을 검사한다. `allow_external_agent`가 빠지면 false이며, 외부 intent의 파싱 성공과 실행 허용을 구분한다. 기존 Android 큐 파서는 연결하지 않는다.
3. `StateMachine.submit(request, authority)`는 현재 허용 범위에 맞는 binding을 in-memory에 기록한다. 공개 메시지에서 authority를 생성하는 함수는 없다. owner+idempotency key가 같고 parsed task digest가 같으면 기존 snapshot, 다르면 conflict다. 다른 authority epoch/revision/소유 범위는 이전 job을 이어받지 못한다.
4. `control(message, authority, source=Source.OWNER)`는 in-memory approve/start/cancel/retry만 처리한다. write는 요청 digest·scope·authority·시작할 job revision에 묶인 승인1개를 한 번 소비해야 한다. 재시도에는 새 승인이 필요하다.
5. `observe(message, authority, source=Source.HOST)`만 host-attested effect/취소 ACK를 반영한다. agent나 JSON의 `source:"host"`는 이 출처가 아니다. 실제 endpoint는 caller 인증과 ledger 검증으로 출처를 정해야 한다. 이 Python enum 자체는 인증이나 OS 격리 수단이 아니다.
6. `snapshot`도 현재 authority를 재검사하며 frozen snapshot을 반환한다. 같은 in-memory 인스턴스의 전이는 RLock 안에서 CAS를 검사한다. crash-safe 원자성·다중 process/분산 CAS·실제 ledger는 구현하지 않았다.

`authority.revision`은 캡처한 권한 상태의 버전이고, `expected_revision`은 해당 job 전이의 CAS 버전이다. 두 값을 혼용하지 않는다. 권한 subset의 철회와 외부 허용 false도 각 동작에서 검사하므로, caller가 revision을 잘못 올리지 않은 합성 경우에도 새 효과를 허용하지 않는다.

`input_digest`는 여기서는 intent의 합성값이며 실제 원문·전체 파일·대상 버전을 읽어 증명한 값이 아니다. 실제 ApprovalBinding의 account/recipient/body/attachment·read-delivery·기한 검증은 후속 host 계층의 책임이다. 실제 grant 발급·암호화 인증으로 오해해 이 모듈을 endpoint에 연결하지 않는다.

## 상태와 효과를 분리

| 상황 | 상태·효과 |
|---|---|
|read/proposal 제출|READY, effect NONE. 아직 도구 실행 아님|
|write 제출|AWAITING_APPROVAL; 승인 후 READY, 승인 consume 뒤에만 논리 RUNNING|
|host의 not-applied|PAUSED/NOT_APPLIED. 자동 재시도하지 않으며 owner의 retry→start가 별도로 필요|
|host의 effect-unknown|PAUSED/UNKNOWN. retry 거부. host의 후속 관찰로 NOT_APPLIED 또는 VERIFIED를 확인해야 함|
|실행 중 cancel|CANCEL_REQUESTED. 실제 취소 ACK·정리 완료를 뜻하지 않음|
|host cancel ACK|CANCELLED. in-flight 효과는 UNKNOWN으로 남고 이미 검증된 효과는 보존|
|취소 중/취소 뒤 늦은 효과|취소 상태를 유지하면서 host-attested 결과 digest를 보존. 이를 긍정 업무 완료로 바꾸지 않음|
|일반 RUNNING/UNKNOWN의 host 검증|요청·scope·epoch·revision 일치 시에만 COMPLETED/host-attested VERIFIED|

agent의 성공 설명·프로세스 종료0·임의 `VERIFIED`는 전이 근거가 아니다. 이 시제품의 `VERIFIED`도 **합성 host assertion을 타입/상태로 수용했다는 뜻**이며 실제 DB/file/hash/send/readback 검증이 아니다. 이미 취소된 작업에서 UNKNOWN을 보정하는 지원 범위 밖의 복잡한 late-event history나, 권한 철회 뒤의 제한된 reconciliation API는 아직 없다.

## 한도와 재현

- UTF-8 wire16KiB, 구조 깊이8, node512. 중복키·NaN/Infinity·unpaired surrogate·float/exponent token 거부. integer는 bool을 제외하고 안전한53bit 범위, epoch/revision은0 이상이다.
- opaque ID1–64자, digest 소문자hex64자, 요청 capability1–3개 중복0. 메시지는 알려진 필드만 허용한다.
- 동시에 보존하는 in-memory job 최대64개. attempt 기본2회, 설정 가능1–3회. TTL/자동 eviction/원문 저장/재시작 복원 없음.
- 오류는 고정 코드만 반환하고 입력·예외 원문을 복사하지 않는다. 인증 실패·실제 권한 판정을 이 코드가 대신한다고 주장하지 않는다.

저장소 루트에서 CPython3.10 이상과 표준 라이브러리만 사용한다. `-I`는 환경/user-site 영향을 줄이고 `-B`는 bytecode cache 생성을 막는다.

```text
python -I -B -m unittest discover -s pc-worker/orchestration -p "test_*.py" -v
```

실제 오프라인 결과(2026-11-15): **38개 unittest PASS, 실패0/오류0/skip0**.27개 wire golden(유효shape9/거부18),7개 상태 script의31개 step은 위38개 검사 안에서 실행되며 독립 검사 수로 중복 합산하지 않는다. 최초35검사 중1개는 완료된 효과의 retry 거부 코드 기대가 PHASE였으나 실제 더 구체적인 EFFECT_UNCERTAIN_OR_APPLIED여서 실패했다. 기대를 실제 효과 계약에 맞게 교정하고 취소/후속 관찰3개를 추가해 최종38개 전체를 통과했다.

실제 인증·보관·TTL·provider·endpoint·Kotlin parity·agent/폰 제어·actual local model·실제 외부 효과는 **미구현/미검증**이다.38 PASS는 T1 순수 합성 계약·상태 근거다. 본 작업에서 기존 core/app 보안 검사·APK assemble·서명·ADB·CI 게시·실배포·네트워크·모델·외부 agent 프로세스를 실행하지 않았다. 현재 실행 중인1061 모델 업무 검사와 결과를 섞지 않는다.

## 후속 통합 전 필요한 일

호스트 authority/grant/receipt를 실제 인증·현재 ledger·단회 승인·권한 철회·별도 보관 정책에 연결하고, 실제 target/원문/byte readback을 검증해야 한다. 구형 payload migration, 실제 provider pre-tool gate·격리·정리/취소 ACK, cursor/event sequence, signed artifact pin, Android/Kotlin의 같은 golden 허용/거부는 다음 단계다. 현재 legacy 외부 불허와 unknown effect 재시도 차단을 그 과정에서 유지한다. 최종 제품 wire는 통합 담당이 버전·schema digest·호환성 검토 후 확정한다.
