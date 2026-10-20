import { defineConfig, devices } from '@playwright/test'

const PORT = 5174

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  // 불안정한 테스트를 재시도로 덮지 않는다. 실패는 바로 드러나야 한다.
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: `http://localhost:${PORT}`,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 },
      },
    },
    {
      name: 'mobile',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: true,
    // 결제창은 가짜 스크립트로 대체하므로 실제 키 없이 형식만 맞춘 값을 쓴다(e2e/mock/toss.ts).
    env: { VITE_TOSS_CLIENT_KEY: 'test_ck_e2e' },
  },
})
