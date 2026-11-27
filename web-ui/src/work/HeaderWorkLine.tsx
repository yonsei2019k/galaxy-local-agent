import './work.css'
import type { Snapshot } from '../bridge'
import { Icon } from '../Icon'
import { withStickers, withoutEmoji } from '../icons/Sticker'
import { workPresentation } from './workPresentation'
import { useWorkDuration, workHistory } from './workHistory'

/**
 * The header's work entry. `round` (single conversation, 2026-11-28 concept match) draws it as a 40px grey circle
 * with a clock beside the conversation button; the same text stays inside for screen readers, the title and checks.
 */
export function HeaderWorkLine({ snapshot, onOpen, round }: { snapshot: Snapshot | null; onOpen(): void; round?: boolean }) {
  const { active, recent } = workHistory(snapshot)
  const current = active[0]
  const elapsed = useWorkDuration(current)
  const input = snapshot?.ready && snapshot.websiteCard?.inputHeight ? snapshot.websiteCard : null
  const className = round ? 'header-work header-work--round' : 'header-work'
  const icon = round ? <Icon name="clock" /> : null
  if (input) return <button type="button" className={className} title={round ? withoutEmoji(input.title) : undefined}
    onClick={() => document.querySelector('[data-native-website-slot]')?.scrollIntoView?.({ block: 'nearest' })}>
    {icon}<span className={round ? 'sr-only' : undefined}>{withStickers(input.title)}</span>
  </button>
  if (!current && !recent.length) return null
  const detail = current && [current.label || '작업', workPresentation(current).title,
    active.length > 1 ? `외 ${active.length - 1}개` : null].filter(Boolean).join(' · ')
  return <button type="button" className={className} aria-label="작업 목록 열기" data-live={round && current ? 'true' : undefined}
    title={withoutEmoji([detail || '최근 작업', elapsed].filter(Boolean).join(' · '))}
    onClick={onOpen}>
    {icon}
    <span className={round ? 'sr-only' : 'header-work-text'}>{withStickers(detail || '최근 작업')}</span>
    {elapsed && <span className={round ? 'sr-only' : 'header-work-time'}> · {elapsed}</span>}
  </button>
}
