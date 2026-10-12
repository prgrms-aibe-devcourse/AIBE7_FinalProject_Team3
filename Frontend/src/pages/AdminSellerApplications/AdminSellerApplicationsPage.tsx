import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import type { MockAccount } from '../../features/auth/mock/mockAuth'
import type {
  SellerApplication,
  SellerApplicationStatus,
} from '../../features/seller/mock/mockSellerApplications'
import { dateLabel } from '../../utils/date'

type Props = {
  account: MockAccount | null
  applications: SellerApplication[]
  onReview: (
    id: string,
    status: 'APPROVED' | 'REJECTED',
    reason?: string,
  ) => void
  notify: (message: string) => void
}

const PAGE_SIZE = 2
const statuses: SellerApplicationStatus[] = ['PENDING', 'APPROVED', 'REJECTED']
const statusText: Record<SellerApplicationStatus, string> = {
  PENDING: '대기',
  APPROVED: '승인',
  REJECTED: '반려',
}

export default function AdminSellerApplicationsPage({
  account,
  applications,
  onReview,
  notify,
}: Props) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [confirming, setConfirming] = useState<'APPROVED' | 'REJECTED' | null>(
    null,
  )
  const [reviewResult, setReviewResult] = useState<
    'SUCCESS' | 'FAILURE' | 'CONFLICT'
  >('SUCCESS')
  const [listResult, setListResult] = useState<'READY' | 'LOADING' | 'ERROR'>(
    'READY',
  )
  const [error, setError] = useState('')

  if (!account)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  if (account.role !== 'ADMIN')
    return (
      <EmptyState
        title="접근 권한이 없어요."
        link="/my"
        label="내 계정으로 돌아가기"
        variant="page"
      />
    )

  const status =
    statuses.find((item) => item === params.get('status')) ?? 'PENDING'
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const filtered = applications.filter((item) => item.status === status)
  const pageItems = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const selected =
    pageItems.find((item) => item.id === selectedId) ?? pageItems[0]
  const hasNext = (page + 1) * PAGE_SIZE < filtered.length

  const changePage = (nextPage: number) => {
    setParams({ status, page: String(nextPage) })
    setSelectedId(null)
    setConfirming(null)
    setError('')
  }

  const review = (decision: 'APPROVED' | 'REJECTED') => {
    if (!selected || selected.status !== 'PENDING') return
    if (decision === 'REJECTED' && !reason.trim()) {
      setError('반려 사유를 입력해주세요.')
      return
    }
    if (confirming !== decision) {
      setConfirming(decision)
      setError('')
      return
    }
    if (reviewResult !== 'SUCCESS') {
      setError(
        reviewResult === 'CONFLICT'
          ? '이미 심사된 신청입니다. 목록을 다시 확인해주세요.'
          : '심사 결과를 저장하지 못했어요. 다시 시도해주세요.',
      )
      setConfirming(null)
      return
    }
    onReview(selected.id, decision, reason)
    notify('Mock 심사 결과를 표시했어요. 실제 권한은 변경되지 않았습니다.')
    setSelectedId(null)
    setReason('')
    setConfirming(null)
    setError('')
  }

  const reject = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    review('REJECTED')
  }

  return (
    <section className="page-section seller-review-page">
      <p className="eyebrow">ADMIN</p>
      <h1>판매자 신청 심사</h1>
      <section className="panel seller-review-controls">
        <div className="chips" aria-label="신청 상태 필터">
          {statuses.map((item) => (
            <button
              key={item}
              type="button"
              className={item === status ? 'active' : ''}
              aria-pressed={item === status}
              onClick={() => {
                setParams({ status: item, page: '0' })
                setSelectedId(null)
                setConfirming(null)
                setError('')
              }}
            >
              {statusText[item]}
            </button>
          ))}
        </div>
        <label>
          목록 결과 예시
          <select
            value={listResult}
            onChange={(event) =>
              setListResult(event.target.value as typeof listResult)
            }
          >
            <option value="READY">조회 성공</option>
            <option value="LOADING">불러오는 중</option>
            <option value="ERROR">조회 실패</option>
          </select>
        </label>
      </section>
      <div className="seller-review-layout">
        <section className="panel seller-review-list">
          <h2>신청 목록</h2>
          {listResult === 'LOADING' ? (
            <EmptyState title="신청 목록을 불러오는 중이에요." />
          ) : listResult === 'ERROR' ? (
            <div>
              <EmptyState title="신청 목록을 불러오지 못했어요." />
              <button
                type="button"
                className="secondary-button"
                onClick={() => setListResult('READY')}
              >
                다시 시도
              </button>
            </div>
          ) : pageItems.length ? (
            <>
              <div className="seller-review-items">
                {pageItems.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    className="seller-review-item"
                    aria-pressed={selected?.id === item.id}
                    onClick={() => {
                      setSelectedId(item.id)
                      setReason('')
                      setConfirming(null)
                      setError('')
                    }}
                  >
                    <strong>{item.brandName}</strong>
                    <small>
                      {item.contactEmail} · {dateLabel(item.appliedAt)} ·{' '}
                      {statusText[item.status]}
                    </small>
                  </button>
                ))}
              </div>
              <div className="seller-review-pagination">
                <button
                  type="button"
                  className="secondary-button"
                  disabled={page === 0}
                  onClick={() => changePage(page - 1)}
                >
                  이전
                </button>
                <span>{page + 1}페이지</span>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={!hasNext}
                  onClick={() => changePage(page + 1)}
                >
                  다음
                </button>
              </div>
            </>
          ) : (
            <EmptyState title="조건에 맞는 신청이 없어요." />
          )}
        </section>
        <section className="panel seller-review-detail">
          <h2>선택 신청 심사</h2>
          {listResult !== 'READY' || !selected ? (
            <p className="seller-review-note">신청을 선택해주세요.</p>
          ) : (
            <>
              <strong>{selected.brandName}</strong>
              <p>{selected.contactEmail}</p>
              <small>신청일 {dateLabel(selected.appliedAt)}</small>
              <p className="seller-review-note">
                브랜드 소개는 현재 목록 조회 계약에 포함되지 않습니다.
              </p>
              {selected.status === 'PENDING' ? (
                <div className="seller-review-actions">
                  <label>
                    심사 결과 예시
                    <select
                      value={reviewResult}
                      onChange={(event) => {
                        setReviewResult(
                          event.target.value as typeof reviewResult,
                        )
                        setConfirming(null)
                        setError('')
                      }}
                    >
                      <option value="SUCCESS">처리 성공</option>
                      <option value="FAILURE">제출 실패</option>
                      <option value="CONFLICT">중복 심사</option>
                    </select>
                  </label>
                  <button
                    type="button"
                    className="primary-button"
                    onClick={() => review('APPROVED')}
                  >
                    {confirming === 'APPROVED'
                      ? 'Mock 승인 결과 보기'
                      : '승인 확인'}
                  </button>
                  <form onSubmit={reject}>
                    <label>
                      반려 사유 (필수)
                      <textarea
                        required
                        value={reason}
                        onChange={(event) => {
                          setReason(event.target.value)
                          setConfirming(null)
                          setError('')
                        }}
                      />
                    </label>
                    <button type="submit" className="secondary-button">
                      {confirming === 'REJECTED'
                        ? 'Mock 반려 결과 보기'
                        : '반려 확인'}
                    </button>
                  </form>
                  {confirming && (
                    <p className="seller-review-note" aria-live="polite">
                      {selected.brandName} 신청을{' '}
                      {confirming === 'APPROVED' ? '승인' : '반려'}하는 Mock
                      결과를 표시할까요?
                    </p>
                  )}
                  {error && (
                    <p className="form-error" role="alert">
                      {error}
                    </p>
                  )}
                </div>
              ) : (
                <p className="seller-review-note">
                  이미 {statusText[selected.status]} 처리된 신청입니다.
                </p>
              )}
            </>
          )}
        </section>
      </div>
      <small>심사와 권한 변경은 이 화면의 Mock 예시입니다.</small>
    </section>
  )
}
