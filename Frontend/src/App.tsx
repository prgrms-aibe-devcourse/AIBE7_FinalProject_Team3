import { useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import Footer from './components/Footer/Footer'
import Header from './components/Header/Header'
import useAuth from './features/auth/useAuth'
import useOrders from './features/order/useOrders'
import useWish from './features/wish/useWish'
import DropDetailPage from './pages/DropDetail/DropDetailPage'
import DropListPage from './pages/DropList/DropListPage'
import LoginPage from './pages/Login/LoginPage'
import MyPage from './pages/MyPage/MyPage'
import NotFoundPage from './pages/NotFound/NotFoundPage'
import SellerDashboardPage from './pages/SellerDashboard/SellerDashboardPage'
import SellerDropFormPage from './pages/SellerDropForm/SellerDropFormPage'
import type { SharedProps } from './types/store'

export default function App() {
  const [toast, setToast] = useState('')

  const notify = (message: string) => {
    setToast(message)
    window.setTimeout(() => setToast(''), 2600)
  }
  const { authenticated, login } = useAuth()
  const { wishes, toggleWish } = useWish(authenticated, notify)
  const { orders, addOrder, updateOrder } = useOrders()

  const shared: SharedProps = {
    authenticated,
    wishes,
    orders,
    notify,
    toggleWish,
    addOrder,
    updateOrder,
  }

  return (
    <div className="app-shell">
      <Header authenticated={authenticated} />
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/wish" replace />} />
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
          <Route path="/my" element={<MyPage {...shared} />} />
          <Route
            path="/login"
            element={<LoginPage mode="login" onLogin={login} notify={notify} />}
          />
          <Route
            path="/signup"
            element={
              <LoginPage mode="signup" onLogin={login} notify={notify} />
            }
          />
          <Route path="/seller" element={<SellerDashboardPage />} />
          <Route
            path="/seller/drops/new"
            element={<SellerDropFormPage notify={notify} />}
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
