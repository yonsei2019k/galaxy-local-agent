import { useId } from 'react'
import { METAL, type MetalArt } from './generated'

// 승인·결제·금고 화면의 금속 그림(160×160). 원본은 design/metal.json이고 그라디언트는 viewBox 좌표다.
export type MetalName = keyof typeof METAL

export function Metal({ name, className }: { name: MetalName; className?: string }) {
  const art: MetalArt = METAL[name]
  // Several pictures can share a page, so gradient ids are per instance.
  const prefix = `zm${useId().replace(/[^a-zA-Z0-9_-]/g, '')}-`
  const paint = (value?: string) => value?.startsWith('grad:') ? `url(#${prefix}${value.slice(5)})` : value
  return (
    <svg className={className} viewBox="0 0 160 160" focusable="false" aria-hidden="true" data-metal={name}>
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
