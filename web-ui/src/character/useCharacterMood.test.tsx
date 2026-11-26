// @vitest-environment jsdom
import { act, createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, expect, test, vi } from 'vitest'
import type { Snapshot, Task } from '../bridge'
import { useCharacterMood } from './useCharacterMood'

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

  show(snapshot({ busy: true, messages: [old], tasks: [task('running')] }))
  expect(container.textContent).toBe('thinking')

  show(snapshot({ messages: [old, { id: 'a1', role: 'assistant', text: '새 답변' }], tasks: [task('completed')] }))
  expect(container.textContent).toBe('celebrate') // the running task finished
  act(() => { vi.advanceTimersByTime(1_700) })
  expect(container.textContent).toBe('idle')

  show(snapshot({ messages: [old, { id: 'a1', role: 'assistant', text: '새 답변' }, { id: 'a2', role: 'assistant', text: '또' }] }))
  expect(container.textContent).toBe('answered')
  act(() => { vi.advanceTimersByTime(1_000) })
  expect(container.textContent).toBe('idle')
  act(() => root.unmount())
})
