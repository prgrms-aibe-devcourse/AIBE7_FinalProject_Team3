import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { DropStatus } from '../../../types/drop'
import { count, hoursLabel } from '../logic/demandView'
import { useDemandAnalysis } from '../logic/useDemand'

// 수요 분석이 의미 있는 시점(DEMAND-006)에만 보여 주고, 조회 중·실패면 상세 화면을 가리지 않게 숨긴다.
// 분석은 판매 시작 전 WISH DROP만 가능하므로 그 외에는 조회하지 않는다.
export default function DemandSuggestionCard({
  dropId,
  status,
  saleStartsAt,
}: {
  dropId: number
  status?: DropStatus
  saleStartsAt?: string
}) {
  const [now] = useState(() => Date.now())
  const enabled =
    status === 'WISH' && !!saleStartsAt && Date.parse(saleStartsAt) > now
  const query = useDemandAnalysis(dropId, enabled)
  const analysis = query.data
  if (!enabled || !analysis?.suggestion?.ready) return null
  const recommended = analysis.plan?.recommended
  return (
    <section
      className="panel demand-suggestion"
      aria-labelledby="demand-suggestion-title"
    >
      <div>
        <h2 id="demand-suggestion-title">수요 분석 제안</h2>
        <p>
          활성 WISH {count(analysis.demand?.activeWishCount, '명')} · 판매
          시작까지 {hoursLabel(analysis.demand?.hoursUntilSaleStart)} ·{' '}
          {recommended == null
            ? '데이터가 부족해 수량을 계산하지 않았어요'
            : `준비 권장 수량 ${count(recommended)}`}
        </p>
      </div>
      <Link className="primary-button" to={`/seller/drops/${dropId}/demand`}>
        제안서 보기 ↗
      </Link>
    </section>
  )
}
