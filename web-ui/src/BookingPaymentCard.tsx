import type { BookingPayment, BookingPaymentAction } from './bridge'
import { Metal } from './icons/Metal'
import { StickerText } from './icons/Sticker'

const won = new Intl.NumberFormat('ko-KR')

export function BookingPaymentCard({ payment, enabled, onAction }: {
  payment: BookingPayment
  enabled: boolean
  onAction?: (action: BookingPaymentAction, id: string) => boolean
}) {
  const busy = payment.state === 'checking'
  const unavailable = !enabled || !onAction || busy
  const status = busy ? '예약처에서 결제 상태를 확인하고 있어요.' :
    payment.state === 'ready' ? '공식 결제 앱에서 진행한 뒤 결과를 다시 확인해 주세요.' :
    '아직 결제 결과가 확인되지 않았어요. 같은 결제 화면을 열어 상태를 확인할 수 있어요.'

  return <article className="booking-payment-card" aria-label="예약 결제 확인" aria-busy={busy}>
    <div className="booking-payment-heading">
      <Metal name="lock" className="booking-payment-art" />
      <strong><StickerText text={payment.seller} /></strong>
      <span>{won.format(payment.amount)}원</span>
    </div>
    <p role="status">{status}</p>
    <div className="reply-options">
      {payment.state === 'ready' && <button type="button" disabled={unavailable}
        onClick={() => onAction?.('openBookingPayment', payment.id)}>공식 결제 앱 열기</button>}
      <button type="button" disabled={unavailable}
        onClick={() => onAction?.('checkBookingPayment', payment.id)}>결제 결과 다시 확인</button>
    </div>
  </article>
}
