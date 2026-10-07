/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // e2e/*.spec.ts는 Playwright가 실행한다.
  test: { include: ['src/**/*.test.ts'] },
})
