import type { IconName } from '../Icon'
import type { Task } from '../bridge'
import type { CharacterState } from '../character/characterData'

export type WorkTone = 'active' | 'waiting' | 'review' | 'complete' | 'failed' | 'neutral'

export interface WorkPresentation {
  tone: WorkTone
  title: string
  icon: IconName
  characterState: CharacterState
}

const presentations: Record<string, WorkPresentation> = {
  queued: { title: '대기 중', tone: 'active', icon: 'clock', characterState: 'work' },
  running: { title: '진행 중', tone: 'active', icon: 'activity', characterState: 'work' },
  waiting_resource: { title: '작업 순서 기다리는 중', tone: 'active', icon: 'clock', characterState: 'work' },
  waiting_goal: { title: '재개 대기', tone: 'waiting', icon: 'clock', characterState: 'wait' },
  approval: { title: '승인 필요', tone: 'review', icon: 'shield', characterState: 'alert' },
  waiting_approval: { title: '승인 필요', tone: 'review', icon: 'shield', characterState: 'alert' },
  paused: { title: '일시 정지', tone: 'review', icon: 'pause', characterState: 'alert' },
  waiting_input: { title: '답변 대기', tone: 'review', icon: 'chat', characterState: 'alert' },
  completed: { title: '응답 종료', tone: 'complete', icon: 'check', characterState: 'done' },
  failed: { title: '실패', tone: 'failed', icon: 'alert', characterState: 'alert' },
  cancelled: { title: '중단됨', tone: 'neutral', icon: 'stop', characterState: 'none' },
  interrupted: { title: '재시작으로 중단됨', tone: 'neutral', icon: 'stop', characterState: 'none' },
  clarification_record: { title: '질문 기록', tone: 'neutral', icon: 'chat', characterState: 'none' },
}

const unknownPhase: WorkPresentation = { title: '작업 상태', tone: 'neutral', icon: 'clock', characterState: 'none' }

export function workPresentation(task: Task): WorkPresentation {
  return Object.hasOwn(presentations, task.phase) ? presentations[task.phase] : unknownPhase
}

/** Kept for existing importers: same lookup under the old name. */
export function taskPhase(task: Task): WorkPresentation {
  return workPresentation(task)
}

// Only the exact known phrase (optionally followed by the host's record tail) is rewritten or hidden.
// Anything else, such as a failure wording, stays verbatim so it is never turned into success.
const hiddenSteps = /^(?:모델 추론|후속 조건 검증 기록|답변 구간 저장 확인)(?: · 실행 결과 기록)?$/
const rewrittenSteps: Array<[RegExp, string]> = [
  [/^사이트 화면 확인(?: · 실행 결과 기록)?$/, '사이트 화면을 읽었어요'],
  [/^공개 웹 검색(?: · 실행 결과 기록)?$/, '웹에서 찾아봤어요'],
  [/^웹페이지 읽기(?: · 실행 결과 기록)?$/, '웹페이지를 읽었어요'],
]

/** Internal step wording in people's words. An empty result means "do not show". */
export function friendlyStep(label: string): string {
  const text = label.trim()
  if (hiddenSteps.test(text)) return ''
  for (const [pattern, replacement] of rewrittenSteps) if (pattern.test(text)) return replacement
  return text
}

/** Verbatim, non-empty, de-duplicated list (live steps reported by the host are never reworded). */
export function verbatimSteps(labels: string[]): string[] {
  return [...new Set(labels.map(label => label.trim()).filter(Boolean))]
}

/** Friendly, non-empty, de-duplicated step list in first-seen order. */
export function friendlySteps(labels: string[]): string[] {
  return [...new Set(labels.map(friendlyStep).filter(Boolean))]
}

export function taskSteps(task: Task): string[] {
  return friendlySteps((task.evidence ?? []).map(item => item.label))
}

/** First non-empty line, cut to `max` characters with an ellipsis. */
export function detailLine(detail: string, max = 40): string {
  const line = detail.split(/\r?\n/).map(part => part.trim()).find(Boolean) ?? ''
  const chars = Array.from(line)
  return chars.length > max ? chars.slice(0, max).join('') + '…' : line
}
