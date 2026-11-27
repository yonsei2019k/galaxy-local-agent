// 집순이: an animal-ear hood mascot drawn like the approved 2026-11-27 concept critter() (3D clay shading).
// Choice ids, labels and palette names are unchanged so stored choices keep working.
export type CharacterVariant = 'bear' | 'cat' | 'bunny'
export type CharacterColor = 'butter' | 'peach' | 'strawberry' | 'sky' | 'matcha' | 'grey' | 'cocoa' | 'snow'
export type CharacterExpr = 'basic' | 'smile' | 'focus' | 'surprise' | 'sleepy' | 'wink'
export type CharacterState = 'none' | 'work' | 'wait' | 'done' | 'alert'

export interface CharacterChoice { variant: CharacterVariant; color: CharacterColor; expr: CharacterExpr }

export function isCharacterChoice(value: unknown): value is CharacterChoice {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const choice = value as Partial<CharacterChoice>
  return VARIANTS.some(item => item.id === choice.variant) &&
    COLORS.some(item => item.id === choice.color) && EXPR_OPTIONS.some(item => item.id === choice.expr)
}

export const DEFAULT_CHARACTER: CharacterChoice = { variant: 'bear', color: 'butter', expr: 'basic' }

/** Facial features only (eyes, brows, mouth); shapes have no outline in the 3D clay drawing. */
export const INK = '#1D1B22'
export const CHEEK = '#FF7D93'

/** Linear blend of two #RRGGBB colors; t=0 keeps a, t=1 gives b. */
export function mixHex(a: string, b: string, t: number): string {
  const channel = (hex: string, index: number) => parseInt(hex.slice(1 + index * 2, 3 + index * 2), 16)
  return '#' + [0, 1, 2].map(index => Math.round(channel(a, index) + (channel(b, index) - channel(a, index)) * t)
    .toString(16).padStart(2, '0')).join('').toUpperCase()
}

export interface Palette { fill: string; dark: string; light: string }

export const PALETTES: Record<CharacterColor, Palette> = {
  butter: { fill: '#FFD970', dark: '#C9952A', light: '#FFF4CC' },
  peach: { fill: '#FFB797', dark: '#D9785A', light: '#FFE8DE' },
  strawberry: { fill: '#FFB5CC', dark: '#D9728F', light: '#FFE8F0' },
  sky: { fill: '#ACD5FF', dark: '#5B93CC', light: '#E6F3FF' },
  matcha: { fill: '#C4E39E', dark: '#6F9A45', light: '#EEF7E2' },
  grey: { fill: '#DCD8D2', dark: '#8C857A', light: '#F2F0EC' },
  cocoa: { fill: '#CFA484', dark: '#7E5C44', light: '#EEDFD3' },
  snow: { fill: '#FFFFFF', dark: '#8E98AB', light: '#F2F4F9' },
}

export interface ExprPaths {
  brow: string; eyeStroke: string; eyeFill: string; shine: string
  mouthStroke: string; mouthFill: string; mouthColor: string
}

/** Path for a filled ellipse centred on (cx, cy). */
function oval(cx: number, cy: number, rx: number, ry: number): string {
  return `M${cx - rx} ${cy} a${rx} ${ry} 0 1 0 ${rx * 2} 0 a${rx} ${ry} 0 1 0 ${-rx * 2} 0`
}
/** Big glossy eye from the concept: a dark oval with one white sparkle at the upper left. */
const eye = (cx: number, cy: number, rx = 8.5, ry = 11) => ({ fill: oval(cx, cy, rx, ry), shine: oval(cx - 2.6, cy - 4, 3.2, 3.2) })
const eyes = (left: { fill: string; shine: string }, right: { fill: string; shine: string }) =>
  ({ eyeFill: `${left.fill} ${right.fill}`, shine: `${left.shine} ${right.shine}` })
const arc = (x: number, y: number) => `M${x - 9} ${y + 3} Q${x} ${y - 9} ${x + 9} ${y + 3}`

export const EXPRESSIONS: Record<CharacterExpr, ExprPaths> = {
  basic: { brow: '', eyeStroke: '', ...eyes(eye(80, 112), eye(120, 112)), mouthStroke: 'M94 129 Q100 134 106 129', mouthFill: '', mouthColor: 'none' },
  smile: { brow: '', eyeStroke: `${arc(80, 116)} ${arc(120, 116)}`, eyeFill: '', shine: '', mouthStroke: '', mouthFill: 'M92 128 Q100 138 108 128 Z', mouthColor: INK },
  focus: { brow: '', eyeStroke: '', ...eyes(eye(83, 108, 8, 10.5), eye(123, 108, 8, 10.5)), mouthStroke: 'M94 132 L106 130', mouthFill: '', mouthColor: 'none' },
  surprise: { brow: '', eyeStroke: '', ...eyes(eye(80, 112, 10, 12.5), eye(120, 112, 10, 12.5)), mouthStroke: '', mouthFill: 'M95 134 a5 6 0 1 0 10 0 a5 6 0 1 0 -10 0', mouthColor: INK },
  sleepy: { brow: '', eyeStroke: 'M71 116 Q80 121 89 116 M111 116 Q120 121 129 116', eyeFill: '', shine: '', mouthStroke: '', mouthFill: 'M96 132 a4 3 0 1 0 8 0 a4 3 0 1 0 -8 0', mouthColor: INK },
  wink: { brow: '', eyeStroke: arc(120, 116), ...eyes(eye(80, 112), { fill: '', shine: '' }), mouthStroke: 'M93 130 Q100 136 107 130', mouthFill: '', mouthColor: 'none' },
}

/** A working state shows its own face; the chosen expression applies when there is no state. */
export const STATE_EXPR: Record<Exclude<CharacterState, 'none'>, CharacterExpr> = {
  work: 'focus', wait: 'sleepy', done: 'smile', alert: 'surprise',
}

export const VARIANTS: { id: CharacterVariant; label: string }[] = [
  { id: 'bear', label: '곰돌이' }, { id: 'cat', label: '고양이' }, { id: 'bunny', label: '토끼' },
]
export const COLORS: { id: CharacterColor; label: string }[] = [
  { id: 'butter', label: '버터' }, { id: 'peach', label: '복숭아' }, { id: 'strawberry', label: '딸기우유' },
  { id: 'sky', label: '하늘' }, { id: 'matcha', label: '말차' }, { id: 'grey', label: '회색곰' },
  { id: 'cocoa', label: '코코아' }, { id: 'snow', label: '눈송이' },
]
export const EXPR_OPTIONS: { id: CharacterExpr; label: string }[] = [
  { id: 'basic', label: '기본' }, { id: 'smile', label: '웃음' }, { id: 'focus', label: '집중' },
  { id: 'surprise', label: '놀람' }, { id: 'sleepy', label: '졸림' }, { id: 'wink', label: '윙크' },
]
