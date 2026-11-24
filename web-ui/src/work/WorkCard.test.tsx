import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Conversation } from '../Conversation'
import type { Snapshot, Task } from '../bridge'
import { WorkCard } from './WorkCard'
import { WorkSheet } from './WorkSheet'
import { friendlyStep, taskPhase, workPresentation } from './workPresentation'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

let host: HTMLDivElement
let root: ReturnType<typeof createRoot>
beforeEach(() => {
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})
afterEach(() => {
  act(() => root.unmount())
  host.remove()
  vi.restoreAllMocks()
})

const task = (overrides: Partial<Task> = {}): Task =>
  ({ id: 't1', phase: 'running', label: '자료 정리', detail: '문서를 읽는 중입니다', canCancel: true, ...overrides })

const base: Snapshot = {
  threadId: 'thread-a', branchId: 'branch-a', ready: true, draft: '', busy: false,
  status: '', modelId: 'auto', modelLabel: '자동 선택', composerEpoch: 0,
  activeRunId: null, actionEpoch: 1, messages: [], tasks: [], singleConversation: true,
  threads: [{ id: 'thread-a', title: '대화 A', selected: true }],
  models: [{ id: 'auto', label: '자동 선택' }],
}

function renderConversation(snapshot: Snapshot, onStopGoalWait: (runId: string) => void = () => {}) {
  act(() => root.render(<Conversation snapshot={snapshot} connected enabled onCopy={() => {}} onFiles={() => {}}
    onMessageMenu={() => {}} onStopGoalWait={onStopGoalWait} onNavigate={() => {}} onDismissOfficeGuide={() => {}}
    onOfficeCalendar={() => {}} hasDraft={false} onDraftExample={() => {}} />))
}

describe('workPresentation', () => {
  it('mapsEveryKnownPhaseToTone', () => {
    const expected: Record<string, [string, string]> = {
      running: ['active', 'work'], queued: ['active', 'work'], waiting_resource: ['active', 'work'],
      waiting_goal: ['waiting', 'wait'],
      approval: ['review', 'alert'], waiting_approval: ['review', 'alert'], paused: ['review', 'alert'], waiting_input: ['review', 'alert'],
      completed: ['complete', 'done'],
      failed: ['failed', 'alert'],
      cancelled: ['neutral', 'none'], interrupted: ['neutral', 'none'], clarification_record: ['neutral', 'none'],
      surprise_phase: ['neutral', 'none'],
    }
    for (const [phase, [tone, characterState]] of Object.entries(expected)) {
      const presentation = workPresentation(task({ phase }))
      expect([phase, presentation.tone, presentation.characterState]).toEqual([phase, tone, characterState])
    }
    expect(workPresentation(task({ phase: 'waiting_goal' })).title).toBe('재개 대기')
    expect(workPresentation(task({ phase: 'waiting_approval' })).title).toBe('승인 필요')
    expect(workPresentation(task({ phase: 'surprise_phase' })).title).toBe('작업 상태')
    expect(taskPhase(task({ phase: 'paused' })).title).toBe('일시 정지')
  })

  it('friendlyStepRewritesKnownLabelsAndHidesInternal', () => {
    expect(friendlyStep('사이트 화면 확인 · 실행 결과 기록')).toBe('사이트 화면을 읽었어요')
    expect(friendlyStep('공개 웹 검색 · 실행 결과 기록')).toBe('웹에서 찾아봤어요')
    expect(friendlyStep('웹페이지 읽기 · 실행 결과 기록')).toBe('웹페이지를 읽었어요')
    expect(friendlyStep('설정 화면 확인 · 실행 결과 기록')).toBe('설정 화면 확인 · 실행 결과 기록')
    expect(friendlyStep('모델 추론')).toBe('')
    expect(friendlyStep('후속 조건 검증 기록 · 실행 결과 기록')).toBe('')
    expect(friendlyStep('답변 구간 저장 확인')).toBe('')
    expect(friendlyStep('로그인 화면 확인')).toBe('로그인 화면 확인')
  })

  it('friendlyStepNeverTurnsFailuresIntoSuccessOrHidesThem', () => {
    for (const label of ['사이트 화면 확인 실패', '웹페이지 읽기 오류 · 실행 결과 기록', '모델 추론 실패', '공개 웹 검색 시간 초과',
      '후속 조건 검증 기록 실패 · 실행 결과 기록']) {
      expect(friendlyStep(label)).toBe(label)
    }
    expect(friendlyStep('웹페이지 읽기')).toBe('웹페이지를 읽었어요')
    expect(friendlyStep('모델 추론 · 실행 결과 기록')).toBe('')
  })
})

describe('WorkCard', () => {
  it('rendersOneButtonLineWithTitleStateAndFirstDetailLine', () => {
    const onOpen = vi.fn()
    const detail = `${'가'.repeat(60)}\n두 번째 줄`
    act(() => root.render(<WorkCard task={task({ detail })} onOpen={onOpen} />))
    const card = host.querySelector('button#work-t1.work-card') as HTMLButtonElement
    expect(card).not.toBeNull()
    expect(card.textContent).toContain('자료 정리')
    expect(card.textContent).toContain('진행 중')
    expect(card.textContent).toContain('가'.repeat(40) + '…')
    expect(card.textContent).not.toContain('가'.repeat(41))
    expect(card.textContent).not.toContain('두 번째 줄')
    expect(card.querySelector('[role="progressbar"]')).not.toBeNull()
    act(() => card.click())
    expect(onOpen).toHaveBeenCalledWith('t1')
    act(() => root.render(<WorkCard task={task({ phase: 'completed' })} onOpen={onOpen} />))
    expect(host.querySelector('[role="progressbar"]')).toBeNull()
    expect(host.querySelector('.work-card--complete')).not.toBeNull()
  })
})

describe('WorkSheet', () => {
  it('renders verified timestamps in order and freezes a finished duration', () => {
    const t=task({ phase: 'completed', startedAt: 1_000, finishedAt: 62_000, evidence: [
      { id: 'verified', label: '진도 100% 확인', detail: '사이트에 기록됐어요', at: 60_000 },
      { id: 'open', label: '강의실 입장을 눌렀어요', detail: '', at: 2_000 },
    ] })
    act(() => root.render(<WorkSheet task={t} enabled onClose={() => {}} onStopGoalWait={() => {}} />))
    expect(host.querySelector('.work-state')?.textContent).toBe('응답 종료 · 1:01')
    expect([...host.querySelectorAll('.work-timeline strong')].map(row => row.textContent))
      .toEqual(['강의실 입장을 눌렀어요', '진도 100% 확인'])
    expect(host.querySelector('.work-timeline time')?.getAttribute('datetime')).toBe('1970-01-01T00:00:02.000Z')
  })

  it('sheetShowsStopOnlyWhenCancellable', () => {
    const stop = vi.fn()
    const waiting = task({ phase: 'waiting_goal', canCancel: true })
    const render = (t: Task | null, enabled = true) =>
      act(() => root.render(<WorkSheet task={t} enabled={enabled} onClose={() => {}} onStopGoalWait={stop} />))
    const stopButton = () => [...host.querySelectorAll('button')].find(item => item.textContent === '예약 중지')
    render(waiting)
    expect(stopButton()).toBeDefined()
    act(() => stopButton()!.click())
    expect(stop).toHaveBeenCalledWith('t1')
    render({ ...waiting, canCancel: false })
    expect(stopButton()).toBeUndefined()
    render(waiting, false)
    expect(stopButton()).toBeUndefined()
    render({ ...waiting, phase: 'running' })
    expect(stopButton()).toBeUndefined()
    render(null)
    expect(host.querySelector('.sheet-body')).toBeNull()
  })

  it('listsFriendlyStepsAndEvidenceWithoutInternalNoise', () => {
    const evidence = [
      { id: 'e1', label: '모델 추론', detail: '' },
      { id: 'e2', label: '사이트 화면 확인 · 실행 결과 기록', detail: '로그인 화면입니다' },
      { id: 'e3', label: '사이트 화면 확인 · 실행 결과 기록', detail: '다시 확인' },
    ]
    act(() => root.render(<WorkSheet task={task({ evidence })} enabled onClose={() => {}} onStopGoalWait={() => {}} />))
    expect([...host.querySelectorAll('.work-steps li')].map(item => item.textContent)).toEqual(['사이트 화면을 읽었어요'])
    expect([...host.querySelectorAll('.work-evidence li')].map(item => item.textContent))
      .toEqual(['사이트 화면을 읽었어요로그인 화면입니다', '사이트 화면을 읽었어요다시 확인'])
    expect(host.textContent).not.toContain('모델 추론')
    expect(host.textContent).not.toContain('실행 결과 기록')
  })
})

describe('Conversation work cards', () => {
  it('unanchoredTasksAppearAtEndAsCards', () => {
    renderConversation({
      ...base, messages: [{ id: 'u1', role: 'user', text: '안녕' }],
      tasks: [task({ id: 'x1', phase: 'completed', label: '이전 작업', evidence: [{ id: 'e', label: '사이트 화면 확인', detail: '' }] }),
        task({ id: 'x2', phase: 'failed', label: '다른 작업' })],
    })
    const cards = [...host.querySelectorAll('.transcript .work-card')]
    expect(cards.map(card => card.id)).toEqual(['work-x1', 'work-x2'])
    expect(host.querySelectorAll('details.task-evidence')).toHaveLength(0)
    expect(host.querySelector('[aria-label="일시 정지된 작업"], [aria-label="현재 작업 상태"]')).toBeNull()
    expect(host.querySelectorAll('.messages + .work-cards > .work-card')).toHaveLength(2)
  })

  it('cardOpensSheetWithSteps', () => {
    const evidence = [
      { id: 'e1', label: '공개 웹 검색 · 실행 결과 기록', detail: '' },
      { id: 'e2', label: '웹페이지 읽기 · 실행 결과 기록', detail: '' },
      { id: 'e3', label: '모델 추론', detail: '' },
    ]
    renderConversation({ ...base, messages: [{ id: 'u1', role: 'user', text: '찾아줘' }],
      tasks: [task({ id: 'r1', messageId: 'u1', phase: 'completed', canCancel: false, evidence })] })
    expect(host.querySelector('.sheet-body')).toBeNull()
    const card = host.querySelector('.message--user + #work-r1.work-card') as HTMLButtonElement
    expect(card).not.toBeNull()
    act(() => card.click())
    expect(host.querySelector('.work-history-list')).not.toBeNull()
    act(() => host.querySelector<HTMLButtonElement>('.work-history-row')!.click())
    expect([...host.querySelectorAll('.work-sheet .work-steps li')].map(item => item.textContent))
      .toEqual(['웹에서 찾아봤어요', '웹페이지를 읽었어요'])
    expect(host.querySelector('.work-sheet')?.hasAttribute('open')).toBe(true)
    act(() => (host.querySelector('.work-sheet .sheet-close') as HTMLButtonElement).click())
    expect(host.querySelector('.work-sheet .sheet-body')).toBeNull()
  })

  it('websiteCardShowsItsStepsInTheSheetAndKeepsTheNativeSlot', () => {
    const website = { id: 'w1', runId: 'w1', messageId: 'u1', title: '한림에듀', status: '강의실 확인 중',
      steps: ['접속 중', '웹페이지 읽기 오류', '강의실 확인 중'], working: true }
    const stop = vi.fn()
    renderConversation({ ...base, messages: [{ id: 'u1', role: 'user', text: '이어듣기' }],
      tasks: [task({ id: 'w1', messageId: 'u1', phase: 'waiting_goal', canCancel: true })], websiteCard: website }, stop)
    expect(host.querySelectorAll('.work-card')).toHaveLength(1)
    const card = host.querySelector('#website-work .work-card') as HTMLButtonElement
    expect(card.textContent).toContain('한림에듀')
    expect(card.textContent).toContain('재개 대기')
    act(() => card.click())
    act(() => host.querySelector<HTMLButtonElement>('.work-history-row')!.click())
    expect([...host.querySelectorAll('.work-sheet .work-steps li')].map(item => item.textContent))
      .toEqual(['접속 중', '웹페이지 읽기 오류', '강의실 확인 중'])
    act(() => ([...host.querySelectorAll('.work-sheet button')].find(item => item.textContent === '예약 중지') as HTMLButtonElement).click())
    expect(stop).toHaveBeenCalledWith('w1')
    renderConversation({ ...base, messages: [{ id: 'u1', role: 'user', text: '이어듣기' }],
      websiteCard: { ...website, inputHeight: 320 } })
    expect(host.querySelector('[data-native-website-slot="w1"]')?.getAttribute('style')).toContain('320px')
    expect(host.querySelector('.work-card')).toBeNull()
  })

  it('websiteCardUsesReviewToneAndAnnouncesStatus', () => {
    const website = { id: 'w2', runId: 'w2', messageId: 'u1', title: '한림에듀', status: '확인이 필요해요', steps: [], working: false }
    renderConversation({ ...base, messages: [{ id: 'u1', role: 'user', text: '이어듣기' }],
      tasks: [task({ id: 'w2', messageId: 'u1', phase: 'paused', canCancel: true })], websiteCard: website })
    expect(host.querySelector('#website-work .work-card--review')).not.toBeNull()
    const live = host.querySelector('.conversation-wrap > [role="status"]')
    expect(live?.getAttribute('aria-live')).toBe('polite')
    expect(live?.textContent).toContain('확인이 필요해요')
  })

  it('announcesTheActiveTaskDetail', () => {
    renderConversation({ ...base, busy: true, activeRunId: 'r1', messages: [{ id: 'u1', role: 'user', text: '해줘' }],
      tasks: [task({ id: 'r1', messageId: 'u1', detail: '문서를 읽는 중입니다' })] })
    expect(host.querySelector('.conversation-wrap > [role="status"]')?.textContent).toContain('문서를 읽는 중입니다')
  })

  it('showsOnlyTheMostRecentClarificationRecord', () => {
    renderConversation({ ...base, messages: [{ id: 'u1', role: 'user', text: '질문' }],
      tasks: [task({ id: 'q1', phase: 'clarification_record', label: '옛 질문', canCancel: false }),
        task({ id: 'q2', phase: 'clarification_record', label: '새 질문', canCancel: false })] })
    expect([...host.querySelectorAll('.work-card')].map(card => card.id)).toEqual(['work-q2'])
  })

  it('closesTheSheetAndDropsItsButtonsWhenTheTaskDisappears', () => {
    const waiting = task({ id: 'g1', messageId: 'u1', phase: 'waiting_goal', canCancel: true })
    const withTask = { ...base, messages: [{ id: 'u1', role: 'user' as const, text: '내일' }], tasks: [waiting] }
    renderConversation(withTask)
    act(() => (host.querySelector('#work-g1') as HTMLButtonElement).click())
    act(() => host.querySelector<HTMLButtonElement>('.work-history-row')!.click())
    expect(host.querySelector('.work-sheet')?.hasAttribute('open')).toBe(true)
    expect([...host.querySelectorAll('.work-sheet button')].some(item => item.textContent === '예약 중지')).toBe(true)
    renderConversation({ ...withTask, tasks: [] })
    expect(host.querySelector('.work-history-list')?.textContent).toContain('이 대화에 표시할 작업이 없어요')
    expect([...host.querySelectorAll('.work-sheet button')].some(item => item.textContent === '예약 중지')).toBe(false)
    renderConversation(withTask)
    expect(host.querySelector('.work-history-list')).not.toBeNull()
  })

  it('assistantAnswerAfterACardedRequestStartsANewGroup', () => {
    renderConversation({ ...base, messages: [
      { id: 'u1', role: 'user', text: '요청' }, { id: 'a1', role: 'assistant', text: '첫 답' },
      { id: 'a2', role: 'assistant', text: '이어지는 답' },
      { id: 'u2', role: 'user', text: '다음 요청' }, { id: 'a3', role: 'assistant', text: '다음 답' },
    ], tasks: [task({ id: 'r2', messageId: 'u2', phase: 'completed', canCancel: false })] })
    const articles = [...host.querySelectorAll('.message--assistant')]
    expect(articles.map(item => item.querySelector('.message-label') !== null)).toEqual([true, false, true])
    expect(host.querySelector('.message--user + #work-r2 + .message--assistant .message-label')).not.toBeNull()
  })
})
