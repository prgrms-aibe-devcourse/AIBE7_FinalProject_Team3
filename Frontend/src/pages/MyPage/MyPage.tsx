import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import {
  mockSignupErrors,
  nicknameError,
  type MockAccount,
} from '../../features/auth/mock/mockAuth'
import { drops } from '../../features/drop/mock/mockDrops'
import {
  buyerOrderStatusText,
  type BuyerOrder,
} from '../../features/order/mock/mockBuyerOrders'
import type { SharedProps } from '../../types/store'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

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
  wishes,
  orders,
  buyerOrders,
  toggleWish,
  notify,
}: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const [editing, setEditing] = useState(false)
  const [nickname, setNickname] = useState(account?.nickname ?? '')
  const [saveResult, setSaveResult] = useState<'success' | 'nickname' | 'save'>(
    'success',
  )
  const [error, setError] = useState('')

  if (!account)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />

  const wishedDrops = drops.filter((drop) => wishes.has(drop.id))
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
              {account.role === 'SELLER' ? '승인된 판매자' : '일반 회원'}
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
          {account.role === 'SELLER' ? (
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
            <span>{wishedDrops.length}</span>
          </div>
          {wishedDrops.length ? (
            wishedDrops.map((drop) => (
              <div className="compact-item" key={drop.id}>
                <img src={drop.image} alt="" />
                <div>
                  <Link to={`/drops/${drop.id}`}>
                    <strong>{drop.name}</strong>
                  </Link>
                  <small>{dateLabel(drop.saleStartsAt)} 오픈</small>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    if (toggleWish(drop.id)) notify('WISH를 취소했어요.')
                  }}
                >
                  취소
                </button>
              </div>
            ))
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
              switchAccount(
                event.target.value === 'SELLER' ? 'SELLER' : 'MEMBER',
              )
            }
          >
            <option value="MEMBER">일반 회원 예시</option>
            <option value="SELLER">승인 판매자 예시</option>
          </select>
        </label>
        <small>계정 유형 전환과 목록은 이 화면의 Mock 데모입니다.</small>
      </div>
    </section>
  )
}
