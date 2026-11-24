import { useLayoutEffect, useRef, useState } from 'react'
import './character.css'
import '../sheet.css'
import { Character } from './Character'
import {
  COLORS, EXPR_OPTIONS, PALETTES, VARIANTS, type CharacterChoice,
} from './characterData'

export function CharacterPicker({ open, value, onChange, onClose }: {
  open: boolean
  value: CharacterChoice
  onChange(next: CharacterChoice): void
  onClose(): void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [wiggle, setWiggle] = useState(0)
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

  return <dialog ref={dialog} className="sheet sheet--motion character-sheet" aria-label="집순이 모습 바꾸기"
    onClose={onClose} onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    {open && <div className="sheet-body">
      <button type="button" className="character-preview" aria-label="집순이 귀 흔들기" onClick={() => setWiggle(value => value + 1)}>
        <Character choice={value} size={160} wiggle={wiggle} decorative />
      </button>
      <section aria-label="모양">
        <h2>모양</h2>
        <div className="choice-row">
          {VARIANTS.map(item => <button key={item.id} type="button" className="choice" aria-pressed={value.variant === item.id}
            onClick={() => onChange({ ...value, variant: item.id })}>{item.label}</button>)}
        </div>
      </section>
      <section aria-label="색">
        <h2>색</h2>
        <div className="choice-row choice-row--wrap">
          {COLORS.map(item => <button key={item.id} type="button" className="choice" aria-pressed={value.color === item.id}
            onClick={() => onChange({ ...value, color: item.id })}>
            <span className="swatch" aria-hidden="true" style={{ background: PALETTES[item.id].fill, borderColor: PALETTES[item.id].dark }} />
            {item.label}
          </button>)}
        </div>
      </section>
      <section aria-label="표정">
        <h2>표정</h2>
        <div className="choice-row choice-row--wrap">
          {EXPR_OPTIONS.map(item => <button key={item.id} type="button" className="choice" aria-pressed={value.expr === item.id}
            onClick={() => onChange({ ...value, expr: item.id })}>{item.label}</button>)}
        </div>
      </section>
      <button type="button" className="sheet-close" onClick={onClose}>닫기</button>
    </div>}
  </dialog>
}
