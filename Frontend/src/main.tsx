import { StrictMode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import App from './App'
import { prepareCsrf } from './api/auth/csrf'
import { configureAuthSession, installAuthSession } from './api/auth/session'
import { authKeys } from './features/auth/logic/queryKeys'
import './styles/global.css'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
})

// 세션 만료 시 인터셉터가 QueryClient에 직접 의존하지 않도록 콜백으로 연결한다(M01-04).
configureAuthSession({
  onSessionExpired: () => queryClient.setQueryData(authKeys.me(), null),
})
installAuthSession()
// 앱 시작 시 CSRF 쿠키를 한 번 준비한다(M00-05).
void prepareCsrf()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <HashRouter>
        <App />
      </HashRouter>
    </QueryClientProvider>
  </StrictMode>,
)
