import { useState } from 'react'
import { Link } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import {
  dashboardDropStatuses,
  dashboardOrderStatuses,
  dashboardPaymentStatuses,
} from '../../features/seller/logic/dashboardStatuses'
import {
  useDashboardSummary,
  useUpcomingDrops,
} from '../../features/seller/logic/useDashboard'
import { applicationErrorMessage } from '../../features/seller/logic/sellerApplicationState'
import { dateLabel, inputDate } from '../../utils/date'

const PRESETS = [
  { key: 'ALL', label: '전체', days: null },
  { key: 'TODAY', label: '오늘', days: 0 },
  { key: 'WEEK', label: '7일', days: 6 },
] as const

type UpcomingDrop = {
  dropId: number
  name?: string
  status?: string
  upcomingAt?: string
}

export default function SellerDashboardPage() {
  const [preset, setPreset] = useState<string>('ALL')
  const [range, setRange] = useState({ from: '', to: '' })
  const from = range.from
    ? new Date(`${range.from}T00:00:00`).toISOString()
    : undefined
  const to = range.to
    ? new Date(`${range.to}T23:59:59.999`).toISOString()
    : undefined

  // 기간과 무관한 현재 상태(DROP 상태·재고·발송 대기·보정)는 from/to 없이 별도로 조회한다.
  const currentQuery = useDashboardSummary()
  const periodQuery = useDashboardSummary(from, to)
  const startingQuery = useUpcomingDrops('START')
  const endingQuery = useUpcomingDrops('END')
  const current = currentQuery.data
  const period = periodQuery.data

  const applyPreset = (key: string, days: number | null) => {
    setPreset(key)
    if (days === null) {
      setRange({ from: '', to: '' })
      return
    }
    const end = new Date()
    const start = new Date()
    start.setDate(end.getDate() - days)
    setRange({ from: inputDate(start), to: inputDate(end) })
  }

  const editRange = (next: { from: string; to: string }) => {
    setPreset('CUSTOM')
    setRange(next)
  }

  const upcomingPanel = (title: string, query: typeof startingQuery) => (
    <section className="panel dashboard-upcoming-panel">
      <div className="panel-heading">
        <div>
          <h2>{title}</h2>
          <p>24시간 이내 일정</p>
        </div>
      </div>
      {query.isLoading ? (
        <p>불러오는 중...</p>
      ) : query.isError ? (
        <p role="alert">{applicationErrorMessage(query.error)}</p>
      ) : query.data && query.data.length > 0 ? (
        <div className="dashboard-upcoming-list">
          {(query.data as UpcomingDrop[]).map((drop) => (
            <article key={drop.dropId}>
              <div>
                <strong>{drop.name}</strong>
                <small>
                  {drop.status} ·{' '}
                  {drop.upcomingAt ? dateLabel(drop.upcomingAt) : ''}
                </small>
              </div>
              <Link className="text-link" to={`/seller/drops/${drop.dropId}`}>
                상세 보기 ↗
              </Link>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState title={`${title} DROP이 없어요.`} />
      )}
    </section>
  )

  return (
    <section className="page-section seller-page seller-dashboard-page">
      <div className="seller-title">
        <div>
          <p className="eyebrow">CREATOR WORKSPACE</p>
          <h1>판매자 종합</h1>
          <p>현재 운영 현황과 처리할 일을 확인합니다.</p>
        </div>
        <Link className="primary-button" to="/seller/drops/new">
          ＋ 새 DROP 만들기
        </Link>
      </div>

      {currentQuery.isLoading ? (
        <p>불러오는 중...</p>
      ) : currentQuery.isError ? (
        <p role="alert">{applicationErrorMessage(currentQuery.error)}</p>
      ) : current ? (
        <>
          <section
            aria-label="현재 DROP 상태"
            className="stats-grid dashboard-drop-grid"
          >
            {dashboardDropStatuses.map((status) => (
              <Link key={status} to={`/seller/drops?status=${status}&page=0`}>
                <span>{status}</span>
                <strong>
                  {current.dropCounts?.[status] ?? 0}
                  <em>개</em>
                </strong>
                <small>해당 목록 ↗</small>
              </Link>
            ))}
          </section>
          <p className="dashboard-sales-entry">
            <Link className="text-link" to="/seller/sales?status=ENDED&page=0">
              판매 완료 DROP 실적 보기 ↗
            </Link>
          </p>

          <section
            aria-label="현재 재고와 처리할 일"
            className="stats-grid dashboard-current-grid"
          >
            <Link to="/seller/inventory">
              <span>가용 · 확보 · 판매 재고</span>
              <strong className="dashboard-stock-count">
                {current.stockSummary?.available ?? 0} ·{' '}
                {current.stockSummary?.reserved ?? 0} ·{' '}
                {current.stockSummary?.sold ?? 0}
              </strong>
              <small>재고 현황 ↗</small>
            </Link>
            <Link to="/seller/orders?orderStatus=PAID">
              <span>발송 대기 · PAID 주문</span>
              <strong>
                {current.orderCounts?.PAID ?? 0}
                <em>건</em>
              </strong>
              <small>결제 완료 주문 ↗</small>
            </Link>
            <article
              className={current.reconciliationRequired ? 'warning' : undefined}
            >
              <span>보정 필요 결제</span>
              <strong>
                {current.reconciliationRequired ?? 0}
                <em>건</em>
              </strong>
              <small>전용 목록 준비 중 · 상세 링크 없음</small>
            </article>
          </section>
        </>
      ) : null}

      <div className="dashboard-upcoming-grid">
        {upcomingPanel('시작 임박', startingQuery)}
        {upcomingPanel('종료 임박', endingQuery)}
      </div>

      <section className="panel period-panel">
        <div className="panel-heading">
          <div>
            <h2>기간 주문·결제 건수</h2>
            <p>
              기간 선택은 이 영역에만 적용됩니다. 주문과 결제 시도는 다른
              건수입니다.
            </p>
          </div>
        </div>
        <div className="period-picker">
          <div className="chips">
            {PRESETS.map((item) => (
              <button
                key={item.key}
                type="button"
                className={preset === item.key ? 'active' : undefined}
                aria-pressed={preset === item.key}
                onClick={() => applyPreset(item.key, item.days)}
              >
                {item.label}
              </button>
            ))}
          </div>
          <input
            type="date"
            aria-label="시작일"
            value={range.from}
            max={range.to || undefined}
            onChange={(event) =>
              editRange({ from: event.target.value, to: range.to })
            }
          />
          <span aria-hidden="true">~</span>
          <input
            type="date"
            aria-label="종료일"
            value={range.to}
            min={range.from || undefined}
            onChange={(event) =>
              editRange({ from: range.from, to: event.target.value })
            }
          />
        </div>
        {periodQuery.isLoading ? (
          <p>불러오는 중...</p>
        ) : periodQuery.isError ? (
          <p role="alert">{applicationErrorMessage(periodQuery.error)}</p>
        ) : period ? (
          <>
            <h3 className="count-label">주문 상태별 건수</h3>
            <div className="count-grid">
              {dashboardOrderStatuses.map((status) => (
                <div key={status}>
                  <small>{status}</small>
                  <b
                    className={
                      (period.orderCounts?.[status] ?? 0) === 0
                        ? 'zero'
                        : undefined
                    }
                  >
                    {period.orderCounts?.[status] ?? 0}
                  </b>
                </div>
              ))}
            </div>
            <h3 className="count-label">결제 시도 상태별 건수</h3>
            <div className="count-grid">
              {dashboardPaymentStatuses.map((status) => (
                <div key={status}>
                  <small>{status}</small>
                  <b
                    className={
                      (period.paymentCounts?.[status] ?? 0) === 0
                        ? 'zero'
                        : undefined
                    }
                  >
                    {period.paymentCounts?.[status] ?? 0}
                  </b>
                </div>
              ))}
            </div>
          </>
        ) : null}
      </section>
    </section>
  )
}
