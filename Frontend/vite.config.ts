/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_API_PROXY_TARGET')
  const apiProxyTarget =
    env.VITE_API_PROXY_TARGET?.trim() || 'http://localhost:8080'

  return {
    plugins: [react()],
    server: { proxy: { '/api': apiProxyTarget } },
    // e2e/*.spec.ts는 Playwright가 실행한다.
    test: { include: ['src/**/*.test.ts'] },
  }
})
