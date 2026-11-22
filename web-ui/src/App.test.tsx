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

  it('puts shared rooms together and uses the newly saved staff title on the same room', () => {
    mount()
    const original = { id: 'thread-a', title: '과장 김호영', selected: true }
    snapshot({ ...base, threads: [original], staffRooms: [original] })
    const shared = host.querySelector('.desktop-sidebar [aria-label="공용 채팅방"]')!
    expect(Array.from(shared.querySelectorAll('button'), row => row.textContent?.trim())).toEqual(['사무실', '협력사 핫라인'])
    click('사무실')
    expect(requests().at(-1)).toMatchObject({ action: 'openOffice', threadId: 'thread-a' })
    const renamed = { ...original, title: '김호영 과장' }
    snapshot({ ...base, threads: [renamed], staffRooms: [renamed] })
    expect(host.querySelector('.topbar-title')?.textContent).toBe('김호영 과장')
    click('김호영 과장')
    expect(requests().at(-1)).toMatchObject({ action: 'selectThread', payload: { id: 'thread-a' } })
  })

  it('renders assistant output and evidence as text and copies the selected answer by native identity', () => {
    mount()
    const text = '<img src="https://invalid.example/image.png" onerror="alert(1)">\n<b>결과</b>'
    snapshot({
      ...base, messages: [{ id: 'answer-a', role: 'assistant', text }, { id: 'blank-progress', role: 'assistant', text: ' ' }],
      tasks: [{ id: 'run-a', phase: 'waiting_approval', label: '승인 검토', detail: '확인 요청', canCancel: false,
        evidence: [{ id: 'evidence-a', label: '호스트 결과', detail: text }] }],
    })
    expect(host.querySelectorAll('.message')).toHaveLength(1)
    expect(Array.from(host.querySelectorAll('.rich-answer > p'), node => node.textContent).join('\n')).toBe(text)
    expect(host.querySelector('.task-evidence p')?.textContent).toBe(text)
    expect(host.querySelector('.message img, .task-evidence img, .message b')).toBeNull()
    click('답변 복사')
    expect(requests().at(-1)).toEqual({ version: 1, threadId: 'thread-a', branchId: 'branch-a', action: 'copy', payload: { messageId: 'answer-a' } })
    expect(postIntent(base, { action: 'copy', payload: { messageId: 'answer-a' } })).toBe(false)
    expect(host.querySelector('.task-state')?.textContent).toBe('승인 필요')
    click('답변 메뉴')
    expect(requests().at(-1)).toMatchObject({ action: 'messageMenu', payload: { messageId: 'answer-a' } })
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
    expect(host.querySelector('[aria-label="일시 정지된 작업"] .task-state')?.textContent).toBe('일시 정지')
    expect(host.querySelector('.task-evidence:not([open]) summary')?.textContent).toContain('이전 작업 기록')
    expect(host.querySelector('.active-model, [aria-label="추가 지시"], [aria-label="현재 작업 중단"]')).toBeNull()
    expect(input().value).toBe('이전 질문의 답을 작성 중')
    expect(button('보내기').disabled).toBe(false)
    const approval = { ...paused, phase: 'waiting_approval' }
    snapshot({ ...staleNativeTarget, actionEpoch: 3, tasks: [approval, completed] })
    expect(host.querySelector('.task-card--review .task-state')?.textContent).toBe('승인 필요')
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
