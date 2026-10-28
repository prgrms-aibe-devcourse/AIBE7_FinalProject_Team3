import { expect, login, test, type Page } from '../../fixtures'

// 마이페이지 계정 카드·주문 행(GR-160). 닉네임·비밀번호 모달은 my-account.spec.ts에서 확인한다.

const orders = (page: Page) => page.locator('.compact-item.order')
const stat = (page: Page, name: '주문' | 'WISH') =>
  page.locator('.account-stats div').filter({ hasText: name }).locator('dd')

async function openMyPage(page: Page) {
  await login(page)
  await page.goto('/#/my')
  await expect(orders(page)).toHaveCount(8)
}

test('계정 카드에 기본 프로필·역할 배지·주문/WISH 개수를 보여 준다', async ({
  page,
}) => {
  await openMyPage(page)
  const card = page.getByRole('region', { name: '내 계정' })
  // 프로필 이미지가 없으면 닉네임 첫 글자를 표시한다
  await expect(card.locator('.avatar')).toHaveText('드')
  await expect(card.locator('.avatar img')).toHaveCount(0)
  await expect(card.locator('.account-heading strong')).toHaveText('드롭헌터')
  await expect(card.locator('.account-badge')).toHaveText(['일반 회원'])
  await expect(stat(page, '주문')).toHaveText('8')
  await expect(stat(page, 'WISH')).toHaveText('1')
  // 계정 카드에는 설정 이동 버튼을 두지 않는다
  await expect(card.getByRole('button')).toHaveCount(0)
})

test('주문·WISH 조회에 실패하면 계정 카드의 개수를 비워 둔다', async ({
  page,
}) => {
  await page.route('**/api/v1/orders?*', (route) =>
    route.fulfill({
      status: 500,
      json: {
        success: false,
        data: null,
        error: { code: 'INTERNAL_SERVER_ERROR', message: '실패' },
      },
    }),
  )
  await login(page)
  await page.goto('/#/my')
  await expect(page.getByText('주문 목록을 불러오지 못했어요.')).toBeVisible()
  await expect(stat(page, '주문')).toHaveText('')
  await expect(stat(page, 'WISH')).toHaveText('1')
})

test('주문 행은 상태 배지를 두고 결제 대기만 결제하기로 이동한다', async ({
  page,
}) => {
  await openMyPage(page)
  await expect(orders(page).locator('.order-status')).toHaveText([
    '결제 대기',
    '결제 대기',
    '결제 완료',
    '배송 준비',
    '배송 중',
    '배송 완료',
    '취소 완료',
    '주문 만료',
  ])
  await expect(orders(page).locator('.order-status.pending')).toHaveCount(2)
  await expect(
    orders(page).getByRole('link', { name: /결제하기$/ }),
  ).toHaveCount(2)
  await expect(
    orders(page).getByRole('link', { name: /주문 상세$/ }),
  ).toHaveCount(6)

  await page.getByRole('link', { name: 'ORD-20261001-000001 결제하기' }).click()
  await expect(page).toHaveURL(/#\/checkout\/[^/]+$/)
  await expect(page.getByRole('button', { name: '결제하기' })).toBeVisible()
})

test('일반 회원은 계정 설정에서 판매자 신청으로 이동한다', async ({ page }) => {
  await openMyPage(page)
  const seller = page.getByRole('group', { name: '판매자' })
  await expect(seller).toContainText('신청 후 운영자 승인이 필요해요')
  await seller.getByRole('link', { name: '판매자 신청 ↗' }).click()
  await expect(page).toHaveURL(/#\/seller\/apply$/)
})

// GR-195: 판매 종료·취소된 WISH는 목록에서 비슷한 굿즈를 펼쳐 볼 수 있다(DROP.md 1.4)
test('판매 종료·취소된 WISH만 비슷한 굿즈를 펼쳐 보고 상세로 이동한다', async ({
  page,
}) => {
  const item = (dropId: number, name: string, status: string) => ({
    dropId,
    name,
    thumbnailUrl: null,
    minPrice: 39000,
    status,
    saleStartsAt: '2026-09-20T10:00:00+09:00',
    wishedAt: '2026-09-10T10:00:00+09:00',
  })
  await page.route('**/api/v1/users/me/wishes**', (route) =>
    route.fulfill({
      json: {
        success: true,
        data: {
          content: [
            item(301, '에브리데이 캔버스 백 · 판매 종료', 'ENDED'),
            item(9001, '취소된 굿즈', 'CANCELED'),
            item(101, '판매 전 굿즈', 'WISH'),
          ],
          page: 0,
          size: 20,
          totalElements: 3,
          totalPages: 1,
          hasNext: false,
        },
      },
    }),
  )
  await openMyPage(page)
  const wishes = page.locator('.wish-panel')
  const toggles = wishes.getByRole('button', { name: '비슷한 굿즈 보기' })
  await expect(toggles).toHaveCount(2)

  await toggles.first().click()
  const ended = wishes.locator('#wish-related-301')
  await expect(ended).toContainText('비슷한 굿즈가 곧 열려요')
  await expect(ended.getByRole('link')).toHaveCount(2)
  await expect(
    wishes.getByRole('button', { name: '비슷한 굿즈 접기' }),
  ).toHaveAttribute('aria-expanded', 'true')

  // 한 번에 하나만 펼친다
  await wishes.getByRole('button', { name: '비슷한 굿즈 보기' }).click()
  await expect(ended).toHaveCount(0)
  await expect(wishes.locator('#wish-related-9001')).toHaveText(
    '지금 WISH할 수 있는 비슷한 굿즈가 없어요.',
  )

  await wishes.getByRole('button', { name: '비슷한 굿즈 접기' }).click()
  await wishes.getByRole('button', { name: '비슷한 굿즈 보기' }).first().click()
  await ended.getByRole('link').first().click()
  await expect(page).toHaveURL(/#\/drops\/101$/)
})
