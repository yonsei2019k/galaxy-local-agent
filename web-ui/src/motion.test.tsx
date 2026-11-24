import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Character } from './character/Character'
import { CharacterPicker } from './character/CharacterPicker'
import { DEFAULT_CHARACTER } from './character/characterData'
import { ComposerSheet } from './ComposerSheet'
import { WorkCard } from './work/WorkCard'
import { WorkSheet } from './work/WorkSheet'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

let host: HTMLDivElement
let root: ReturnType<typeof createRoot>
const task = { id: 'demo', label: '자료 정리', detail: '', phase: 'running', canCancel: false }
const noop = () => {}

beforeEach(() => {
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})

afterEach(() => {
  act(() => root.unmount())
  host.remove()
  vi.unstubAllGlobals()
})

it('shows state layers without scaling up motion for small avatars', () => {
  act(() => root.render(<>
    <Character choice={DEFAULT_CHARACTER} state="work" size={64} />
    <Character choice={DEFAULT_CHARACTER} state="wait" size={32} />
  </>))
  expect(host.querySelector('.character--state-work.character--full-motion')?.getAttribute('data-animated')).toBe('true')
  expect(host.querySelector('.character--state-work [data-layer="left-hand"]')).not.toBeNull()
  expect(host.querySelector('.character--state-wait')?.classList.contains('character--full-motion')).toBe(false)
})

it('disables motion when the system requests reduced motion', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  act(() => root.render(<Character choice={DEFAULT_CHARACTER} size={80} />))
  expect(host.querySelector('.character')?.getAttribute('data-animated')).toBe('false')
})

it('pauses offscreen characters and resumes when they return', () => {
  let update: IntersectionObserverCallback
  class Observer {
    constructor(callback: IntersectionObserverCallback) { update = callback }
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('IntersectionObserver', Observer)
  act(() => root.render(<Character choice={DEFAULT_CHARACTER} size={80} />))
  const element = host.querySelector('.character')!
  const setIntersection = (isIntersecting: boolean) =>
    act(() => update([{ target: element, isIntersecting } as IntersectionObserverEntry], {} as IntersectionObserver))
  setIntersection(false)
  expect(element.getAttribute('data-paused')).toBe('true')
  setIntersection(true)
  expect(element.getAttribute('data-paused')).toBe('false')
})

it('uses one shared sheet motion and lets the character preview wiggle on demand', () => {
  act(() => root.render(<>
    <CharacterPicker open value={DEFAULT_CHARACTER} onChange={noop} onClose={noop} />
    <ComposerSheet open={false} enabled snapshot={null} onAttach={noop} onSelectModel={noop} onClose={noop} />
    <WorkSheet task={null} enabled onStopGoalWait={noop} onClose={noop} />
  </>))
  expect(host.querySelectorAll('dialog.sheet--motion')).toHaveLength(3)
  act(() => host.querySelector<HTMLButtonElement>('[aria-label="집순이 귀 흔들기"]')?.click())
  expect(host.querySelector('.character-ears--wiggle')).not.toBeNull()
})

it('animates a completed transition without celebrating a failed task or historical record', () => {
  const show = (phase: string) => act(() => root.render(<WorkCard task={{ ...task, phase }} onOpen={noop} />))
  show('completed')
  expect(host.querySelector('.work-icon--just-finished')).toBeNull()
  show('failed')
  expect(host.querySelector('.work-icon--just-finished')).toBeNull()
  show('running')
  show('completed')
  expect(host.querySelector('.work-icon--just-finished')).not.toBeNull()
})
