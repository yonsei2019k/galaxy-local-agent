import './work.css'
import { Icon } from '../Icon'
import type { Task } from '../bridge'
import { detailLine, workPresentation } from './workPresentation'

export function WorkCard({ task, onOpen }: { task: Task; onOpen(taskId: string): void }) {
  const presentation = workPresentation(task)
  const line = detailLine(task.detail ?? '')
  return (
    <button type="button" id={`work-${task.id}`} className={`work-card work-card--${presentation.tone}`}
      onClick={() => onOpen(task.id)}>
      <span className="work-icon"><Icon name={presentation.icon} /></span>
      <span className="work-text">
        <span className="work-title" dir="auto">{task.label || '작업'}</span>
        <span className="work-meta">
          <span className="work-state">{presentation.title}</span>
          {line && <span className="work-detail" dir="auto"> · {line}</span>}
        </span>
      </span>
      <span className="work-chevron" aria-hidden="true">›</span>
      {task.phase === 'running' && <span className="work-progress" role="progressbar" aria-label="작업 진행 중"><span /></span>}
    </button>
  )
}
