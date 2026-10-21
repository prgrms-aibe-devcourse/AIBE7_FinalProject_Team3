import { Link, NavLink, useLocation } from 'react-router-dom'
import wordmark from '../../assets/images/grab-wordmark.png'

export default function Header({
  authenticated,
  seller,
}: {
  authenticated: boolean
  seller: boolean
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
        {!sellerMode && (
          <Link
            className="account-button"
            to={authenticated ? '/my' : '/login'}
          >
            {authenticated ? 'MY' : '로그인'}
          </Link>
        )}
      </div>
    </header>
  )
}
