import { useState } from 'react'
import { Route, Routes, useNavigate } from 'react-router-dom'
import Footer from './components/Footer/Footer'
import Header from './components/Header/Header'
import useAuth from './features/auth/logic/useAuth'
import useOrders from './features/order/logic/useOrders'
import useWish from './features/wish/logic/useWish'
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

  const notify = (message: string) => {
    setToast(message)
    window.setTimeout(() => setToast(''), 2600)
  }
  const { user, status, authenticated, login, logout } = useAuth()
  const { wishes, wishesReady, toggleWish } = useWish(authenticated, notify)
  const { buyerOrders, addOrder, updateBuyerOrder } = useOrders()

  const shared: SharedProps = {
    authenticated,
    authStatus: status,
    wishes,
    wishesReady,
    notify,
    toggleWish,
    addOrder,
  }

  return (
    <div className="app-shell">
      <Header authenticated={authenticated} />
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
            path="/checkout/:orderId"
            element={
              <CheckoutPage
                authStatus={status}
                buyerOrders={buyerOrders}
                updateBuyerOrder={updateBuyerOrder}
                notify={notify}
              />
            }
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
          <Route path="/signup" element={<SignupPage notify={notify} />} />
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
