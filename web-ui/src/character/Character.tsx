import './character.css'
import { useVisibleMotion } from '../motion'
import {
  CHEEK, EXPRESSIONS, HAIR, INK, PALETTES, SKIN, STATE_EXPR,
  type CharacterChoice, type CharacterState,
} from './characterData'

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
  return (
    <svg ref={ref} className={`character character--state-${state}${size > 32 ? ' character--full-motion' : ''}`}
      viewBox="0 0 200 200" width={size} height={size} data-animated={!reduced} data-paused={!visible}
      data-variant={choice.variant} focusable="false" {...a11y}>
      <g data-layer="figure">
      <g key={wiggle} className={wiggle > 0 ? 'character-ears--wiggle' : undefined}>
      {choice.variant === 'bear' && <g data-layer="ears-bear">
        <circle cx="52" cy="52" r="22" fill={c.fill} stroke={INK} strokeWidth="4" />
        <circle cx="52" cy="52" r="11" fill={c.light} />
        <circle cx="148" cy="52" r="22" fill={c.fill} stroke={INK} strokeWidth="4" />
        <circle cx="148" cy="52" r="11" fill={c.light} />
      </g>}
      {choice.variant === 'cat' && <g data-layer="ears-cat">
        <path d="M44 86 L50 24 L94 52 Z" fill={c.fill} stroke={INK} strokeWidth="4" strokeLinejoin="round" />
        <path d="M56 70 L59 42 L80 56 Z" fill={c.light} />
        <path d="M156 86 L150 24 L106 52 Z" fill={c.fill} stroke={INK} strokeWidth="4" strokeLinejoin="round" />
        <path d="M144 70 L141 42 L120 56 Z" fill={c.light} />
      </g>}
      {choice.variant === 'bunny' && <g data-layer="ears-bunny">
        <ellipse cx="70" cy="42" rx="15" ry="38" transform="rotate(-16 70 42)" fill={c.fill} stroke={INK} strokeWidth="4" />
        <ellipse cx="70" cy="44" rx="7" ry="26" transform="rotate(-16 70 44)" fill={c.light} />
        <ellipse cx="130" cy="42" rx="15" ry="38" transform="rotate(16 130 42)" fill={c.fill} stroke={INK} strokeWidth="4" />
        <ellipse cx="130" cy="44" rx="7" ry="26" transform="rotate(16 130 44)" fill={c.light} />
      </g>}
      </g>
      <g data-layer="body">
        <path d="M36 202 C36 160 62 140 100 140 C138 140 164 160 164 202 Z" fill={c.fill} stroke={INK} strokeWidth="4" strokeLinejoin="round" />
        <circle cx="100" cy="100" r="68" fill={c.fill} stroke={INK} strokeWidth="4" />
        <circle cx="100" cy="108" r="56" fill={c.light} />
        <circle cx="100" cy="108" r="49" fill={SKIN} stroke={INK} strokeWidth="3.5" />
        <path d="M58 98 Q66 64 100 66 Q134 64 142 98 Q128 84 114 88 Q104 76 96 86 Q84 78 70 88 Q62 90 58 98 Z" fill={HAIR} stroke={INK} strokeWidth="3" strokeLinejoin="round" />
        <ellipse cx="70" cy="125" rx="10" ry="6.5" fill={CHEEK} opacity="0.6" />
        <ellipse cx="130" cy="125" rx="10" ry="6.5" fill={CHEEK} opacity="0.6" />
        <path d="M88 157 L86 180 M112 157 L114 180" stroke="#FFFFFF" strokeWidth="3.5" strokeLinecap="round" />
        <circle cx="86" cy="182" r="4" fill="#FFFFFF" stroke={INK} strokeWidth="2" />
        <circle cx="114" cy="182" r="4" fill="#FFFFFF" stroke={INK} strokeWidth="2" />
      </g>
      <g data-layer="expr" data-expr={exprKey}>
        <path d={e.brow} fill="none" stroke={INK} strokeWidth="3.5" strokeLinecap="round" />
        <g data-layer="eyes">
          <path d={e.eyeStroke} fill="none" stroke={INK} strokeWidth="4" strokeLinecap="round" />
          <path d={e.eyeFill} fill={INK} />
          <path d={e.shine} fill="#FFFFFF" />
        </g>
        <path d={e.mouthStroke} fill="none" stroke={INK} strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" />
        <path d={e.mouthFill} fill={e.mouthColor} stroke={INK} strokeWidth="3" strokeLinejoin="round" />
      </g>
      </g>
      {exprKey === 'sleepy' && <g data-layer="zz">
        <text x="150" y="74" fontSize="18" fill={c.dark} style={{ fontFamily: 'var(--font-display)' }}>z</text>
        <text x="162" y="58" fontSize="24" fill={c.dark} style={{ fontFamily: 'var(--font-display)' }}>Z</text>
      </g>}
      {state === 'work' && <g data-layer="prop-work">
        <path d="M60 150 H140 A6 6 0 0 1 146 156 V192 H54 V156 A6 6 0 0 1 60 150 Z" fill="#E9ECF5" stroke={INK} strokeWidth="4" strokeLinejoin="round" />
        <path d="M95 168 a5 5 0 0 1 5 -3 a5 5 0 0 1 5 3 a5 5 0 0 1 -5 8 a5 5 0 0 1 -5 -8 Z" fill={c.dark} />
        <circle data-layer="left-hand" cx="58" cy="188" r="9" fill={SKIN} stroke={INK} strokeWidth="3" />
        <circle data-layer="right-hand" cx="142" cy="188" r="9" fill={SKIN} stroke={INK} strokeWidth="3" />
      </g>}
      {state === 'wait' && <g data-layer="prop-wait">
        <g data-layer="blanket">
        <path d="M16 174 Q58 150 100 166 Q142 182 184 160 L192 204 L8 204 Z" fill="#FFFFFF" stroke={INK} strokeWidth="4" strokeLinejoin="round" />
        <circle cx="44" cy="182" r="5" fill={c.fill} />
        <circle cx="80" cy="188" r="5" fill={c.fill} />
        <circle cx="118" cy="186" r="5" fill={c.fill} />
        <circle cx="156" cy="184" r="5" fill={c.fill} />
        </g>
        <g data-layer="speech-bubble">
        <path d="M128 16 H182 A14 14 0 0 1 182 44 H150 L142 52 L144 44 H128 A14 14 0 0 1 128 16 Z" fill="#FFFFFF" stroke={INK} strokeWidth="3" strokeLinejoin="round" />
        <circle cx="145" cy="30" r="2.6" fill="#8A5A00" /><circle cx="155" cy="30" r="2.6" fill="#8A5A00" /><circle cx="165" cy="30" r="2.6" fill="#8A5A00" />
        </g>
      </g>}
      {state === 'done' && <g data-layer="prop-done">
        <path data-layer="steam" d="M138 146 Q134 138 138 130 M150 146 Q146 138 150 130" fill="none" stroke={INK} strokeWidth="2.5" strokeLinecap="round" opacity="0.45" />
        <path d="M128 152 H160 V176 A8 8 0 0 1 152 184 H136 A8 8 0 0 1 128 176 Z" fill="#FFFFFF" stroke={INK} strokeWidth="4" strokeLinejoin="round" />
        <path d="M160 158 A8 8 0 0 1 160 174" fill="none" stroke={INK} strokeWidth="4" strokeLinecap="round" />
        <path d="M139 166 a3.5 3.5 0 0 1 5 -2 a3.5 3.5 0 0 1 5 2 q0 4 -5 7 q-5 -3 -5 -7 Z" fill={c.dark} />
        <circle cx="126" cy="172" r="8.5" fill={SKIN} stroke={INK} strokeWidth="3" />
      </g>}
      {state === 'alert' && <g data-layer="prop-alert">
        <g data-layer="wave">
        <path d="M146 156 Q170 136 172 104" fill="none" stroke={INK} strokeWidth="22" strokeLinecap="round" />
        <path d="M146 156 Q170 136 172 104" fill="none" stroke={c.fill} strokeWidth="14" strokeLinecap="round" />
        <circle cx="172" cy="94" r="11" fill={SKIN} stroke={INK} strokeWidth="3.5" />
        </g>
        <g data-layer="exclamation">
        <circle cx="40" cy="40" r="17" fill="#FFE9E2" stroke="#C9401F" strokeWidth="3" />
        <text x="40" y="49" fontSize="24" textAnchor="middle" fill="#C9401F" style={{ fontFamily: 'var(--font-display)' }}>!</text>
        </g>
      </g>}
    </svg>
  )
}
