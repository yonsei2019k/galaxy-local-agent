import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Character } from './Character'
import { CharacterPicker } from './CharacterPicker'
import { DEFAULT_CHARACTER, type CharacterChoice } from './characterData'
import { STORAGE_KEY, useCharacter, resetCharacterStoreForTest } from './useCharacter'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

let host: HTMLDivElement
let root: ReturnType<typeof createRoot>
beforeEach(() => {
  localStorage.clear()
  resetCharacterStoreForTest()
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})
afterEach(() => {
  act(() => root.unmount())
  host.remove()
  vi.restoreAllMocks()
})

function Probe({ log }: { log: { value?: CharacterChoice; set?: (c: CharacterChoice) => void } }) {
  const [choice, setChoice] = useCharacter()
  log.value = choice
  log.set = setChoice
  return null
}

describe('Character', () => {
  it('rendersEachVariantEarsAndDefaults', () => {
    const layers = ['ears-bear', 'ears-cat', 'ears-bunny']
    for (const variant of ['bear', 'cat', 'bunny'] as const) {
      act(() => root.render(<Character choice={{ ...DEFAULT_CHARACTER, variant }} size={80} />))
      for (const layer of layers) {
        const count = host.querySelectorAll(`[data-layer="${layer}"]`).length
        expect(count).toBe(layer === `ears-${variant}` ? 1 : 0)
      }
    }
    expect(DEFAULT_CHARACTER).toEqual({ variant: 'bear', color: 'butter', expr: 'basic' })
    act(() => root.render(<Character choice={DEFAULT_CHARACTER} size={80} />))
    expect(host.querySelector('[data-layer="ears-bear"]')).not.toBeNull()
    expect(host.querySelector('[role="img"]')?.getAttribute('aria-label')).toBe('집순이')
    act(() => root.render(<Character choice={DEFAULT_CHARACTER} size={24} decorative />))
    expect(host.querySelector('[role="img"]')).toBeNull()
    expect(host.querySelector('.character')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('stateOverridesExpression', () => {
    act(() => root.render(<Character choice={DEFAULT_CHARACTER} state="wait" size={80} />))
    expect(host.querySelector('[data-expr="sleepy"]')).not.toBeNull()
    expect(host.querySelector('[data-layer="prop-wait"]')).not.toBeNull()
    expect(host.querySelector('[data-layer="zz"]')).not.toBeNull()
    expect(host.querySelector('.character--state-wait')).not.toBeNull()
  })

  it('storageFailureFallsBackToDefault', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    const log: { value?: CharacterChoice; set?: (c: CharacterChoice) => void } = {}
    act(() => root.render(<Probe log={log} />))
    expect(log.value).toEqual(DEFAULT_CHARACTER)
    act(() => log.set!({ variant: 'cat', color: 'sky', expr: 'smile' }))
    expect(log.value?.variant).toBe('cat')
  })

  it('rejectsUnknownStoredValues', () => {
    localStorage.setItem(STORAGE_KEY, '{"variant":"dog","color":"butter","expr":"basic"}')
    const log: { value?: CharacterChoice } = {}
    act(() => root.render(<Probe log={log} />))
    expect(log.value).toEqual(DEFAULT_CHARACTER)
  })

  it('pickerChangesAndPersists', () => {
    const seen: CharacterChoice[] = []
    function Harness() {
      const [choice, setChoice] = useCharacter()
      return <CharacterPicker open value={choice} onChange={next => { seen.push(next); setChoice(next) }} onClose={() => {}} />
    }
    act(() => root.render(<Harness />))
    const press = (name: string) => {
      const button = [...host.querySelectorAll('button')].find(b => b.textContent?.includes(name))
      expect(button, name).toBeTruthy()
      act(() => button!.click())
    }
    press('고양이')
    press('하늘')
    expect(seen[0]).toMatchObject({ variant: 'cat' })
    expect(seen[1]).toEqual({ variant: 'cat', color: 'sky', expr: 'basic' })
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({ variant: 'cat', color: 'sky', expr: 'basic' })
    const pressed = [...host.querySelectorAll('button[aria-pressed="true"]')].map(b => b.textContent)
    expect(pressed.join('|')).toContain('고양이')
    expect(pressed.join('|')).toContain('하늘')
  })

  it('shares one choice across hooks', () => {
    const a: { value?: CharacterChoice; set?: (c: CharacterChoice) => void } = {}
    const b: { value?: CharacterChoice } = {}
    act(() => root.render(<><Probe log={a} /><Probe log={b} /></>))
    act(() => a.set!({ variant: 'bunny', color: 'cocoa', expr: 'wink' }))
    expect(b.value).toEqual({ variant: 'bunny', color: 'cocoa', expr: 'wink' })
  })
})
