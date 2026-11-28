import type { Receipt, ReceiptStatus, RunStatus } from './bridge'
import type { CharacterSituation } from './character/characterData'
import { Character } from './character/Character'
import { useCharacter } from './character/useCharacter'
import { situationForPhase } from './character/useCharacterMood'

/**
 * Host facts as concept-style grey cards. Nothing here is a host-written conversation sentence:
 * the status card shows the run's label, reason and settings path; the receipt card shows labeled
 * exact values and a proof word. Only the model writes chat sentences.
 */

/** Plain words for how far an action is proven. */
export const RECEIPT_STATUS_LABELS: Record<ReceiptStatus, string> = {
  verified: '다시 봐서 확인함',
  prepared: '앱에 넘김 · 끝났는지는 아직 모름',
  unverified: '결과 못 봄',
  failed: '안 됨',
}

/** Face for a runStatus phase (its ids differ a little from task phases). */
export function runStatusSituation(phase: string): CharacterSituation {
  if (phase === 'waiting_reply' || phase === 'waiting_login' || phase === 'waiting_approval') return 'confirm'
  if (phase === 'waiting_time') return 'waiting'
  if (phase === 'interrupted') return 'blocked'
  return situationForPhase(phase)
}

export function RunStatusCard({ status, report, onAction }: {
  status: RunStatus
  /** The host's step lines for this run, without the status line this card already shows. */
  report: string[]
  /** Existing UI action for the status's action id; no handler means no button. */
  onAction?: () => void
}) {
  const [character] = useCharacter()
  const recent = report.slice(-8)
  const earlier = report.slice(0, -8)
  return <section className="execution-report run-status-card" aria-label={status.label} data-phase={status.phase}>
    <span className="execution-report-face" aria-hidden="true">
      <Character choice={character} situation={runStatusSituation(status.phase)} size={40} decorative />
    </span>
    <strong>{status.label}</strong>
    {status.reason && <p className="run-status-reason" dir="auto">{status.reason}</p>}
    {status.instruction && <p className="run-status-instruction" dir="auto">{status.instruction}</p>}
    {!!recent.length && <ol>{recent.map((line, index) => <li key={index}>{line}</li>)}</ol>}
    {!!earlier.length && <details>
      <summary>앞서 확인한 단계 {earlier.length}개</summary>
      <ol>{earlier.map((line, index) => <li key={index}>{line}</li>)}</ol>
    </details>}
    {onAction && status.actionLabel && <button type="button" className="run-status-action" onClick={onAction}>{status.actionLabel}</button>}
  </section>
}

export function ReceiptCard({ receipt }: { receipt: Receipt }) {
  const [character] = useCharacter()
  const allVerified = receipt.items.length > 0 && receipt.items.every(item => item.verified)
  const situation: CharacterSituation = receipt.items.some(item => item.status === 'failed') ? 'blocked'
    : receipt.complete && allVerified ? 'done' : 'waiting'
  return <section className="execution-report receipt-card" aria-label="한 일">
    <span className="execution-report-face" aria-hidden="true">
      <Character choice={character} situation={situation} size={40} decorative />
    </span>
    {receipt.items.length === 0 && <strong>확인한 일 없음</strong>}
    {receipt.items.map((item, index) => <div key={index} className={`receipt-item receipt-item--${item.status}`}>
      <strong dir="auto">{item.title}</strong>
      <p className="receipt-status">{RECEIPT_STATUS_LABELS[item.status]}</p>
      {!!item.fields.length && <dl>{item.fields.map((field, at) => <div key={at} className="receipt-field">
        <dt>{field.label}</dt><dd dir="auto">{field.value}</dd>
      </div>)}</dl>}
      {item.link && <a className="receipt-link" href={item.link} rel="noreferrer noopener" referrerPolicy="no-referrer">열기</a>}
    </div>)}
    {!receipt.complete && <p className="receipt-incomplete">부탁한 일이 다 끝났는지는 아직 모름</p>}
  </section>
}
