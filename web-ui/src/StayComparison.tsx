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
  return { index, name, room, area, url: url.href, host: url.hostname, checkedAt,
    online, onsite, total, available: typeof row.available === 'boolean' ? row.available : null,
    cancellation: label(row.cancellation, 140) }
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

export type StayPhotoRequest = { messageId: string; request: (messageId: string, index: number) => boolean }
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
      if (response?.messageId !== messageId || !Number.isInteger(response.index) ||
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
  return <section className="stay-comparison" aria-label="숙소 비교">
    <header className="stay-header"><h3>숙소 비교</h3>
      <p>{data.checkIn} ~ {data.checkOut} · {nights}박 · {data.guests}명{data.party ? ` (${data.party})` : null}</p>
    </header>
    <ul className="stay-list">{(showAll ? stays : stays.slice(0, 3)).map(stay => {
      const thumbnail = photos[stay.index]
      const image = typeof thumbnail === 'object' ? thumbnail : null
      return <li key={stay.index}>
      <details>
      <summary className="stay-top">
        {image && <img className="stay-thumbnail" src={image.src} alt="" />}
        <span className="stay-name"><strong>{stay.name}</strong><span>{stay.area} · {stay.room}</span></span>
        <span className="stay-price"><strong>{stay.total === null ? '총액 미확인' : won(stay.total)}</strong>
          <span>{stay.available === true ? '조회 당시 예약 가능' : stay.available === false ? '조회 당시 예약 불가' : '예약 가능 여부 미확인'}</span></span>
      </summary>
      <div className="stay-facts">
        <span>온라인 {stay.online === null ? '미확인' : won(stay.online)}</span>
        <span>현장 {stay.onsite === null ? '미확인' : won(stay.onsite)}</span>
      </div>
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
        {onPrepare && stay.available !== false && <button type="button" onClick={() => onPrepare(
          `${stay.name}의 ${stay.room} 객실을 ${data.checkIn}부터 ${data.checkOut}까지 ${data.guests}명${data.party ? ` (${data.party})` : ""}으로 예약 준비해줘. ${stay.url}에서 객실과 인원, 최종 총액과 현장 추가비 및 취소 조건을 다시 확인해. 인증이 필요하면 휴대폰에서 이어갈 수 있게 알려줘. 예약 확정·결제는 하지 말고 최종 확인 화면에서 멈춰줘.`
        )}>예약 준비</button>}
      </div>
      </details>
    </li>
    })}</ul>
    {stays.length > 3 && <button type="button" className="stay-toggle" aria-expanded={showAll}
      onClick={() => setShowAll(value => !value)}>{showAll ? '후보 접기' : `후보 ${stays.length}곳 모두 보기`}</button>}
  </section>
}

