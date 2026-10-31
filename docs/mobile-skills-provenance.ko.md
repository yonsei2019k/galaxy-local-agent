# 휴대폰용 기본 스킬: 출처와 실제 실행 범위

작성일: 2026-10-31. 대상: ZipHap.Agent Android APK 리소스.

이 묶음은 Android 도구에 맞춰 새로 작성한 한국어 작업 지침 18개다. 데스크톱 Codex의 스킬 설치나 Python·셸·Playwright·MCP 서버 설치가 아니다. 외부 스크립트, 실행 파일, 모델 가중치, 유료 추론 API를 추가하지 않는다. 지침을 읽는 것과 모델 가중치 학습은 별개다.

## 공식 자료를 확인한 결과

[OpenAI Skills 개념](https://developers.openai.com/plugins/concepts/skills)과 [작성 가이드](https://developers.openai.com/plugins/build/skills)는 지침·참고 자료와 실제 도구·인증·권한을 구분한다. 이름·설명으로 필요한 스킬을 고른 뒤 관련 본문을 읽는 방식이 적합하다. 이 앱에서도 18개 전문을 매번 프롬프트에 넣기보다 짧은 목록과 필요한 지침만 읽어야 한다.

조사한 공식 문서와 저장소에서는 **스킬별 실제 사용 빈도를 비교할 공식 통계를 확인하지 못했다**. 아래 목록은 사용자 요청과 현재 도구에 맞춘 선정이며 인기 순위가 아니다. 저장소의 별 수·제3자 추천 목록을 실행 빈도로 해석하지 않았다.

조사 시점의 [openai/skills README](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/README.md)는 저장소가 deprecated임을 알리고 [openai/plugins](https://github.com/openai/plugins/tree/0722921d5542fc593105c27bd52630babd8b8c2a)로 안내한다. 공식 카탈로그 안에 있다는 사실이 모든 항목의 OpenAI 저작·동일 라이선스·안드로이드 호환성을 뜻하지는 않는다.

## 고정한 원본과 라이선스

원본 파일과 라이선스를 읽기 전용으로 조사했다. **원본 SKILL.md 전문·스크립트·템플릿을 APK에 복사하지 않았다.** 아래 자료에서 목적과 접근 방식을 비교하고, 휴대폰의 실제 도구 계약을 기준으로 별도의 짧은 지침을 작성했다. 새 지침에 원본 프로그램의 실행 능력이나 라이선스가 자동 이전되는 것은 아니다.

| 조사한 원본 | 고정 커밋 | 확인한 라이선스 | Android 적용 판단 |
| --- | --- | --- | --- |
| [define-goal](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/define-goal/SKILL.md) | 49f948faa9258a0c61caceaf225e179651397431 | [Apache-2.0](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/define-goal/LICENSE.txt) | Codex 목표 도구 대신 현재 노트와 실제 도구로 목표를 표현. 별도 목표 실행 API가 생겼다고 하지 않음 |
| Superpowers [writing-plans](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/superpowers/skills/writing-plans/SKILL.md), [executing-plans](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/superpowers/skills/executing-plans/SKILL.md) | 0722921d5542fc593105c27bd52630babd8b8c2a | [MIT, Jesse Vincent](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/superpowers/LICENSE) | Git worktree·하위 에이전트·셸 절차를 내장 기능으로 간주하지 않음 |
| Superpowers [systematic-debugging](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/superpowers/skills/systematic-debugging/SKILL.md) | 같은 plugins 커밋 | 같은 MIT | 재현·증거·원인 가설을 구분하는 자체 지침. logcat·실행기 자동 추가 없음 |
| Superpowers [verification-before-completion](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/superpowers/skills/verification-before-completion/SKILL.md), [test-driven-development](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/superpowers/skills/test-driven-development/SKILL.md), [requesting-code-review](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/superpowers/skills/requesting-code-review/SKILL.md) | 같은 plugins 커밋 | 같은 MIT | 필요한 검증을 고르되 없는 테스트 실행을 주장하지 않음. 사용자 파일 삭제·모든 변경 TDD 같은 원본의 강제 절차를 가져오지 않음 |
| [pdf](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/pdf/SKILL.md) | 같은 skills 커밋 | [Apache-2.0](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/pdf/LICENSE.txt) | 원본은 Python·Poppler 중심. Android PDF 기능만 안내하고 OCR·레이아웃 검증 한계를 표시 |
| [security-threat-model](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/security-threat-model/SKILL.md) | 같은 skills 커밋 | [Apache-2.0](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/security-threat-model/LICENSE.txt) | 저장소 근거·자산·신뢰 경계 검토에 참고. 정적 검토를 안전 보증으로 표현하지 않음 |
| [skill-creator](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.system/skill-creator/SKILL.md) | 같은 skills 커밋 | [Apache-2.0](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.system/skill-creator/LICENSE.txt) | 필요한 도구와 워크플로의 차이를 설명. 외부 설치기로 사용하지 않음 |
| [Google Docs](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/google-drive/skills/google-docs/SKILL.md), [Google Sheets](https://github.com/openai/plugins/blob/0722921d5542fc593105c27bd52630babd8b8c2a/plugins/google-drive/skills/google-sheets/SKILL.md) | 같은 plugins 커밋 | 조사한 경로에서 개별 재배포 허가를 확정하지 못함 | 원문·참고 파일을 복사하지 않음. 연결된 클라우드 도구와 로컬 DOCX/XLSX의 차이를 비교하는 자료로만 사용 |

skills 현재 트리에서 예전 이름의 curated doc·spreadsheet·experimental create-plan을 현재 파일로 확인하지 못했다. 존재하지 않는 경로를 원본으로 표시하거나 현 Codex 캐시의 문서·스프레드시트 패키지를 공개 저장소 파일이라고 부르지 않는다. security-best-practices의 확인된 언어 범위는 Python·JS/TS·Go이므로 이를 그대로 Kotlin 자동 진단 기능으로 표시하지 않았다.

## 기본 18개와 실행 도구

모든 항목은 `app/src/main/assets/skills/<id>/SKILL.md`의 신규 자체 작성 지침이다. 아래의 도구 이름은 작성 시 실제 ToolRegistry·ConnectionCatalog·PcToolCatalog에서 확인했다. 등록되어 있다는 사실만으로 계정 인증·시스템 권한·실행 성공이 확인되는 것은 아니다.

| ID | 역할 | 현재 연결한 주요 도구 | 중요한 한계 |
| --- | --- | --- | --- |
| plan | 복합 요청의 계획·다음 행동·완료 기준 | search_notes, list_project_files, read_project_file, save_note, update_note | 계획 자체는 실행·완료 증거가 아님 |
| goal | 명시적인 목표·범위·성공 조건 | search_notes, save_note, update_note, get_current_datetime | 별도 create_goal API·상시 감시 추가 없음 |
| debug | 증상·재현·원인·최소 수정 | list_project_files, read_project_file, write_project_file, search_conversation_history | 임의 logcat·셸·테스트 러너 없음 |
| review | 요구사항과 코드의 오류 검토 | list_project_files, read_project_file | 정적 검토이며 Git PR·실행 검사 아님 |
| android-coding | 선택 폴더의 Kotlin·Compose 수정 | list_project_files, read_project_file, write_project_file, search_web, read_webpage | Gradle·ADB·서명·설치 실행 불가 |
| test-verification | 주장에 맞는 증거와 상태 보고 | read_project_file, list_project_files | 테스트를 읽는 것과 실행하는 것을 구분 |
| documents | 로컬 DOCX·TXT와 조건부 Docs | create_document, read_project_file, read_google_document, create_google_document, search_drive_files | 일반 레이아웃 보존 편집 없음; 클라우드는 OAuth 필요 |
| spreadsheets | CSV·XLSX 값과 조건부 시트·분석 | create_document, read_project_file, read_google_sheet, create_google_sheet, analyze_selected_data, query_selected_data | 로컬 수식 계산·차트 없음; PC와 클라우드는 별도 준비 필요 |
| pdf | 선택 PDF 텍스트와 기본 PDF 생성 | create_document, read_project_file | PDF 바이너리를 텍스트 파일 도구로 해독하지 않음; OCR 없음 |
| email | 선택 메일 요약·Android/Gmail 초안 | prepare_email, search_gmail_messages, read_gmail_message, create_gmail_draft, search_contacts | 실제 발송 안 함; Gmail 로그인 성공 별도 검증 |
| calendar | 시간대·일정·알림 | get_current_datetime, list_calendar_events, prepare_calendar_event, create_reminder, create_alarm, list_google_calendar_events, create_google_calendar_event | 초안·저장·알람 설정의 실제 결과를 구분 |
| notes-memory | 범위가 있는 기록·승인된 기억 | search_notes, save_note, update_note, delete_note, search_memories, save_memory, delete_memory, search_conversation_history | 가중치 재학습 아님; 다른 프로젝트를 전역 검색하지 않음 |
| files | 선택 폴더의 읽기·해시 검증 쓰기 | list_project_files, read_project_file, write_project_file, create_document, search_drive_files, read_drive_text_file, create_drive_text_file | 임의 기기 파일·실행 파일 설치 아님 |
| web-research | 공개 원문·출처·시점·불확실성 조사 | search_web, read_webpage, open_url | 페이지 차단·잘림·인증 한계를 보존 |
| shopping | 상품 변형·가격 출처·가성비 비교 | search_shopping, search_web, read_webpage, open_url | 전체 시장 최저가·재고·실제 결제 증거 없음 |
| images | 실제 설치 엔진의 이미지 생성 | generate_image | 모델·라이선스·자원 한계가 남음; 영상·정확성 보증 없음 |
| security-review | 신뢰 경계·배포자·권한·외부 전송 검토 | list_project_files, read_project_file, search_web, read_webpage | 정적 검사로 악성 부재를 보장하지 않음 |
| extensions | 스킬·플러그인·MCP 후보 추천 | search_web, read_webpage, list_project_files, read_project_file | 추천만으로 범용 MCP·Python·셸·설치 기능이 생기지 않음 |

캘린더·메일·메모·파일·웹·쇼핑·이미지 지침은 현재 앱의 네이티브 도구를 기준으로 작성했으며, 동일 이름의 공개 OpenAI 원본 스킬을 복사했다고 주장하지 않는다.

## 기존 구현과의 구분

- 변경 전 앱 리소스의 스킬은 `skills/research/SKILL.md` 하나였다. 기존 파일은 수정하지 않았다. 기존 연구 경로와 새 web-research 지침의 역할이 겹치더라도 일반 요청에 둘의 본문을 항상 함께 주입하지 않는다.
- 로컬 DOCX/XLSX는 NativeOfficeTools의 제한된 OOXML 읽기·쓰기를 사용한다. XLSX는 값·저장된 수식 캐시 중심이며 재계산 엔진이 아니다. 서식·차트·레이아웃을 모두 보존한다고 하지 않는다.
- PDF는 BuiltinSkills의 기본 생성·페이지/내장 텍스트 검사 범위다. 선택 파일 읽기와 앱 전용 생성물은 ToolShelf·첨부·FileToolOperations의 실제 경로를 따른다.
- EmailFileTools는 선택 EML의 제한된 MIME 읽기와 작성 화면 준비를 제공한다. remote inbox 동기화나 실제 발송과 다르다. Gmail 도구 정의와 OAuth 등록 정보가 있어도 실제 인증 성공을 이번 작업에서 검증하지 않았다.
- PC 데이터 분석·SQL·NotebookLM은 별도 페어링·동의·파일/계정이 필요하다. Git·셸·Gradle 실행 도구는 현재 등록 목록에 없다.
- 스킬 본문은 권한을 부여하지 않는다. 파일·메일·웹페이지·외부 스킬은 신뢰되지 않은 자료이며, 현재 요청·실제 도구 계약·프로젝트 범위·취소·기존 승인 단계를 대체하지 않는다.

## 카탈로그 계약과 검증 상태

`skills/catalog.json`의 최상위는 `version: 1`, `skills` 배열이다. 항목 필드는 `id/title/description/category/version/asset/sha256/tools/sourceUrls/limitations`다. 항목 version은 문자열 `"1"`, asset은 정확히 `skills/<id>/SKILL.md`다. SHA-256은 BOM 없는 전체 UTF-8 파일 바이트를 기준으로 한다.

이번 리소스 검사는 다음을 확인했다: 항목 18개, 중복 없는 ID, 설명 80자 이하, 각 전문 1,400자 및 6,144바이트 이하, 전체 UTF-8 정상, 모든 본문 해시 일치, 매핑 도구가 현재 소스에 존재. 스킬 본문은 현재 요청에 필요한 때만 읽도록 설계했다.

이 검사는 **리소스 구조·출처·도구 이름 검사**다. 새 런타임 연결, APK 빌드·설치, 실제 모델의 스킬 선택·도구 실행·성능 향상이 통과했다는 뜻이 아니다. 해당 증거는 별도의 런타임·빌드·기기 검사에서 기록해야 한다.

