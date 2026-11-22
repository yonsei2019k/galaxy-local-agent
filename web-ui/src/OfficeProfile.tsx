import { useState } from 'react'
import type { OfficeEntries } from './bridge'

export const OFFICE_SEATS: { key: string; label: string }[] = [
  { key: 'CHAIR', label: '회장 이수빈' },
  { key: 'DIRECTOR', label: '과장 김호영' },
  { key: 'ASSOCIATE_GYUMIN', label: '대리 김규민' },
  { key: 'ASSOCIATE_SEOYUL', label: '대리 김서율' },
]

const DATE = /^\d{4}-\d{2}-\d{2}$/

export function normalizeOfficeEntries(draft: OfficeEntries): OfficeEntries | null {
  const result: OfficeEntries = {}
  for (const { key } of OFFICE_SEATS) {
    const birthDate = draft[key]?.birthDate?.trim() ?? ''
    const schoolGrade = draft[key]?.schoolGrade?.trim() ?? ''
    if (birthDate && !DATE.test(birthDate)) return null
    if (schoolGrade.length > 20) return null
    if (birthDate || schoolGrade) result[key] = { ...(birthDate ? { birthDate } : {}), ...(schoolGrade ? { schoolGrade } : {}) }
  }
  return result
}

export function OfficeProfileForm({ initial, enabled, onSave, onClear, onClose }: {
  initial: OfficeEntries
  enabled: boolean
  onSave(entries: OfficeEntries): boolean
  onClear(): boolean
  onClose(): void
}) {
  const [draft, setDraft] = useState<OfficeEntries>(initial)
  const [invalid, setInvalid] = useState(false)
  const set = (key: string, field: 'birthDate' | 'schoolGrade', value: string) =>
    setDraft(current => ({ ...current, [key]: { ...current[key], [field]: value } }))

  return (
    <form className="office-profile" aria-label="직원 정보" onSubmit={event => {
      event.preventDefault()
      const entries = normalizeOfficeEntries(draft)
      setInvalid(entries === null)
      if (entries && enabled && onSave(entries)) onClose()
    }}>
      <h2>직원 정보</h2>
      <p className="office-profile-note">이 정보는 이 휴대폰에만 암호화해 저장하고 공개 코드나 클라우드로 보내지 않습니다.</p>
      {OFFICE_SEATS.map(({ key, label }) => (
        <fieldset key={key}>
          <legend>{label}</legend>
          <label>생년월일
            <input type="date" aria-label={`${label} 생년월일`} value={draft[key]?.birthDate ?? ''} min="1900-01-01"
              onChange={event => set(key, 'birthDate', event.target.value)} />
          </label>
          <label>학년
            <input type="text" aria-label={`${label} 학년`} value={draft[key]?.schoolGrade ?? ''} maxLength={20}
              onChange={event => set(key, 'schoolGrade', event.target.value)} />
          </label>
        </fieldset>
      ))}
      {invalid && <p role="alert">생년월일은 YYYY-MM-DD, 학년은 20자 이하로 입력해 주세요.</p>}
      <div className="office-profile-actions">
        <button type="submit" disabled={!enabled}>저장</button>
        <button type="button" disabled={!enabled} onClick={() => { if (onClear()) onClose() }}>지우기</button>
        <button type="button" onClick={onClose}>닫기</button>
      </div>
    </form>
  )
}
