// Port of the owner-approved 집순이 drawing (Character.dc.html). Values are copied as-is.
export type CharacterVariant = 'bear' | 'cat' | 'bunny'
export type CharacterColor = 'butter' | 'peach' | 'strawberry' | 'sky' | 'matcha' | 'grey' | 'cocoa' | 'snow'
export type CharacterExpr = 'basic' | 'smile' | 'focus' | 'surprise' | 'sleepy' | 'wink'
export type CharacterState = 'none' | 'work' | 'wait' | 'done' | 'alert'

export interface CharacterChoice { variant: CharacterVariant; color: CharacterColor; expr: CharacterExpr }

export const DEFAULT_CHARACTER: CharacterChoice = { variant: 'bear', color: 'butter', expr: 'basic' }

export const INK = '#3B2F3A'
export const SKIN = '#FFEDE3'
export const HAIR = '#7A5240'
export const CHEEK = '#FF8FA8'

export interface Palette { fill: string; dark: string; light: string }

export const PALETTES: Record<CharacterColor, Palette> = {
  butter: { fill: '#FFE08A', dark: '#C9952A', light: '#FFF4CC' },
  peach: { fill: '#FFC6AE', dark: '#D9785A', light: '#FFE8DE' },
  strawberry: { fill: '#FFC9DA', dark: '#D9728F', light: '#FFE8F0' },
  sky: { fill: '#C4E2FF', dark: '#5B93CC', light: '#E6F3FF' },
  matcha: { fill: '#D4EAB4', dark: '#6F9A45', light: '#EEF7E2' },
  grey: { fill: '#DCD8D2', dark: '#8C857A', light: '#F2F0EC' },
  cocoa: { fill: '#D2AE93', dark: '#7E5C44', light: '#EEDFD3' },
  snow: { fill: '#FFFFFF', dark: '#8E98AB', light: '#F2F4F9' },
}

export interface ExprPaths {
  brow: string; eyeStroke: string; eyeFill: string; shine: string
  mouthStroke: string; mouthFill: string; mouthColor: string
}

export const EXPRESSIONS: Record<CharacterExpr, ExprPaths> = {
  basic: { brow: '', eyeStroke: 'M71 108 Q80 115 89 108 M111 108 Q120 115 129 108', eyeFill: '', shine: '', mouthStroke: 'M92 124 Q96 129 100 124 Q104 129 108 124', mouthFill: '', mouthColor: 'none' },
  smile: { brow: '', eyeStroke: 'M71 112 Q80 102 89 112 M111 112 Q120 102 129 112', eyeFill: '', shine: '', mouthStroke: '', mouthFill: 'M89 121 Q100 137 111 121 Z', mouthColor: '#D9576F' },
  focus: { brow: 'M72 96 L87 100 M128 96 L113 100', eyeStroke: '', eyeFill: 'M75 110 a5 6 0 1 0 10 0 a5 6 0 1 0 -10 0 M115 110 a5 6 0 1 0 10 0 a5 6 0 1 0 -10 0', shine: 'M80 107 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0 M120 107 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0', mouthStroke: 'M95 126 L105 126', mouthFill: '', mouthColor: 'none' },
  surprise: { brow: 'M72 94 Q80 89 88 94 M112 94 Q120 89 128 94', eyeStroke: '', eyeFill: 'M73 109 a7 7.5 0 1 0 14 0 a7 7.5 0 1 0 -14 0 M113 109 a7 7.5 0 1 0 14 0 a7 7.5 0 1 0 -14 0', shine: 'M79 105 a2.4 2.4 0 1 0 4.8 0 a2.4 2.4 0 1 0 -4.8 0 M119 105 a2.4 2.4 0 1 0 4.8 0 a2.4 2.4 0 1 0 -4.8 0', mouthStroke: '', mouthFill: 'M96 127 a4 5 0 1 0 8 0 a4 5 0 1 0 -8 0', mouthColor: '#3B2F3A' },
  sleepy: { brow: '', eyeStroke: 'M72 110 Q80 113 88 110 M112 110 Q120 113 128 110', eyeFill: '', shine: '', mouthStroke: 'M96 125 Q100 128 104 125', mouthFill: '', mouthColor: 'none' },
  wink: { brow: '', eyeStroke: 'M71 112 Q80 102 89 112', eyeFill: 'M115 110 a5 6 0 1 0 10 0 a5 6 0 1 0 -10 0', shine: 'M120 107 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0', mouthStroke: 'M91 123 Q100 131 109 123', mouthFill: '', mouthColor: 'none' },
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
