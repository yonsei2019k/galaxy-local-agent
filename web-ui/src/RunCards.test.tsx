import { act } from 'react'
import { createRoot } from 'react-dom/client'
import type { Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { SNAPSHOT_EVENT, readSnapshot } from './bridge'
import type { ConversationMessage, Snapshot } from './bridge'
import { statusCardFor } from './Conversation'

const base: Snapshot = {
  threadId: 'thread-a', branchId: 'branch-a', ready: true, draft: '', busy: false, singleConversation: true,
  status: '', modelId: 'auto', modelLabel: '자동 선택', composerEpoch: 0,
  activeRunId: null, actionEpoch: 1, messages: [], tasks: [],
  threads: [{ id: 'thread-a', title: '대화 A', selected: true }],
  models: [{ id: 'auto', label: '자동 선택' }],
}

const postMessage = vi.fn<(message: string) => void>()
let host: HTMLDivElement
let root: Root

function requests() { return postMessage.mock.calls.map(([message]) => JSON.parse(message)) }
function show(messages: ConversationMessage[], extra: Partial<Snapshot> = {}) {
  act(() => { window.dispatchEvent(new CustomEvent(SNAPSHOT_EVENT, { detail: { ...base, ...extra, messages } })) })
}
function card(selector = '.run-status-card') { return host.querySelector<HTMLElement>(selector) }

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
  vi.stubGlobal('matchMedia', vi.fn((media: string) => ({ matches: false, media, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  window.ziphapNative = { postMessage }
  act(() => { root.render(<App />) })
})

afterEach(() => {
  act(() => root.unmount())
  host.remove()
  delete window.ziphapNative
  postMessage.mockReset()
  vi.unstubAllGlobals()
})

const paused = (status: Partial<NonNullable<ConversationMessage['runStatus']>> = {}): ConversationMessage => ({
  id: 'answer-a', role: 'assistant', text: '',
  runStatus: { phase: 'paused', label: '잠깐 멈춤', reason: 'PC 연결 기다리는 중', ...status },
})

describe('run status card', () => {
  it('shows an answer without text as the grey status card: face, bold label, grey reason, no host sentence', () => {
    show([{ id: 'ask', role: 'user', text: '사이트에서 확인해 줘' }, paused()])
    const status = card()!
    expect(status).not.toBeNull()
    expect(status.querySelector('.execution-report-face .character, .execution-report-face svg, .execution-report-face img')).not.toBeNull()
    expect(status.querySelector('strong')?.textContent).toBe('잠깐 멈춤')
    expect(status.querySelector('.run-status-reason')?.textContent).toBe('PC 연결 기다리는 중')
    expect(status.querySelector('button')).toBeNull()
    expect(host.querySelector('.rich-answer')).toBeNull()
  })

  it('puts the settings path on one grey line inside the card', () => {
    show([paused({ reason: '일정 보기 허락 필요', instruction: '기능 목록 → 연결 → 휴대폰 캘린더에서 읽기 허용' })])
    const lines = card()!.querySelectorAll('.run-status-instruction')
    expect(lines).toHaveLength(1)
    expect(lines[0].textContent).toBe('기능 목록 → 연결 → 휴대폰 캘린더에서 읽기 허용')
  })

  it.each([
    ['open_pc_connection', 'PC 연결 보기', 'connections'],
    ['open_calendar_permission', '일정 허락하기', 'capabilities'],
    ['open_screen_setup', '설정 열기', 'privacy'],
  ])('maps %s to the existing navigate intent', (action, actionLabel, panel) => {
    show([paused({ action, actionLabel })])
    const pill = card()!.querySelector<HTMLButtonElement>('button.run-status-action')!
    expect(pill.textContent).toBe(actionLabel)
    act(() => { pill.click() })
    expect(requests().at(-1)).toMatchObject({ action: 'navigate', payload: { panel } })
  })

  it('maps reply and new_request to the composer focus and choose_model to the existing model menu', () => {
    show([paused({ phase: 'waiting_reply', label: '답 기다리는 중', reason: undefined, action: 'reply', actionLabel: '답하기' })])
    act(() => { card()!.querySelector<HTMLButtonElement>('button.run-status-action')!.click() })
    expect(document.activeElement?.getAttribute('aria-label')).toBe('메시지')
    const before = requests().length
    show([paused({ action: 'choose_model', actionLabel: '모델 고르기', instruction: '모델 메뉴 → 개인정보 · 로컬' })])
    act(() => { card()!.querySelector<HTMLButtonElement>('button.run-status-action')!.click() })
    expect(requests().length).toBe(before)
    expect(host.querySelector('dialog[aria-label="더하기 메뉴"]')?.hasAttribute('open')).toBe(true)
  })

  it('shows no button for an action without an existing handler', () => {
    show([paused({ reason: '사이트 로그인 필요', action: 'open_login', actionLabel: '로그인하기' })])
    expect(card()!.querySelector('button')).toBeNull()
    expect(card()!.textContent).not.toContain('로그인하기')
  })

  it('shows the report lines inside the status card once, not as a second report card', () => {
    show([{ ...paused(), executionReport: ['PC 웹 페이지 읽기 · 확인함'] }])
    expect(host.querySelectorAll('.execution-report')).toHaveLength(1)
    expect(card()!.querySelectorAll('li')).toHaveLength(1)
    expect(host.textContent?.match(/잠깐 멈춤/g)).toHaveLength(1)
  })

  it('leaves the card out when the model wrote text, and for live runs that already have a work card', () => {
    show([{ ...paused(), text: '연결되면 이어서 할게요.' }])
    expect(card()).toBeNull()
    expect(host.querySelector('.rich-answer')?.textContent).toContain('연결되면 이어서 할게요.')
    expect(statusCardFor(paused({ phase: 'running', label: '하는 중' }))).toBeUndefined()
    expect(statusCardFor(paused({ phase: 'waiting_approval', label: '허락 기다리는 중' }))).toBeUndefined()
  })

  it('rejects malformed status fields', () => {
    expect(readSnapshot({ ...base, messages: [paused({ action: 'Open Login!' })] })).toBeNull()
    expect(readSnapshot({ ...base, messages: [{ id: 'u', role: 'user', text: 'x', runStatus: { phase: 'paused', label: '잠깐 멈춤' } }] })).toBeNull()
  })
})

describe('receipt card', () => {
  const sms: ConversationMessage = {
    id: 'answer-sms', role: 'assistant', text: '',
    runStatus: { phase: 'completed', label: '끝남' },
    receipt: { complete: false, items: [{ title: '문자 준비', status: 'prepared', verified: false,
      fields: [{ label: '받는 번호', value: '010-0000-0002' }, { label: '문자 내용', value: '내일 7시에 만나요' }] }] },
  }

  it('renders exact values as labeled fields and keeps prepared distinct from verified', () => {
    show([sms])
    const receipt = card('.receipt-card')!
    expect(receipt.querySelector('.receipt-item strong')?.textContent).toBe('문자 준비')
    expect([...receipt.querySelectorAll('dt')].map(item => item.textContent)).toEqual(['받는 번호', '문자 내용'])
    expect([...receipt.querySelectorAll('dd')].map(item => item.textContent)).toEqual(['010-0000-0002', '내일 7시에 만나요'])
    expect(receipt.querySelector('.receipt-status')?.textContent).toBe('앱에 넘김 · 끝났는지는 아직 모름')
    expect(receipt.querySelector('.receipt-item--prepared')).not.toBeNull()
    expect(receipt.querySelector('.receipt-incomplete')).not.toBeNull()
    expect(card()).toBeNull()
  })

  it('marks a re-read result as verified and shows the model text beside the card', () => {
    show([{ id: 'answer-note', role: 'assistant', text: '메모 넣어 뒀어요.',
      receipt: { complete: true, items: [{ title: '메모 저장', status: 'verified', verified: true,
        fields: [{ label: '제목', value: '주차' }, { label: '내용', value: 'B2 기둥 17번\n두  칸' }] }] } }])
    const receipt = card('.receipt-card')!
    expect(receipt.querySelector('.receipt-item--verified .receipt-status')?.textContent).toBe('다시 봐서 확인함')
    expect(receipt.querySelectorAll('dd')[1].textContent).toBe('B2 기둥 17번\n두  칸')
    expect(receipt.querySelector('.receipt-incomplete')).toBeNull()
    expect(host.querySelector('.rich-answer')?.textContent).toContain('메모 넣어 뒀어요.')
  })

  it('rejects a receipt whose verified flag disagrees with its status', () => {
    const forged = { ...sms, receipt: { complete: true, items: [{ ...sms.receipt!.items[0], verified: true }] } }
    expect(readSnapshot({ ...base, messages: [forged] })).toBeNull()
  })
})
