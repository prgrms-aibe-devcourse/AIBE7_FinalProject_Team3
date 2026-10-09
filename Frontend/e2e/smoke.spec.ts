import { expect, test } from './fixtures'

test('첫 화면은 메인 화면을 보여준다', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { level: 1 })).toContainText(
    '발견한 취향을 놓치지 않도록.',
  )
})
