// 합성 Snapshot 고정 자료. 개인정보 없음. bridge.ts의 Snapshot 모양을 따른다.
const base = {
  threadId: 'thread-a', branchId: 'branch-a', ready: true, draft: '', busy: false,
  status: '', modelId: 'auto', modelLabel: '자동 선택', composerEpoch: 0,
  activeRunId: null, actionEpoch: 1, messages: [], tasks: [],
  threads: [{ id: 'thread-a', title: '대화 A', selected: true }],
  models: [{ id: 'auto', label: '자동 선택' }],
  singleConversation: true,
};

const user = (id, text) => ({ id, role: 'user', text });
const assistant = (id, text, extra = {}) => ({ id, role: 'assistant', text, ...extra });
const task = (id, phase, label, detail, extra = {}) =>
  ({ id, phase, label, detail, canCancel: phase === 'running' || phase === 'queued', ...extra });
const live = (tasks, extra = {}) => ({ ...base, busy: true, activeRunId: tasks[0].id, actionEpoch: 2, tasks, ...extra });

const longParagraph = '이 문단은 한국어와 English가 섞인 긴 설명입니다. The quick brown fox jumps over the lazy dog, 그리고 서비스 상태를 확인한 다음 결과를 정리해서 다시 보고합니다. ' +
  '긴 토큰 예시 https://example.invalid/a/very/long/path/that/keeps/going/and/going/without/any/break 도 포함됩니다. '.repeat(2);
const table = '| 항목 | 값 | 비고 |\n| --- | --- | --- |\n| 처리 건수 | 128 | 정상 |\n| 실패 | 2 | 재시도 필요 |\n| Latency (ms) | 340 | p95 |';
const code = '```ts\nconst result = items.filter(item => item.ok).map(item => item.id)\nconsole.log("done", result.length, "very long line to check horizontal overflow behaviour in code blocks")\n```';

function chatLong() {
  const messages = [];
  for (let i = 1; i <= 20; i++) {
    const id = `m${i}`;
    if (i % 2) messages.push(user(id, `사용자 질문 ${i}: 지난 주 결과를 요약해 주세요.`));
    else if (i === 6) messages.push(assistant(id, longParagraph));
    else if (i === 8) messages.push(assistant(id, `표로 정리했습니다.\n\n${table}`));
    else if (i === 10) messages.push(assistant(id, `예시 코드입니다.\n\n${code}`));
    else messages.push(assistant(id, `답변 ${i}: 요청하신 내용을 확인했고 다음 단계를 안내드립니다.`));
  }
  return { ...base, messages };
}


// 혼합 긴 대화: 요청 -> 작업 카드 -> 답변을 10번 반복(30개 항목). 카드 압축 효과를 재기 위한 현실적 자료.
function mixedLong() {
  const topics = ['주간 회의록', '강의 자료', '월간 지출 내역', '여행 일정표', '독서 메모', '운동 기록', '설문 결과', '발표 개요', '재고 목록', '학습 계획'];
  const answers = [
    '정리를 마쳤습니다. 핵심 내용은 세 문단으로 묶었습니다. 필요하면 더 짧게 줄일 수 있습니다.',
    '요청하신 내용을 확인해 표로 만들었습니다. 빠진 항목은 없었습니다. 수정할 부분이 있으면 알려 주세요.',
    '문서를 읽고 요점을 추렸습니다. 날짜가 겹치는 부분은 따로 표시했습니다. 다음 단계로 초안을 만들 수 있습니다. 원하시면 바로 진행하겠습니다.',
    '결과를 정리했습니다. 합계가 맞는지 두 번 확인했습니다.',
    '자료를 모두 검토했습니다. 중복된 줄은 하나로 합쳤습니다. 남은 질문은 마지막에 모아 두었습니다.',
    '기록을 한 곳에 모았습니다. 주별 변화가 한눈에 보이도록 나누었습니다. 이어서 그래프도 만들 수 있습니다.',
    '응답을 분류해 요약했습니다. 가장 많은 의견은 첫 항목에 두었습니다.',
    '개요를 다섯 단계로 짰습니다. 각 단계에 한 줄 설명을 붙였습니다. 순서는 바꿀 수 있습니다.',
    '이 작업은 파일을 열지 못해 끝내지 못했습니다. 파일 위치를 다시 알려 주시면 이어서 하겠습니다.',
    '지금 목록을 훑어 보는 중입니다. 끝나는 대로 결과를 알려 드리겠습니다.',
  ];
  const messages = [], tasks = [];
  for (let i = 1; i <= 10; i++) {
    const phase = i <= 8 ? 'completed' : i === 9 ? 'failed' : 'running';
    const uid = `mu${i}`;
    messages.push(user(uid, `${topics[i - 1]}을 정리해 주세요.`));
    tasks.push({ ...task(`mt${i}`, phase, `${topics[i - 1]} 정리`, phase === 'running' ? '목록을 읽는 중입니다' : phase === 'failed' ? '파일을 열지 못했습니다' : '정리를 마쳤습니다'),
      messageId: uid,
      evidence: [1, 2, 3].map(k => ({ id: `me${i}-${k}`, label: ['문서 열기', '내용 요약', '결과 저장'][k - 1], detail: `합성 단계 ${k}를 처리했습니다` })) });
    messages.push(assistant(`ma${i}`, answers[i - 1]));
  }
  return { ...base, busy: true, activeRunId: 'mt10', actionEpoch: 2, messages, tasks };
}

const u = user('u1', '강의 자료를 정리해 주세요');
const anchored = t => ({ ...t, messageId: 'u1' });
const evidence = [{ id: 'e1', label: '문서 열기', detail: '합성 문서를 열었습니다' }, { id: 'e2', label: '요약 작성', detail: '세 문단으로 정리했습니다' }];

function camping() {
  const result = (kind, row) => '```service-result\n' +
    JSON.stringify({version: 1, kind, items: [{title: '시험 캠핑장', ...row}]}) + '\n```';
  const stays = '```stays\n' + JSON.stringify({
    version: 1, currency: 'KRW', checkIn: '2026-12-01', checkOut: '2026-12-02', guests: 4,
    stays: [{
      name: '시험 캠핑장', room: 'A-04', area: '테스트 지역', url: 'https://example.org/camp/a-04',
      checkedAt: '2026-11-24T20:10:00+09:00', online: 90000, onsite: 10000, total: 100000, available: true,
      driveFrom: '시청', driveMinutes: 21, bathroom: '개별 화장실', heating: '바닥난방'
    }]
  }) + '\n```';
  return {...base, messages: [
    user('camp1', '4인 가족 글램핑 자리를 비교해 줘.'),
    assistant('camp2', stays),
    assistant('camp3', result('camp_sites', {dates: '2026-12-01 ~ 2026-12-02', party: '4명', availableSites: 'A-04, A-12',
      mapUrl: 'https://example.org/map.png', mapImageUrl: 'https://example.org/map.png',
      inventoryUrl: 'https://example.org/booking', checkedAt: '2026-11-24T20:10:00+09:00'})),
    assistant('camp4', result('booking_review', {seller: '시험 예약처', room: 'A-04', party: '4명',
      dates: '2026-12-01 ~ 2026-12-02', paymentDeadline: '오늘 21:53', total: '100,000원', methodOptions: ['card', 'bank_transfer']})),
  ], tasks: [{...task('camp-run', 'paused', '글램핑 예약 준비', '휴대폰 인증 대기'), messageId: 'camp1'}]};
}


// 되묻기 선택지와 답변 속 이모티콘(스티커로 바뀌어 그려진다). 이모티콘은 이스케이프로 적는다.
function choices() {
  const cap = '\u{1F393}', cal = '\u{1F4C5}', spark = '\u2728', pin = '\u{1F4CD}', party = '\u{1F389}', heart = '\u2764\uFE0F';
  return { ...base, threads: [{ id: 'thread-a', title: `${cap} 졸업식 준비`, selected: true }], messages: [
    user('c1', `졸업식 일정 찾아서 가족한테 보낼 문자도 써 줘 ${heart}`),
    assistant('c2', `${cap} 졸업식 일정을 찾았어요 ${spark}\n\n- ${cal} **12월 20일(토) 오전 11시** 본식\n- ${pin} 대강당, 주차는 정문 쪽이 편해요\n\n문자 초안도 준비했어요 ${party} 마음에 들면 보내기 전에 한 번 확인해 주세요 ${heart}`),
  ], tasks: [{ ...task('c-ask', 'waiting_input', '졸업식 문자', '어느 날짜 기준으로 문자를 쓸까요?'), messageId: 'c1',
    choices: [{ label: `12월 20일 본식 ${cap}`, recommended: true }, { label: '12월 19일 예행연습', recommended: false }, { label: '둘 다 넣어 줘', recommended: false }] }] };
}

// 휴대폰이 그리는 승인 카드 자리(웹은 빈 자리만 그린다).
const approval = { ...base, messages: [user('ap1', '내일 오전 회의를 일정에 넣어 줘')],
  approvalCard: { id: '00000000-0000-4000-8000-000000000001', messageId: 'ap1' } };

module.exports = {
  empty: { ...base },
  chatShort: { ...base, messages: [user('m1', '안녕하세요'), assistant('m2', '안녕하세요. 무엇을 도와드릴까요?')] },
  chatLong: chatLong(),
  mixedLong: mixedLong(),
  taskRunning: live([anchored(task('r1', 'running', '자료 정리', '문서를 읽는 중입니다', { evidence }))], { messages: [u] }),
  taskPaused: { ...base, messages: [u], tasks: [anchored(task('r2', 'paused', '자료 정리', '사용자 확인을 기다립니다'))] },
  taskWaitingGoal: { ...base, messages: [u], tasks: [anchored(task('r3', 'waiting_goal', '매일 점검', '내일 오전에 다시 확인합니다', { canCancel: true }))] },
  taskFailed: { ...base, messages: [u], tasks: [anchored(task('r4', 'failed', '자료 정리', '파일을 열지 못했습니다', { evidence }))] },
  taskCompleted: { ...base, messages: [u, assistant('a1', '정리를 마쳤습니다.')], tasks: [anchored(task('r5', 'completed', '자료 정리', '완료했습니다', { evidence }))] },
  unanchoredTasks: { ...base, messages: [u], tasks: [task('x1', 'completed', '이전 작업', '완료했습니다'), task('x2', 'failed', '다른 작업', '실패했습니다'), task('x3', 'paused', '보류 작업', '일시 중지')] },
  twoLiveTasks: live([anchored(task('l1', 'running', '첫째 작업', '진행 중')), anchored(task('l2', 'queued', '둘째 작업', '대기 중'))], { messages: [u] }),
  websiteCard: live([task('w1', 'running', '웹 작업', '', { messageId: 'u1' })], {
    messages: [u],
    websiteCard: { id: 'w1', messageId: 'u1', title: '웹사이트 작업', status: '페이지를 확인하는 중입니다', steps: ['접속 중', '페이지를 확인하는 중입니다'], working: true },
  }),
  files: { ...base, messages: [user('f0', '보고서를 만들어 주세요'), assistant('f1', '보고서를 PDF로 만들었습니다.', { fileFormats: ['pdf'] })] },
  camping: camping(),
  choices: choices(),
  approval,
  guide: { ...base, showOfficeGuide: true, officeSelected: true },
};
