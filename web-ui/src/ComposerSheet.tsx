import { useLayoutEffect, useRef } from 'react'
import './character/character.css'
import './composer-sheet.css'
import { Icon } from './Icon'
import type { Snapshot } from './bridge'

export function ComposerSheet({ open, enabled, snapshot, activeLabel, onAttach, onSelectModel, onClose }: {
  open: boolean
  enabled: boolean
  snapshot: Snapshot | null
  /** Name of the model of the run in progress, shown read-only. */
  activeLabel?: string
  onAttach(): void
  onSelectModel(id: string): void
  onClose(): void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
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

  const models = snapshot?.models ?? []
  const selectedLabel = snapshot?.modelLabel || models.find(model => model.id === snapshot?.modelId)?.label || '답변 방식'
  return <dialog ref={dialog} className="sheet composer-sheet" aria-label="더하기 메뉴"
    onClose={onClose} onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    {open && <div className="sheet-body">
      <button type="button" className="sheet-action" disabled={!enabled} onClick={() => { onClose(); onAttach() }}>
        <Icon name="file" /><span>파일 첨부</span>
      </button>
      {models.length > 0 && <fieldset className="model-choices" disabled={!enabled}>
        <legend>답변 방식</legend>
        {models.map(model => <label key={model.id} className="model-choice">
          <input type="radio" name="answer-model" value={model.id} checked={model.id === snapshot?.modelId}
            onChange={() => onSelectModel(model.id)} />
          <span>{model.id === snapshot?.modelId ? selectedLabel : model.label}</span>
        </label>)}
      </fieldset>}
      {activeLabel && <p className="active-model-note">실행 중: {activeLabel}</p>}
      <button type="button" className="sheet-close" onClick={onClose}>닫기</button>
    </div>}
  </dialog>
}
