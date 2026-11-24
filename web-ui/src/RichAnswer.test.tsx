import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { expect, test } from 'vitest'
import { RichAnswer } from './RichAnswer'

const render = (text: string) => renderToStaticMarkup(createElement(RichAnswer, { text }))

test('renders mobile table, lists, headings and unfinished streaming code', () => {
  const html = render('# 결과\n- 첫 항목\n- 둘째 항목\n\n| 이름 | 값 |\n| --- | --- |\n| **합계** | 10 |\n\n```js\nconst result =')
  expect(html).toContain('<h2>결과</h2>')
  expect(html).toContain('<ul><li>첫 항목</li><li>둘째 항목</li></ul>')
  expect(html).toContain('class="answer-table"')
  expect(html).toContain('<strong>합계</strong>')
  expect(html).toContain('<code>const result =</code>')
})

test('does not fetch remote, file, content or data images from model text', () => {
  const html = render('<script>alert(1)</script>\n![외부](https://invalid.example/track.png)\n![기기](content://private/x.png)\n![파일](file:///private.png)\n![data](data:image/png;base64,abc)\n[unsafe](javascript:alert)')
  expect(html).not.toContain('<img')
  expect(html).not.toContain('<script')
  expect(html).not.toContain('href=')
  expect(html).toContain('외부 · 이미지를 표시할 수 없습니다')
  expect(render('![캐릭터](./ziphap-mascot.png)')).toContain('alt="캐릭터"')
  expect(render('[출처](https://example.org/)')).toContain('href="https://example.org/"')
})

test('makes a chart only from a complete, valid label/value block', () => {
  const input = '```chart\n| 분류 | 수량 |\n| --- | --- |\n| 사과 | 4 |'
  expect(render(input)).not.toContain('<meter')
  expect(render(input + '\n```')).toContain('<meter min="0" max="4" value="4"')
  expect(render(input.replace('4', 'NaN') + '\n```')).not.toContain('<meter')
})

const quote = {
  name: '해안가 시험 숙소', room: '바다 전망 객실', area: '테스트 지역',
  url: 'https://example.org/stay/a', checkedAt: '2026-11-24T20:10:00+09:00',
  online: 179000, onsite: 40000, total: 219000, available: true, cancellation: '입실 3일 전부터 일부 환불'
}
const stayBlock = (stays: object[], checkIn = '2026-12-01') =>
  '```stays\n' + JSON.stringify({ version: 1, currency: 'KRW', checkIn, checkOut: '2026-12-02',
    guests: 4, party: '성인 2명, 아동 2명', stays }) + '\n```'

test('compares a shared party with only arithmetically complete totals ranked first', () => {
  const html = render(stayBlock([
    { ...quote, name: '요금 미확인', online: 80000, onsite: null, total: 80000 },
    quote, { ...quote, name: '총액이 더 낮은 곳', online: 101000, onsite: 10000, total: 111000 }
  ]))
  expect(html).toContain('1박 · 4명 (성인 2명, 아동 2명)')
  expect(html).toContain('총액 미확인')
  expect(html).toContain('현장 미확인')
  expect(html).toContain('219,000원')
  expect(html.indexOf('총액이 더 낮은 곳')).toBeLessThan(html.indexOf('해안가 시험 숙소'))
  expect(html.indexOf('해안가 시험 숙소')).toBeLessThan(html.indexOf('요금 미확인'))
})

test('shows a compact comparison and degrades invalid or incomplete results safely', () => {
  const html = render(stayBlock(Array.from({ length: 4 }, (_, i) => ({ ...quote, name: '시험 숙소 ' + i }))))
  expect(html).toContain('후보 4곳 모두 보기')
  expect(html).not.toContain('시험 숙소 3')
  for (const url of ['javascript:alert(1)', 'https://user:secret@example.org/a', 'https://127.0.0.1/a']) {
    const invalid = render(stayBlock([{ ...quote, url }]))
    expect(invalid).toContain('숙소 비교표를 표시할 수 없어요')
    expect(invalid).not.toContain('href=')
  }
  expect(render(stayBlock([quote], '2026-11-31'))).not.toContain('숙소 상세')
})
