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
