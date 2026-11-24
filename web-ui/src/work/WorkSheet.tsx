import { useLayoutEffect, useRef } from 'react'
import '../sheet.css'
import './work.css'
import { Icon } from '../Icon'
import type { Task } from '../bridge'
import { friendlyStep, taskSteps, verbatimSteps, workPresentation } from './workPresentation'

export function WorkSheet({ task, enabled, steps, onClose, onStopGoalWait }: {
  task: Task | null
  enabled: boolean
  /** Replaces the steps derived from the task's evidence (website card live steps, shown verbatim). */
  steps?: string[]
  onClose(): void
  onStopGoalWait(runId: string): void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const open = task !== null
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

  const presentation = task && workPresentation(task)
  const stepList = task ? (steps ? verbatimSteps(steps) : taskSteps(task)) : []
  const evidence = (task?.evidence ?? []).filter(item => item.detail)
  return <dialog ref={dialog} className="sheet work-sheet" aria-label={task ? `${task.label || '작업'} 상세` : '작업 상세'}
    onClose={onClose} onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    {task && presentation && <div className="sheet-body">
      <header className={`work-sheet-head work-card--${presentation.tone}`}>
        <span className="work-icon"><Icon name={presentation.icon} /></span>
        <div className="work-text">
          <h2 className="work-sheet-title" dir="auto">{task.label || '작업'}</h2>
          <p className="work-state">{presentation.title}</p>
        </div>
      </header>
      {task.detail && <p className="work-sheet-detail" dir="auto">{task.detail}</p>}
      {stepList.length > 0 && <section aria-label="진행 단계">
        <h2>진행 단계</h2>
        <ol className="work-steps">{stepList.map(step => <li key={step} dir="auto">{step}</li>)}</ol>
      </section>}
      {evidence.length > 0 && <section aria-label="확인한 내용">
        <h2>확인한 내용</h2>
        <ul className="work-evidence">{evidence.map(item => <li key={item.id}>
          {friendlyStep(item.label) && <div className="evidence-label">{friendlyStep(item.label)}</div>}
          <p dir="auto">{item.detail}</p>
        </li>)}</ul>
      </section>}
      {task.phase === 'waiting_goal' && task.canCancel && enabled &&
        <button type="button" className="work-stop" onClick={() => onStopGoalWait(task.id)}>예약 중지</button>}
      <button type="button" className="sheet-close" onClick={onClose}>닫기</button>
    </div>}
  </dialog>
}
