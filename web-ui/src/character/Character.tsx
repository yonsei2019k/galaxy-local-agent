import { useId } from 'react'
import './character.css'
import { useVisibleMotion } from '../motion'
import {
  CHEEK, EXPRESSIONS, INK, PALETTES, STATE_EXPR, mixHex,
  type CharacterChoice, type CharacterState,
} from './characterData'

/** Blur filters are skipped at or below 32px: they cost paint time and only smear a tiny avatar. */
export const CHARACTER_FILTER_MIN_SIZE = 33

// Shapes follow the approved concept's critter(): shaded by gradients and soft shadows, never outlined.
const HOOD = 'M100 34 C152 34 178 66 178 116 C178 164 150 188 100 188 C50 188 22 164 22 116 C22 66 48 34 100 34Z'

export function Character({ choice, state = 'none', size, decorative, wiggle = 0 }: {
  choice: CharacterChoice
  state?: CharacterState
  size: number
  decorative?: boolean
  wiggle?: number
}) {
  const c = PALETTES[choice.color] ?? PALETTES.butter
  const exprKey = state === 'none' ? choice.expr : STATE_EXPR[state]
  const e = EXPRESSIONS[exprKey]
  const a11y = decorative ? { 'aria-hidden': true as const } : { role: 'img' as const, 'aria-label': '집순이' }
  const { ref, reduced, visible } = useVisibleMotion<SVGSVGElement>()
  // One page shows several 집순이 at once, so every gradient and filter id is per instance.
  const uid = `zc${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const id = (name: string) => `${uid}-${name}`
  const url = (name: string) => `url(#${id(name)})`
  const filtered = size >= CHARACTER_FILTER_MIN_SIZE
  const soft = filtered ? url('soft') : undefined
  const glow = mixHex(c.fill, '#FFFFFF', 0.5)
  const shade = mixHex(c.fill, c.dark, 0.6)
  const inner = mixHex(c.light, '#FFFFFF', 0.25)
  const face = choice.color === 'snow' || choice.color === 'grey' ? '#FFF8EF' : '#FFF1E4'
  return (
    <svg ref={ref} className={`character character--state-${state}${size > 32 ? ' character--full-motion' : ''}`}
      viewBox="0 0 200 210" width={size} height={size} data-animated={!reduced} data-paused={!visible}
      data-filtered={filtered} data-variant={choice.variant} focusable="false" {...a11y}>
      <defs>
        <radialGradient id={id('hood')} cx="34%" cy="26%" r="82%">
          <stop offset="0" stopColor={glow} /><stop offset=".46" stopColor={c.fill} /><stop offset="1" stopColor={shade} />
        </radialGradient>
        <radialGradient id={id('face')} cx="42%" cy="36%" r="72%">
          <stop offset="0" stopColor="#FFFDFB" /><stop offset=".7" stopColor={face} /><stop offset="1" stopColor={shade} stopOpacity=".55" />
        </radialGradient>
        <linearGradient id={id('ao')} x1="0" y1="0" x2="0" y2="1">
          <stop offset=".55" stopColor="#000000" stopOpacity="0" /><stop offset="1" stopColor="#000000" stopOpacity=".22" />
        </linearGradient>
        <radialGradient id={id('ground')}>
          <stop offset="0" stopColor="#2A1D14" stopOpacity=".26" /><stop offset="1" stopColor="#2A1D14" stopOpacity="0" />
        </radialGradient>
        <linearGradient id={id('prop')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#FFFFFF" /><stop offset="1" stopColor="#DCE0EA" />
        </linearGradient>
        {filtered && <filter id={id('soft')} x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="3.2" /></filter>}
      </defs>
      <ellipse data-layer="ground-shadow" cx="100" cy="196" rx="66" ry="10" fill={url('ground')} />
      <g data-layer="figure">
      <g key={wiggle} className={wiggle > 0 ? 'character-ears--wiggle' : undefined}>
      {choice.variant === 'bear' && <g data-layer="ears-bear">
        <circle cx="54" cy="50" r="22" fill={url('hood')} />
        <circle cx="146" cy="50" r="22" fill={url('hood')} />
        <circle cx="54" cy="52" r="11" fill={inner} opacity=".7" />
        <circle cx="146" cy="52" r="11" fill={inner} opacity=".7" />
      </g>}
      {choice.variant === 'cat' && <g data-layer="ears-cat">
        <path d="M42 70 L52 18 Q56 10 63 16 L92 44 Z" fill={url('hood')} />
        <path d="M158 70 L148 18 Q144 10 137 16 L108 44 Z" fill={url('hood')} />
        <path d="M54 52 L57 28 L76 44Z" fill={inner} opacity=".7" />
        <path d="M146 52 L143 28 L124 44Z" fill={inner} opacity=".7" />
      </g>}
      {choice.variant === 'bunny' && <g data-layer="ears-bunny">
        <ellipse cx="72" cy="34" rx="15" ry="40" transform="rotate(-12 72 34)" fill={url('hood')} />
        <ellipse cx="128" cy="34" rx="15" ry="40" transform="rotate(12 128 34)" fill={url('hood')} />
        <ellipse cx="72" cy="36" rx="7" ry="28" transform="rotate(-12 72 36)" fill={inner} opacity=".7" />
        <ellipse cx="128" cy="36" rx="7" ry="28" transform="rotate(12 128 36)" fill={inner} opacity=".7" />
      </g>}
      </g>
      <g data-layer="body">
        <path d={HOOD} fill={url('hood')} />
        <path data-layer="hood-shade" d={HOOD} fill={url('ao')} />
        <ellipse data-layer="hood-rim" cx="100" cy="116" rx="55" ry="47" fill={shade} opacity={filtered ? '.45' : '.3'} filter={soft} />
        <ellipse data-layer="face" cx="100" cy="114" rx="50" ry="42" fill={url('face')} />
        <ellipse cx="64" cy="130" rx="9" ry="5" fill={CHEEK} opacity=".42" filter={soft} />
        <ellipse cx="136" cy="130" rx="9" ry="5" fill={CHEEK} opacity=".42" filter={soft} />
      </g>
      <g data-layer="expr" data-expr={exprKey}>
        <path d={e.brow} fill="none" stroke={INK} strokeWidth="3.5" strokeLinecap="round" />
        <g data-layer="eyes">
          <path d={e.eyeStroke} fill="none" stroke={INK} strokeWidth="5" strokeLinecap="round" />
          <path d={e.eyeFill} fill={INK} />
          <path d={e.shine} fill="#FFFFFF" />
        </g>
        <path d={e.mouthStroke} fill="none" stroke={INK} strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" />
        <path d={e.mouthFill} fill={e.mouthColor} />
      </g>
      <g data-layer="drawstrings">
        <path d="M90 158 L88 176 M110 158 L112 176" stroke={shade} strokeWidth="4" strokeLinecap="round" />
        <circle cx="88" cy="178" r="4.2" fill={glow} />
        <circle cx="112" cy="178" r="4.2" fill={glow} />
      </g>
      <g data-layer="gloss">
        {filtered && <ellipse cx="62" cy="60" rx="20" ry="10" transform="rotate(-32 62 60)" fill="#FFFFFF" opacity=".5" filter={soft} />}
        <ellipse cx="58" cy="58" rx="6" ry="3" transform="rotate(-32 58 58)" fill="#FFFFFF" opacity=".85" />
      </g>
      </g>
      {exprKey === 'sleepy' && <g data-layer="zz">
        <text x="150" y="74" fontSize="18" fill={c.dark} style={{ fontFamily: 'var(--font-display)' }}>z</text>
        <text x="162" y="58" fontSize="24" fill={c.dark} style={{ fontFamily: 'var(--font-display)' }}>Z</text>
      </g>}
      {state === 'work' && <g data-layer="prop-work">
        <path d="M60 156 H140 A6 6 0 0 1 146 162 V198 H54 V162 A6 6 0 0 1 60 156 Z" fill={url('prop')} />
        <path d="M54 192 H146 V198 H54 Z" fill="#000000" opacity=".08" />
        <path d="M95 174 a5 5 0 0 1 5 -3 a5 5 0 0 1 5 3 a5 5 0 0 1 -5 8 a5 5 0 0 1 -5 -8 Z" fill={c.dark} />
        <circle data-layer="left-hand" cx="58" cy="194" r="9" fill={url('hood')} />
        <circle data-layer="right-hand" cx="142" cy="194" r="9" fill={url('hood')} />
      </g>}
      {state === 'wait' && <g data-layer="prop-wait">
        <g data-layer="blanket">
        <path d="M14 178 Q58 154 100 170 Q142 186 186 164 L194 210 L6 210 Z" fill={url('prop')} />
        <path d="M14 178 Q58 154 100 170 Q142 186 186 164 L194 210 L6 210 Z" fill={url('ao')} />
        <circle cx="44" cy="188" r="5" fill={c.fill} />
        <circle cx="80" cy="194" r="5" fill={c.fill} />
        <circle cx="118" cy="192" r="5" fill={c.fill} />
        <circle cx="156" cy="190" r="5" fill={c.fill} />
        </g>
        <g data-layer="speech-bubble">
        <path d="M128 10 H182 A14 14 0 0 1 182 38 H150 L142 46 L144 38 H128 A14 14 0 0 1 128 10 Z" fill={url('prop')} />
        <circle cx="145" cy="24" r="2.6" fill="#8A5A00" /><circle cx="155" cy="24" r="2.6" fill="#8A5A00" /><circle cx="165" cy="24" r="2.6" fill="#8A5A00" />
        </g>
      </g>}
      {state === 'done' && <g data-layer="prop-done">
        <path data-layer="steam" d="M142 150 Q138 142 142 134 M154 150 Q150 142 154 134" fill="none" stroke="#B9AFB8" strokeWidth="2.5" strokeLinecap="round" opacity=".7" />
        <path d="M164 162 A8 8 0 0 1 164 178" fill="none" stroke="#DCE0EA" strokeWidth="4.5" strokeLinecap="round" />
        <path d="M132 156 H164 V180 A8 8 0 0 1 156 188 H140 A8 8 0 0 1 132 180 Z" fill={url('prop')} />
        <path d="M143 170 a3.5 3.5 0 0 1 5 -2 a3.5 3.5 0 0 1 5 2 q0 4 -5 7 q-5 -3 -5 -7 Z" fill={c.dark} />
        <circle cx="130" cy="176" r="8.5" fill={url('hood')} />
      </g>}
      {state === 'alert' && <g data-layer="prop-alert">
        <g data-layer="wave">
        <path d="M150 160 Q174 140 176 108" fill="none" stroke={shade} strokeWidth="18" strokeLinecap="round" />
        <path d="M148 158 Q170 140 172 110" fill="none" stroke={c.fill} strokeWidth="10" strokeLinecap="round" />
        <circle cx="176" cy="98" r="11" fill={url('hood')} />
        </g>
        <g data-layer="exclamation">
        <circle cx="34" cy="30" r="17" fill="#FFE9E2" />
        <text x="34" y="39" fontSize="24" textAnchor="middle" fill="#C9401F" style={{ fontFamily: 'var(--font-display)' }}>!</text>
        </g>
      </g>}
    </svg>
  )
}
