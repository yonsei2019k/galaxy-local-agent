import { useEffect, useRef, useState } from 'react'
import type { Snapshot } from '../bridge'
import type { CharacterSituation } from './characterData'

/**
 * How 집순이 reacts, derived only from what the snapshot already shows (no new behavior).
 * Lasting situations follow the current state; one-shot situations are short flashes that fire on a transition:
 * - confirm: an approval card, or a task asks for approval or an answer (beats every flash)
 * - blocked: a task is paused, or a task that was seen running failed (flash)
 * - working / waiting: a task is running, or is parked waiting to resume
 * - thinking: the app is busy interpreting or planning without a running task, or voice is preparing a reply
 * - listening: voice is listening, or the composer holds a draft
 * - done: a task that was seen running just completed (flash with sparkles)
 * - greeting: an empty conversation was opened (flash, once per conversation)
 * - answered: a new assistant answer just arrived (one short bounce)
 * Motion itself is CSS and is switched off by prefers-reduced-motion; the mood attribute stays for styling.
 */
export type CharacterMood = CharacterSituation | 'answered'

const CONFIRM_PHASES = new Set(['approval', 'waiting_approval', 'waiting_input'])
const WORK_PHASES = new Set(['running', 'queued', 'waiting_resource'])

/** The lasting situation for a snapshot, before one-shot flashes. */
export function lastingSituation(snapshot: Snapshot | null): CharacterSituation {
  if (snapshot?.ready !== true) return 'idle'
  const phases = [...new Set(snapshot.tasks.map(task => task.phase))]
  if (snapshot.approvalCard || phases.some(phase => CONFIRM_PHASES.has(phase))) return 'confirm'
  if (phases.includes('paused')) return 'blocked'
  if (phases.some(phase => WORK_PHASES.has(phase))) return 'working'
  if (phases.includes('waiting_goal')) return 'waiting'
  const voice = snapshot.voiceTalk?.stage
  if (snapshot.busy || voice === 'thinking' || voice === 'preparing') return 'thinking'
  if (voice === 'listening' || voice === 'finishing' || snapshot.voicePrompt === 'listening' ||
    snapshot.voicePrompt === 'finishing' || snapshot.draft?.trim()) return 'listening'
  return 'idle'
}

type FlashMood = 'answered' | 'done' | 'blocked' | 'greeting'
const FLASH_MS: Record<FlashMood, number> = { answered: 900, done: 1_600, blocked: 2_400, greeting: 1_600 }
const FLASH_RANK: Record<FlashMood, number> = { answered: 0, greeting: 1, done: 2, blocked: 3 }

export function useCharacterMood(snapshot: Snapshot | null): CharacterMood {
  const ready = snapshot?.ready === true
  const lastAnswer = ready ? snapshot.messages.filter(message => message.role === 'assistant' && message.text.trim()).at(-1)?.id : undefined
  const conversation = ready ? snapshot.threads?.find(thread => thread.selected)?.id ?? snapshot.threadId : undefined
  const empty = ready && snapshot.messages.length === 0
  const seenAnswer = useRef<string | undefined | null>(null)
  const seenRunning = useRef(new Set<string>())
  const greeted = useRef(new Set<string>())
  const [flash, setFlash] = useState<{ mood: FlashMood; at: number } | null>(null)
  const raise = (mood: FlashMood) =>
    setFlash(current => current && FLASH_RANK[current.mood] > FLASH_RANK[mood] ? current : { mood, at: Date.now() })

  useEffect(() => {
    if (!ready) return
    // The first answer seen after mounting is history, not news.
    if (seenAnswer.current === null) { seenAnswer.current = lastAnswer; return }
    if (lastAnswer && lastAnswer !== seenAnswer.current) raise('answered')
    seenAnswer.current = lastAnswer
  }, [ready, lastAnswer])

  useEffect(() => {
    // Greet once per conversation, only when it opens empty (first run or a new conversation).
    if (!ready || !empty || conversation === undefined || greeted.current.has(conversation)) return
    greeted.current.add(conversation)
    raise('greeting')
  }, [ready, empty, conversation])

  useEffect(() => {
    if (!ready) return
    let finished = false
    let failed = false
    const present = new Set(snapshot.tasks.map(task => task.id))
    for (const id of seenRunning.current) if (!present.has(id)) seenRunning.current.delete(id)
    for (const task of snapshot.tasks) {
      if (task.phase === 'running') seenRunning.current.add(task.id)
      else if (task.phase === 'completed' && seenRunning.current.delete(task.id)) finished = true
      else if (task.phase === 'failed' && seenRunning.current.delete(task.id)) failed = true
    }
    if (failed) raise('blocked')
    else if (finished) raise('done')
  }, [ready, snapshot])

  useEffect(() => {
    if (!flash) return
    const timer = window.setTimeout(() => setFlash(null), FLASH_MS[flash.mood])
    return () => window.clearTimeout(timer)
  }, [flash])

  const lasting = lastingSituation(snapshot)
  if (lasting === 'confirm') return 'confirm'
  if (flash) return flash.mood
  return lasting
}

/** Situation for one task's phase (work sheet); unknown or historical phases rest. */
export function situationForPhase(phase: string): CharacterSituation {
  if (CONFIRM_PHASES.has(phase)) return 'confirm'
  if (phase === 'paused' || phase === 'failed') return 'blocked'
  if (WORK_PHASES.has(phase)) return 'working'
  if (phase === 'waiting_goal') return 'waiting'
  if (phase === 'completed') return 'done'
  return 'idle'
}

/** The drawing's situation for a mood: a new answer keeps the resting look (its bounce is on the wrapper). */
export function situationForMood(mood: CharacterMood): CharacterSituation {
  return mood === 'answered' ? 'idle' : mood
}
