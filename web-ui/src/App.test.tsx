import { act } from 'react'
import { createRoot } from 'react-dom/client'
import type { Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { SNAPSHOT_EVENT, postIntent, readSnapshot } from './bridge'
import type { CollaborationMessage, CollaborationSnapshot, Snapshot } from './bridge'

const base: Snapshot = {
  threadId: 'thread-a', branchId: 'branch-a', ready: true, draft: '', busy: false,
  status: '', modelId: 'auto', modelLabel: '자동 선택', composerEpoch: 0,
  activeRunId: null, actionEpoch: 1, messages: [], tasks: [],
  threads: [{ id: 'thread-a', title: '대화 A', selected: true }, { id: 'thread-b', title: '대화 B', selected: false }],
  models: [{ id: 'auto', label: '자동 선택' }, { id: 'local-test', label: '테스트 모델' }],
}

function running(overrides: Partial<Snapshot> = {}): Snapshot {
  return {
    ...base, busy: true, activeRunId: 'run-a', actionEpoch: 2,
    tasks: [{ id: 'run-a', phase: 'running', label: '테스트 작업', detail: '실행 상세', canCancel: true }], ...overrides,
  }
}

const postMessage = vi.fn<(message: string) => void>()

function requests() {
  return postMessage.mock.calls.map(([message]) => JSON.parse(message))
}

let host: HTMLDivElement
let root: Root

function mount(connected = true) {
  if (connected) window.ziphapNative = { postMessage }
  act(() => { root.render(<App />) })
}

function snapshot(value: unknown) {
  act(() => { window.dispatchEvent(new CustomEvent(SNAPSHOT_EVENT, { detail: value })) })
}

function button(label: string) {
  const element = [...host.querySelectorAll('button')].find(item => (item.getAttribute('aria-label') || item.textContent?.trim()) === label)
  if (!element) throw new Error(`Button missing: ${label}`)
  return element
}

function click(label: string) { act(() => { button(label).click() }) }

describe('booking payment handoff', () => {
  const payment = { id: 'host-payment', messageId: 'booking-request', seller: '예시 숙소', amount: 150_000, state: 'ready' } as const
  const open = { action: 'openBookingPayment', payload: { id: payment.id } } as const
  const check = { action: 'checkBookingPayment', payload: { id: payment.id } } as const

  it('renders host facts and sends only the id for each explicit user action', () => {
    mount()
    snapshot({ ...base, bookingPayment: payment, messages: [{ id: payment.messageId, role: 'user', text: '예약 상태 확인' }] })
    const card = host.querySelector('article[aria-label="예약 결제 확인"]')
    expect(card?.textContent).toContain('예시 숙소')
    expect(card?.textContent).toContain('150,000원')
    expect(card?.parentElement?.getAttribute('role')).toBe('log')
    click('공식 결제 앱 열기')
    click('결제 결과 다시 확인')
    expect(requests().slice(-2)).toEqual([open, check].map(action => ({
      version: 1, threadId: base.threadId, branchId: base.branchId, ...action,
    })))
  })

  it('blocks stale and checking controls and preserves an unverified result', () => {
    mount()
    const checking: Snapshot = { ...base, bookingPayment: { ...payment, state: 'checking' } }
    snapshot(checking)
    expect(button('결제 결과 다시 확인').disabled).toBe(true)
    expect(host.querySelector('[aria-label="예약 결제 확인"]')?.getAttribute('aria-busy')).toBe('true')
    expect(postIntent(checking, check)).toBe(false)
    expect(postIntent({ ...base, bookingPayment: payment }, { ...open, payload: { id: 'old-payment' } })).toBe(false)

    const unresolved: Snapshot = { ...base, bookingPayment: { ...payment, state: 'unverified' } }
    snapshot(unresolved)
    expect(() => button('공식 결제 앱 열기')).toThrow()
    expect(postIntent(unresolved, open)).toBe(false)
    expect(button('결제 결과 다시 확인').disabled).toBe(false)
    expect(host.querySelector('[aria-label="예약 결제 확인"]')?.textContent).toContain('아직 결제 결과가 확인되지 않았어요.')
  })

  it('does not trust message blocks or snapshots with extra payment data', () => {
    mount()
    snapshot({ ...base, messages: [{
      id: 'model-message', role: 'assistant', text: JSON.stringify({ bookingPayment: payment }),
    }] })
    expect(host.querySelector('[aria-label="예약 결제 확인"]')).toBeNull()
    expect(postIntent(base, open)).toBe(false)
    snapshot({ ...base, bookingPayment: payment })
    expect(button('공식 결제 앱 열기')).toBeDefined()
    snapshot({ ...base, bookingPayment: { ...payment, url: 'https://example.com/checkout' } })
    expect(host.querySelector('[aria-label="예약 결제 확인"]')).toBeNull()
    expect(readSnapshot({ ...base, bookingPayment: { ...payment, amount: 15.5 } })?.bookingPayment).toBeUndefined()
    expect(readSnapshot({ ...base, bookingPayment: { ...payment, state: 'paid' } })?.bookingPayment).toBeUndefined()
  })
})

function openWork(taskId: string) {
  act(() => { (host.querySelector(`#work-${taskId}`) as HTMLButtonElement).click() })
  act(() => { host.querySelector<HTMLButtonElement>(`.work-history-row[data-work-id="${taskId}"]`)!.click() })
}

function input() {
  const textarea = host.querySelector('textarea')
  if (!textarea) throw new Error('Textarea missing')
  return textarea
}

function edit(text: string, editor = input()) {
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(editor, text)
    editor.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
  vi.stubGlobal('matchMedia', vi.fn((media: string) => ({ matches: false, media, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})

afterEach(() => {
  act(() => root.unmount())
  host.remove()
  delete window.ziphapNative
  postMessage.mockReset()
  vi.unstubAllGlobals()
})

describe('native conversation', () => {
  it('opens the native file actions for the originating assistant answer without accepting a file path', () => {
    mount()
    snapshot({ ...base, messages: [
      { id: 'file-msg', role: 'assistant', text: '문서를 준비했어요.', fileFormats: ['pdf'] },
      { id: 'user-msg', role: 'user', text: '[[file:artifact-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.pdf]]' },
    ] })
    click('PDF 열기·저장')
    expect(requests().at(-1)).toMatchObject({ action: 'messageFiles', threadId: base.threadId, branchId: base.branchId,
      payload: { messageId: 'file-msg' } })
    expect(host.querySelectorAll('.answer-files')).toHaveLength(1)
  })

  it('shows login and observed progress inside the chat while preserving the draft', () => {
    mount()
    const active = running({ draft: '기존 초안', singleConversation: true, inlineLogin: true, browserLoginStatus: '로그인 결과를 확인 중이에요.',
      tasks: [{ id: 'run-a', phase: 'running', label: '교육 이어듣기', detail: '', canCancel: true,
        evidence: [{ id: 'observed', label: '로그인 화면 확인', detail: '' }] }] })
    snapshot(active)
    expect(host.querySelector('.main-column textarea')).toBeNull()
    expect(host.querySelector('.browser-login-status')?.textContent).toBe(active.browserLoginStatus)
    expect(host.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBeNull()
    openWork('run-a')
    expect(host.querySelector('.work-sheet .work-steps li')?.textContent).toBe('로그인 화면 확인')
    snapshot({ ...active, inlineLogin: false, tasks: [{ ...active.tasks[0], phase: 'paused' }] })
    expect(input().value).toBe('기존 초안')
    expect(host.querySelector('[role="progressbar"]')).toBeNull()
  })

  it('keeps only the main conversation with no room switcher or collaborator chat', () => {
    mount()
    snapshot({ ...base, singleConversation: true, staffRooms: base.threads })
    expect(host.querySelectorAll('[aria-label="직원 채팅방"],[aria-label="메인 채팅"],[aria-label="외부 협업"]')).toHaveLength(0)
    expect(host.textContent).not.toContain('협력사 핫라인')
    expect(host.textContent).not.toContain('대화 B')
    expect(requests().some(request => request.action === 'selectThread')).toBe(false)
  })

  it('keeps credentials and the observed status in the transcript, with the composer open', () => {
    mount()
    const active = { ...base, draft: '기존 초안', messages: [{ id: 'user', role: 'user' as const, text: '수강해줘' }],
      websiteCard: { id: 'login', messageId: 'user', title: 'KAIRB 온라인 교육', status: '', steps: [], working: false, inputHeight: 400 } }
    snapshot(active)
    const slot = host.querySelector('main [data-native-website-slot]')
    expect(slot?.previousElementSibling?.textContent).toContain('수강해줘')
    expect(input().value).toBe('기존 초안')
    snapshot({ ...active, websiteCard: { ...active.websiteCard, inputHeight: undefined, status: '수강 목록 확인' } })
    expect(host.querySelector('main [aria-label="KAIRB 온라인 교육"]')?.textContent).toContain('수강 목록 확인')
    expect(input().value).toBe('기존 초안')
  })

  it('uses the website card as the single live status and preserves details after pausing', () => {
    mount()
    const active = running({ singleConversation: true, messages: [{ id: 'u', role: 'user', text: '강의 이어듣기' }],
      websiteCard: { id: 'run-a', runId: 'run-a', messageId: 'u', title: '한림에듀', status: '강의실 확인 중', steps: [], working: true } })
    snapshot(active)
    expect(host.querySelector('.header-work')?.textContent).toContain('한림에듀')
    expect(host.querySelectorAll('main .work-card')).toHaveLength(1)
    expect(host.querySelector('main .website-work-card [role="progressbar"]')).not.toBeNull()
    snapshot({ ...active, busy: false, activeRunId: null, websiteCard: { ...active.websiteCard!, working: false, status: '화면 전환 확인 필요' } })
    expect(host.querySelector('main .website-work-card')?.textContent).toContain('화면 전환 확인 필요')
    expect(host.querySelector('main [role="progressbar"]')).toBeNull()
  })

  it('routes the single chat menu and mobile shortcuts to the existing capabilities', () => {
    mount()
    snapshot({ ...base, singleConversation: true })
    expect(Array.from(host.querySelectorAll('.desktop-sidebar .destination-nav button'), button => button.textContent))
      .toEqual(['기본 채팅', '연락처', '둘러보기', '보관함', '나의 집순이'])
    click('연락처')
    expect(requests().at(-1)).toMatchObject({ action: 'navigate', payload: { panel: 'contacts' } })
    click('대화 검색')
    expect(requests().at(-1)).toMatchObject({ action: 'navigate', payload: { panel: 'historySearch' } })
    const shortcut = host.querySelector('.bottom-nav button:last-child') as HTMLButtonElement
    act(() => { shortcut.click() })
    expect(requests().at(-1)).toMatchObject({ action: 'navigate', payload: { panel: 'settings' } })
    snapshot({ ...running(), singleConversation: true })
    expect(host.querySelector('.header-work')?.textContent).toContain('테스트 작업')
    snapshot({ ...base, singleConversation: true, inlineLogin: true })
    expect(host.querySelector('.bottom-nav')).toBeNull()
  })

  it('places a running browser task below its request and keeps the same card with its result', () => {
    mount()
    const message: Snapshot['messages'][number] = { id: 'web-request', role: 'user', text: '사이트 진도를 확인해줘.' }
    const task = { id: 'run-a', messageId: message.id, phase: 'running', label: '웹 작업', detail: '사이트 읽는 중', canCancel: true }
    snapshot({ ...running(), singleConversation: true, messages: [message], tasks: [task] })
    expect(host.querySelector('.message--user + #work-run-a [role="progressbar"]')).not.toBeNull()
    expect(host.querySelectorAll('#work-run-a')).toHaveLength(1)
    snapshot({ ...base, singleConversation: true, messages: [message, { id: 'web-result', role: 'assistant', text: '진도를 확인했어요.' }],
      tasks: [{ ...task, phase: 'completed', detail: '', canCancel: false,
        evidence: [{ id: 'web-read', label: '페이지 조회 결과 확인', detail: '' }] }] })
    expect(host.querySelector('.message--user + #work-run-a + .message--assistant')).not.toBeNull()
    expect(host.querySelector('#work-run-a [role="progressbar"]')).toBeNull()
    openWork('run-a')
    expect(host.querySelector('.work-sheet .work-steps li')?.textContent).toBe('페이지 조회 결과 확인')
  })

  it('uses microphone actions scoped to this room and leaves recognized text unsent', () => {
    mount()
    snapshot({ ...base, voiceDraftEpoch: 0 })
    edit('기존 초안')
    click('음성으로 입력')
    expect(requests().at(-1)).toMatchObject({
      action: 'startVoicePrompt', threadId: 'thread-a', branchId: 'branch-a', payload: {},
    })
    snapshot({ ...base, voicePrompt: 'permission', voiceDraftEpoch: 0 })
    expect(button('음성으로 입력').disabled).toBe(true)
    snapshot({ ...base, voicePrompt: 'listening', voiceDraftEpoch: 0 })
    expect(input().disabled).toBe(true)
    expect(button('보내기').disabled).toBe(true)
    click('말하기 완료')
    expect(requests().at(-1)?.action).toBe('finishVoicePrompt')
    snapshot({ ...base, voicePrompt: 'finishing', voiceDraftEpoch: 0 })
    expect(button('음성으로 입력').disabled).toBe(true)
    snapshot({ ...base, draft: '기존 초안 오늘 일정 알려줘', voiceDraftEpoch: 1 })
    expect(input().value).toBe('기존 초안 오늘 일정 알려줘')
    expect(input().disabled).toBe(false)
    expect(requests().some(request => request.action === 'send')).toBe(false)

    click('음성으로 입력')
    snapshot({ ...base, draft: input().value, voicePrompt: 'listening', voiceDraftEpoch: 1 })
    click('음성 입력 취소')
    expect(requests().at(-1)?.action).toBe('cancelVoicePrompt')
  })

  it('does not overwrite a newer local edit with a dictation snapshot', () => {
    mount()
    snapshot({ ...base, draft: '이전 초안', voiceDraftEpoch: 0 })
    edit('사용자가 새로 입력한 내용')
    snapshot({ ...base, draft: '이전 초안 음성 입력', voiceDraftEpoch: 1 })
    expect(input().value).toBe('사용자가 새로 입력한 내용')
    expect(readSnapshot({ ...base, voicePrompt: 'invalid', voiceDraftEpoch: 1 })).toBeNull()
  })

  it('renders the host author on office replies and keeps the default for ordinary replies', () => {
    mount()
    snapshot({ ...base, messages: [
      { id: 'office', role: 'assistant', text: '회장 이수빈: 검토 결과', authorLabel: '대리 김규민' },
      { id: 'ordinary', role: 'assistant', text: '일반 답변' },
    ] })
    const office = host.querySelector('[aria-label="대리 김규민 답변"]')
    expect(office?.querySelector('.message-label')?.textContent?.trim()).toBe('대리 김규민')
    expect(host.querySelector('[aria-label="집순이 답변"] .message-label')?.textContent?.trim()).toBe('집순이')
    expect(readSnapshot({ ...base, messages: [
      { id: 'input', role: 'user', text: '사용자 입력', authorLabel: '대리 김규민' },
    ] })).toBeNull()
  })

  it('shows two host-bound previews and clears them after switching conversations', () => {
    mount()
    host.style.width = '320px'
    const messages: Snapshot['messages'] = [{ id: 'answer', role: 'assistant', text: '확인한 결과' }]
    const jpeg = 'data:image/jpeg;base64,AA=='
    const resultPreviews: Snapshot['resultPreviews'] = [
      { messageId: 'answer', kind: 'chart', src: jpeg },
      { messageId: 'answer', kind: 'image', src: jpeg },
    ]
    snapshot({ ...base, messages, resultPreviews })
    const images = [...host.querySelectorAll<HTMLImageElement>('.answer-result img')]
    expect(images.map(image => image.alt)).toEqual(['데이터 분석 차트', '기기에서 생성한 이미지'])
    act(() => { images[0].dispatchEvent(new Event('error')) })
    expect(host.querySelector('.answer-result [role="status"]')?.textContent).toContain('표시할 수 없습니다')
    expect(readSnapshot({ ...base, messages, resultPreviews: [{ ...resultPreviews[0], src: 'https://invalid.example/image.jpg' }] })).toBeNull()
    expect(readSnapshot({ ...base, messages: [], resultPreviews })).toBeNull()
    snapshot({ ...base, threadId: 'thread-b', branchId: 'branch-b',
      threads: base.threads.map(room => ({ ...room, selected: room.id === 'thread-b' })) })
    expect(host.querySelector('.answer-result')).toBeNull()
  })

  it('binds renaming to the visible conversation and drops old drafts on a room switch', () => {
    mount()
    snapshot(base)
    click('이름 변경')
    const title = host.querySelector<HTMLInputElement>('input[aria-label="대화 이름"]')!
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(title, '제주 여행')
      title.dispatchEvent(new Event('input', { bubbles: true }))
    })
    click('저장')
    expect(requests().at(-1)).toMatchObject({
      threadId: 'thread-a', branchId: 'branch-a', action: 'renameThread', payload: { title: '제주 여행' },
    })
    click('이름 변경')
    snapshot({
      ...base, threadId: 'thread-b', branchId: 'branch-b',
      threads: base.threads.map(thread => ({ ...thread, selected: thread.id === 'thread-b' })),
    })
    expect(host.querySelector('input[aria-label="대화 이름"]')).toBeNull()
  })

  it('waits for a valid, ready native snapshot and makes the web-only view inert', () => {
    mount(false)
    snapshot(base)
    expect(host.textContent).toContain('Android 앱에서 대화를 열어 주세요.')
    expect(host.textContent).toContain('무엇을 도와드릴까요?')
    expect(input().disabled).toBe(true)
    expect(button('사무실').disabled).toBe(true)
    expect(host.querySelector('.desktop-sidebar')?.textContent).not.toContain('새 대화')
    expect(requests()).toEqual([])
  })

  it('opens an empty conversation on the room scene with the chosen 집순이 among a few others', () => {
    mount()
    snapshot(base)
    const scene = host.querySelector('.welcome .room-scene')!
    expect(scene).not.toBeNull()
    const host집순이 = scene.querySelector('.room-host svg.character')!
    expect(host집순이.getAttribute('role')).toBe('img')
    const guests = [...scene.querySelectorAll('.room-guest svg.character')]
    expect(guests.length).toBeGreaterThanOrEqual(3)
    expect(guests.length).toBeLessThanOrEqual(4)
    for (const guest of guests) expect(guest.getAttribute('aria-hidden')).toBe('true')
    expect(host.querySelector('.welcome h1')?.textContent).toBe('무엇을 도와드릴까요?')
    const chips = [...host.querySelectorAll('.room-suggestions button')].map(item => item.textContent)
    expect(chips).toEqual(['내일 오전 일정 정리해 줘', '이번 주말 서울 날씨 찾아 줘', '오늘 할 일 같이 정리해 줘'])
  })

  it('sends an empty-state suggestion right away as plain text with no permission fields', () => {
    mount()
    snapshot(base)
    const before = requests().length
    click('내일 오전 일정 정리해 줘')
    const sent = requests().slice(before)
    expect(sent).toHaveLength(1)
    expect(sent[0]).toMatchObject({ action: 'send', threadId: 'thread-a', branchId: 'branch-a', payload: { text: '내일 오전 일정 정리해 줘' } })
    // Only the sentence and the composer's own resend token travel; no permission, tool or model field.
    expect(Object.keys(sent[0].payload).sort()).toEqual(['submitToken', 'text'])
    // A held draft is never replaced or bypassed by a suggestion.
    snapshot({ ...base, draft: '쓰던 글' })
    const held = requests().length
    expect(host.querySelector('.room-suggestions button')?.hasAttribute('disabled')).toBe(true)
    expect(requests().length).toBe(held)
  })

  it('shows model-written reply choices as chips: recommended ink pill, one send, and 직접 입력 only focuses', () => {
    mount()
    const question: Snapshot = { ...base, singleConversation: true,
      messages: [{ id: 'question', role: 'assistant', text: '이 시간으로 예약할까요?' }],
      tasks: [{ id: 'question-run', phase: 'waiting_input', label: '', detail: '오후 3시가 비어 있어요.', canCancel: false,
        choices: [{ label: '이 시간으로 예약할게요', recommended: true }, { label: '다른 시간 볼래요', recommended: false }] }],
    }
    snapshot(question)
    const chips = [...host.querySelectorAll<HTMLButtonElement>('.reply-chips .reply-chip')]
    expect(chips.map(chip => chip.textContent)).toEqual(['이 시간으로 예약할게요추천', '다른 시간 볼래요', '직접 입력'])
    expect(chips[0].classList.contains('reply-chip--recommended')).toBe(true)
    expect(chips[0].getAttribute('aria-label')).toBe('이 시간으로 예약할게요, 추천')
    expect(chips[1].classList.contains('reply-chip--recommended')).toBe(false)
    // The labels are the model's own words; the screen adds no fixed yes/no buttons.
    expect(host.textContent).not.toMatch(/그래|아니야/)
    // The header 집순이 calls the owner while a choice is pending.
    expect(host.querySelector('.character-button')?.getAttribute('data-mood')).toBe('confirm')

    const before = requests().length
    act(() => { chips[2].click() })
    expect(document.activeElement).toBe(input())
    expect(requests().length).toBe(before)

    act(() => { chips[0].click() })
    act(() => { chips[0].click(); chips[1].click() })
    const sent = requests().slice(before)
    expect(sent).toHaveLength(1)
    expect(sent[0]).toMatchObject({ action: 'chooseReply', payload: { runId: 'question-run', index: 0 } })
    expect(Object.keys(sent[0].payload).sort()).toEqual(['index', 'runId'])
    expect(chips[0].getAttribute('aria-pressed')).toBe('true')
    expect(chips[0].classList.contains('is-chosen')).toBe(true)
    for (const chip of chips) expect(chip.disabled).toBe(true)
  })

  it('draws the booking payment card dark with a metal lock and unchanged wording', () => {
    mount()
    snapshot({ ...base, bookingPayment: { id: 'p1', seller: '예시 숙소', amount: 150_000, state: 'ready' } })
    const card = host.querySelector('article[aria-label="예약 결제 확인"]')!
    expect(card.querySelector('svg[data-metal="lock"]')?.getAttribute('aria-hidden')).toBe('true')
    const ids = [...card.querySelectorAll('svg[data-metal] [id]')].map(element => element.id)
    expect(ids.length).toBeGreaterThan(0)
    for (const element of card.querySelectorAll('svg[data-metal] [fill^="url(#"], svg[data-metal] [stroke^="url(#"]')) {
      const ref = (element.getAttribute('fill')?.startsWith('url(') ? element.getAttribute('fill') : element.getAttribute('stroke'))!.slice(5, -1)
      expect(ids).toContain(ref)
    }
    expect([...card.querySelectorAll('button')].map(item => item.textContent)).toEqual(['공식 결제 앱 열기', '결제 결과 다시 확인'])
  })

  it('requests hydration and rejects malformed or cross-thread native snapshots', () => {
    mount()
    expect(requests()).toEqual([{ version: 1, threadId: '', branchId: '', action: 'ready', payload: {} }])
    snapshot({ ...base, messages: [{ id: 'invalid', role: 'assistant', text: { html: 'text' } }] })
    expect(input().disabled).toBe(true)
    expect(readSnapshot({ ...base, threadId: 'thread-b' })).toBeNull()
    snapshot(base)
    expect(input().disabled).toBe(false)
  })

  it('sends all actions with the current conversation and branch after a thread switch', () => {
    mount()
    snapshot({ ...base, draft: 'A 전용 초안' })
    click('대화 B')
    expect(requests().at(-1)).toEqual({ version: 1, threadId: 'thread-a', branchId: 'branch-a', action: 'selectThread', payload: { id: 'thread-b' } })
    snapshot({ ...base, threadId: 'thread-b', branchId: 'branch-b', draft: 'B 전용 초안', threads: base.threads.map(thread => ({ ...thread, selected: thread.id === 'thread-b' })) })
    expect(input().value).toBe('B 전용 초안')
    click('설정')
    expect(requests().at(-1)).toEqual({ version: 1, threadId: 'thread-b', branchId: 'branch-b', action: 'navigate', payload: { panel: 'settings' } })
    click('더하기 메뉴')
    click('파일 첨부')
    expect(requests().at(-1)).toEqual({ version: 1, threadId: 'thread-b', branchId: 'branch-b', action: 'attach', payload: {} })
  })

  it('keeps fixed staff order and only marks the active room while omitting other rooms from the menu', () => {
    mount()
    const staffRooms = ['회장 이수빈', '과장 김호영', '대리 김규민', '대리 김서율']
      .map((title, index) => ({ id: `seat-${index}`, title, selected: false, working: index === 2 }))
    const threads = [...base.threads, ...staffRooms, { id: 'synthetic-qa', title: 'PC 원격 시험', selected: false }]
    snapshot({ ...base, threads, staffRooms })
    const menu = host.querySelector('.desktop-sidebar [aria-label="직원 채팅방"]')!
    const items = Array.from(menu.querySelectorAll('button'))
    expect(items.map(row => row.textContent?.trim())).toEqual(staffRooms.map(room => room.title))
    expect(items.map(row => Boolean(row.querySelector('[aria-label="작업 중"]')))).toEqual([false, false, true, false])
    expect(host.querySelector('.desktop-sidebar')?.textContent).not.toContain('PC 원격 시험')
    expect(host.querySelector('.desktop-sidebar')?.textContent).not.toContain('프로젝트')
    items[2].click()
    expect(requests().at(-1)).toMatchObject({ action: 'selectThread', payload: { id: 'seat-2' } })
  })

  it('puts the main chat above employees and preserves the saved employee name', () => {
    mount()
    const original = { id: 'thread-a', title: '과장 김호영', selected: true }
    snapshot({ ...base, threads: [original], staffRooms: [original] })
    const sidebar = host.querySelector('.desktop-sidebar')!
    expect(Array.from(sidebar.querySelectorAll('button'), row => row.textContent?.trim()).slice(0, 3))
      .toEqual(['사무실', '과장 김호영', '협력사 핫라인'])
    click('사무실')
    expect(requests().at(-1)).toMatchObject({ action: 'openOffice', threadId: 'thread-a' })
    const renamed = { ...original, title: '김호영 과장' }
    snapshot({ ...base, threads: [renamed], staffRooms: [renamed] })
    expect(host.querySelector('.topbar-title')?.textContent).toBe('김호영 과장')
    click('김호영 과장')
    expect(requests().at(-1)).toMatchObject({ action: 'selectThread', payload: { id: 'thread-a' } })
  })

  it('introduces 집순이, shows setup in order, and prepares an editable example without sending', () => {
    mount()
    const emptyChat = { ...base, singleConversation: true, officeSelected: true, showOfficeGuide: true, messages: [], tasks: [] }
    snapshot({ ...emptyChat, officeCalendarReadGranted: false })
    const input = host.querySelector('textarea') as HTMLTextAreaElement
    expect(input.disabled).toBe(false)
    expect(host.querySelector('h1')?.textContent).toBe('안녕하세요, 집순이예요!')
    expect(requests().map(item => item.action)).not.toContain('requestOfficeCalendar')
    click('필요한 설정 보기')
    click('앱과 컴퓨터 연결')
    expect(requests().at(-1)).toMatchObject({ action: 'navigate', payload: { panel: 'connections' } })
    click('해볼 일 고르기')
    click('오늘 일정 확인')
    expect(input.value).toBe('오늘 남은 일정을 시간순으로 알려줘.')
    expect(requests().slice(-2)).toMatchObject([
      { action: 'draft', payload: { text: input.value } },
      { action: 'requestOfficeCalendar', payload: {} },
    ])
    expect(requests().map(item => item.action)).not.toContain('send')
    expect(Array.from(host.querySelectorAll<HTMLButtonElement>('.office-guide-actions button'))
      .find(button => button.textContent === '웹에서 정보 찾기')?.disabled).toBe(true)
    click('바로 대화하기')
    expect(requests().at(-1)).toMatchObject({ action: 'dismissOfficeGuide', payload: {} })
    snapshot({ ...emptyChat, showOfficeGuide: false, officeCalendarReadGranted: true })
    expect(host.querySelector('.office-guide')).toBeNull()
    expect(readSnapshot({ ...base, officeSelected: false, showOfficeGuide: true })).toBeNull()
    expect(postIntent(base, { action: 'requestOfficeCalendar' })).toBe(false)
  })

  it('opens the attachment picker with an editable summary draft', () => {
    mount()
    snapshot({ ...base, singleConversation: true, officeSelected: true, showOfficeGuide: true, messages: [], tasks: [] })
    click('필요한 설정 보기')
    click('해볼 일 고르기')
    click('첨부한 자료 요약')
    expect(requests().slice(-2)).toMatchObject([
      { action: 'draft', payload: { text: '첨부한 자료에서 중요한 내용만 정리해줘.' } },
      { action: 'attach' },
    ])
    expect(requests().map(item => item.action)).not.toContain('send')
  })

  it('shows the one-time 집순이 introduction alongside existing conversation history', () => {
    mount()
    snapshot({ ...base, singleConversation: true, officeSelected: true, showOfficeGuide: true,
      messages: [{ id: 'prior-answer', role: 'assistant', text: 'Saved answer' }] })
    expect(host.querySelector('.transcript .messages .message')).not.toBeNull()
    expect(host.querySelector('.transcript .welcome--compact h1')?.textContent).toBe('안녕하세요, 집순이예요!')
  })

  it('renders assistant output and evidence as text and opens the answer menu by native identity', () => {
    mount()
    const text = '<img src="https://invalid.example/image.png" onerror="alert(1)">\n<b>결과</b>'
    snapshot({
      ...base, messages: [{ id: 'answer-a', role: 'assistant', text }, { id: 'blank-progress', role: 'assistant', text: ' ' }],
      tasks: [{ id: 'run-a', phase: 'waiting_approval', label: '승인 검토', detail: '확인 요청', canCancel: false,
        evidence: [{ id: 'evidence-a', label: '호스트 결과', detail: text }] }],
    })
    expect(host.querySelectorAll('.message')).toHaveLength(1)
    expect(Array.from(host.querySelectorAll('.rich-answer > p'), node => node.textContent).join('\n')).toBe(text)
    openWork('run-a')
    expect(host.querySelector('.work-evidence p')?.textContent).toBe(text)
    expect(host.querySelector('.message img, .work-evidence img, .message b')).toBeNull()
    expect(postIntent(base, { action: 'copy', payload: { messageId: 'answer-a' } })).toBe(false)
    expect(host.querySelector('.work-card .work-state')?.textContent).toBe('승인 필요')
    click('답변 메뉴')
    click('더 보기')
    expect(requests().at(-1)).toMatchObject({ action: 'messageMenu', payload: { messageId: 'answer-a' } })
  })

  it('groupsConsecutiveAssistantAnswersUnderOneLabel', () => {
    mount()
    snapshot({ ...base, messages: [
      { id: 'a1', role: 'assistant', text: '하나' }, { id: 'a2', role: 'assistant', text: '둘' }] })
    expect(host.querySelectorAll('.message--assistant .message-label')).toHaveLength(1)
    snapshot({ ...base, messages: [
      { id: 'a1', role: 'assistant', text: '하나' }, { id: 'u1', role: 'user', text: '질문' }, { id: 'a2', role: 'assistant', text: '둘' }] })
    expect(host.querySelectorAll('.message--assistant .message-label')).toHaveLength(2)
  })

  it('assistantAnswerHasNoBubbleBox', () => {
    mount()
    snapshot({ ...base, messages: [{ id: 'a1', role: 'assistant', text: '답' }] })
    expect(host.querySelector('.assistant-bubble')).toBeNull()
    expect(host.querySelector('.assistant-text')).not.toBeNull()
  })

  it('keepsSingleMenuAndRemovesCopyIcon', () => {
    mount()
    snapshot({ ...base, messages: [{ id: 'a1', role: 'assistant', text: '답' }] })
    expect(host.querySelector('[aria-label="답변 복사"]')).toBeNull()
    expect(host.querySelectorAll('.message-actions button')).toHaveLength(1)
  })

  it('copiesThroughAnswerMenuPopover', () => {
    mount()
    snapshot({ ...base, messages: [{ id: 'a1', role: 'assistant', text: '답' }, { id: 'u1', role: 'user', text: '질문' }] })
    const trigger = button('답변 메뉴')
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu')
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    click('답변 메뉴')
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(document.activeElement?.textContent).toBe('복사')
    expect(host.querySelectorAll('[role="menuitem"]')).toHaveLength(2)
    click('복사')
    expect(requests().at(-1)).toEqual({ version: 1, threadId: 'thread-a', branchId: 'branch-a', action: 'copy', payload: { messageId: 'a1' } })
    expect(host.querySelector('[role="menu"]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    click('메시지 메뉴')
    expect(requests().at(-1)).toMatchObject({ action: 'messageMenu', payload: { messageId: 'u1' } })
    expect(requests().filter(r => r.action === 'copy')).toHaveLength(1)
  })

  it('closesAnswerMenuOnEscapeAndOutsideTap', () => {
    mount()
    snapshot({ ...base, messages: [{ id: 'a1', role: 'assistant', text: '답' }] })
    click('답변 메뉴')
    act(() => { document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })) })
    expect(host.querySelector('[role="menu"]')).toBeNull()
    expect(document.activeElement).toBe(button('답변 메뉴'))
    click('답변 메뉴')
    act(() => { document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })) })
    expect(host.querySelector('[role="menu"]')).toBeNull()
  })

  it('keeps the first draft on a busy submission rejection and reuses its retry token', () => {
    mount()
    const active = running()
    snapshot(active)
    edit('원문을 유지합니다')
    expect(requests().at(-1)).toMatchObject({ action: 'draft', payload: { text: '원문을 유지합니다' } })
    click('다음에 보내기')
    const sent = requests().at(-1)
    expect(sent).toMatchObject({ action: 'send', payload: { text: '원문을 유지합니다', submitToken: expect.stringMatching(/^[0-9a-f-]{36}$/) } })
    expect(button('다음에 보내기').disabled).toBe(true)
    expect(input().value).toBe('원문을 유지합니다')

    snapshot({ ...active, draft: '원문을 유지합니다', status: '현재 요청을 대기열에 넣을 수 없습니다.', composerEpoch: 1 })
    expect(input().value).toBe('원문을 유지합니다')
    expect(button('다음에 보내기').disabled).toBe(false)
    click('다음에 보내기')
    expect(requests().at(-1)).toEqual(sent)

    snapshot({ ...active, draft: '', composerEpoch: 2 })
    expect(input().value).toBe('')
    expect(button('다음에 보내기').disabled).toBe(true)
  })

  it('ignores an intermediate stale draft echo and preserves a newer edit during submit', () => {
    mount()
    snapshot(base)
    edit('첫 지시')
    snapshot({ ...base, status: '다른 상태 이벤트' })
    expect(input().value).toBe('첫 지시')
    click('보내기')
    edit('이어서 쓴 지시')
    snapshot({ ...base, draft: '', composerEpoch: 1 })
    expect(input().value).toBe('이어서 쓴 지시')
    expect(button('보내기').disabled).toBe(false)
  })

  it('gives a new attachment-only request an independent token', () => {
    mount()
    snapshot({ ...base, attachments: [{ id: 'first-file', name: '첫 자료.txt' }] })
    click('보내기')
    const first = requests().at(-1)
    expect(first).toMatchObject({ action: 'send', payload: { text: '' } })
    snapshot({ ...base, attachments: [], composerEpoch: 1 })
    expect(button('보내기').disabled).toBe(true)
    snapshot({ ...base, attachments: [{ id: 'second-file', name: '다른 자료.txt' }], composerEpoch: 1 })
    click('보내기')
    expect(requests().at(-1).payload.submitToken).not.toBe(first.payload.submitToken)
  })

  it('removes a selected file and opens chat actions without leaving the conversation', () => {
    mount()
    snapshot({ ...base, draft: '요청 원문', attachments: [{ id: 'unused-file', name: '다른 자료.txt' }] })
    click('다른 자료.txt 빼기')
    expect(requests().at(-1)).toMatchObject({ threadId: 'thread-a', branchId: 'branch-a',
      action: 'removeAttachment', payload: { id: 'unused-file' } })
    snapshot({ ...base, draft: '요청 원문', canCancelPreparation: true, otherApprovalPending: true })
    click('준비 취소')
    expect(requests().at(-1)).toMatchObject({ action: 'cancelPreparation', payload: {} })
    click('확인이 필요한 대화 열기')
    expect(requests().at(-1)).toMatchObject({ action: 'openWaitingApproval', payload: {} })
    expect(host.querySelector('.desktop-sidebar .more-menu')).toBeNull()
    click('이 대화')
    expect(requests().at(-1)).toMatchObject({ action: 'manageThreads' })
    expect(input().value).toBe('요청 원문')
    expect(host.textContent).not.toContain('기존 화면')
  })

  it('pins stop and steer to the native execution id and action epoch', () => {
    mount()
    snapshot(running({ draft: '현재 작업 추가 지시' }))
    click('현재 작업 중단')
    expect(requests().at(-1)).toMatchObject({ action: 'stop', payload: { runId: 'run-a', actionEpoch: 2 } })
    const newer = running({ activeRunId: 'run-b', actionEpoch: 3, tasks: [{ id: 'run-b', phase: 'running', label: '다음 작업', detail: '', canCancel: true }], draft: '다음 작업 추가 지시' })
    snapshot(newer)
    expect(postIntent(newer, { action: 'stop', payload: { runId: 'run-a', actionEpoch: 2 } })).toBe(false)
    click('추가 지시')
    expect(requests().at(-1)).toMatchObject({ action: 'steer', payload: { runId: 'run-b', actionEpoch: 3, text: '다음 작업 추가 지시' } })
  })

  it('keeps long input available for queued submission and respects the steer limit', () => {
    mount()
    const draft = '나'.repeat(4_001)
    snapshot(running({ draft }))
    expect(input().value).toBe(draft)
    expect(button('추가 지시').disabled).toBe(true)
    expect(button('다음에 보내기').disabled).toBe(false)
  })

  it('keeps a read-only question next to the composer and archives its notice after the reply', () => {
    mount()
    const messages: Snapshot['messages'] = [{ id: 'question-a', role: 'assistant', text: 'Which city?' }]
    const task = { id: 'ask-a', phase: 'waiting_input', label: '답변을 기다리고 있어요', detail: '아래에 답해 주시면 이 대화에서 이어갈게요.', canCancel: false }
    snapshot({ ...base, messages, tasks: [task] })
    expect(host.querySelector('.reply-needed')?.textContent).toBe(task.detail)
    expect(host.querySelector('[aria-label="일시 정지된 작업"]')).toBeNull()
    expect(input().disabled).toBe(false)
    snapshot({ ...base, messages: [...messages, { id: 'reply-a', role: 'user', text: 'Busan' }],
      tasks: [{ ...task, phase: 'clarification_record' }] })
    expect(host.querySelector('.reply-needed')).toBeNull()
    expect(host.querySelector('.work-card .work-state')?.textContent).toBe('질문 기록')
    expect(host.querySelector('details.task-evidence')).toBeNull()
  })

  it('keeps voice controls scoped to the chat and requires user confirmation to send a transcript', () => {
    mount()
    const room: Snapshot = { ...base, voiceDraftEpoch: 1,
      messages: [{ id: 'answer-voice', role: 'assistant', text: '답변입니다.' }] }
    snapshot(room)
    click('답변 읽어주기')
    expect(requests().at(-1)).toMatchObject({ action: 'toggleAnswerSpeech', payload: { messageId: 'answer-voice' } })
    snapshot({ ...room, spokenMessageId: 'answer-voice' })
    click('답변 읽기 중지')
    click('음성 대화')
    expect(requests().at(-1)).toMatchObject({ action: 'openVoiceTalk', payload: {} })
    const review = { stage: 'review', blocked: false, transcript: '오늘 일정 알려줘', reply: '' } as const
    snapshot({ ...room, voiceTalk: review })
    expect(host.textContent).toContain(review.transcript)
    expect(requests().some(item => item.action === 'sendVoiceTalk')).toBe(false)
    click('이대로 보내기')
    expect(requests().at(-1)).toMatchObject({ action: 'sendVoiceTalk', branchId: 'branch-a', payload: {} })
    snapshot({ ...room, voiceTalk: { ...review, blocked: true } })
    expect(button('이대로 보내기').disabled).toBe(true)
    snapshot({ ...room, voiceTalk: { ...review, stage: 'speaking', transcript: '', reply: '읽는 답변' } })
    click('읽기 멈추고 말하기')
    expect(requests().at(-1)).toMatchObject({ action: 'listenVoiceTalk' })
    click('끝내기')
    expect(requests().at(-1)).toMatchObject({ action: 'closeVoiceTalk' })
  })

  it('sends one answer to the current question and preserves a composed reply', () => {
    mount()
    const question: Snapshot = { ...base,
      messages: [{ id: 'question', role: 'assistant', text: '어느 지역인가요?' }],
      tasks: [{ id: 'question-run', phase: 'waiting_input', label: '', detail: '', canCancel: false,
        choices: [{ label: '강릉', recommended: true }, { label: '삼척', recommended: false }] }],
    }
    snapshot({ ...question, draft: '직접 쓴 답' })
    expect(button('삼척').disabled).toBe(true)
    expect(input().value).toBe('직접 쓴 답')
    snapshot({ ...question, composerEpoch: 1 })
    expect(button('삼척').disabled).toBe(false)
    click('삼척')
    expect(button('삼척').disabled).toBe(true)
    expect(requests().at(-1)).toMatchObject({ action: 'chooseReply', threadId: 'thread-a', branchId: 'branch-a',
      payload: { runId: 'question-run', index: 1 } })
    const answered = { ...question, tasks: [] }
    snapshot(answered)
    expect(host.querySelector('.reply-options')).toBeNull()
    expect(postIntent(answered, { action: 'chooseReply', payload: { runId: 'question-run', index: 1 } })).toBe(false)
  })

  it('cancels a timed goal from its own chat card without stopping another run', () => {
    mount()
    snapshot({ ...base, messages: [{ id: 'work-a', role: 'user', text: 'Continue tomorrow' }],
      tasks: [{ id: 'wait-a', messageId: 'work-a', phase: 'waiting_goal', label: '예약 시각에 이어가기',
        detail: '11월 25일 09:00 이후', canCancel: true }] })
    expect(host.querySelector('.work-card .work-state')?.textContent).toBe('재개 대기')
    expect([...host.querySelectorAll('button')].some(item => item.textContent === '예약 중지')).toBe(false)
    openWork('wait-a')
    click('예약 중지')
    expect(requests().at(-1)).toMatchObject({ action: 'stopGoalWait', payload: { runId: 'wait-a' } })
  })

  it('keeps a paused clarification visible and never targets a paused or completed historical run', () => {
    mount()
    const question = '탈퇴 확인 화면에서 어느 계정을 선택할까요?'
    const paused = { id: 'run-a', phase: 'paused', label: '확인 후 이어가기', detail: '소유자 확인 대기', canCancel: true,
      evidence: [{ id: 'e-a', label: '설정 화면 확인', detail: '확인할 계정 선택 단계' }] }
    const completed = { id: 'run-b', phase: 'completed', label: '응답 종료', detail: '', canCancel: true }
    const staleNativeTarget = running({ activeModelLabel: '테스트 모델', canSteer: true, draft: '이전 질문의 답을 작성 중',
      messages: [{ id: 'question', role: 'assistant', text: question }], tasks: [paused, completed] })
    snapshot(staleNativeTarget)
    expect(host.querySelector('.rich-answer')?.textContent).toBe(question)
    expect(host.querySelector('#work-run-a .work-state')?.textContent).toBe('일시 정지')
    expect(host.querySelector('#work-run-b .work-state')?.textContent).toBe('응답 종료')
    expect(host.querySelector('details.task-evidence')).toBeNull()
    expect(host.querySelector('.active-model, [aria-label="추가 지시"], [aria-label="현재 작업 중단"]')).toBeNull()
    expect(input().value).toBe('이전 질문의 답을 작성 중')
    expect(button('보내기').disabled).toBe(false)
    const approval = { ...paused, phase: 'waiting_approval' }
    snapshot({ ...staleNativeTarget, actionEpoch: 3, tasks: [approval, completed] })
    expect(host.querySelector('.work-card--review .work-state')?.textContent).toBe('승인 필요')
    click('현재 작업 중단')
    expect(requests().at(-1)).toMatchObject({ action: 'stop', payload: { runId: 'run-a', actionEpoch: 3 } })
    expect(input().value).toBe('이전 질문의 답을 작성 중')
  })

  it('keeps new input with identical text when a coalesced send acknowledgment arrives after a pause', () => {
    mount()
    const draft = '가입한 계정을 확인해 주세요'
    snapshot({ ...base, draft })
    click('보내기')
    const first = requests().at(-1)
    edit('첫 요청 뒤의 수정')
    edit(draft)
    snapshot({ ...base, draft })
    snapshot({ ...base, ready: false, draft: '' })
    expect(input().value).toBe(draft)
    snapshot({ ...base, draft: '', composerEpoch: 1, status: '확인할 내용이 있어 일시 정지했습니다.' })
    expect(input().value).toBe(draft)
    expect(button('보내기').disabled).toBe(false)
    click('보내기')
    expect(requests().at(-1).payload.submitToken).not.toBe(first.payload.submitToken)
  })

  it('keeps the latest message reachable in a shortened pane and preserves manual reading position', () => {
    const callbacks = new Map<Element, () => void>()
    vi.stubGlobal('ResizeObserver', class {
      private target?: Element
      constructor(private readonly callback: () => void) {}
      observe(target: Element) { this.target = target; callbacks.set(target, this.callback) }
      disconnect() { if (this.target) callbacks.delete(this.target) }
    })
    mount()
    snapshot({ ...base, draft: '질문의 답을 적고 있습니다', messages: [{ id: 'long-answer', role: 'assistant', text: '길어진 답변\n'.repeat(100) }] })
    const pane = host.querySelector<HTMLElement>('main[aria-label="대화"]')
    if (!pane) throw new Error('Chat pane missing')
    let height = 510
    let offset = 0
    Object.defineProperties(pane, {
      clientHeight: { get: () => height }, scrollHeight: { get: () => 1_510 },
      scrollTop: { get: () => offset, set: (value: number) => { offset = Math.min(value, 1_510 - height) } },
    })
    act(() => { callbacks.get(pane)?.() })
    expect(pane.scrollTop).toBe(1_000)
    input().focus()
    height = 126
    act(() => { callbacks.get(pane)?.() })
    expect(pane.scrollTop).toBe(1_384)
    expect(document.activeElement).toBe(input())
    expect(input().value).toBe('질문의 답을 적고 있습니다')
    pane.scrollTop = 240
    act(() => { pane.dispatchEvent(new Event('scroll')) })
    height = 108
    act(() => { callbacks.get(pane)?.() })
    expect(pane.scrollTop).toBe(240)
    click('최근 메시지로 이동')
    expect(pane.scrollTop).toBe(1_402)
  })

  describe('header work line and composer sheet', () => {
    const scrolled: Array<{ id: string; options: unknown }> = []
    beforeEach(() => {
      scrolled.length = 0
      Object.defineProperty(Element.prototype, 'scrollIntoView', { configurable: true, writable: true,
        value(this: Element, options?: unknown) { scrolled.push({ id: this.id, options }) } })
    })
    afterEach(() => {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
      vi.useRealTimers()
    })

    const message: Snapshot['messages'][number] = { id: 'u1', role: 'user', text: '자료를 정리해줘.' }

    it('headerShowsTaskAndOpensRecentWorkList', () => {
      mount()
      snapshot({ ...running(), singleConversation: true, messages: [message],
        tasks: [{ id: 'run-a', messageId: 'u1', phase: 'running', label: '자료 정리', detail: '', canCancel: true }] })
      const line = host.querySelector<HTMLButtonElement>('.topbar .header-work')
      expect(line?.tagName).toBe('BUTTON')
      expect(line?.textContent).toBe('자료 정리 · 진행 중')
      expect(host.querySelector('.current-work')).toBeNull()
      act(() => { line?.click() })
      expect(host.querySelector('.work-history-list')?.textContent).toContain('자료 정리')
      expect(scrolled).toEqual([])
      snapshot({ ...base, singleConversation: true, messages: [message],
        tasks: [{ id: 'run-a', messageId: 'u1', phase: 'completed', label: '자료 정리', detail: '', canCancel: false }] })
      expect(host.querySelector('.header-work')?.textContent).toBe('최근 작업')
    })

    it('shows increasing elapsed time from the actual start and omits unknown times', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-11-24T12:00:30Z'))
      mount()
      snapshot({ ...running(), singleConversation: true, messages: [message], tasks: [
        { id: 'run-a', phase: 'running', label: '온라인 교육', detail: '영상 재생 중', canCancel: true,
          startedAt: new Date('2026-11-24T12:00:00Z').getTime() },
        { id: 'older', phase: 'completed', label: '이전 작업', detail: '', canCancel: false },
      ] })
      expect(host.querySelector('.header-work')?.textContent).toContain('0:30')
      expect(host.querySelector('#work-run-a .work-duration')?.textContent).toContain('0:30')
      expect(host.querySelector('#work-older .work-duration')).toBeNull()
      act(() => vi.advanceTimersByTime(1000))
      expect(host.querySelector('.header-work')?.textContent).toContain('0:31')
    })

    it('headerShowsTaskCountForTwoLiveTasks', () => {
      mount()
      snapshot({ ...running(), singleConversation: true, messages: [message], tasks: [
        { id: 'l1', messageId: 'u1', phase: 'running', label: '첫째 작업', detail: '', canCancel: true },
        { id: 'l2', messageId: 'u1', phase: 'queued', label: '둘째 작업', detail: '', canCancel: true },
        { id: 'p1', messageId: 'u1', phase: 'paused', label: '멈춘 작업', detail: '', canCancel: true },
      ] })
      const line = host.querySelector<HTMLButtonElement>('.header-work')
      expect(line?.textContent).toBe('첫째 작업 · 진행 중 · 외 1개')
      act(() => { line?.click() })
      expect([...host.querySelectorAll('.work-history-row')].map(row => row.querySelector('.work-title')?.textContent))
        .toEqual(['첫째 작업', '둘째 작업', '멈춘 작업'])
      snapshot({ ...base, singleConversation: true, messages: [message], tasks: [
        { id: 'w1', messageId: 'u1', phase: 'waiting_goal', label: '예약 작업', detail: '', canCancel: true }] })
      expect(host.querySelector('.header-work')?.textContent).toBe('예약 작업 · 재개 대기')
    })

    it('modelChoiceMovedIntoComposerSheetAndSendsSelectModel', () => {
      mount()
      snapshot(running({ singleConversation: true, activeModelLabel: '테스트 모델' }))
      expect(host.querySelector('.topbar select')).toBeNull()
      expect(host.querySelector('[aria-label="다음 답변 방식"]')).toBeNull()
      expect(host.querySelector('.topbar .active-model')).toBeNull()
      expect(host.querySelector('.composer-sheet input[type="radio"]')).toBeNull()
      click('더하기 메뉴')
      const radios = [...host.querySelectorAll<HTMLInputElement>('.composer-sheet input[type="radio"]')]
      expect(radios.map(radio => radio.closest('label')?.textContent)).toEqual(['자동 선택', '테스트 모델'])
      expect(radios.map(radio => radio.checked)).toEqual([true, false])
      expect(host.querySelector('.composer-sheet')?.textContent).toContain('실행 중: 테스트 모델')
      act(() => { radios[1].click() })
      expect(requests().at(-1)).toEqual({ version: 1, threadId: 'thread-a', branchId: 'branch-a',
        action: 'selectModel', payload: { id: 'local-test' } })
      snapshot(running({ singleConversation: true, activeModelLabel: '테스트 모델', modelId: 'local-test', modelLabel: '테스트 모델' }))
      expect([...host.querySelectorAll<HTMLInputElement>('.composer-sheet input[type="radio"]')].map(radio => radio.checked)).toEqual([false, true])
      click('파일 첨부')
      expect(requests().at(-1)).toMatchObject({ action: 'attach', payload: {} })
    })

    it('composerHidesGenericStatusButKeepsVoiceAndLengthNotes', () => {
      mount()
      snapshot({ ...base, status: '작업을 진행하고 있어요', voiceDraftEpoch: 0 })
      const status = () => host.querySelector('.composer-status')?.textContent
      expect(status()).toBe('')
      snapshot({ ...base, status: '작업을 진행하고 있어요', voiceDraftEpoch: 0, voicePrompt: 'listening' })
      expect(status()).toBe('듣고 있어요. 다 말했으면 마이크를 다시 누르세요.')
      snapshot({ ...base, status: '작업을 진행하고 있어요', voiceDraftEpoch: 0 })
      edit('가'.repeat(12_001))
      expect(status()).toBe('12,000자를 넘는 요청은 파일로 첨부해 주세요.')
      edit('짧은 글')
      expect(status()).toBe('')
      postMessage.mockImplementation(() => { throw new Error('bridge down') })
      act(() => { document.dispatchEvent(new Event('visibilitychange')) })
      expect(status()).toBe('앱에 연결하지 못했습니다. 입력을 보존하고 있습니다.')
    })

    it('composerShowsDisconnectedNoteWithoutNativeBridge', () => {
      mount(false)
      expect(host.querySelector('.composer-status')?.textContent).toBe('Android 앱에서 대화를 열어 주세요.')
    })

    it('announcesATaskEndingOnceInTheConversationLiveRegion', () => {
      mount()
      const region = () => host.querySelector('.conversation-wrap > .sr-only[role="status"]')?.textContent
      const task = (phase: string, id = 'run-a', label = '자료 정리') => ({ id, phase, label, detail: '', canCancel: false })
      snapshot({ ...base, tasks: [task('completed', 'old', '예전 작업')] })
      expect(region()).toBe('')
      snapshot({ ...running(), tasks: [task('running')] })
      expect(region()).toBe('자료 정리')
      snapshot({ ...base, tasks: [task('completed')] })
      expect(region()).toBe('자료 정리 응답 종료')
      snapshot({ ...base, tasks: [task('completed')], status: '다시 받은 같은 상태' })
      expect(region()).toBe('자료 정리 응답 종료')
      snapshot({ ...running({ activeRunId: 'run-b' }), tasks: [task('completed'), task('running', 'run-b', '다음 작업')] })
      expect(region()).toBe('다음 작업')
      snapshot({ ...base, tasks: [task('completed'), task('failed', 'run-b', '다음 작업')] })
      expect(region()).toBe('다음 작업 실패')
    })

    it('announcesAllTasksThatEndInTheSameSnapshot', () => {
      mount()
      const region = () => host.querySelector('.conversation-wrap > .sr-only[role="status"]')?.textContent
      const task = (id: string, phase: string, label: string) => ({ id, phase, label, detail: '', canCancel: false })
      snapshot({ ...running(), tasks: [task('a', 'running', '자료 정리'), task('b', 'running', '표 만들기')] })
      snapshot({ ...base, tasks: [task('a', 'completed', '자료 정리'), task('b', 'failed', '표 만들기')] })
      expect(region()).toBe('작업 2개가 끝났어요: 자료 정리 응답 종료, 표 만들기 실패')
    })

    it('announcesOnlyTasksSeenRunningAndForgetsTasksThatLeftTheList', () => {
      mount()
      const region = () => host.querySelector('.conversation-wrap > .sr-only[role="status"]')?.textContent
      const task = (id: string, phase: string, label: string) => ({ id, phase, label, detail: '', canCancel: false })
      snapshot({ ...base, tasks: [task('q', 'queued', '대기만 한 작업')] })
      snapshot({ ...base, tasks: [task('q', 'completed', '대기만 한 작업')] })
      expect(region()).toBe('')
      snapshot({ ...running(), tasks: [task('a', 'running', '사라진 작업')] })
      snapshot({ ...base, tasks: [] })
      snapshot({ ...base, tasks: [task('a', 'completed', '사라진 작업')] })
      expect(region()).toBe('')
    })

    it('showsANewStatusForSixSecondsAndRestartsOnADifferentText', () => {
      vi.useFakeTimers()
      mount()
      const status = () => host.querySelector('.composer-status')?.textContent
      const advance = (ms: number) => act(() => { vi.advanceTimersByTime(ms) })
      snapshot(base)
      expect(status()).toBe('')
      snapshot({ ...base, status: '현재 요청을 대기열에 넣을 수 없습니다.' })
      expect(status()).toBe('현재 요청을 대기열에 넣을 수 없습니다.')
      expect(host.querySelector('.composer-status')?.getAttribute('role')).toBe('status')
      advance(5_999)
      expect(status()).toBe('현재 요청을 대기열에 넣을 수 없습니다.')
      advance(1)
      expect(status()).toBe('')
      snapshot({ ...base, status: '현재 요청을 대기열에 넣을 수 없습니다.', composerEpoch: 1 })
      expect(status()).toBe('')
      snapshot({ ...base, status: '확인할 내용이 있어 일시 정지했습니다.' })
      advance(4_000)
      snapshot({ ...base, status: '다른 안내' })
      advance(4_000)
      expect(status()).toBe('다른 안내')
      advance(2_000)
      expect(status()).toBe('')
      snapshot({ ...base, status: '지워질 안내' })
      expect(status()).toBe('지워질 안내')
      snapshot({ ...base, status: '' })
      expect(status()).toBe('')
    })

    it('keepsConnectionProblemsAheadOfATransientStatus', () => {
      vi.useFakeTimers()
      mount()
      const status = () => host.querySelector('.composer-status')?.textContent
      snapshot(base)
      snapshot({ ...base, status: '잠깐 보이는 안내' })
      expect(status()).toBe('잠깐 보이는 안내')
      postMessage.mockImplementation(() => { throw new Error('bridge down') })
      act(() => { document.dispatchEvent(new Event('visibilitychange')) })
      expect(status()).toBe('앱에 연결하지 못했습니다. 입력을 보존하고 있습니다.')
      act(() => { vi.advanceTimersByTime(6_000) })
      expect(status()).toBe('앱에 연결하지 못했습니다. 입력을 보존하고 있습니다.')
    })

    it('workListPrefersActiveRunThenWebsiteThenFirst', () => {
      mount()
      const tasks = [
        { id: 'l1', messageId: 'u1', phase: 'running', label: '첫째 작업', detail: '', canCancel: true },
        { id: 'l2', messageId: 'u1', phase: 'running', label: '둘째 작업', detail: '', canCancel: true },
      ]
      const press = () => act(() => { host.querySelector<HTMLButtonElement>('.header-work')?.click() })
      const first = () => host.querySelector('.work-history-row .work-title')?.textContent
      snapshot({ ...running({ activeRunId: 'l2' }), singleConversation: true, messages: [message], tasks })
      press()
      expect(first()).toBe('둘째 작업')
      const website = { id: 'w', runId: 'wr', messageId: 'u1', title: '한림에듀', status: '확인 중', steps: [], working: true }
      snapshot({ ...running({ activeRunId: 'l2' }), singleConversation: true, messages: [message], tasks, websiteCard: website })
      expect(host.querySelector('.header-work')?.textContent).toBe('둘째 작업 · 진행 중 · 외 2개')
      press()
      expect(first()).toBe('둘째 작업')
      snapshot({ ...running({ activeRunId: null }), singleConversation: true, messages: [message], tasks, websiteCard: website })
      press()
      expect(first()).toBe('한림에듀')
      snapshot({ ...running({ activeRunId: null }), singleConversation: true, messages: [message], tasks })
      press()
      expect(first()).toBe('첫째 작업')
    })

    it('startingVoiceInputClosesTheComposerSheetForGood', () => {
      mount()
      snapshot({ ...base, voiceDraftEpoch: 0 })
      click('더하기 메뉴')
      expect(host.querySelector('.composer-sheet .sheet-body')).not.toBeNull()
      click('음성으로 입력')
      expect(requests().at(-1)).toMatchObject({ action: 'startVoicePrompt' })
      expect(host.querySelector('.composer-sheet .sheet-body')).toBeNull()
      snapshot({ ...base, voiceDraftEpoch: 0, voicePrompt: 'listening' })
      snapshot({ ...base, voiceDraftEpoch: 1 })
      expect(host.querySelector('[aria-label="더하기 메뉴"]')).not.toBeNull()
      expect(host.querySelector('.composer-sheet .sheet-body')).toBeNull()
    })

    it('composerSheetExplainsAnEmptyModelList', () => {
      mount()
      snapshot({ ...base, models: [] })
      click('더하기 메뉴')
      expect(host.querySelector('.composer-sheet')?.textContent).toContain('선택할 수 있는 답변 방식이 없어요')
      expect(host.querySelector('.composer-sheet input[type="radio"]')).toBeNull()
    })
  })
})

describe('native collaboration', () => {
  const room: CollaborationSnapshot = { bindingEpoch: 7, available: true, loading: false, status: '', cursor: 0, messages: [] }
  const message: CollaborationMessage = {
    sequence: 1, messageId: 'first', sender: 'codex', recipient: 'user', text: '<b>참고 자료</b>',
    createdAt: '2026-11-18T13:00:00Z', dataOnly: true, executionAuthority: false,
  }

  function collaboration() {
    const panel = host.querySelector<HTMLDivElement>('.collaboration')
    if (!panel) throw new Error('Collaboration panel missing')
    return panel
  }

  function editor() {
    const textarea = collaboration().querySelector('textarea')
    if (!textarea) throw new Error('Collaboration textarea missing')
    return textarea
  }

  it('keeps the normal conversation as default and opens collaboration only from navigation', () => {
    mount()
    snapshot({ ...base, draft: '일반 대화 초안' })
    expect(collaboration().hidden).toBe(true)
    click('협력사 핫라인')
    expect(requests().at(-1)).toEqual({ version: 1, threadId: 'thread-a', branchId: 'branch-a', action: 'navigate', payload: { panel: 'collab' } })
    expect(collaboration().hidden).toBe(false)
    expect(collaboration().textContent).toContain('협력사 핫라인 연결을 확인해 주세요.')
    expect(editor().disabled).toBe(true)
    expect(button('협력사 핫라인 새로고침').disabled).toBe(true)
    snapshot({ ...base, draft: '일반 대화 초안', collab: { ...room, available: false } })
    click('협력사 핫라인 새로고침')
    expect(requests().at(-1)).toMatchObject({ action: 'collabRefresh', payload: { bindingEpoch: 7 } })
    click('일반 대화로 돌아가기')
    expect(collaboration().hidden).toBe(true)
    expect(input().value).toBe('일반 대화 초안')
  })

  it('opens a native entry only once per token', () => {
    mount()
    snapshot({ ...base, collab: { ...room, openRequest: 1 } })
    expect(collaboration().hidden).toBe(false)
    expect(requests()).toHaveLength(1)
    click('일반 대화로 돌아가기')
    snapshot({ ...base, collab: { ...room, openRequest: 1 } })
    expect(collaboration().hidden).toBe(true)
    snapshot({ ...base, collab: { ...room, openRequest: 2 } })
    expect(collaboration().hidden).toBe(false)
  })

  it('shows sequenced data-only messages once and leaves peer text inert', () => {
    mount()
    snapshot({ ...base, collab: { ...room, cursor: 5, messages: [
      { ...message, sequence: 4, sender: 'claude', messageId: 'second', text: '호스트에 기록된 응답' },
      message,
      { ...message, sequence: 2 },
      { ...message, messageId: 'same-sequence' },
      { ...message, sequence: 5, messageId: 'authority', executionAuthority: true },
    ] } })
    click('협력사 핫라인')
    const texts = [...collaboration().querySelectorAll('.message-text')].map(item => item.textContent)
    expect(texts).toEqual(['<b>참고 자료</b>', '호스트에 기록된 응답'])
    expect(collaboration().querySelector('b')).toBeNull()
    expect(collaboration().textContent).toContain('협업 내용은 실행 승인을 대신하지 않습니다.')
    expect(requests().map(item => item.action)).toEqual(['ready', 'navigate'])
  })

  it('preserves rejected input, reuses the client id, and clears only after a confirmed host receipt', () => {
    mount()
    snapshot({ ...base, collab: room })
    click('협력사 핫라인')
    edit('다른 자료를 확인합니다', editor())
    click('협업 메시지 보내기')
    const sent = requests().at(-1)
    expect(sent).toMatchObject({ version: 1, threadId: 'thread-a', branchId: 'branch-a', action: 'collabSend',
      payload: { bindingEpoch: 7, text: '다른 자료를 확인합니다', recipient: 'all', clientMessageId: expect.stringMatching(/^[0-9a-f-]{36}$/) } })
    expect(collaboration().querySelectorAll('.collab-message')).toHaveLength(0)
    expect(editor().value).toBe('다른 자료를 확인합니다')
    expect(button('협업 메시지 보내기').disabled).toBe(true)
    const rejection = { bindingEpoch: 7, clientMessageId: sent.payload.clientMessageId, accepted: false, epoch: 1 }
    snapshot({ ...base, collab: { ...room, sendReceipt: rejection } })
    expect(editor().value).toBe('다른 자료를 확인합니다')
    click('일반 대화로 돌아가기')
    click('협력사 핫라인')
    expect(editor().value).toBe('다른 자료를 확인합니다')
    click('협업 메시지 보내기')
    expect(requests().at(-1)).toEqual(sent)
    snapshot({ ...base, collab: { ...room, sendReceipt: rejection } })
    expect(button('협업 메시지 보내기').disabled).toBe(true)
    snapshot({ ...base, collab: { ...room, sendReceipt: { ...rejection, accepted: true, epoch: 2 } } })
    expect(editor().value).toBe('')
    expect(collaboration().querySelectorAll('.collab-message')).toHaveLength(0)
  })

  it('preserves a pending draft across PC replacement and rejects stale actions and receipts', () => {
    mount()
    snapshot({ ...base, collab: room })
    click('협력사 핫라인')
    edit('작성 중인 협업 메시지', editor())
    click('협업 메시지 보내기')
    const old = requests().at(-1)
    const staleReceipt = { bindingEpoch: 7, clientMessageId: old.payload.clientMessageId, accepted: true, epoch: 1 }
    const replacement = { ...base, collab: { ...room, bindingEpoch: 8, sendReceipt: staleReceipt } }
    snapshot(replacement)
    expect(editor().value).toBe('작성 중인 협업 메시지')
    expect(collaboration().textContent).toContain('PC 연결이 바뀌었습니다.')
    expect(requests().filter(item => item.action === 'collabSend')).toHaveLength(1)
    expect(postIntent(replacement, { action: 'collabSend', payload: old.payload })).toBe(false)
    expect(postIntent(replacement, { action: 'collabRefresh', payload: { bindingEpoch: 7 } })).toBe(false)

    click('협업 메시지 보내기')
    const current = requests().at(-1)
    expect(current.payload.bindingEpoch).toBe(8)
    expect(current.payload.clientMessageId).not.toBe(old.payload.clientMessageId)
    snapshot({ ...replacement, collab: { ...replacement.collab, sendReceipt: { ...staleReceipt, clientMessageId: current.payload.clientMessageId } } })
    expect(editor().value).toBe('작성 중인 협업 메시지')
    expect(button('협업 메시지 보내기').disabled).toBe(true)
    snapshot({ ...replacement, collab: { ...replacement.collab, sendReceipt: { ...staleReceipt, bindingEpoch: 8, clientMessageId: current.payload.clientMessageId } } })
    expect(editor().value).toBe('')
  })
})

describe('office staff profile form', () => {
  const office = { ...base, officeSelected: true, officeProfile: { CHAIR: { birthDate: '2000-01-01', schoolGrade: '초3' } } }

  function setInput(label: string, value: string) {
    const element = host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)
    if (!element) throw new Error(`Input missing: ${label}`)
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value)
      element.dispatchEvent(new Event('input', { bubbles: true }))
    })
  }

  it('shows the button only in the Office room, prefills and posts a strict save', () => {
    mount()
    snapshot(base)
    expect(() => button('직원 정보')).toThrow()
    snapshot(office)
    click('직원 정보')
    expect(host.querySelector<HTMLInputElement>('input[aria-label="회장 이수빈 생년월일"]')?.value).toBe('2000-01-01')
    setInput('대리 김규민 학년', '중1')
    click('저장')
    expect(requests().at(-1)).toMatchObject({ action: 'saveOfficeProfile', payload: {
      entries: { CHAIR: { birthDate: '2000-01-01', schoolGrade: '초3' }, ASSOCIATE_GYUMIN: { schoolGrade: '중1' } } } })
  })

  it('refuses invalid input and unscoped or malformed snapshots and actions', () => {
    mount()
    snapshot(office)
    click('직원 정보')
    setInput('회장 이수빈 학년', '가'.repeat(21))
    click('저장')
    expect(requests().some(item => item.action === 'saveOfficeProfile')).toBe(false)
    expect(readSnapshot({ ...base, officeProfile: {} })).toBeNull()
    expect(readSnapshot({ ...office, officeProfile: { STRANGER: { schoolGrade: 'x' } } })).toBeNull()
    expect(readSnapshot({ ...office, officeProfile: { CHAIR: { schoolGrade: 'x'.repeat(21) } } })).toBeNull()
    expect(postIntent(base, { action: 'clearOfficeProfile' })).toBe(false)
    expect(postIntent(office, { action: 'saveOfficeProfile', payload: { entries: { STRANGER: {} } } })).toBe(false)
    expect(postIntent(office, { action: 'clearOfficeProfile' })).toBe(true)
  })
})

it('offers only shown payment methods and drafts preparation for the exact booking', () => {
  mount()
  const item = { title: '시험캠핑', seller: '공식 예약처', room: 'A-06', dates: '2026-12-01 ~ 2026-12-02',
    party: '4명', total: '150,000원', methodOptions: ['card', 'bank_transfer'] }
  const show = (methodOptions: string[]) => snapshot({ ...base, messages: [{
    id: 'booking-review', role: 'assistant', text: '```service-result\n' + JSON.stringify({
      version: 1, kind: 'booking_review', items: [{ ...item, methodOptions }],
    }) + '\n```',
  }] })
  show(['card', 'unknown'])
  expect(() => button('카드결제 준비')).toThrow()
  show(item.methodOptions)
  expect(() => button('네이버페이 준비')).toThrow()
  click('카드결제 준비')
  const draft = requests().find(entry => entry.action === 'draft')?.payload.text
  expect(draft).toContain('A-06')
  expect(draft).toContain('150,000원')
  expect(draft).toContain('공식 결제 화면까지만')
  expect(requests().some(entry => entry.action === 'send' || entry.action === 'steer')).toBe(false)
})

it('drafts a read-only follow-up for the exact offer without submitting a reservation action', () => {
  mount()
  const value = JSON.stringify({
    version: 1, currency: 'KRW', checkIn: '2026-12-01', checkOut: '2026-12-02', guests: 4,
    stays: [{ name: '시험 캠핑장', room: '자리 A-04', area: '시험 지역', url: 'https://example.org/camp/a-04',
      checkedAt: '2026-11-24T20:10:00+09:00', online: 90000, onsite: 0, total: 90000, available: true }],
  })
  snapshot({ ...base, messages: [{ id: 'stay-details', role: 'assistant', text: '```stays\n' + value + '\n```' }] })
  host.querySelector('details')!.open = true
  click('집순이에게 더 물어보기')
  const draft = requests().find(entry => entry.action === 'draft')?.payload.text
  expect(draft).toContain('자리 A-04')
  expect(draft).toContain('2026-12-01 ~ 2026-12-02에 4명')
  expect(draft).toContain('진행 중인 다른 예약이나 선택은 그대로')
  expect(requests().some(entry => entry.action === 'send' || entry.action === 'steer')).toBe(false)
})

it('overlays only available sites on the matching map and drafts a recheck', () => {
  mount()
  const map = 'https://example.org/camp/layout.png'
  const value = JSON.stringify({
    version: 1, kind: 'camp_sites', items: [{ title: '시험 캠핑장', mapUrl: 'https://example.org/camp',
      mapImageUrl: map, inventoryUrl: 'https://example.org/booking', checkedAt: '2026-11-24T20:10:00+09:00',
      dates: '2026-12-01 ~ 2026-12-02', party: '4명', availableSites: 'A-04, A-12',
      pins: [{ site: 'A-12', x: 10, y: 10 }] }],
  })
  snapshot({ ...base, messages: [{ id: 'camp-map', role: 'assistant', text: '```service-result\n' + value + '\n```' }] })
  const response = (imageUrl: string) => act(() => {window.dispatchEvent(new CustomEvent('ziphap:stayPhoto', {detail: {
    messageId: 'camp-map', index: 0, kind: 'map', imageUrl, src: 'data:image/jpeg;base64,/9j/',
    pins: [{ site: 'A-04', x: 40, y: 65 }, { site: 'A-09', x: 65, y: 40 }, { site: 'A-12', x: 160, y: 20 }],
  }}))})
  click('채팅에서 배치도 보기')
  expect(requests().find(entry => entry.action === 'stayPhoto')?.payload).toEqual({ messageId: 'camp-map', index: 0, kind: 'map' })
  response('https://example.org/camp/wrong.png')
  expect(host.querySelector('.camp-map-pin')).toBeNull()
  click('채팅에서 배치도 보기')
  response(map)
  expect(host.querySelectorAll('.camp-map-pin')).toHaveLength(1)
  click('배치도 확대')
  expect(host.querySelector('.camp-map-picture.is-zoomed')).not.toBeNull()
  act(() => {host.querySelector<HTMLButtonElement>('[aria-label="빈자리 A-04"]')!.click()})
  click('이 자리 다시 확인')
  const draft = requests().find(entry => entry.action === 'draft')?.payload.text
  expect(draft).toContain('자리 A-04')
  expect(draft).toContain('2026-12-01 ~ 2026-12-02, 4명')
  expect(requests().some(entry => entry.action === 'send' || entry.action === 'steer')).toBe(false)
})

it('loads a selected photo using the original row after price sorting', () => {
  mount()
  const source = {
    room: '테스트 객실', area: '테스트 지역', checkedAt: '2026-11-24T20:10:00+09:00',
    onsite: 0, available: true, cancellation: null
  }
  const stays = [
    { ...source, name: '두 번째로 보이는 숙소', url: 'https://example.org/stay/a', online: 280000, total: 280000 },
    { ...source, name: '처음 보이는 숙소', url: 'https://example.org/stay/b', online: 90000, total: 90000 }
  ]
  const value = JSON.stringify({ version: 1, currency: 'KRW', checkIn: '2026-12-01', checkOut: '2026-12-02', guests: 2, stays })
  snapshot({ ...base, messages: [{ id: 'stay-answer', role: 'assistant', text: '```stays\n' + value + '\n```' }] })
  expect(requests().some(entry => entry.action === 'stayPhoto')).toBe(false)
  const row = host.querySelector('details')!
  row.open = true
  act(() => { row.querySelector<HTMLButtonElement>('.stay-actions button')!.click() })
  expect(requests().find(entry => entry.action === 'stayPhoto')?.payload).toEqual({ messageId: 'stay-answer', index: 1 })
  act(() => { window.dispatchEvent(new CustomEvent('ziphap:stayPhoto', {
    detail: { messageId: 'stay-answer', index: 1, src: 'data:image/jpeg;base64,/9j/', representative: true }
  })) })
  expect(row.querySelector('figcaption')?.textContent).toBe('숙소 대표 사진')
  expect(row.querySelector<HTMLImageElement>('.stay-thumbnail')?.getAttribute('src')).toBe('data:image/jpeg;base64,/9j/')
  const prepare = [...row.querySelectorAll('button')].find(button => button.textContent === '예약 준비')!
  act(() => { prepare.click() })
  const draft = requests().reverse().find(entry => entry.action === 'draft')?.payload.text
  expect(draft).toContain('총액 90,000원')
  expect(draft).toContain(stays[1].url)
  expect(draft).not.toContain(stays[0].url)
  expect(requests().some(entry => entry.action === 'send')).toBe(false)
})
