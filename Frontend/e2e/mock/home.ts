import type { Page } from '@playwright/test'

// Vite가 제공하는 Mock 모듈만 대체한다. 앱의 상품 데이터나 라우트에 테스트용 상태를 추가하지 않는다.
export async function installHomeScenario(
  page: Page,
  scenario: 'empty' | 'failure',
) {
  const scripts = {
    empty: `homeDrops.GRAB.splice(0);`,
    failure: `
      Object.defineProperty(homeDrops, 'GRAB', {
        configurable: true, get() { throw new Error('홈 인기 Mock 실패'); },
      });
    `,
  }
  await page.route('**/src/features/drop/mock/mockHome.ts', async (route) => {
    const response = await route.fetch()
    await route.fulfill({
      response,
      body: `${await response.text()}\n${scripts[scenario]}`,
    })
  })
}
