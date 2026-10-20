import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getMyWishes } from '../../api/wish/wishes'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  isAdmin,
  isSeller,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import { nicknameError } from '../../features/auth/logic/signupValidation'
import { mockSignupErrors } from '../../features/auth/mock/mockAuth'
import { wishKeys } from '../../features/wish/logic/queryKeys'
import { buyerOrderStatusText } from '../../features/order/logic/buyerOrderView'
import { useMyOrders } from '../../features/order/logic/useBuyerOrders'
import type { SharedProps } from '../../types/store'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

const WISH_PAGE_SIZE = 20
const ORDER_PAGE_SIZE = 20

const WISH_STATUS_TEXT: Record<string, string> = {
  WISH: 'WISH',
  GRAB: 'GRAB',
  ENDED: '판매 종료',
  CANCELED: '판매 취소',
  DRAFT: '준비 중',
}

type Props = SharedProps & {
  user: AuthUser | null
  onLogout: () => Promise<void>
}

export default function MyPage({
  user,
  onLogout,
  authStatus,
  toggleWish,
  notify,
}: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const [editing, setEditing] = useState(false)
  const [page, setPage] = useState(0)
  const [orderPage, setOrderPage] = useState(0)
  const [nickname, setNickname] = useState(user?.nickname ?? '')
  const [saveResult, setSaveResult] = useState<'success' | 'nickname' | 'save'>(
    'success',
  )
  const [error, setError] = useState('')
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')

  const wishesQuery = useQuery({
    queryKey: wishKeys.list(page, WISH_PAGE_SIZE),
    queryFn: () => getMyWishes({ page, size: WISH_PAGE_SIZE }),
    enabled: authStatus === 'authenticated',
  })
  const wishItems = (wishesQuery.data?.content ?? []).flatMap((item) =>
    item.dropId === undefined ? [] : [{ ...item, dropId: item.dropId }],
  )
  const totalWishes = wishesQuery.data?.totalElements ?? 0
  const totalPages = wishesQuery.data?.totalPages ?? 0
  const ordersQuery = useMyOrders(
    { page: orderPage, size: ORDER_PAGE_SIZE },
    authStatus === 'authenticated',
  )
  const orderItems = ordersQuery.data?.content ?? []
  const totalOrderPages = ordersQuery.data?.totalPages ?? 0

  if (authStatus === 'loading') return <AuthLoading />
  // 로그아웃 완료 후의 이동은 아래 처리에 맡겨 로그인 가드와 경합하지 않게 한다.
  if (!user && loggingOut) return <AuthLoading />
  if (!user)
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
    // 사용자 정보가 users/me 캐시라 화면에서만 바꾼 값은 저장되지 않는다. 실제 연동 전까지 안내만 한다(M00-07).
    setEditing(false)
    setError('')
    notify('닉네임 변경은 API 연동 후 제공됩니다.')
  }

  const logout = async () => {
    if (loggingOut) return
    setLoggingOut(true)
    setLogoutError('')
    try {
      await onLogout()
      navigate('/wish')
    } catch {
      setLogoutError('로그아웃하지 못했어요. 잠시 뒤 다시 시도해주세요.')
      setLoggingOut(false)
    }
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
            <strong>{user.nickname}</strong>
            <p>{user.email}</p>
            <small>
              {user.provider} 계정 ·{' '}
              {isAdmin(user)
                ? '운영자'
                : isSeller(user)
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
              setNickname(user.nickname)
            }}
          >
            {editing ? '닫기' : '정보 수정'}
          </button>
        </div>
        {editing && (
          <form className="account-edit" onSubmit={saveNickname}>
            <label>
              이메일
              <input readOnly value={user.email} />
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
            {user.provider === 'LOCAL'
              ? 'LOCAL 계정의 비밀번호 변경과 프로필 이미지 관리는 API 연동 시 제공됩니다.'
              : '소셜 계정에는 비밀번호 변경이 제공되지 않습니다.'}
          </p>
          {isAdmin(user) ? (
            <Link className="secondary-button" to="/admin/seller-applications">
              판매자 신청 심사 ↗
            </Link>
          ) : isSeller(user) ? (
            <Link className="secondary-button" to="/seller">
              판매자 작업 공간 ↗
            </Link>
          ) : (
            <Link className="secondary-button" to="/seller/apply">
              판매자 신청 ↗
            </Link>
          )}
          {logoutError && (
            <p className="form-error" role="alert">
              {logoutError}
            </p>
          )}
          <button
            type="button"
            className="text-link"
            disabled={loggingOut}
            onClick={() => void logout()}
          >
            {loggingOut ? '로그아웃 중…' : '로그아웃'}
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
            <span>{ordersQuery.data?.totalElements ?? 0}</span>
          </div>
          {ordersQuery.isPending ? (
            <div role="status" aria-live="polite">
              <EmptyState title="주문 목록을 불러오는 중이에요." />
            </div>
          ) : ordersQuery.isError ? (
            <div className="catalog-error" role="alert">
              <EmptyState
                title="주문 목록을 불러오지 못했어요."
                description="잠시 후 다시 시도해주세요."
              />
              <button
                type="button"
                className="secondary-button"
                onClick={() => void ordersQuery.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : orderItems.length ? (
            <>
              {orderItems.map((order) => (
                <div className="compact-item order" key={order.id}>
                  <div>
                    <small>
                      {order.orderNumber} · {buyerOrderStatusText[order.status]}
                    </small>
                    <strong>{order.productName}</strong>
                    <small>주문일 {dateLabel(order.orderedAt)}</small>
                    <Link className="text-link" to={`/my/orders/${order.id}`}>
                      주문 상세 ↗
                    </Link>
                  </div>
                  <b>{won(order.totalAmount)}</b>
                </div>
              ))}
              {totalOrderPages > 1 && (
                <div className="catalog-pagination">
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={orderPage === 0}
                    onClick={() =>
                      setOrderPage((current) => Math.max(0, current - 1))
                    }
                  >
                    이전
                  </button>
                  <span aria-live="polite">
                    {orderPage + 1} / {totalOrderPages} 페이지
                  </span>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={!ordersQuery.data?.hasNext}
                    onClick={() => setOrderPage((current) => current + 1)}
                  >
                    다음
                  </button>
                </div>
              )}
            </>
          ) : (
            <EmptyState
              title="아직 주문이 없어요."
              link="/grab"
              label="GRAB 둘러보기"
            />
          )}
        </section>
      </div>
    </section>
  )
}
