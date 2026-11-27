import './work.css'
import type { Snapshot } from '../bridge'
import { withStickers, withoutEmoji } from '../icons/Sticker'
import { workPresentation } from './workPresentation'
import { useWorkDuration, workHistory } from './workHistory'

export function HeaderWorkLine({ snapshot, onOpen }: { snapshot: Snapshot | null; onOpen(): void }) {
  const { active, recent } = workHistory(snapshot)
  const current = active[0]
  const elapsed = useWorkDuration(current)
  const input = snapshot?.ready && snapshot.websiteCard?.inputHeight ? snapshot.websiteCard : null
  if (input) return <button type="button" className="header-work"
    onClick={() => document.querySelector('[data-native-website-slot]')?.scrollIntoView?.({ block: 'nearest' })}>
    <span>{withStickers(input.title)}</span>
  </button>
  if (!current && !recent.length) return null
  const detail = current && [current.label || '작업', workPresentation(current).title,
    active.length > 1 ? `외 ${active.length - 1}개` : null].filter(Boolean).join(' · ')
  return <button type="button" className="header-work" aria-label="작업 목록 열기"
    title={withoutEmoji([detail || '최근 작업', elapsed].filter(Boolean).join(' · '))}
    onClick={onOpen}>
    <span className="header-work-text">{withStickers(detail || '최근 작업')}</span>
    {elapsed && <span className="header-work-time"> · {elapsed}</span>}
  </button>
}
