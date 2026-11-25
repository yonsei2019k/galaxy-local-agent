const templates = {
  government: { label: '민원 안내', fields: { agency: '담당 기관', documents: '준비 서류', fee: '수수료', next: '다음 단계' } },
  performance: { label: '공연 일정', fields: { venue: '공연장', start: '시작일', end: '종료일', status: '공연 상태' } },
  place: { label: '장소', fields: { address: '주소', category: '업종', source: '출처' } },
  product: { label: '상품', fields: { seller: '판매처', option: '옵션', listedPrice: '표시 가격', shipping: '배송비', source: '출처' } },
  issue: { label: '저장소 이슈', fields: { repository: '저장소', number: '번호', state: '상태', updated: '수정일' } },
  channel: { label: '채널 메시지', fields: { workspace: '워크스페이스', channel: '채널', author: '작성자', date: '날짜' } },
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
