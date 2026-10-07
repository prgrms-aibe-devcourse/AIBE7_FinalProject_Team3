import { expect, test as base, type Page } from '@playwright/test'

// Mock 일정이 모두 Date.now() 기준이라 시각을 고정해야 D-day·날짜 표시가 매번 같다.
export const FIXED_NOW = new Date('2026-10-01T10:00:00+09:00')

const PLACEHOLDER_IMAGE = 'src/assets/images/grab-symbol.png'

// fixture 인자를 use 대신 provide로 받는다. react-hooks 린트가 use를 React 훅으로 오인한다.
export const test = base.extend({
  page: async ({ page }, provide) => {
    await page.clock.setFixedTime(FIXED_NOW)
    // 외부 자원(Unsplash·쇼핑몰 이미지, Google Fonts)에 기대지 않는다.
    // 이미지는 로컬 파일로 대신하고 나머지는 막는다.
    // ponytail: 폰트는 막아서 실행 환경의 대체 글꼴로 그려진다. 실제 글꼴 검증이 필요하면 폰트 파일을 로컬에 두고 route로 돌린다.
    await page.route(
      (url) => url.hostname !== 'localhost',
      (route) =>
        route.request().resourceType() === 'image'
          ? route.fulfill({ path: PLACEHOLDER_IMAGE })
          : route.abort(),
    )
    await provide(page)
  },
})

export { expect }

// 로그인 상태는 메모리에만 있어 page.goto로 새로 열면 사라진다. 로그인 후에는 화면 안 링크로 이동한다.
export async function login(page: Page) {
  await page.goto('/#/login')
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page).toHaveURL(/#\/wish$/)
}

export const toast = (page: Page) => page.getByRole('status')
