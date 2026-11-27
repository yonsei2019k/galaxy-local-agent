// @vitest-environment jsdom
import { act, createElement, Fragment } from 'react'
import type { ReactElement } from 'react'
import { createRoot } from 'react-dom/client'
import { renderToStaticMarkup } from 'react-dom/server'
import { expect, test } from 'vitest'
import { RichAnswer } from '../RichAnswer'
import { WorkCard } from '../work/WorkCard'
import { StickerText, stickerFor, withStickers, withoutEmoji } from './Sticker'
import { STICKERS } from './generated'

// 이모티콘은 이스케이프로 적는다(소스 검사에서 이모티콘 글자 0개를 유지).
const CAP = '\u{1F393}', HEART = '❤', VS16 = '️', CAL = '\u{1F4C5}', UNICORN = '\u{1F984}'
;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
function render(element: ReactElement) {
  const container = document.createElement('div')
  document.body.append(container)
  act(() => createRoot(container).render(element))
  return { container }
}
const markup = (text: string) => renderToStaticMarkup(createElement(Fragment, null, ...withStickers(text)))

test('maps common emoji and their variation-selector and skin-tone forms to stickers', () => {
  expect(stickerFor(CAP)).toBe('gradCap')
  expect(stickerFor(HEART)).toBe('heart')
  expect(stickerFor(HEART + VS16)).toBe('heart')
  expect(stickerFor('\u{2705}' + VS16)).toBe('check')
  expect(stickerFor('\u{1F9D1}\u200D\u{1F393}')).toBe('gradCap')
  expect(stickerFor('\u{1F6CF}' + VS16)).toBe('bed')
  expect(stickerFor(UNICORN)).toBeUndefined()
  for (const name of ['calendar', 'check', 'sparkle', 'house', 'mapPin', 'money', 'warning', 'clock', 'phone', 'mail',
    'gift', 'food', 'car', 'train', 'book', 'party'] as const) expect(STICKERS[name].label.length).toBeGreaterThan(0)
})

test('draws a sticker with a Korean label and keeps the original emoji as selectable text', () => {
  const html = markup(`${CAP} 졸업 축하해요`)
  expect(html).toContain('role="img" aria-label="졸업" data-sticker="gradCap"')
  expect(html).toContain(`<span class="sticker-source" aria-hidden="true">${CAP}</span>`)
  expect(html).toContain(' 졸업 축하해요')
})

test('an emoji without its own sticker becomes a neutral sparkle that still announces the original emoji', () => {
  const html = markup(`새 소식 ${UNICORN}`)
  expect(html).toContain(`data-sticker="neutral"`)
  expect(html).toContain(`aria-label="${UNICORN}"`)
})

test('leaves plain text and text symbols such as copyright, trademark and rating stars untouched', () => {
  expect(withStickers('그냥 글이에요')).toEqual(['그냥 글이에요'])
  expect(withStickers('© 2026 ™ ★ 4.5')).toEqual(['© 2026 ™ ★ 4.5'])
  expect(withStickers('1. 숫자 목록 # 제목')).toEqual(['1. 숫자 목록 # 제목'])
})

test('rich answers render stickers in text, lists, bold text and links while the visible text keeps every character', () => {
  const text = `${CAP} 일정을 찾았어요\n- ${CAL} **12월 20일 ${HEART + VS16}**\n- [안내 ${CAL}](https://example.org/)`
  const { container } = render(createElement(RichAnswer, { text }))
  expect(container.querySelectorAll('.sticker')).toHaveLength(4)
  expect(container.querySelector('strong .sticker[data-sticker="heart"]')).not.toBeNull()
  expect(container.querySelector('a .sticker[data-sticker="calendar"]')).not.toBeNull()
  // Copying the answer (selection) still yields the original characters; the message text itself is untouched.
  expect(container.textContent).toBe(`${CAP} 일정을 찾았어요${CAL} 12월 20일 ${HEART + VS16}안내 ${CAL}`)
  expect(container.querySelector('code')).toBeNull()
})

test('code spans keep emoji as written', () => {
  const html = renderToStaticMarkup(createElement(RichAnswer, { text: `\`${CAP}\`` }))
  expect(html).toContain(`<code>${CAP}</code>`)
  expect(html).not.toContain('sticker')
})

test('work card titles draw stickers but keep the task label as their text', () => {
  const task = { id: 't1', phase: 'completed', label: `${CAP} 졸업식 문자`, detail: '', canCancel: false }
  const { container } = render(createElement(WorkCard, { task, onOpen: () => {} }))
  expect(container.querySelector('.work-title .sticker[aria-label="졸업"]')).not.toBeNull()
  expect(container.querySelector('.work-title')?.textContent).toBe(`${CAP} 졸업식 문자`)
})

test('splits ZWJ families, skin tones, flags and keycaps into one sticker each', () => {
  const family = '\u{1F468}\u200D\u{1F469}\u200D\u{1F467}', thumb = '\u{1F44D}\u{1F3FD}', flag = '\u{1F1F0}\u{1F1F7}', keycap = '1\uFE0F\u20E3'
  const nodes = withStickers(`가${family}나${thumb}${flag}다${keycap}${CAP}${CAP}`)
  const stickers = nodes.filter(node => typeof node !== 'string') as { props: { emoji: string } }[]
  expect(stickers.map(node => node.props.emoji)).toEqual([family, thumb, flag, keycap, CAP, CAP])
  expect(nodes.filter(node => typeof node === 'string')).toEqual(['가', '나', '다'])
  expect(stickerFor(thumb)).toBeUndefined()
  // A plain digit or hash is text, not a keycap.
  expect(withStickers('1번 #2')).toEqual(['1번 #2'])
})

test('StickerText draws stickers for any host text and tooltips drop emoji instead of showing system glyphs', () => {
  const html = renderToStaticMarkup(createElement(StickerText, { text: `${CAL} 회의 ${HEART + VS16}` }))
  expect(html).toContain('data-sticker="calendar"')
  expect(html).toContain('data-sticker="heart"')
  expect(withoutEmoji(`${CAP} 졸업식 문자 ${HEART + VS16}`)).toBe('졸업식 문자')
  expect(withoutEmoji('이모티콘 없음')).toBe('이모티콘 없음')
})

test('emoji whose meaning differs from every sticker stay unmapped and keep their own name', () => {
  // 비행기·이륙·착륙, 금지·통행금지, 물음표, 신용카드, 엄지·OK, 클립보드, 학교, 나침반, 텐트·캠핑, 리본
  for (const emoji of ['\u{2708}', '\u{1F6EB}', '\u{1F6EC}', '\u{26D4}', '\u{1F6AB}', '\u{2753}', '\u{1F4B3}',
    '\u{1F44D}', '\u{1F197}', '\u{1F4CB}', '\u{1F3EB}', '\u{1F9ED}', '\u{26FA}', '\u{1F3D5}', '\u{1F380}']) {
    expect(stickerFor(emoji)).toBeUndefined()
  }
  const html = renderToStaticMarkup(createElement(StickerText, { text: '\u{2708}\u{FE0F} 출발' }))
  expect(html).not.toContain('data-sticker="train"')
})
