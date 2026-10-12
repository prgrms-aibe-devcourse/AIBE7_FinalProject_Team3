import { useState } from 'react'
import { Route, Routes } from 'react-router-dom'
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
import SellerOrdersPage from './pages/SellerOrders/SellerOrdersPage'
import type { SharedProps } from './types/store'

export default function App() {
  const [toast, setToast] = useState('')

  const notify = (message: string) => {
    setToast(message)
    window.setTimeout(() => setToast(''), 2600)
  }
  const { account, authenticated, login, logout } = useAuth()
  const { wishes, toggleWish } = useWish(authenticated, notify)
  const { orders, buyerOrders, addOrder, updateBuyerOrder } = useOrders()

  const shared: SharedProps = {
    authenticated,
    wishes,
    orders,
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
                authenticated={authenticated}
                buyerOrders={buyerOrders}
                updateBuyerOrder={updateBuyerOrder}
                notify={notify}
              />
            }
          />
          <Route
            path="/my/orders/:orderId"
            element={
              <OrderDetailPage
                authenticated={authenticated}
                buyerOrders={buyerOrders}
                updateBuyerOrder={updateBuyerOrder}
                notify={notify}
              />
            }
          />
          <Route
            path="/my"
            element={
              <MyPage
                {...shared}
                buyerOrders={buyerOrders}
                account={account}
                onAccountChange={login}
                onLogout={logout}
              />
            }
          />
          <Route
            path="/login"
            element={<LoginPage onLogin={login} notify={notify} />}
          />
          <Route
            path="/signup"
            element={<SignupPage onLogin={login} notify={notify} />}
          />
          <Route
            path="/seller"
            element={<SellerDashboardPage notify={notify} />}
          />
          <Route
            path="/seller/orders"
            element={<SellerOrdersPage notify={notify} />}
          />
          <Route
            path="/seller/drops/new"
            element={<SellerDropFormPage notify={notify} />}
          />
          <Route
            path="/seller/drops/:dropId"
            element={<SellerDropDetailPage />}
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
