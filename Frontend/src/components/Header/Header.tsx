import { Link, NavLink, useLocation } from 'react-router-dom'
import logo from '../../../asset/Logo.png'

export default function Header({ authenticated }: { authenticated: boolean }) {
  const location = useLocation()
  const sellerMode = location.pathname.startsWith('/seller')

  return (
    <header className="site-header">
      <Link className="brand" to={sellerMode ? '/seller' : '/wish'} aria-label="GRAB 홈">
        <img src={logo} alt="" />
        <span>GRAB</span>
      </Link>
      <nav aria-label="주요 메뉴">
        {sellerMode ? (
          <NavLink to="/seller">DROP STUDIO</NavLink>
        ) : (
          <>
            <NavLink to="/wish">WISH</NavLink>
            <NavLink to="/grab">GRAB</NavLink>
          </>
        )}
      </nav>
      <div className="header-actions">
        <Link className="mode-button" to={sellerMode ? '/wish' : '/seller'}>
          {sellerMode ? '쇼퍼 전환' : '셀러 전환'} <span aria-hidden="true">↗</span>
        </Link>
        {!sellerMode && (
          <Link className="account-button" to={authenticated ? '/my' : '/login'}>
            {authenticated ? 'MY' : '로그인'}
          </Link>
        )}
      </div>
    </header>
  )
}
