import { useId } from 'react'
import { CLAY, type MetalArt } from './generated'

// 탭·입력창·메뉴의 색 있는 클레이 아이콘(48×48). 원본은 design/clay.json, 경로와 그라디언트는 scripts/gen-icons.py가 만든다.
// 장식용이다: 뜻은 옆의 글자나 버튼 이름이 전한다. 상태(선택·비활성)는 CSS가 채도와 밝기로 나타낸다.
export type ClayName = keyof typeof CLAY

export function Clay({ name, className }: { name: ClayName; className?: string }) {
  const art: MetalArt = CLAY[name]
  // Several icons share a page, so gradient ids are per instance.
  const prefix = `zy${useId().replace(/[^a-zA-Z0-9_-]/g, '')}-`
  const paint = (value?: string) => value?.startsWith('grad:') ? `url(#${prefix}${value.slice(5)})` : value
  return (
    <svg className={className ? `clay ${className}` : 'clay'} viewBox="0 0 48 48" focusable="false" aria-hidden="true" data-clay={name}>
      <defs>
        {Object.entries(art.gradients).map(([id, g]) => {
          const stops = g.stops.map(([offset, color], i) => <stop key={i} offset={offset} stopColor={color} />)
          return g.type === 'linear'
            ? <linearGradient key={id} id={prefix + id} gradientUnits="userSpaceOnUse" x1={g.x1} y1={g.y1} x2={g.x2} y2={g.y2}>{stops}</linearGradient>
            : <radialGradient key={id} id={prefix + id} gradientUnits="userSpaceOnUse" cx={g.cx} cy={g.cy} r={g.r}>{stops}</radialGradient>
        })}
      </defs>
      {art.shapes.map((shape, i) => (
        <path key={i} d={shape.d} fill={paint(shape.fill) ?? 'none'} stroke={paint(shape.stroke)} strokeWidth={shape.sw}
          strokeLinecap={shape.stroke ? 'round' : undefined} strokeLinejoin={shape.stroke ? 'round' : undefined}
          opacity={shape.opacity} />
      ))}
    </svg>
  )
}
