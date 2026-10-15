import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  isAdmin,
  type AuthStatus,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import type { AdminApplicationStatus } from '../../api/seller/adminApplications'
import {
  useAdminSellerApplications,
  useApproveSellerApplication,
  useRejectSellerApplication,
} from '../../features/seller/logic/useAdminSellerApplications'
import { adminApplicationErrorMessage } from '../../features/seller/logic/sellerApplicationState'
import { dateLabel } from '../../utils/date'

type Props = {
  user: AuthUser | null
  authStatus: AuthStatus
  notify: (message: string) => void
}

const PAGE_SIZE = 20
const statuses: AdminApplicationStatus[] = ['PENDING', 'APPROVED', 'REJECTED']
const statusText: Record<AdminApplicationStatus, string> = {
  PENDING: '대기',
  APPROVED: '승인',
  REJECTED: '반려',
}

export default function AdminSellerApplicationsPage({
  user,
  authStatus,
  notify,
}: Props) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [confirming, setConfirming] = useState<'APPROVED' | 'REJECTED' | null>(
    null,
  )
  const [error, setError] = useState('')

  const status =
    statuses.find((item) => item === params.get('status')) ?? 'PENDING'
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0

  const query = useAdminSellerApplications(
    { status, page, size: PAGE_SIZE },
    authStatus === 'authenticated' && isAdmin(user),
  )
  const approve = useApproveSellerApplication()
  const reject = useRejectSellerApplication()
  const submitting = approve.isPending || reject.isPending

  if (authStatus === 'loading') return <AuthLoading />
  if (!user)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  if (!isAdmin(user))
    return (
      <EmptyState
        title="접근 권한이 없어요."
        link="/my"
        label="내 계정으로 돌아가기"
        variant="page"
      />
    )

  const pageItems = query.data?.content ?? []
  const hasNext = query.data?.hasNext ?? false
  const selected =
    pageItems.find((item) => item.applicationId === selectedId) ?? pageItems[0]

  const changePage = (nextPage: number) => {
    setParams({ status, page: String(nextPage) })
    setSelectedId(null)
    setConfirming(null)
    setError('')
  }

  const review = (decision: 'APPROVED' | 'REJECTED') => {
    if (!selected || selected.status !== 'PENDING' || !selected.applicationId)
      return
    if (decision === 'REJECTED' && !reason.trim()) {
      setError('반려 사유를 입력해주세요.')
      return
    }
    if (confirming !== decision) {
      setConfirming(decision)
      setError('')
      return
    }
    const applicationId = selected.applicationId
    const onSettled = {
      onSuccess: () => {
        notify(
          decision === 'APPROVED' ? '신청을 승인했어요.' : '신청을 반려했어요.',
        )
        setSelectedId(null)
        setReason('')
        setConfirming(null)
        setError('')
      },
      onError: (submitError: unknown) => {
        setError(adminApplicationErrorMessage(submitError))
        setConfirming(null)
        // 동시 심사로 상태가 바뀐 경우(409) 등을 반영하기 위해 목록을 다시 불러온다.
        void query.refetch()
      },
    }
    if (decision === 'APPROVED') {
      approve.mutate(applicationId, onSettled)
    } else {
      reject.mutate({ applicationId, reason }, onSettled)
    }
  }

  const handleRejectSubmit = (event: FormEvent<HTMLFormElement>) => {
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
      </section>
      <div className="seller-review-layout">
        <section className="panel seller-review-list">
          <h2>신청 목록</h2>
          {query.isLoading ? (
            <EmptyState title="신청 목록을 불러오는 중이에요." />
          ) : query.isError ? (
            <div>
              <EmptyState title="신청 목록을 불러오지 못했어요." />
              <button
                type="button"
                className="secondary-button"
                onClick={() => void query.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : pageItems.length ? (
            <>
              <div className="seller-review-items">
                {pageItems.map((item) => (
                  <button
                    key={item.applicationId}
                    type="button"
                    className="seller-review-item"
                    aria-pressed={
                      selected?.applicationId === item.applicationId
                    }
                    onClick={() => {
                      setSelectedId(item.applicationId ?? null)
                      setReason('')
                      setConfirming(null)
                      setError('')
                    }}
                  >
                    <strong>{item.brandName}</strong>
                    <small>
                      {item.contactEmail} · {dateLabel(item.appliedAt ?? '')} ·{' '}
                      {item.status && statusText[item.status]}
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
          {!query.isSuccess || !selected ? (
            <p className="seller-review-note">신청을 선택해주세요.</p>
          ) : (
            <>
              <strong>{selected.brandName}</strong>
              <p>{selected.contactEmail}</p>
              <small>신청일 {dateLabel(selected.appliedAt ?? '')}</small>
              <p className="seller-review-note">
                브랜드 소개는 현재 목록 조회 계약에 포함되지 않습니다.
              </p>
              {selected.status === 'PENDING' ? (
                <div className="seller-review-actions">
                  <button
                    type="button"
                    className="primary-button"
                    disabled={submitting}
                    onClick={() => review('APPROVED')}
                  >
                    {confirming === 'APPROVED' ? '승인 처리 확정' : '승인 확인'}
                  </button>
                  <form onSubmit={handleRejectSubmit}>
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
                    <button
                      type="submit"
                      className="secondary-button"
                      disabled={submitting}
                    >
                      {confirming === 'REJECTED'
                        ? '반려 처리 확정'
                        : '반려 확인'}
                    </button>
                  </form>
                  {confirming && (
                    <p className="seller-review-note" aria-live="polite">
                      {selected.brandName} 신청을{' '}
                      {confirming === 'APPROVED' ? '승인' : '반려'}
                      하시겠어요? 다시 누르면 확정됩니다.
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
                  이미 {selected.status && statusText[selected.status]} 처리된
                  신청입니다.
                </p>
              )}
            </>
          )}
        </section>
      </div>
    </section>
  )
}
