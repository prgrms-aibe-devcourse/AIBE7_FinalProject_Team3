import { expect, test } from './fixtures'

test('첫 화면은 메인 화면을 보여준다', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveURL(/\/$/)
  await expect(
    page.getByRole('img', {
      name: 'GRAB — Grab what you want. 판매자의 DROP이 당신의 GRAB으로.',
    }),
  ).toBeVisible()
})
