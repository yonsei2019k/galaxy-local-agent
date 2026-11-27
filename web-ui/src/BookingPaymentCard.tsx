import type { BookingPayment, BookingPaymentAction } from './bridge'
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
    '아직 결제 결과가 확인되지 않았어요. 카드 거래 내역에서 승인 금액을, 예약처에서 예약번호와 일정을 확인해 주세요.'

  return <article className="booking-payment-card" aria-label="예약 결제 확인" aria-busy={busy}>
    <div className="booking-payment-heading">
      <strong><StickerText text={payment.seller} /></strong>
      <span>{won.format(payment.amount)}원</span>
    </div>
    <p role="status">{status}</p>
    <div className="reply-options">
      {payment.state === 'ready' && <button type="button" disabled={unavailable}
        onClick={() => onAction?.('openBookingPayment', payment.id)}>공식 결제 앱 열기</button>}
      <button type="button" disabled={unavailable}
        onClick={() => onAction?.('checkBookingPayment', payment.id)}>카드 거래 내역 보기</button>
      {payment.hasBookingHistory && <button type="button" disabled={unavailable}
        onClick={() => onAction?.('checkBookingHistory', payment.id)}>예약처에서 내역 보기</button>}
    </div>
  </article>
}
