import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { expect, test } from 'vitest'
import { Icon, type IconName } from '../Icon'
import { ICONS, ILLUSTRATIONS } from './generated'
import { Illustration, type IllustrationName } from './Illustration'

test('every icon renders a decorative duotone svg that follows currentColor', () => {
  for (const name of Object.keys(ICONS) as IconName[]) {
    const html = renderToStaticMarkup(createElement(Icon, { name, className: 'x' }))
    expect(html).toContain('aria-hidden="true"')
    expect(html).toContain('class="x"')
    expect(html).toContain('class="icon-line"')
    expect(html).toContain('stroke="currentColor"')
    expect(html).toContain('stroke-width="1.8"')
  }
  const chat = renderToStaticMarkup(createElement(Icon, { name: 'chat' }))
  expect(chat).toContain('class="icon-soft"')
  expect(chat).toContain('fill="currentColor"')
})

test('illustrations are labelled only when a title is given', () => {
  const names = Object.keys(ILLUSTRATIONS) as IllustrationName[]
  expect(names).toHaveLength(12)
  const titled = renderToStaticMarkup(createElement(Illustration, { name: 'emptyChat', title: '빈 대화' }))
  expect(titled).toContain('role="img"')
  expect(titled).toContain('aria-label="빈 대화"')
  expect(titled).not.toContain('aria-hidden')
  const decorative = renderToStaticMarkup(createElement(Illustration, { name: 'error' }))
  expect(decorative).toContain('aria-hidden="true"')
  expect(decorative).not.toContain('role=')
  expect(decorative).not.toContain('currentColor')
})
