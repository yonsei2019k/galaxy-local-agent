import { ILLUSTRATIONS, type IllustrationShape } from './generated'

// 집순이 팔레트의 그림 일러스트(160×120). 원본은 design/illustrations.json이고 색은 고정이다.
export type IllustrationName = keyof typeof ILLUSTRATIONS

export function Illustration({ name, className, title }: { name: IllustrationName; className?: string; title?: string }) {
  const shapes: readonly IllustrationShape[] = ILLUSTRATIONS[name]
  const a11y = title ? { role: 'img' as const, 'aria-label': title } : { 'aria-hidden': true as const }
  return (
    <svg className={className} viewBox="0 0 160 120" focusable="false" data-illustration={name} {...a11y}>
      {shapes.map((shape, i) => (
        <path key={i} d={shape.d} fill={shape.fill ?? 'none'} stroke={shape.stroke} strokeWidth={shape.sw}
          strokeLinecap={shape.stroke ? 'round' : undefined} strokeLinejoin={shape.stroke ? 'round' : undefined}
          opacity={shape.opacity} />
      ))}
    </svg>
  )
}
