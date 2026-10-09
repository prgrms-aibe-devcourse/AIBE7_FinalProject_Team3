import { expect, test } from './fixtures'

test('첫 화면은 WISH 목록으로 이동한다', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveURL(/#\/wish$/)
  await expect(page.getByRole('heading', { level: 1 })).toContainText(
    '다가올 DROP을 WISH',
  )
  // 고정 시각 기준으로 3일 뒤 오픈하는 DROP(101)
  await expect(
    page.getByRole('article').filter({ hasText: '매일을 담는 캔버스 토트' }),
  ).toContainText('OPEN D−3')
})
