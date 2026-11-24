const templates = {
  mail: { label: '메일', fields: { account: '계정', sender: '보낸 사람', recipient: '받는 사람', date: '날짜', attachments: '첨부' } },
  calendar: { label: '일정', fields: { calendar: '캘린더', start: '시작', end: '종료', location: '장소' } },
  contact: { label: '연락처', fields: { organization: '소속', phone: '전화번호', email: '이메일' } },
  file: { label: '파일', fields: { folder: '폴더', format: '형식', updated: '수정일' } },
} as const

type CardKind = keyof typeof templates
type CardRow = { title: string; summary?: string; fields: [string, string][] }
type ServiceResult = { kind: CardKind; rows: CardRow[] }

function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function boundedText(value: unknown, limit: number): string | undefined {
  if (typeof value !== 'string' || value.length > limit) return
  return value.trim() || undefined
}

export function parseServiceResult(source: string): ServiceResult | null {
  if (source.length > 9500) return null
  try {
    const data: unknown = JSON.parse(source)
    if (!record(data) || data.version !== 1 || typeof data.kind !== 'string' ||
      !Object.hasOwn(templates, data.kind) || !Array.isArray(data.items) ||
      data.items.length < 1 || data.items.length > 6) return null

    const kind = data.kind as CardKind
    const rows: CardRow[] = []
    for (const item of data.items as unknown[]) {
      if (!record(item)) return null
      const title = boundedText(item.title, 160)
      if (!title) return null
      const fields: [string, string][] = []
      for (const [key, label] of Object.entries(templates[kind].fields)) {
        const value = boundedText(item[key], 240)
        if (value) fields.push([label, value])
      }
      rows.push({ title, summary: boundedText(item.summary, 320), fields })
    }
    return { kind, rows }
  } catch {
    return null
  }
}

export function ServiceResultCard({ result }: { result: ServiceResult }) {
  const label = templates[result.kind].label
  return <section className="service-result" aria-label={label + ' 결과'}>
    <header><strong>{label}</strong><span>{result.rows.length}건</span></header>
    <ul>{result.rows.map(({ title, summary, fields }, index) => <li key={index}>
      <h3>{title}</h3>
      {summary && <p>{summary}</p>}
      {fields.length > 0 && <dl>{fields.map(([name, value]) => <div key={name}>
        <dt>{name}</dt><dd>{value}</dd>
      </div>)}</dl>}
    </li>)}</ul>
  </section>
}
