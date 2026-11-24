import { ICONS, ICON_SOFT_OPACITY, ICON_STROKE, type IconLayers } from './icons/generated'

// 집순이 스타일 듀오톤 아이콘. 원본은 design/icons.json, 경로는 scripts/gen-icons.py가 생성한다.
// 선은 currentColor, 옅은 면은 currentColor의 낮은 불투명도(이름 붙은 색조는 고정색)라 주변 색을 따라간다.
export type IconName = keyof typeof ICONS

export function Icon({ name, className }: { name: IconName; className?: string }) {
  const icon: IconLayers = ICONS[name]
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false" data-icon={name}>
      {icon.soft && <path className="icon-soft" d={icon.soft} fill="currentColor" fillOpacity={ICON_SOFT_OPACITY} />}
      {icon.accents.map(accent => <path key={accent.d} className="icon-accent" d={accent.d} fill={accent.color} />)}
      <path className="icon-line" d={icon.line} stroke="currentColor" strokeWidth={ICON_STROKE}
        strokeLinecap="round" strokeLinejoin="round" />
      {icon.dots && <path className="icon-dots" d={icon.dots} fill="currentColor" />}
    </svg>
  )
}
