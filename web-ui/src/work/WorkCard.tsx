import { useEffect, useRef, useState } from 'react'
import './work.css'
import { Icon } from '../Icon'
import { withStickers } from '../icons/Sticker'
import type { Task } from '../bridge'
import { detailLine, workPresentation } from './workPresentation'
import { useWorkDuration } from './workHistory'
import { Character } from '../character/Character'
import { useCharacter } from '../character/useCharacter'

export function WorkCard({ task, onOpen }: { task: Task; onOpen(taskId: string): void }) {
  const presentation = workPresentation(task)
  const [character] = useCharacter()
  const line = detailLine(task.detail ?? '')
  const elapsed = useWorkDuration(task)
  const previous = useRef(task.phase)
  const [justFinished, setJustFinished] = useState(false)
  useEffect(() => {
    setJustFinished(previous.current !== 'completed' && task.phase === 'completed')
    previous.current = task.phase
  }, [task.phase])
  return (
    <button type="button" id={`work-${task.id}`} className={`work-card work-card--${presentation.tone}`}
      onClick={() => onOpen(task.id)}>
      <span className={`work-icon${justFinished ? ' work-icon--just-finished' : ''}`}>
        <Character choice={character} state={presentation.characterState} size={38} decorative />
      </span>
      <span className="work-text">
        <span className="work-title" dir="auto">{withStickers(task.label || '작업')}</span>
        <span className="work-meta">
          <span className="work-state">{presentation.title}</span>
          {elapsed && <span className="work-duration"> · {elapsed}</span>}
          {line && <span className="work-detail" dir="auto"> · {withStickers(line)}</span>}
        </span>
        {task.phase === 'running' && <span className="work-progress" role="progressbar" aria-label="작업 진행 중"><span /></span>}
      </span>
      <Icon name="chevronRight" className="work-chevron" />
    </button>
  )
}
