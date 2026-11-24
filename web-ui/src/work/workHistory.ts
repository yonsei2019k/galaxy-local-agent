import { useSyncExternalStore } from 'react'
import type { Snapshot, Task } from '../bridge'
import { isLiveTaskPhase, isWorkingTaskPhase } from '../composer'

const heldPhases = new Set(['waiting_goal', 'approval', 'waiting_approval', 'paused', 'waiting_input'])

export function websiteWorkFor(snapshot: Snapshot | null): Task | null {
  const website = snapshot?.websiteCard
  if (!website || website.inputHeight) return null
  const work = snapshot.tasks.find(task => task.id === website.runId)
  return {
    id: website.runId ?? `website-${website.id}`,
    phase: work && heldPhases.has(work.phase) ? work.phase
      : website.working ? 'running' : work && !isLiveTaskPhase(work.phase) ? work.phase : 'website',
    label: website.title,
    detail: website.status,
    canCancel: work?.canCancel ?? false,
    evidence: work?.evidence,
    startedAt: work?.startedAt,
    finishedAt: work?.finishedAt,
  }
}

export function workHistory(snapshot: Snapshot | null) {
  if (!snapshot?.ready) return { active: [], recent: [] } as { active: Task[]; recent: Task[] }
  const website = websiteWorkFor(snapshot)
  const all = [
    ...(website ? [website] : []),
    ...snapshot.tasks.filter(task => task.id !== snapshot.websiteCard?.runId),
  ].filter(task => task.phase !== 'clarification_record')
  const active = all.filter(task => isWorkingTaskPhase(task.phase))
  const primary = active.findIndex(task => task.id === snapshot.activeRunId)
  if (primary > 0) active.unshift(...active.splice(primary, 1))
  const recent = all.filter(task => !isWorkingTaskPhase(task.phase)).reverse()
    .sort((a, b) => (b.finishedAt ?? b.startedAt ?? 0) - (a.finishedAt ?? a.startedAt ?? 0))
  return { active, recent }
}

const updates = new Set<() => void>()
let handle: ReturnType<typeof setInterval> | undefined
let second = 0
const noSubscription = () => () => {}
function subscribe(listener: () => void) {
  updates.add(listener)
  if (handle === undefined) {
    second = Math.floor(Date.now() / 1000) * 1000
    handle = setInterval(() => {
      second = Math.floor(Date.now() / 1000) * 1000
      updates.forEach(update => update())
    }, 1000)
  }
  return () => {
    updates.delete(listener)
    if (updates.size === 0 && handle !== undefined) {
      clearInterval(handle)
      handle = undefined
    }
  }
}
const clockSnapshot = () => second
const noClock = () => 0

export function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  const tail = String(seconds % 60).padStart(2, '0')
  return minutes < 60 ? `${minutes}:${tail}` :
    `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}:${tail}`
}

export function useWorkDuration(task: Task | undefined): string | null {
  const start = task?.startedAt
  const live = !!task && !!start && task.finishedAt === undefined && isWorkingTaskPhase(task.phase)
  const now = useSyncExternalStore(live ? subscribe : noSubscription, live ? clockSnapshot : noClock, noClock)
  const end = task?.finishedAt ?? (live ? now : undefined)
  if (!start || !end || end < start) return null
  return formatDuration(Math.floor((end - start) / 1000))
}
