# 카카오톡 공유와 직접 발송 연결 상태

집순이에서 “이 글을 카톡으로 공유해 줘”라고 하면 `prepare_kakao_share`가 공유할 본문을 보여준다. 승인 후 집합은 개인 프로필에서 Android의 텍스트 공유 요청을 `com.kakao.talk`으로 전달한다. 받을 대화방 선택과 최종 보내기는 휴대폰에서 사용자가 한다. 공유 인계 상태는 `PREPARED`이며 이 작업을 자동으로 다시 실행하지 않는다. 카카오톡 미설치 또는 해당 공유 처리기가 없으면 실제 메시지를 전달하기 전에 실패한다. 카카오디벨로퍼스의 별도 템플릿 공유 SDK는 이 경로에 사용하지 않는다. [Android의 텍스트 공유](https://developer.android.com/training/sharing/send)

## 직접 발송을 연결하려면

1. 운영자가 카카오디벨로퍼스에 집합용 앱을 등록하고 카카오 로그인을 켠다. REST API 키에 OAuth 리다이렉트 URI를 등록한다. 집순이 프로젝트의 구글·Microsoft용 로그인 연결이나 사용자의 기존 카카오톡 앱 로그인은 이 앱의 액세스 토큰을 발급하지 않는다. [카카오 로그인 REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api)
2. 본인에게 보내려면 메시지 전송 동의항목 `talk_message`를 등록하고 휴대폰에서 사용자 동의를 받는다. 친구에게 보내려면 카카오 서비스 내 친구목록 `friends` 동의와 카카오톡 소셜 API 권한도 확보한다. 팀 멤버 시험 범위를 넘어 일반 서비스 사용자에게 제공하려면 비즈니스 정보 심사와 카카오톡 친구/메시지 추가 기능 신청 절차를 진행한다. 대상은 해당 서비스의 조건을 충족하는 카카오 사용자이며 전화번호 또는 대화방 이름으로 발송할 수 없다. [메시지 이용 조건 및 권한 신청](https://developers.kakao.com/docs/ko/kakaotalk-message/common)
3. REST 방식에서는 휴대폰에서 인가 요청을 열고 원래 세션과 콜백의 `state`를 대조한다. 등록된 URI로 받은 인가 코드를 신뢰된 백엔드에서 `https://kauth.kakao.com/oauth/token`에 교환한다. 클라이언트 시크릿을 사용하는 키라면 백엔드에만 둔다. 토큰은 모델·대화·로그로 전달하지 않는다. 해당 계정의 암호화 저장, 철회, 만료 시 재인증을 연결한다. [인가 및 토큰 교환](https://developers.kakao.com/docs/ko/kakaologin/rest-api)
4. 본인은 `/v2/api/talk/memo/default/send`, 친구는 실제 피커나 친구 목록 결과에서 선택한 최대 5개 UUID를 `/v1/api/talk/friends/message/default/send`에 전달한다. 두 경로 모두 `https://kapi.kakao.com`에서 POST로 호출한다. 확정 카드에는 연결 계정, 대상, 실제 템플릿과 링크를 표시하고 1회 승인을 묶는다. 템플릿·수신자가 바뀌면 새 승인을 받는다. [공식 발송 요청 형식](https://developers.kakao.com/docs/ko/kakaotalk-message/rest-api)
5. 본인 발송의 성공 응답은 `result_code = 0`이다. 친구 발송은 HTTP 200에서도 일부만 성공할 수 있다. `successful_receiver_uuids` 및 `failure_info`를 원래 선택 대상과 비교해 사람별 결과를 기록한다. 통신 단절로 응답이 사라졌으면 결과 미확인으로 남겨 중복 발송을 방지한다. 발송 성공 응답은 읽음 확인으로 사용하지 않는다. [공식 응답 규격](https://developers.kakao.com/docs/ko/kakaotalk-message/rest-api)

## 이번 배포의 경계

직접 발송용 OAuth 콜백·계정 연결·수신자 피커·송신 도구는 미구현이다. 구현할 때는 `prepare_kakao_share`를 그대로 유지하고 별도의 명시적인 직접 발송 권한과 상태 기록을 연결한다. 기존 공유 도구에 부여한 승인은 직접 발송 권한이 되지 않는다.

2026-11-25 점검에서 지정된 카카오 앱 키 환경 변수는 이 개발 프로세스와 Windows 사용자·컴퓨터 범위에 설정되어 있지 않았다. 타 프로세스와 외부 카카오디벨로퍼스 콘솔의 발급 여부는 확인하지 않았다. 실제 카카오톡 발송은 시험하지 않았다.
