import { expect, test as base, type Page } from '@playwright/test'
import { FIXED_NOW, installPublicApi } from './mock/api'
import { getAuth, installAuthApi } from './mock/auth'
import { installStockStream } from './mock/stockStream'
import { installDropImageUploads } from './mock/dropImages'
import { installSellerDropApi } from './mock/sellerDrops'
import { installFakeToss } from './mock/toss'

export type { Page }
export { getAuth }

const PLACEHOLDER_IMAGE = 'src/assets/images/grab-symbol.png'

// fixture 인자를 use 대신 provide로 받는다. react-hooks 린트가 use를 React 훅으로 오인한다.
export const test = base.extend({
  page: async ({ page }, provide) => {
    await page.clock.setFixedTime(FIXED_NOW)
    await installStockStream(page)
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
    // 토스 결제창 스크립트만 가짜로 돌린다(위 차단보다 나중에 등록해 우선한다).
    await installFakeToss(page)
    // 공개 DROP API를 fixture로 고정한다. 시나리오 테스트는 뒤에서 다시 등록해 덮어쓴다.
    await installPublicApi(page)
    await installDropImageUploads(page)
    await installSellerDropApi(page)
    // 인증 API는 공개 API 뒤에 등록해 인증 경로만 처리하고 나머지는 위 핸들러로 넘긴다.
    installAuthApi(page)
    await provide(page)
  },
})

export { expect }

async function submitLogin(page: Page) {
  await page.goto('/#/login')
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/#\/wish$/)
}

// 인증 상태는 users/me 쿼리에서 오므로, role은 로그인 전에 mock 상태로 정한다.
export async function login(page: Page) {
  await submitLogin(page)
}

export async function loginSeller(page: Page) {
  getAuth(page).setRoles(['USER', 'SELLER'])
  await submitLogin(page)
}

export async function loginAdmin(page: Page) {
  getAuth(page).setRoles(['USER', 'ADMIN'])
  await submitLogin(page)
}

export const toast = (page: Page) => page.locator('.toast')
