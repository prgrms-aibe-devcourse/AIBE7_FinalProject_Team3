import { useEffect, useState } from 'react'
import { openingSeconds, serverTimeAt } from '../logic/serverTime'
import { dateLabel } from '../../../utils/date'

export default function DropOpenCountdown({
  saleStartsAt,
  serverTime,
  receivedAt,
  wishCount,
}: {
  saleStartsAt: string
  serverTime: string
  receivedAt: number
  wishCount: number
}) {
  const [seconds, setSeconds] = useState(() =>
    openingSeconds(saleStartsAt, serverTime, receivedAt),
  )

  useEffect(() => {
    let timer = 0
    const tick = () => {
      window.clearTimeout(timer)
      if (document.visibilityState === 'hidden') return
      const now = Date.now()
      const remaining = openingSeconds(
        saleStartsAt,
        serverTime,
        receivedAt,
        now,
      )
      const remainingMs =
        Date.parse(saleStartsAt) - serverTimeAt(serverTime, receivedAt, now)
      setSeconds(remaining)
      if (remaining === null || remaining === 0) return
      timer = window.setTimeout(
        tick,
        remaining > 600
          ? Math.min(remainingMs - 600_000, 2_147_483_647)
          : remainingMs % 1000 || 1000,
      )
    }
    timer = window.setTimeout(tick, 0)
    document.addEventListener('visibilitychange', tick)
    return () => {
      window.clearTimeout(timer)
      document.removeEventListener('visibilitychange', tick)
    }
  }, [saleStartsAt, serverTime, receivedAt])

  const countdown = seconds !== null && seconds <= 600
  return (
    <>
      <span className="detail-state-label">
        {countdown
          ? seconds === 0
            ? '오픈 상태 확인 중'
            : '오픈까지'
          : '오픈 예정'}
      </span>
      {countdown && (
        <strong className="detail-countdown">
          {String(Math.floor(seconds / 60)).padStart(2, '0')}:
          {String(seconds % 60).padStart(2, '0')}
        </strong>
      )}
      <strong
        className={
          countdown
            ? 'detail-open-time detail-open-time-secondary'
            : 'detail-open-time'
        }
      >
        <time dateTime={saleStartsAt}>{dateLabel(saleStartsAt)}</time> 오픈
      </strong>
      <p className="detail-wish-count">{wishCount} WISH</p>
    </>
  )
}
