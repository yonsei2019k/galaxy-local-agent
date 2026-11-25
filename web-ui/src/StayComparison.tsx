import { useEffect, useState } from 'react'
import './stays.css'

type Stay = {
  index: number
  name: string
  room: string
  area: string
  url: string
  host: string
  checkedAt: string
  online: number | null
  onsite: number | null
  total: number | null
  available: boolean | null
  cancellation: string | null
  address: string | null
  rateTerms: string | null
  checkoutHost: string | null
  driveMinutes: number | null
  driveFrom: string | null
  bathroom: string | null
  heating: string | null
  optionalFees: string | null
}
type Comparison = { checkIn: string; checkOut: string; guests: number; party: string | null; stays: Stay[] }

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}

function label(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null
  const text = value.trim()
  return text.length > 0 && text.length <= max && !/[\u0000-\u001f\u007f]/.test(text) ? text : null
}

function validDay(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(value + 'T00:00:00Z')
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function money(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null
}

function readStay(value: unknown, index: number): Stay | null {
  const row = record(value)
  if (!row) return null
  const name = label(row.name, 80), room = label(row.room, 80), area = label(row.area, 60)
  const checkedAt = label(row.checkedAt, 36)
  if (!name || !room || !area || !checkedAt ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(checkedAt) ||
      !validDay(checkedAt.slice(0, 10)) || !Number.isFinite(Date.parse(checkedAt))) return null
  const href = label(row.url, 2048)
  if (!href) return null
  let url: URL
  try { url = new URL(href) } catch { return null }
  if (url.protocol !== 'https:' || url.username || url.password ||
      url.hostname === 'appassets.androidplatform.net' || !url.hostname.includes('.') ||
      /\.(?:localhost|local|internal)\.?$/.test(url.hostname) ||
      !/^[a-z0-9.-]+$/.test(url.hostname) || !/[a-z]/.test(url.hostname)) return null
  const online = money(row.online)
  const onsite = money(row.onsite)
  const quotedTotal = money(row.total)
  const total = online !== null && onsite !== null && online + onsite === quotedTotal ? quotedTotal : null
  const driveMinutes = money(row.driveMinutes)
  const driveFrom = label(row.driveFrom, 60)
  return { index, name, room, area, url: url.href, host: url.hostname, checkedAt,
    online, onsite, total, available: typeof row.available === 'boolean' ? row.available : null,
    cancellation: label(row.cancellation, 140), address: label(row.address, 180),
    rateTerms: label(row.rateTerms, 200), checkoutHost: publicCheckoutHost(row.checkoutHost),
    driveMinutes: driveFrom && driveMinutes !== null && driveMinutes <= 1440 ? driveMinutes : null,
    driveFrom, bathroom: label(row.bathroom, 60), heating: label(row.heating, 90),
    optionalFees: label(row.optionalFees, 180) }
}

function publicCheckoutHost(value: unknown): string | null {
  const host = label(value, 253)?.toLowerCase().replace(/\.$/, '')
  return host && /^[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?$/.test(host) &&
    host.includes('.') && /[a-z]/.test(host) && !host.includes('..') &&
    !/\.(?:localhost|local|internal)$/.test(host) && host !== 'appassets.androidplatform.net' ? host : null
}

function channelSite(host: string): string {
  // Count common country-code domains at the registrable level, including mobile subdomains.
  const canonical = host.toLowerCase().replace(/\.$/, '')
  const compound = /\.(?:ac|co|go|ne|or|re)\.(?:kr|jp|uk)$|\.(?:com|net|org)\.(?:au|br|cn|tw|sg|hk|my|ph)$/.test(canonical)
  return canonical.split('.').slice(compound ? -3 : -2).join('.')
}

function confirmedTerm(value: string | null): boolean {
  if (!value) return false
  const term = value.normalize('NFKC').trim()
  return !/^(?:[-–—?]+|n\/?a|null|none|tbd)$/i.test(term) &&
    !/(?:미확인|확인\s*필요|알\s*수\s*없|unknown|unconfirmed|not verified)/i.test(term)
}

function groupStays(stays: Stay[]): Stay[][] {
  const groups = new Map<string, Stay[]>()
  for (const stay of stays) {
    const identity = [stay.address, stay.name, stay.room, stay.rateTerms, stay.cancellation]
    const match = identity.every(confirmedTerm)
      ? JSON.stringify(identity.map(value => value!.normalize('NFKC').toLowerCase().replace(/\s+/g, ' ')))
      : String(stay.index)
    const group = groups.get(match)
    if (group) group.push(stay)
    else groups.set(match, [stay])
  }
  return [...groups.values()]
}

const priceConfirmed = (stay: Stay): stay is Stay & { total: number } =>
  stay.available === true && stay.total !== null

function priceComparison(group: Stay[]) {
  const winner = group[0]
  if (!priceConfirmed(winner) || !winner.checkoutHost) return null
  const checkedAt = Date.parse(winner.checkedAt)
  const site = channelSite(winner.host)
  const checkout = channelSite(winner.checkoutHost)
  const baseline = group.slice(1).find(candidate => priceConfirmed(candidate) && candidate.checkoutHost &&
    ![site, checkout].includes(channelSite(candidate.host)) &&
    ![site, checkout].includes(channelSite(candidate.checkoutHost)) &&
    Math.abs(checkedAt - Date.parse(candidate.checkedAt)) <= 30 * 60 * 1000)
  if (!baseline || !priceConfirmed(baseline) || baseline.total <= winner.total) return null
  return { winner, baseline, savings: baseline.total - winner.total }
}

export function parseStayComparison(code: string): Comparison | null {
  if (code.length > 14_000) return null
  let data: Record<string, unknown> | null
  try { data = record(JSON.parse(code)) } catch { return null }
  if (!data || data.version !== 1 || data.currency !== 'KRW') return null
  const { checkIn, checkOut, guests } = data
  if (!validDay(checkIn) || !validDay(checkOut) || checkIn >= checkOut ||
      typeof guests !== 'number' || !Number.isInteger(guests) || guests < 1 || guests > 30 ||
      !Array.isArray(data.stays) || data.stays.length < 1 || data.stays.length > 6) return null
  const stays = data.stays.map(readStay)
  if (!stays.every((stay): stay is Stay => stay !== null)) return null
  return { checkIn, checkOut, guests, party: label(data.party, 80), stays }
}

const won = (amount: number) => amount.toLocaleString('ko-KR') + '원'
const localTime = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'
})

function bookingDraft(data: Comparison, stay: Stay): string {
  const amount = (value: number | null) => value === null ? '미확인' : won(value)
  return [
    `${stay.name}의 ${stay.room} 예약을 준비해줘. ${data.checkIn} ~ ${data.checkOut}, ${data.guests}명${data.party ? ' (' + data.party + ')' : ''}이야.`,
    `비교할 때 확인한 금액은 온라인 ${amount(stay.online)}, 현장 ${amount(stay.onsite)}, 총액 ${amount(stay.total)}이야.`,
    `판매처의 객실 조건은 “${stay.rateTerms ?? '미확인'}”, 취소 조건은 “${stay.cancellation ?? '미확인'}”으로 표시됐어.`,
    `조회 시각 ${localTime.format(new Date(stay.checkedAt))} · ${stay.url}`,
    '같은 객실·일정·인원으로 온라인 요금, 현장 추가비, 취소 조건을 다시 확인하고 달라진 점을 알려줘.',
    '인증은 휴대폰에서 진행하고 예약 확정이나 결제 전 최종 확인 화면에서 멈춰줘.',
  ].join('\n')
}

export type StayPhotoRequest = { messageId: string; request: (messageId: string, index: number, kind?: 'map') => boolean }
type Photo = 'loading' | 'missing' | { src: string; representative: boolean }

export function StayComparison({ data, onPrepare, photo }: {
  data: Comparison; onPrepare?: (text: string) => void; photo?: StayPhotoRequest
}) {
  const [showAll, setShowAll] = useState(false)
  const [photos, setPhotos] = useState<Record<number, Photo>>({})
  const messageId = photo?.messageId
  useEffect(() => {
    if (!messageId) return
    function receive(event: Event) {
      const response = (event as CustomEvent).detail
      if (response?.messageId !== messageId || response.kind === 'map' || !Number.isInteger(response.index) ||
          response.index < 0 || response.index > 5) return
      const src = response.src
      const loaded: Photo = typeof src === 'string' && src.length <= 240_024 &&
        /^data:image\/jpeg;base64,[a-z0-9+/=]+$/i.test(src)
        ? { src, representative: response.representative === true } : 'missing'
      setPhotos(previous => previous[response.index] === 'loading'
        ? { ...previous, [response.index]: loaded } : previous)
    }
    window.addEventListener('ziphap:stayPhoto', receive)
    return () => window.removeEventListener('ziphap:stayPhoto', receive)
  }, [messageId])
  function loadPhoto(index: number) {
    if (!photo) return
    setPhotos(previous => ({ ...previous, [index]: 'loading' }))
    if (!photo.request(photo.messageId, index)) setPhotos(previous => ({ ...previous, [index]: 'missing' }))
  }
  const rank = (stay: Stay) => stay.available === false ? 2 : stay.available && stay.total !== null ? 0 : 1
  const stays = [...data.stays].sort((a, b) => rank(a) - rank(b) ||
    (rank(a) === 0 && rank(b) === 0 ? a.total! - b.total! : 0))
  const nights = Math.round((Date.parse(data.checkOut) - Date.parse(data.checkIn)) / 86_400_000)
  const groups = groupStays(stays)
  function offer(stay: Stay) {
    const thumbnail = photos[stay.index]
    const image = typeof thumbnail === 'object' ? thumbnail : null
    const amenities = [stay.bathroom, stay.heating].filter(Boolean)
    const drive = stay.driveMinutes === null ? null : stay.driveFrom + ' 기준 차량 약 ' + stay.driveMinutes + '분'
    const detailsDraft = [
      `${stay.name} · ${stay.room}의 상세 정보를 확인해줘. ${data.checkIn} ~ ${data.checkOut}에 ${data.guests}명이야.`,
      '같은 객실의 인원 추가비, 난방·화장실, 입퇴실 시간, 선택 비용과 취소 조건을 확인해줘.',
      '캠핑장이면 공식 전체 배치도와 해당 날짜에 예약 가능한 자리도 같이 보여줘.',
      '진행 중인 다른 예약이나 선택은 그대로 두고 상세 조회만 해줘.',
      stay.url,
    ].join('\n')
    return <li key={stay.index}>
      <details>
        <summary className="stay-top">
          {image && <img className="stay-thumbnail" src={image.src} alt="" />}
          <span className="stay-name"><strong>{stay.name}</strong><span>{stay.area} · {stay.room}</span>
            <span>{[drive, ...amenities].filter(Boolean).join(' · ') || stay.host}</span></span>
          <span className="stay-price"><strong>{stay.total === null ? '총액 미확인' : won(stay.total)}</strong>
            <span>{stay.available === true ? '조회 당시 예약 가능' : stay.available === false ? '조회 당시 예약 불가' : '예약 가능 여부 미확인'}</span></span>
        </summary>
        <div className="stay-facts">
          <span>온라인 {stay.online === null ? '미확인' : won(stay.online)}</span>
          <span>현장 {stay.onsite === null ? '미확인' : won(stay.onsite)}</span>
        </div>
        {stay.rateTerms && <p className="stay-cancellation">객실 조건 · {stay.rateTerms}</p>}
        {stay.optionalFees && <p className="stay-cancellation">선택 비용 · {stay.optionalFees}</p>}
        <p className="stay-cancellation">취소 · {stay.cancellation ?? "미확인"}</p>
        <p className="stay-source">{stay.host} · {localTime.format(new Date(stay.checkedAt))} 조회</p>
        {image && <figure className="stay-photo">
          <img src={image.src} alt={stay.name + (image.representative ? ' 대표 사진' : ' 출처 사진')} />
          <figcaption>{image.representative ? '숙소 대표 사진' : '숙소 출처 사진'}</figcaption>
        </figure>}
        {thumbnail === 'missing' && <p className="stay-source" role="status">사진을 불러오지 못했어요. 숙소 상세에서 확인해 주세요.</p>}
        <div className="stay-actions">
          {photo && !image && <button type="button" disabled={Object.values(photos).includes('loading')}
            onClick={() => loadPhoto(stay.index)}>{thumbnail === 'loading' ? '사진 불러오는 중' : '사진 보기'}</button>}
          <a href={stay.url} rel="noreferrer noopener" referrerPolicy="no-referrer">숙소 상세</a>
          {onPrepare && <button type="button" onClick={() => onPrepare(detailsDraft)}>집순이에게 더 물어보기</button>}
          {onPrepare && stay.available !== false && <button type="button" onClick={() => onPrepare(
            bookingDraft(data, stay)
          )}>예약 준비</button>}
        </div>
      </details>
    </li>
  }

  return <section className="stay-comparison" aria-label="숙소 비교">
    <header className="stay-header"><h3>숙소 비교</h3>
      <p>{data.checkIn} ~ {data.checkOut} · {nights}박 · {data.guests}명{data.party ? ` (${data.party})` : null}</p>
    </header>
    <ul className="stay-list">{(showAll ? groups : groups.slice(0, 3)).map(group => {
      if (group.length === 1) return offer(group[0])
      const comparison = priceComparison(group)
      return <li key={group[0].index} className="stay-offer-group">
        <h4>{group[0].name} · {group[0].room}</h4>
        {comparison && <p className="stay-savings">
          <strong>예상 {won(comparison.savings)} 절약</strong>
          <span>{comparison.winner.host} {won(comparison.winner.total)} · 비교 기준 {comparison.baseline.host} {won(comparison.baseline.total)}</span>
        </p>}
        <ul className="stay-list" aria-label="같은 객실의 판매처">{group.map(offer)}</ul>
      </li>
    })}</ul>
    {groups.length > 3 && <button type="button" className="stay-toggle" aria-expanded={showAll}
      onClick={() => setShowAll(value => !value)}>{showAll ? '후보 접기' : '후보 ' + groups.length + '곳 모두 보기'}</button>}
  </section>
}
