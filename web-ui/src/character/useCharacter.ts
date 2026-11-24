import { useSyncExternalStore } from 'react'
import { COLORS, DEFAULT_CHARACTER, EXPR_OPTIONS, VARIANTS, type CharacterChoice } from './characterData'

export const STORAGE_KEY = 'ziphap.character.v1'

function parse(raw: string | null): CharacterChoice {
  if (!raw) return DEFAULT_CHARACTER
  try {
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) return DEFAULT_CHARACTER
    const { variant, color, expr } = value as Record<string, unknown>
    const ok = VARIANTS.some(v => v.id === variant) && COLORS.some(c => c.id === color) && EXPR_OPTIONS.some(x => x.id === expr)
    return ok ? { variant, color, expr } as CharacterChoice : DEFAULT_CHARACTER
  } catch {
    return DEFAULT_CHARACTER
  }
}

function load(): CharacterChoice {
  try {
    return parse(window.localStorage.getItem(STORAGE_KEY))
  } catch {
    return DEFAULT_CHARACTER
  }
}

let current: CharacterChoice | null = null
const listeners = new Set<() => void>()

function snapshot(): CharacterChoice {
  if (current === null) current = load()
  return current
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function setCharacter(next: CharacterChoice) {
  current = { variant: next.variant, color: next.color, expr: next.expr }
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(current))
  } catch { /* storage unavailable: keep the in-memory choice */ }
  listeners.forEach(listener => listener())
}

/** Test seam: forget the module-level choice so the next read reloads storage. */
export function resetCharacterStoreForTest() {
  current = null
}

export function useCharacter(): [CharacterChoice, (next: CharacterChoice) => void] {
  return [useSyncExternalStore(subscribe, snapshot, () => DEFAULT_CHARACTER), setCharacter]
}
