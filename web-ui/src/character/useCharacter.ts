import { createContext, useContext, useSyncExternalStore } from 'react'
import { DEFAULT_CHARACTER, isCharacterChoice, type CharacterChoice } from './characterData'

export const STORAGE_KEY = 'ziphap.character.v1'

function parse(raw: string | null): CharacterChoice {
  if (!raw) return DEFAULT_CHARACTER
  try {
    const value: unknown = JSON.parse(raw)
    return isCharacterChoice(value) ? value : DEFAULT_CHARACTER
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

type CharacterSetting = [CharacterChoice, (next: CharacterChoice) => void]
export const NativeCharacterSetting = createContext<CharacterSetting | null>(null)

export function useCharacter(): CharacterSetting {
  const native = useContext(NativeCharacterSetting)
  const fallback = useSyncExternalStore(subscribe, snapshot, () => DEFAULT_CHARACTER)
  return native ?? [fallback, setCharacter]
}
