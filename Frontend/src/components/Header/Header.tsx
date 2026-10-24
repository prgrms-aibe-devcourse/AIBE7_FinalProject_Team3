import { Link, NavLink, useLocation } from 'react-router-dom'
import wordmark from '../../assets/images/grab-wordmark.png'
import Avatar from '../Avatar/Avatar'

// 로그인 상태 MY 링크에 그릴 프로필 이미지와 기본 표시용 이름(GR-132). 값은 App이 내 정보에서 넘긴다.
export type HeaderAccount = { imageUrl: string | null; name: string }

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
        {/* 로그인 상태 MY는 프로필 이미지로 그린다. 이미지는 장식이고 링크 이름은 "마이페이지"다. */}
        {!sellerMode &&
          (authenticated && account ? (
            <Link className="account-avatar" to="/my" aria-label="마이페이지">
              <Avatar src={account.imageUrl} name={account.name} size={36} />
            </Link>
          ) : (
            <Link className="account-button" to="/login">
              로그인
            </Link>
          ))}
      </div>
    </header>
  )
}
