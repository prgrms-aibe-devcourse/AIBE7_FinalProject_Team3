import { useState } from 'react'
import type { MockAccount } from './mockAuth'

export default function useAuth() {
  const [account, setAccount] = useState<MockAccount | null>(null)
  return {
    account,
    authenticated: account !== null,
    login: setAccount,
    logout: () => setAccount(null),
  }
}
