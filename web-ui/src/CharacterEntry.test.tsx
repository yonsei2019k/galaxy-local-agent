import { act } from 'react'
import { createRoot } from 'react-dom/client'
import type { Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { SNAPSHOT_EVENT, postIntent } from './bridge'
import type { Snapshot } from './bridge'

const base: Snapshot = {
  threadId: 'thread-a', branchId: 'branch-a', ready: true, draft: '', busy: false, singleConversation: true,
  status: '', modelId: 'auto', modelLabel: '자동 선택', composerEpoch: 0,
  activeRunId: null, actionEpoch: 1, messages: [], tasks: [],
  threads: [{ id: 'thread-a', title: '대화 A', selected: true }],
  models: [{ id: 'auto', label: '자동 선택' }],
  character: { variant: 'bear', color: 'butter', expr: 'basic' },
}

const postMessage = vi.fn<(message: string) => void>()
let host: HTMLDivElement
let root: Root
const requests = () => postMessage.mock.calls.map(([message]) => JSON.parse(message))
const show = (value: Partial<Snapshot> = {}) =>
  act(() => { window.dispatchEvent(new CustomEvent(SNAPSHOT_EVENT, { detail: { ...base, ...value } })) })
const picker = () => host.querySelector<HTMLDialogElement>('dialog[aria-label="집순이 모습 바꾸기"]')!
const shownAcks = () => requests().filter(item => item.action === 'characterPickerShown')

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

describe('character entry points', () => {
  it('marks the header 집순이 with a small pencil badge and a tooltip', () => {
    show()
    const button = host.querySelector<HTMLButtonElement>('.character-button')!
    expect(button.title).toBe('집순이 모습 바꾸기')
    expect(button.querySelector('.character-edit-badge svg')?.getAttribute('data-icon')).toBe('edit')
    act(() => { button.click() })
    expect(picker().hasAttribute('open')).toBe(true)
  })

  it('opens the same picker from the empty chat button', () => {
    show()
    const change = [...host.querySelectorAll('button')].find(item => item.textContent === '집순이 모습 바꾸기')!
    expect(change.classList.contains('welcome-character-change')).toBe(true)
    act(() => { change.click() })
    expect(picker().hasAttribute('open')).toBe(true)
    expect(host.querySelectorAll('dialog[aria-label="집순이 모습 바꾸기"]')).toHaveLength(1)
  })

  it('opens the picker once from the native flag and acknowledges it so the host clears it', () => {
    show()
    expect(picker().hasAttribute('open')).toBe(false)
    show({ openCharacterPicker: 3 })
    expect(picker().hasAttribute('open')).toBe(true)
    expect(shownAcks()).toEqual([expect.objectContaining({ action: 'characterPickerShown', payload: { epoch: 3 } })])
    show({ openCharacterPicker: 3 })
    expect(shownAcks()).toHaveLength(1)
    // Host cleared the flag: the picker stays as the owner left it, and nothing is sent again.
    show()
    expect(shownAcks()).toHaveLength(1)
    act(() => { picker().dispatchEvent(new Event('close')) })
    expect(picker().hasAttribute('open')).toBe(false)
    show({ openCharacterPicker: 4 })
    expect(picker().hasAttribute('open')).toBe(true)
    expect(shownAcks().at(-1)?.payload).toEqual({ epoch: 4 })
  })

  it('rejects an acknowledgement for another epoch', () => {
    expect(postIntent({ ...base, openCharacterPicker: 5 }, { action: 'characterPickerShown', payload: { epoch: 4 } })).toBe(false)
    expect(postIntent(base, { action: 'characterPickerShown', payload: { epoch: 5 } })).toBe(false)
  })
})
