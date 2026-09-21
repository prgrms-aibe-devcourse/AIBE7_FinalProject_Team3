import { useState } from 'react'

export default function useAuth() {
  const [authenticated, setAuthenticated] = useState(false)
  return { authenticated, login: () => setAuthenticated(true) }
}
