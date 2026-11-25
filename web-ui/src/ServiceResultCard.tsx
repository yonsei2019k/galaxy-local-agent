import { useEffect, useState } from 'react'
import type { StayPhotoRequest } from './StayComparison'

const templates = {
  order_review: { label: '주문 전 확인', fields: { seller: '판매처', option: '옵션', quantity: '수량', delivery: '배송·배달비', discount: '적용 할인', total: '총액', paymentMethods: '결제 방법', checkedAt: '확인 시각' } },
  booking_review: { label: '예약 전 확인', fields: { seller: '예약처', dates: '일정', stayTimes: '입실·퇴실', party: '인원', room: '객실·자리', vehicles: '차량', online: '온라인 결제', onsite: '현장 결제', total: '총액', optionalFees: '선택 비용', cancellation: '취소·환불', paymentMethods: '결제 방법', paymentDeadline: '결제 기한', checkedAt: '확인 시각' } },
  camp_sites: { label: '캠핑장 자리 확인', fields: { dates: '일정', party: '인원', availableSites: '예약 가능한 자리', facilities: '화장실·개수대·매점', access: '자리별 동선', checkedAt: '확인 시각' } },
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
type SitePin = { site: string; x: number; y: number }
type CampMap = { dates: string; party: string; checkedAt: string; mapUrl: string; imageUrl: string; sites: string[] }
const paymentLabels = { card: '카드결제', bank_transfer: '무통장 입금', kakao_pay: '카카오페이', naver_pay: '네이버페이', toss_pay: '토스페이' } as const
type PaymentMethod = keyof typeof paymentLabels
type BookingReview = { seller: string; room: string; dates: string; party: string; total: string; methods: PaymentMethod[] }
type CardRow = { title: string; summary?: string; fields: [string, string][]; mapUrl?: string; inventoryUrl?: string; map?: CampMap; booking?: BookingReview }
type ServiceResult = { kind: CardKind; rows: CardRow[] }

function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

function boundedText(value: unknown, limit: number): string | undefined {
  if (typeof value !== 'string' || value.length > limit) return
  return value.trim() || undefined
}

function bookingReview(item: Record<string, unknown>): BookingReview | undefined {
  const seller = boundedText(item.seller, 100), room = boundedText(item.room, 100)
  const dates = boundedText(item.dates, 100), party = boundedText(item.party, 80)
  const total = boundedText(item.total, 30)
  if (!seller || !room || !dates || !party || !total ||
      [seller, room, dates, party].some(value => /[\r\n\u0000-\u001f]/.test(value)) ||
      !/^[0-9][0-9,]*\s*원$/.test(total) || !Array.isArray(item.methodOptions) ||
      item.methodOptions.length < 1 || item.methodOptions.length > 5) return
  const methods = item.methodOptions.filter((value): value is PaymentMethod =>
    typeof value === 'string' && Object.hasOwn(paymentLabels, value))
  if (methods.length !== item.methodOptions.length || new Set(methods).size !== methods.length) return
  return {seller, room, dates, party, total, methods}
}

function publicLink(value: unknown): string | undefined {
  const text = boundedText(value, 2048)
  if (!text) return
  try {
    const url = new URL(text)
    if (url.protocol === 'https:' && !url.username && !url.password && !url.port &&
      /^[a-z0-9.-]+\.[a-z][a-z0-9-]*$/.test(url.hostname) &&
      !/\.(?:localhost|local|internal)$/.test(url.hostname) &&
      url.hostname !== 'appassets.androidplatform.net') return url.href
  } catch { /* Keep malformed URLs out of actions. */ }
}

function campMap(item: Record<string, unknown>, mapUrl: string | undefined): CampMap | undefined {
  const dates = boundedText(item.dates, 100), party = boundedText(item.party, 80)
  const checkedAt = boundedText(item.checkedAt, 100), mapImage = publicLink(item.mapImageUrl)
  if (!dates || !party || !checkedAt || !mapUrl || !mapImage ||
      !publicLink(item.inventoryUrl) || new URL(mapUrl).hostname !== new URL(mapImage).hostname ||
      new URL(mapImage).search || new URL(mapImage).hash ||
      !/^(?:\d{4}-\d{2}-\d{2})\s*~\s*(?:\d{4}-\d{2}-\d{2})$/.test(dates) ||
      !Number.isFinite(Date.parse(checkedAt))) return
  const sitePattern = /^[A-Z0-9][A-Z0-9-]{0,9}$/
  const sites = [...new Set(boundedText(item.availableSites, 240)?.toUpperCase().split(/[,·\s]+/).filter(site => sitePattern.test(site)))]
  return { dates, party, checkedAt, mapUrl, imageUrl: mapImage, sites }
}

function mapPins(value: unknown, sites: string[]): SitePin[] {
  if (!Array.isArray(value) || value.length > 60) return []
  const pins = new Map<string, SitePin>()
  for (const pin of value as unknown[]) {
    if (!record(pin) || typeof pin.site !== 'string' || typeof pin.x !== 'number' || typeof pin.y !== 'number') continue
    const site = pin.site.trim().toUpperCase()
    if (!sites.includes(site) || ![pin.x, pin.y].every(value => Number.isFinite(value) && value >= 0 && value <= 100)) continue
    if (pins.has(site)) return [] // Conflicting coordinates must not suggest a position.
    pins.set(site, {site, x: pin.x, y: pin.y})
  }
  return [...pins.values()]
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
      const mapUrl = kind === 'camp_sites' ? publicLink(item.mapUrl) : undefined
      rows.push({ title, summary: boundedText(item.summary, 320), fields, mapUrl,
        booking: kind === 'booking_review' && data.items.length === 1 ? bookingReview(item) : undefined,
        ...(kind === 'camp_sites' ? { inventoryUrl: publicLink(item.inventoryUrl),
          map: data.items.length === 1 ? campMap(item, mapUrl) : undefined } : {}) })
    }
    return { kind, rows }
  } catch {
    return null
  }
}

function CampMapPreview({ map, title, photo, onAsk }: {
  map: CampMap; title: string; photo: StayPhotoRequest; onAsk?: (text: string) => void
}) {
  const [state, setState] = useState<'idle' | 'loading' | 'missing' | {src: string; pins: SitePin[]}>('idle')
  const [selectedSite, setSelectedSite] = useState<string>()
  const [zoomed, setZoomed] = useState(false)
  useEffect(() => {
    function receive(event: Event) {
      const detail = (event as CustomEvent).detail
      if (detail?.messageId !== photo.messageId || detail.index !== 0 || detail.kind !== 'map') return
      const valid = publicLink(detail.imageUrl) === map.imageUrl &&
        typeof detail.src === 'string' && detail.src.length <= 533_372 &&
        /^data:image\/jpeg;base64,[a-z0-9+/=]+$/i.test(detail.src)
      setState(current => current === 'loading'
        ? (valid ? {src: detail.src, pins: mapPins(detail.pins, map.sites)} : 'missing') : current)
    }
    window.addEventListener('ziphap:stayPhoto', receive)
    return () => window.removeEventListener('ziphap:stayPhoto', receive)
  }, [photo.messageId, map.imageUrl, map.sites])
  if (typeof state === 'string') return <>
    <div className="stay-actions"><button type="button" disabled={state === 'loading'} onClick={() => {
      setState('loading')
      if (!photo.request(photo.messageId, 0, 'map')) setState('missing')
    }}>{state === 'loading' ? '배치도를 불러오는 중' : '채팅에서 배치도 보기'}</button></div>
    {state === 'missing' && <p role="status">배치도를 불러오지 못했어요. 전체 배치도 링크에서 확인할 수 있어요.</p>}
  </>
  return <figure className="camp-map">
    <div className="stay-actions"><button type="button" aria-pressed={zoomed}
      onClick={() => setZoomed(value => !value)}>{zoomed ? '전체 보기' : '배치도 확대'}</button></div>
    <div className="camp-map-scroll" role="region" aria-label="캠핑장 배치도" tabIndex={0}>
      <div className={zoomed ? 'camp-map-picture is-zoomed' : 'camp-map-picture'}>
      <img src={state.src} alt={title + ' 공식 자리 배치도'} onError={() => setState('missing')} />
      {state.pins.map(pin => <button type="button" key={pin.site} className="camp-map-pin"
        style={{left: pin.x + '%', top: pin.y + '%'}} aria-label={`빈자리 ${pin.site}`}
        aria-pressed={selectedSite === pin.site} onClick={() => setSelectedSite(pin.site)} />)}
      </div>
    </div>
    <figcaption>{state.pins.length ? '지도에 위치가 확인된 빈자리 ' + state.pins.length + '곳을 표시했어요.' :
      '지도에서 위치를 확인한 빈자리가 없어요. 목록에서 자리 번호를 확인해 주세요.'}</figcaption>
    {map.sites.length > 0 && <div className="stay-actions" role="group" aria-label="빈자리 번호 목록">
      {map.sites.map(site => <button key={site} type="button" aria-pressed={selectedSite === site}
        onClick={() => setSelectedSite(site)}>{site}</button>)}
    </div>}
    {selectedSite && <div className="stay-actions">
      <span aria-live="polite">{selectedSite} 선택</span>
      {onAsk && <button type="button" onClick={() => onAsk([
        `${title}의 자리 ${selectedSite}를 다시 확인해줘. ${map.dates}, ${map.party} 기준이야.`,
        '공식 예약 페이지에서 자리와 최종 금액을 확인하고 예약이나 결제는 확정하지 마.',
        `배치도 출처 ${map.mapUrl}`,
      ].join('\n'))}>이 자리 다시 확인</button>}
    </div>}
  </figure>
}

export function ServiceResultCard({ result, photo, onAsk }: {
  result: ServiceResult; photo?: StayPhotoRequest; onAsk?: (text: string) => void
}) {
  const label = templates[result.kind].label
  return <section className="service-result" aria-label={label + ' 결과'}>
    <header><strong>{label}</strong><span>{result.rows.length}건</span></header>
    <ul>{result.rows.map(({ title, summary, fields, mapUrl, inventoryUrl, map, booking }, index) => <li key={index}>
      <h3>{title}</h3>
      {summary && <p>{summary}</p>}
      {fields.length > 0 && <dl>{fields.map(([name, value]) => <div key={name}>
        <dt>{name}</dt><dd>{value}</dd>
      </div>)}</dl>}
      {(mapUrl || inventoryUrl) && <div className="stay-actions">
        {mapUrl && <a href={mapUrl} rel="noreferrer noopener" referrerPolicy="no-referrer">전체 배치도</a>}
        {inventoryUrl && <a href={inventoryUrl} rel="noreferrer noopener" referrerPolicy="no-referrer">예약 페이지</a>}
      </div>}
      {map && photo && <CampMapPreview key={JSON.stringify([map.imageUrl, map.dates, map.party, map.checkedAt])}
        map={map} title={title} photo={photo} onAsk={onAsk} />}
      {booking && onAsk && <div className="stay-actions" role="group" aria-label="결제수단 선택">
        {booking.methods.map(method => <button key={method} type="button" onClick={() => onAsk([
          `${title} · ${booking.room} 예약의 ${paymentLabels[method]} 화면을 열어줘.`,
          `예약처 ${booking.seller}, 일정 ${booking.dates}, ${booking.party}, 표시 총액 ${booking.total}이야.`,
          '원래 예약 페이지에서 대상과 재고, 최종 금액과 선택한 결제수단을 다시 확인하고 공식 결제 화면까지만 열어줘.',
        ].join('\n'))}>{paymentLabels[method]} 준비</button>)}
      </div>}
    </li>)}</ul>
  </section>
}
