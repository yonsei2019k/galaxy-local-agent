// @vitest-environment jsdom
import { act, createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, expect, test, vi } from 'vitest'
import type { Snapshot, Task } from '../bridge'
import { lastingSituation, situationForPhase, useCharacterMood } from './useCharacterMood'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
afterEach(() => vi.useRealTimers())

const snapshot = (extra: Partial<Snapshot>) => ({ ready: true, busy: false, messages: [], tasks: [], ...extra }) as unknown as Snapshot
const task = (phase: string): Task => ({ id: 't1', phase, label: '작업', detail: '', canCancel: false })

function Probe({ value }: { value: Snapshot }) {
  return createElement('span', null, useCharacterMood(value))
}

test('the header character reacts only to what the snapshot already shows', () => {
  vi.useFakeTimers()
  const container = document.createElement('div')
  const root = createRoot(container)
  const show = (value: Snapshot) => act(() => root.render(createElement(Probe, { value })))
  const old = { id: 'a0', role: 'assistant' as const, text: '지난 답변' }

  show(snapshot({ messages: [old] }))
  expect(container.textContent).toBe('idle') // history on mount is not news

  show(snapshot({ busy: true, messages: [old] }))
  expect(container.textContent).toBe('thinking') // busy before any tool runs: interpreting or planning

  show(snapshot({ busy: true, messages: [old], tasks: [task('running')] }))
  expect(container.textContent).toBe('working')

  show(snapshot({ messages: [old, { id: 'a1', role: 'assistant', text: '새 답변' }], tasks: [task('completed')] }))
  expect(container.textContent).toBe('done') // the running task finished
  act(() => { vi.advanceTimersByTime(1_700) })
  expect(container.textContent).toBe('idle')

  show(snapshot({ messages: [old, { id: 'a1', role: 'assistant', text: '새 답변' }, { id: 'a2', role: 'assistant', text: '또' }] }))
  expect(container.textContent).toBe('answered')
  act(() => { vi.advanceTimersByTime(1_000) })
  expect(container.textContent).toBe('idle')
  act(() => root.unmount())
})

test('each app state picks its situation', () => {
  expect(lastingSituation(null)).toBe('idle')
  expect(lastingSituation(snapshot({}))).toBe('idle')
  expect(lastingSituation(snapshot({ draft: '내일 일정' }))).toBe('listening')
  expect(lastingSituation(snapshot({ voiceTalk: { stage: 'listening' } } as Partial<Snapshot>))).toBe('listening')
  expect(lastingSituation(snapshot({ busy: true }))).toBe('thinking')
  expect(lastingSituation(snapshot({ tasks: [task('running')] }))).toBe('working')
  expect(lastingSituation(snapshot({ tasks: [task('waiting_goal')] }))).toBe('waiting')
  expect(lastingSituation(snapshot({ tasks: [task('paused')] }))).toBe('blocked')
  expect(lastingSituation(snapshot({ tasks: [task('waiting_input')] }))).toBe('confirm')
  expect(lastingSituation(snapshot({ busy: true, approvalCard: { id: 'x'.repeat(36) } }))).toBe('confirm')
  // A finished or failed task in history is not news by itself.
  expect(lastingSituation(snapshot({ tasks: [task('completed'), task('failed')] }))).toBe('idle')
  expect(['approval', 'failed', 'queued', 'waiting_goal', 'completed', 'cancelled'].map(situationForPhase))
    .toEqual(['confirm', 'blocked', 'working', 'waiting', 'done', 'idle'])
})

test('one-shot situations fire on a transition and do not repeat while the state lasts', () => {
  vi.useFakeTimers()
  const container = document.createElement('div')
  const root = createRoot(container)
  const show = (value: Snapshot) => act(() => root.render(createElement(Probe, { value })))
  const thread = (id: string) => ({ threads: [{ id, title: '', selected: true }] })

  show(snapshot(thread('c1')))
  expect(container.textContent).toBe('greeting') // an empty conversation opened
  act(() => { vi.advanceTimersByTime(1_700) })
  expect(container.textContent).toBe('idle')
  show(snapshot(thread('c1')))
  expect(container.textContent).toBe('idle') // same empty conversation: no second greeting

  show(snapshot({ ...thread('c1'), tasks: [task('running')] }))
  show(snapshot({ ...thread('c1'), tasks: [task('failed')] }))
  expect(container.textContent).toBe('blocked') // a task seen running failed
  act(() => { vi.advanceTimersByTime(2_500) })
  show(snapshot({ ...thread('c1'), tasks: [task('failed')] }))
  expect(container.textContent).toBe('idle') // the failure is not replayed

  show(snapshot({ ...thread('c1'), tasks: [task('waiting_approval')] }))
  expect(container.textContent).toBe('confirm')
  act(() => { vi.advanceTimersByTime(10_000) })
  show(snapshot({ ...thread('c1'), tasks: [task('waiting_approval')] }))
  expect(container.textContent).toBe('confirm') // lasting: the call stays until the request is answered
  act(() => root.unmount())
})
