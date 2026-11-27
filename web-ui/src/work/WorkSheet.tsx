import { useLayoutEffect, useRef } from 'react'
import '../sheet.css'
import './work.css'
import { Character } from '../character/Character'
import { useCharacter } from '../character/useCharacter'
import { situationForPhase } from '../character/useCharacterMood'
import { Icon } from '../Icon'
import { StickerText, withStickers } from '../icons/Sticker'
import type { Task } from '../bridge'
import { detailLine, friendlyStep, taskSteps, verbatimSteps, workPresentation } from './workPresentation'
import { useWorkDuration } from './workHistory'

type WorkHistoryRows = { active: Task[]; recent: Task[] }

function HistoryRow({ task, onSelect }: { task: Task; onSelect?(id: string): void }) {
  const state = workPresentation(task)
  const elapsed = useWorkDuration(task)
  return <li>
    <button type="button" data-work-id={task.id} className={`work-history-row work-card--${state.tone}`}
      onClick={() => onSelect?.(task.id)}>
      <span className="work-icon" aria-hidden="true"><Icon name={state.icon} /></span>
      <span className="work-text">
        <span className="work-title" dir="auto">{withStickers(task.label || '작업')}</span>
        <span className="work-list-state"><StickerText text={[state.title, elapsed, detailLine(task.detail)].filter(Boolean).join(' · ')} /></span>
      </span>
      <Icon name="chevronRight" className="work-chevron" />
    </button>
  </li>
}

export function WorkSheet({ task, enabled, steps, history, onSelect, onBack, onClose, onStopGoalWait }: {
  task: Task | null
  enabled: boolean
  /** Replaces the steps derived from the task's evidence (website card live steps, shown verbatim). */
  steps?: string[]
  history?: WorkHistoryRows
  onSelect?(id: string): void
  onBack?(): void
  onClose(): void
  onStopGoalWait(runId: string): void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [character] = useCharacter()
  const open = task !== null || history !== undefined
  const elapsed = useWorkDuration(task ?? undefined)
  useLayoutEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open && !element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    }
    if (!open && element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
  }, [open])
  useLayoutEffect(() => {
    const element = dialog.current
    if (!element?.open) return
    element.scrollTop = 0
    element.querySelector<HTMLButtonElement>(task ? '.work-history-back' : '.work-history-row, .work-history-heading button')?.focus()
  }, [open, task?.id])

  const presentation = task && workPresentation(task)
  const timeline = (task?.evidence ?? []).filter(item => (friendlyStep(item.label) || item.detail))
    .sort((a, b) => (a.at ?? Number.MAX_SAFE_INTEGER) - (b.at ?? Number.MAX_SAFE_INTEGER))
  const hasTiming = timeline.some(item => item.at !== undefined)
  const stepList = task ? (steps ? verbatimSteps(steps) : hasTiming ? [] : taskSteps(task)) : []
  const evidence = (task?.evidence ?? []).filter(item => item.detail)
  return <dialog ref={dialog} className={`sheet sheet--motion work-sheet${history ? ' work-sheet--history' : ''}`}
    aria-label={task ? `${task.label || '작업'} 상세` : history ? '작업 목록' : '작업 상세'}
    onClose={onClose} onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    {!task && history && <div className="sheet-body work-history-list">
      <header className="work-history-heading"><h2>작업 목록</h2>
        <button type="button" className="sheet-close" onClick={onClose}>닫기</button>
      </header>
      {([['진행·대기 중', history.active], ['최근 작업', history.recent]] as const).map(([label, items]) =>
        items.length > 0 && <section key={label} aria-label={label}>
          <h2>{label} <span>{items.length}건</span></h2>
          <ul>{items.map(item => <HistoryRow key={item.id} task={item} onSelect={onSelect} />)}</ul>
        </section>)}
      {!history.active.length && !history.recent.length && <p>이 대화에 표시할 작업이 없어요.</p>}
    </div>}
    {task && presentation && <div className="sheet-body">
      {history && <button type="button" className="work-history-back" onClick={onBack}>
        <Icon name="back" /> 작업 목록
      </button>}
      <header className={`work-sheet-head work-card--${presentation.tone}`}>
        <Character choice={character} situation={situationForPhase(task.phase)} size={64} decorative />
        <div className="work-text">
          <h2 className="work-sheet-title" dir="auto">{withStickers(task.label || '작업')}</h2>
          <p className="work-state">{[presentation.title, elapsed].filter(Boolean).join(' · ')}</p>
        </div>
      </header>
      {task.detail && <p className="work-sheet-detail" dir="auto"><StickerText text={task.detail} /></p>}
      {stepList.length > 0 && <section aria-label="진행 단계">
        <h2>진행 단계</h2>
        <ol className="work-steps">{stepList.map(step => <li key={step} dir="auto"><StickerText text={step} /></li>)}</ol>
      </section>}
      {hasTiming ? <section aria-label="시간순 작업 기록">
        <h2>시간순 작업 기록</h2>
        <ol className="work-timeline">{timeline.map(item => <li key={item.id}>
          {item.at !== undefined && <time dateTime={new Date(item.at).toISOString()}>
            {new Date(item.at).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })}
          </time>}
          <div>{friendlyStep(item.label) && <strong dir="auto"><StickerText text={friendlyStep(item.label)} /></strong>}
            {item.detail && <p dir="auto"><StickerText text={item.detail} /></p>}</div>
        </li>)}</ol>
      </section> : evidence.length > 0 && <section aria-label="확인한 내용">
        <h2>확인한 내용</h2>
        <ul className="work-evidence">{evidence.map(item => <li key={item.id}>
          {friendlyStep(item.label) && <div className="evidence-label"><StickerText text={friendlyStep(item.label)} /></div>}
          <p dir="auto"><StickerText text={item.detail} /></p>
        </li>)}</ul>
      </section>}
      {task.phase === 'waiting_goal' && task.canCancel && enabled &&
        <button type="button" className="work-stop" onClick={() => onStopGoalWait(task.id)}>예약 중지</button>}
      <button type="button" className="sheet-close" onClick={onClose}>닫기</button>
    </div>}
  </dialog>
}
