import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { parseStayComparison, StayComparison } from './StayComparison'
import type { StayPhotoRequest } from './StayComparison'
import { parseServiceResult, ServiceResultCard } from './ServiceResultCard'
import { withStickers } from './icons/Sticker'
import './answer.css'

const inlineToken = /(!?)\[([^\]\n]*)\]\(([^\s()]+)\)|`([^`\n]+)`|\*\*([^*\n]+)\*\*/g
const imageRoot = 'https://appassets.androidplatform.net/assets/react/'
const localImage = /^(?:\.\/)?(?:[a-z0-9_-]+\/)*[a-z0-9_-]+\.(?:png|jpe?g|webp)$/i

function AnswerImage({ source, alternate }: { source: string; alternate: string }) {
  const [failed, setFailed] = useState(false)
  const path = source.startsWith(imageRoot) ? source.slice(imageRoot.length) : source
  const label = alternate || '답변 이미지'
  if (!localImage.test(path) || failed) {
    return <span className="answer-image-error">{label} · 이미지를 표시할 수 없습니다</span>
  }
  return <img src={imageRoot + path.replace(/^\.\//, '')} alt={label} loading="lazy"
    referrerPolicy="no-referrer" onError={() => setFailed(true)} />
}

function inline(text: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let end = 0
  for (const match of text.matchAll(inlineToken)) {
    const index = match.index
    nodes.push(...withStickers(text.slice(end, index), end))
    if (match[1] === '!') {
      nodes.push(<AnswerImage key={index + match[3]} source={match[3]} alternate={match[2]} />)
    } else if (match[3]) {
      let href: string | undefined
      try {
        const url = new URL(match[3])
        if (['https:', 'http:'].includes(url.protocol) && !url.username && !url.password &&
          url.origin !== new URL(imageRoot).origin) href = url.href
      } catch { /* Keep malformed URLs as text. */ }
      nodes.push(href ? <a key={index} href={href} rel="noreferrer noopener" referrerPolicy="no-referrer">{withStickers(match[2] || href, index)}</a> : withStickers(match[2] || match[0], index))
    } else if (match[4]) {
      nodes.push(<code key={index}>{match[4]}</code>)
    } else {
      nodes.push(<strong key={index}>{withStickers(match[5], index)}</strong>)
    }
    end = index + match[0].length
  }
  nodes.push(...withStickers(text.slice(end), end))
  return nodes
}

const cells = (line: string) => line.trim().replace(/^\||\|$/g, '').split(/(?<!\\)\|/).map(cell => cell.trim().replace(/\\\|/g, '|'))
const delimiter = (line: string) => line.includes('|') && cells(line).every(cell => /^:?-{3,}:?$/.test(cell))
const listItem = /^\s*(?:(\d{1,9})[.)]|[-*+])\s+(.+)$/
const heading = /^\s{0,3}(#{1,6})\s+(.+)$/
const fence = /^\s{0,3}(`{3,}|~{3,})\s*([\w+-]*)\s*$/

function chart(code: string): ReactNode | null {
  // A bounded label/value table in a chart fence. All labels remain React text.
  const lines = code.trim().split('\n')
  if (lines.length < 2 || lines.length > 21 || !delimiter(lines[1])) return null
  const header = cells(lines[0])
  const rows = lines.slice(2).map(cells)
  if (header.length !== 2 || rows.length === 0 || rows.some(row =>
    row.length !== 2 || !row[0] || row[0].length > 100 || !/^-?\d+(?:\.\d+)?$/.test(row[1]) ||
    !Number.isFinite(Number(row[1])) || Number(row[1]) < 0)) return null
  const max = Math.max(1, ...rows.map(row => Number(row[1])))
  return <figure className="answer-chart"><figcaption>{withStickers(header.join(' · '))}</figcaption>
    <ul>{rows.map(([name, value], i) => <li key={i}>
      <span>{withStickers(name)}</span><span>{value}</span>
      <meter min={0} max={max} value={Number(value)} aria-label={name} />
    </li>)}</ul></figure>
}

function renderAnswer(text: string, onPrepareStay?: (text: string) => void, photo?: StayPhotoRequest): ReactNode[] {
  const lines = text.split(/\r?\n/)
  const result: ReactNode[] = []
  let i = 0
  let firstStayBlock = true
  let firstMapBlock = true
  while (i < lines.length) {
    const key = i
    const line = lines[i++]
    if (!line.trim()) continue
    const opening = line.match(fence)
    if (opening) {
      const body: string[] = []
      const closing = new RegExp('^ {0,3}' + opening[1][0] + '{' + opening[1].length + ',}\\s*$')
      while (i < lines.length && !closing.test(lines[i])) body.push(lines[i++])
      const closed = i < lines.length
      if (closed) i++
      const code = body.join('\n')
      if (opening[2] === 'service-result') {
        const data = closed ? parseServiceResult(code) : null
        if (data) {
          result.push(<ServiceResultCard key={key} result={data} photo={firstMapBlock ? photo : undefined} onAsk={onPrepareStay} />)
          if (data.kind === 'camp_sites') firstMapBlock = false
        }
        continue
      }
      if (opening[2] === 'stays') {
        const data = closed ? parseStayComparison(code) : null
        result.push(data ? <StayComparison key={key} data={data} onPrepare={onPrepareStay} photo={firstStayBlock ? photo : undefined} /> :
          <p key={key}>숙소 비교표를 표시할 수 없어요.</p>)
        firstStayBlock = false
        continue
      }
      result.push(<div key={key}>{(closed && opening[2] === 'chart' && chart(code)) ||
        <pre tabIndex={0}><code>{code}</code></pre>}</div>)
      continue
    }
    const title = line.match(heading)
    if (title) {
      const Tag = (['h2', 'h3', 'h4', 'h5', 'h6', 'h6'] as const)[title[1].length - 1]
      result.push(<Tag key={key}>{inline(title[2])}</Tag>)
      continue
    }
    if (line.includes('|') && i < lines.length && delimiter(lines[i])) {
      const headers = cells(line)
      if (headers.length > 1 && headers.length === cells(lines[i]).length) {
        i++
        const rows: string[][] = []
        while (i < lines.length && lines[i].includes('|') && cells(lines[i]).length === headers.length) {
          rows.push(cells(lines[i++]))
        }
        result.push(<div key={key} className="answer-table" role="region" aria-label="답변 표" tabIndex={0}>
          <table><thead><tr>{headers.map((value, col) => <th scope="col" key={col}>{inline(value)}</th>)}</tr></thead>
            <tbody>{rows.map((row, index) => <tr key={index}>{row.map((value, col) => <td key={col}>{inline(value)}</td>)}</tr>)}</tbody>
          </table></div>)
        continue
      }
    }
    const item = line.match(listItem)
    if (item) {
      const ordered = Boolean(item[1])
      const items = [item[2]]
      while (i < lines.length) {
        const next = lines[i].match(listItem)
        if (!next || Boolean(next[1]) !== ordered) break
        items.push(next[2])
        i++
      }
      const children = items.map((value, index) => <li key={index}>{inline(value)}</li>)
      result.push(ordered ? <ol key={key} start={Number(item[1])}>{children}</ol> : <ul key={key}>{children}</ul>)
      continue
    }
    result.push(<p key={key}>{inline(line)}</p>)
  }
  return result
}

export function RichAnswer({ text, onPrepareStay, photo }: { text: string; onPrepareStay?: (text: string) => void; photo?: StayPhotoRequest }) {
  const blocks = useMemo(() => renderAnswer(text, onPrepareStay, photo), [text, onPrepareStay, photo])
  return <div className="rich-answer" dir="auto">{blocks}</div>
}
