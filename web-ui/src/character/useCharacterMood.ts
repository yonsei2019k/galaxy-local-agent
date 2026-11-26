import { useEffect, useRef, useState } from 'react'
import type { Snapshot } from '../bridge'

/**
 * How the header 집순이 reacts, derived only from what the snapshot already shows (no new behavior):
 * - thinking: a run is in progress (the app is busy or a task is running)
 * - answered: a new assistant answer just arrived (one short bounce)
 * - celebrate: a task that was seen running just completed (a small sparkle burst)
 * Motion itself is CSS and is switched off by prefers-reduced-motion; the mood attribute stays for styling.
 */
export type CharacterMood = 'idle' | 'thinking' | 'answered' | 'celebrate'

const ANSWER_MS = 900
const CELEBRATE_MS = 1_600

export function useCharacterMood(snapshot: Snapshot | null): CharacterMood {
  const ready = snapshot?.ready === true
  const thinking = ready && (snapshot.busy || snapshot.tasks.some(task => task.phase === 'running'))
  const lastAnswer = ready ? snapshot.messages.filter(message => message.role === 'assistant' && message.text.trim()).at(-1)?.id : undefined
  const seenAnswer = useRef<string | undefined | null>(null)
  const seenRunning = useRef(new Set<string>())
  const [flash, setFlash] = useState<{ mood: 'answered' | 'celebrate'; at: number } | null>(null)

  useEffect(() => {
    if (!ready) return
    // The first answer seen after mounting is history, not news.
    if (seenAnswer.current === null) { seenAnswer.current = lastAnswer; return }
    if (lastAnswer && lastAnswer !== seenAnswer.current) setFlash(current => current?.mood === 'celebrate' ? current : { mood: 'answered', at: Date.now() })
    seenAnswer.current = lastAnswer
  }, [ready, lastAnswer])

  useEffect(() => {
    if (!ready) return
    let finished = false
    const present = new Set(snapshot.tasks.map(task => task.id))
    for (const id of seenRunning.current) if (!present.has(id)) seenRunning.current.delete(id)
    for (const task of snapshot.tasks) {
      if (task.phase === 'running') seenRunning.current.add(task.id)
      else if (task.phase === 'completed' && seenRunning.current.delete(task.id)) finished = true
    }
    if (finished) setFlash({ mood: 'celebrate', at: Date.now() })
  }, [ready, snapshot])

  useEffect(() => {
    if (!flash) return
    const timer = window.setTimeout(() => setFlash(null), flash.mood === 'celebrate' ? CELEBRATE_MS : ANSWER_MS)
    return () => window.clearTimeout(timer)
  }, [flash])

  if (flash) return flash.mood
  return thinking ? 'thinking' : 'idle'
}
