import { Link, NavLink, useLocation } from 'react-router-dom'
import wordmark from '../../assets/images/grab-wordmark.png'
import AccountMenu from './AccountMenu'

// 로그인 상태 계정 메뉴에 그릴 프로필 이미지·기본 표시용 이름과 로그아웃 처리(GR-132).
// 값과 로그아웃 후 이동·실패 안내는 App이 맡는다.
export type HeaderAccount = {
  imageUrl: string | null
  name: string
  onLogout: () => Promise<void>
}

export default function Header({
  authenticated,
  seller,
  account,
}: {
  authenticated: boolean
  seller: boolean
  account: HeaderAccount | null
}) {
  const location = useLocation()
  const sellerMode =
    /^\/seller(?:\/|$)/.test(location.pathname) &&
    !/^\/seller\/apply\/?$/.test(location.pathname)

  return (
    <header className="site-header">
      <Link className="header-brand" to="/" aria-label="GRAB 홈">
        <img className="header-wordmark" src={wordmark} alt="GRAB" />
      </Link>
      <nav
        aria-label="주요 메뉴"
        className={sellerMode ? 'seller-nav' : undefined}
      >
        {sellerMode ? (
          <>
            <NavLink end to="/seller">
              DASHBOARD
            </NavLink>
            <NavLink to="/seller/drops">DROPS</NavLink>
            <NavLink to="/seller/orders">ORDERS</NavLink>
            <NavLink to="/seller/inventory">INVENTORY</NavLink>
            <NavLink to="/seller/sales">SALES</NavLink>
          </>
        ) : (
          <>
            <NavLink to="/wish">WISH</NavLink>
            <NavLink to="/grab">GRAB</NavLink>
            {seller && <NavLink to="/seller">SELLER</NavLink>}
          </>
        )}
      </nav>
      <div className="header-actions">
        {sellerMode && (
          <Link className="secondary-button" to="/wish">
            SHOP ↗
          </Link>
        )}
        {/* 로그인 상태는 프로필 이미지 버튼으로 마이페이지·로그아웃 메뉴를 연다 */}
        {!sellerMode &&
          (authenticated && account ? (
            <AccountMenu
              imageUrl={account.imageUrl}
              name={account.name}
              onLogout={account.onLogout}
            />
          ) : (
            <Link className="account-button" to="/login">
              로그인
            </Link>
          ))}
      </div>
    </header>
  )
}
