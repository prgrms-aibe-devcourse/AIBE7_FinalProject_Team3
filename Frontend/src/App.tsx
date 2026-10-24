import { useRef, useState } from 'react'
import { Route, Routes, useNavigate } from 'react-router-dom'
import Footer from './components/Footer/Footer'
import Header from './components/Header/Header'
import useAuth from './features/auth/logic/useAuth'
import { isSeller } from './features/auth/logic/authUser'
import useWish from './features/wish/logic/useWish'
import AuthCallbackPage from './pages/AuthCallback/AuthCallbackPage'
import CheckoutPage from './pages/Checkout/CheckoutPage'
import DropDetailPage from './pages/DropDetail/DropDetailPage'
import DropListPage from './pages/DropList/DropListPage'
import HomePage from './pages/Home/HomePage'
import LoginPage from './pages/Login/LoginPage'
import MyPage from './pages/MyPage/MyPage'
import OrderDetailPage from './pages/OrderDetail/OrderDetailPage'
import SignupPage from './pages/Signup/SignupPage'
import NotFoundPage from './pages/NotFound/NotFoundPage'
import SellerDashboardPage from './pages/SellerDashboard/SellerDashboardPage'
import SellerDropDetailPage from './pages/SellerDropDetail/SellerDropDetailPage'
import SellerDropFormPage from './pages/SellerDropForm/SellerDropFormPage'
import SellerDropsPage from './pages/SellerDrops/SellerDropsPage'
import SellerOrdersPage from './pages/SellerOrders/SellerOrdersPage'
import SellerInventoryPage from './pages/SellerInventory/SellerInventoryPage'
import SellerSalesPage from './pages/SellerSales/SellerSalesPage'
import SellerApplyPage from './pages/SellerApply/SellerApplyPage'
import AdminSellerApplicationsPage from './pages/AdminSellerApplications/AdminSellerApplicationsPage'
import type { SharedProps } from './types/store'

export default function App() {
  const navigate = useNavigate()
  const [toast, setToast] = useState('')

  const toastTimer = useRef<number | undefined>(undefined)

  // 새 안내는 이전 안내의 타이머를 지워 제 시간(2.6초)만큼 보이게 한다.
  // 이전 타이머가 남아 있으면 로그인 직후의 안내가 곧바로 지워진다(GR-132).
  const notify = (message: string) => {
    setToast(message)
    window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(''), 2600)
  }
  const { user, status, authenticated, login, refresh, logout } = useAuth()
  const { wishes, wishesReady, toggleWish } = useWish(authenticated, notify)

  // 헤더 계정 메뉴의 로그아웃(GR-132). 마이페이지 로그아웃과 같이 성공하면 WISH로 이동하고,
  // 실패하면 로그인 상태를 유지한 채 안내한다.
  const logoutFromHeader = async () => {
    try {
      await logout()
      navigate('/wish')
    } catch {
      notify('로그아웃하지 못했어요. 잠시 뒤 다시 시도해주세요.')
    }
  }

  const shared: SharedProps = {
    authenticated,
    authStatus: status,
    wishes,
    wishesReady,
    notify,
    toggleWish,
  }

  return (
    <div className="app-shell">
      <Header
        authenticated={authenticated}
        seller={isSeller(user)}
        account={
          user
            ? {
                imageUrl: user.profileImageUrl,
                name: user.nickname,
                onLogout: logoutFromHeader,
              }
            : null
        }
      />
      <main>
        <Routes>
          <Route path="/" element={<HomePage {...shared} />} />
          <Route
            path="/wish"
            element={<DropListPage status="WISH" {...shared} />}
          />
          <Route
            path="/grab"
            element={<DropListPage status="GRAB" {...shared} />}
          />
          <Route
            path="/drops/:dropId"
            element={<DropDetailPage {...shared} />}
          />
          <Route
            path="/checkout"
            element={<CheckoutPage authStatus={status} notify={notify} />}
          />
          <Route
            path="/checkout/:orderId"
            element={<CheckoutPage authStatus={status} notify={notify} />}
          />
          <Route
            path="/my/orders/:orderId"
            element={<OrderDetailPage authStatus={status} notify={notify} />}
          />
          <Route
            path="/my"
            element={<MyPage {...shared} user={user} onLogout={logout} />}
          />
          <Route
            path="/login"
            element={<LoginPage login={login} notify={notify} />}
          />
          <Route
            path="/auth/callback"
            element={<AuthCallbackPage refreshAuth={refresh} notify={notify} />}
          />
          <Route
            path="/signup"
            element={<SignupPage refreshAuth={refresh} notify={notify} />}
          />
          <Route path="/seller" element={<SellerDashboardPage />} />
          <Route
            path="/seller/apply"
            element={
              <SellerApplyPage
                user={user}
                authStatus={status}
                notify={notify}
              />
            }
          />
          <Route
            path="/admin/seller-applications"
            element={
              <AdminSellerApplicationsPage
                user={user}
                authStatus={status}
                notify={notify}
              />
            }
          />
          <Route
            path="/seller/orders"
            element={<SellerOrdersPage notify={notify} />}
          />
          <Route
            path="/seller/inventory"
            element={<SellerInventoryPage user={user} authStatus={status} />}
          />
          <Route
            path="/seller/sales"
            element={<SellerSalesPage user={user} authStatus={status} />}
          />
          <Route
            path="/seller/drops"
            element={
              <SellerDropsPage
                user={user}
                authStatus={status}
                notify={notify}
              />
            }
          />
          <Route
            path="/seller/drops/new"
            element={
              <SellerDropFormPage
                onPublished={(id) =>
                  navigate(`/seller/drops/${id}`, { replace: true })
                }
                notify={notify}
              />
            }
          />
          <Route
            path="/seller/drops/:dropId/edit"
            element={
              <SellerDropFormPage
                onPublished={(id) =>
                  navigate(`/seller/drops/${id}`, { replace: true })
                }
                notify={notify}
              />
            }
          />
          <Route
            path="/seller/drops/:dropId"
            element={
              <SellerDropDetailPage
                user={user}
                authStatus={status}
                notify={notify}
              />
            }
          />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>
      <Footer />
      <div
        className={`toast ${toast ? 'is-visible' : ''}`}
        role="status"
        aria-live="polite"
      >
        {toast}
      </div>
    </div>
  )
}
