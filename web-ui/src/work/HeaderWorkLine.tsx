import './work.css'
import type { Snapshot } from '../bridge'
import { isWorkingTaskPhase } from '../composer'
import { workPresentation } from './workPresentation'

interface HeaderWork {
  text: string
  /** Looks up the card to bring into view; evaluated on press so it follows the current DOM. */
  find(): Element | null
}

interface Entry extends HeaderWork {
  /** The run this entry stands for, when the host named one. */
  runId?: string
  website?: boolean
}

function headerWork(snapshot: Snapshot | null): HeaderWork | null {
  if (!snapshot?.ready) return null
  const website = snapshot.websiteCard
  const websiteTask = snapshot.tasks.find(task => task.id === website?.runId)
  const entries: Entry[] = []
  if (website) {
    const websiteLive = websiteTask !== undefined && isWorkingTaskPhase(websiteTask.phase)
    if (website.inputHeight) {
      entries.push({ text: website.title, runId: website.runId, website: true, find: () => document.querySelector('[data-native-website-slot]') })
    } else if (website.working || websiteLive) {
      const phase = websiteLive ? websiteTask.phase : 'running'
      const state = workPresentation({ id: website.id, label: website.title, detail: '', canCancel: false, phase }).title
      entries.push({ text: `${website.title} · ${state}`, runId: website.runId, website: true, find: () => document.getElementById('website-work') })
    }
  }
  for (const task of snapshot.tasks) {
    if (task.id === website?.runId || !isWorkingTaskPhase(task.phase)) continue
    entries.push({ text: `${task.label || '작업'} · ${workPresentation(task).title}`, runId: task.id, find: () => document.getElementById(`work-${task.id}`) })
  }
  if (entries.length === 0) return null
  // Where a press goes: the run the host is acting on, then the website card, then the first entry.
  const target = (snapshot.activeRunId !== null ? entries.find(entry => entry.runId === snapshot.activeRunId) : undefined)
    ?? entries.find(entry => entry.website) ?? entries[0]
  return entries.length === 1 ? entries[0] : { text: `작업 ${entries.length}개`, find: target.find }
}

/** One line under the conversation name: what is running or waiting, and a way to jump to its card. */
export function HeaderWorkLine({ snapshot }: { snapshot: Snapshot | null }) {
  const work = headerWork(snapshot)
  if (!work) return null
  return <button type="button" className="header-work" title={work.text}
    onClick={() => work.find()?.scrollIntoView?.({ block: 'nearest' })}>
    <span>{work.text}</span>
  </button>
}
