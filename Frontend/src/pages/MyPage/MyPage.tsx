import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getMyWishes } from '../../api/wish/wishes'
import EmptyState from '../../components/EmptyState/EmptyState'
import {
  mockSignupErrors,
  nicknameError,
  type MockAccount,
} from '../../features/auth/mock/mockAuth'
import { wishKeys } from '../../features/wish/logic/queryKeys'
import {
  buyerOrderStatusText,
  type BuyerOrder,
} from '../../features/order/mock/mockBuyerOrders'
import type { SharedProps } from '../../types/store'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

const WISH_PAGE_SIZE = 20

const WISH_STATUS_TEXT: Record<string, string> = {
  WISH: 'WISH',
  GRAB: 'GRAB',
  ENDED: '판매 종료',
  CANCELED: '판매 취소',
  DRAFT: '준비 중',
}

type Props = SharedProps & {
  account: MockAccount | null
  onAccountChange: (account: MockAccount) => void
  onLogout: () => void
  buyerOrders: BuyerOrder[]
}

export default function MyPage({
  account,
  onAccountChange,
  onLogout,
  authenticated,
  orders,
  buyerOrders,
  toggleWish,
  notify,
}: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const [editing, setEditing] = useState(false)
  const [page, setPage] = useState(0)
  const [nickname, setNickname] = useState(account?.nickname ?? '')
  const [saveResult, setSaveResult] = useState<'success' | 'nickname' | 'save'>(
    'success',
  )
  const [error, setError] = useState('')

  const wishesQuery = useQuery({
    queryKey: wishKeys.list(page, WISH_PAGE_SIZE),
    queryFn: () => getMyWishes({ page, size: WISH_PAGE_SIZE }),
    enabled: authenticated,
  })
  const wishItems = (wishesQuery.data?.content ?? []).flatMap((item) =>
    item.dropId === undefined ? [] : [{ ...item, dropId: item.dropId }],
  )
  const totalWishes = wishesQuery.data?.totalElements ?? 0
  const totalPages = wishesQuery.data?.totalPages ?? 0

  if (!account)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />

  const saveNickname = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const invalid = nicknameError(nickname)
    if (invalid) {
      setError(invalid)
      return
    }
    if (saveResult !== 'success') {
      setError(mockSignupErrors[saveResult])
      return
    }
    onAccountChange({ ...account, nickname: nickname.trim() })
    setEditing(false)
    setError('')
    notify('데모 닉네임을 변경했어요.')
  }

  const switchAccount = (role: MockAccount['role']) => {
    onAccountChange({ ...account, role })
    setNickname(account.nickname)
    setEditing(false)
    setError('')
  }

  return (
    <section className="page-section">
      <p className="eyebrow">MY GRAB</p>
      <h1>나의 취향과 주문</h1>
      <section className="panel account-panel">
        <div className="account-heading">
          <div>
            <p className="eyebrow">MY ACCOUNT</p>
            <h2>내 계정</h2>
            <strong>{account.nickname}</strong>
            <p>{account.email}</p>
            <small>
              {account.provider} 계정 ·{' '}
              {account.role === 'ADMIN'
                ? '운영자'
                : account.role === 'SELLER'
                  ? '승인된 판매자'
                  : '일반 회원'}
            </small>
          </div>
          <button
            type="button"
            className="secondary-button"
            onClick={() => {
              setEditing(!editing)
              setError('')
              setNickname(account.nickname)
            }}
          >
            {editing ? '닫기' : '정보 수정'}
          </button>
        </div>
        {editing && (
          <form className="account-edit" onSubmit={saveNickname}>
            <label>
              이메일
              <input readOnly value={account.email} />
            </label>
            <label>
              닉네임 변경
              <input
                required
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
              />
            </label>
            <label className="demo-select">
              편집 결과 예시
              <select
                value={saveResult}
                onChange={(event) => {
                  setSaveResult(event.target.value as typeof saveResult)
                  setError('')
                }}
              >
                <option value="success">저장 성공</option>
                <option value="nickname">닉네임 중복</option>
                <option value="save">저장 실패</option>
              </select>
            </label>
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            <button type="submit" className="primary-button">
              저장
            </button>
          </form>
        )}
        <div className="account-footer">
          <p>
            {account.provider === 'LOCAL'
              ? 'LOCAL 계정의 비밀번호 변경과 프로필 이미지 관리는 API 연동 시 제공됩니다.'
              : '소셜 계정에는 비밀번호 변경이 제공되지 않습니다.'}
          </p>
          {account.role === 'ADMIN' ? (
            <Link className="secondary-button" to="/admin/seller-applications">
              판매자 신청 심사 ↗
            </Link>
          ) : account.role === 'SELLER' ? (
            <Link className="secondary-button" to="/seller">
              판매자 작업 공간 ↗
            </Link>
          ) : (
            <Link className="secondary-button" to="/seller/apply">
              판매자 신청 ↗
            </Link>
          )}
          <button
            type="button"
            className="text-link"
            onClick={() => {
              onLogout()
              navigate('/wish')
            }}
          >
            로그아웃
          </button>
        </div>
      </section>
      <div className="my-columns">
        <section className="panel wish-panel">
          <div className="panel-heading">
            <h2>WISH</h2>
            <span>{totalWishes}</span>
          </div>
          {wishesQuery.isPending ? (
            <div role="status" aria-live="polite">
              <EmptyState title="WISH 목록을 불러오는 중이에요." />
            </div>
          ) : wishesQuery.isError ? (
            <div className="catalog-error" role="alert">
              <EmptyState
                title="WISH 목록을 불러오지 못했어요."
                description="잠시 후 다시 시도해주세요."
              />
              <button
                type="button"
                className="secondary-button"
                onClick={() => void wishesQuery.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : wishItems.length ? (
            <>
              {wishItems.map((item) => (
                <div className="compact-item" key={item.dropId}>
                  {item.thumbnailUrl ? (
                    <img src={item.thumbnailUrl} alt="" />
                  ) : (
                    <span role="img" aria-label={`${item.name} 이미지 없음`}>
                      이미지 준비 중
                    </span>
                  )}
                  <div>
                    <Link to={`/drops/${item.dropId}`}>
                      <strong>{item.name}</strong>
                    </Link>
                    <small>
                      {item.minPrice != null
                        ? `${won(item.minPrice)}부터`
                        : '가격 미정'}{' '}
                      · {WISH_STATUS_TEXT[item.status ?? ''] ?? item.status}
                    </small>
                    <small>
                      오픈 일정 ·{' '}
                      {item.saleStartsAt
                        ? dateLabel(item.saleStartsAt)
                        : '미정'}
                    </small>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      void toggleWish(item.dropId)
                    }}
                  >
                    취소
                  </button>
                </div>
              ))}
              {totalPages > 1 && (
                <div className="catalog-pagination">
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={page === 0}
                    onClick={() =>
                      setPage((current) => Math.max(0, current - 1))
                    }
                  >
                    이전
                  </button>
                  <span aria-live="polite">
                    {page + 1} / {totalPages} 페이지
                  </span>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={!wishesQuery.data?.hasNext}
                    onClick={() => setPage((current) => current + 1)}
                  >
                    다음
                  </button>
                </div>
              )}
            </>
          ) : (
            <EmptyState
              title="아직 담은 WISH가 없어요."
              link="/wish"
              label="WISH 둘러보기"
            />
          )}
        </section>
        <section className="panel order-panel">
          <div className="panel-heading">
            <h2>주문</h2>
            <span>{orders.length}</span>
          </div>
          {orders.length ? (
            orders.map((order) => (
              <div className="compact-item order" key={order.id}>
                <img src={order.drop.image} alt="" />
                <div>
                  <small>
                    {order.id} ·{' '}
                    {
                      buyerOrderStatusText[
                        buyerOrders.find((item) => item.id === order.id)
                          ?.status ?? 'PAYMENT_PENDING'
                      ]
                    }
                  </small>
                  <strong>{order.drop.name}</strong>
                  <small>
                    {order.optionLabel} · {order.quantity}개
                  </small>
                  <Link className="text-link" to={`/my/orders/${order.id}`}>
                    주문 상세 ↗
                  </Link>
                </div>
                <b>{won(order.total)}</b>
              </div>
            ))
          ) : (
            <EmptyState
              title="아직 주문이 없어요."
              link="/grab"
              label="GRAB 둘러보기"
            />
          )}
        </section>
      </div>
      <div className="my-demo-controls">
        <label>
          계정 유형 예시
          <select
            value={account.role}
            onChange={(event) =>
              switchAccount(event.target.value as MockAccount['role'])
            }
          >
            <option value="MEMBER">일반 회원 예시</option>
            <option value="SELLER">승인 판매자 예시</option>
            <option value="ADMIN">운영자 예시</option>
          </select>
        </label>
        <small>계정 유형 전환과 목록은 이 화면의 Mock 데모입니다.</small>
      </div>
    </section>
  )
}
