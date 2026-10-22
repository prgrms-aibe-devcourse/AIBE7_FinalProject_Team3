import { expect, test } from '../../fixtures'

const serverNow = Date.parse('2026-10-22T10:00:00+09:00')

test('서버 시각으로 10분 경계에 진입하고 화면 이동 시 타이머를 정리한다', async ({
  page,
}) => {
  await page.clock.install({ time: new Date(serverNow + 300_000) })
  await page.route('**/api/v1/drops/102', (route) =>
    route.fulfill({
      json: {
        success: true,
        data: {
          dropId: 102,
          name: '카운트다운 상품',
          status: 'WISH',
          serverTime: new Date(serverNow).toISOString(),
          saleStartsAt: new Date(serverNow + 601_000).toISOString(),
          saleEndsAt: new Date(serverNow + 3_600_000).toISOString(),
          actions: { wishable: true, wishCancelable: true, orderable: false },
        },
      },
    }),
  )
  await page.goto('/#/drops/102')
  await expect(page.locator('.detail-state-label')).toHaveText('오픈 예정')
  await expect(page.locator('.detail-countdown')).toHaveCount(0)
  await page.clock.fastForward(1150)
  await expect(page.locator('.detail-countdown')).toHaveText('10:00')
  await page.clock.runFor(850)
  await expect(page.locator('.detail-countdown')).toHaveText('09:59')
  await page.getByRole('link', { name: '← 목록으로' }).click()
  await expect(page.locator('.detail-countdown')).toHaveCount(0)
})

test('0초에 서버를 재조회하되 actions가 바뀌기 전에는 구매를 허용하지 않는다', async ({
  page,
}) => {
  await page.clock.install({ time: new Date(serverNow - 300_000) })
  let requests = 0
  let opened = false
  let failure = false
  let responseTime = serverNow
  const startsAt = serverNow + 1000
  await page.route('**/api/v1/drops/102', (route) => {
    requests++
    if (failure) return route.fulfill({ status: 500, json: { success: false } })
    return route.fulfill({
      json: {
        success: true,
        data: {
          dropId: 102,
          name: '카운트다운 상품',
          status: 'WISH',
          serverTime: new Date(responseTime).toISOString(),
          saleStartsAt: new Date(startsAt).toISOString(),
          saleEndsAt: new Date(serverNow + 3_600_000).toISOString(),
          actions: {
            wishable: !opened,
            wishCancelable: !opened,
            orderable: opened,
          },
        },
      },
    })
  })
  await page.goto('/#/drops/102')
  await expect(page.locator('.detail-countdown')).toHaveText('00:01')
  await expect.poll(() => requests).toBeGreaterThanOrEqual(2)
  const before = requests
  failure = true
  responseTime = startsAt
  await page.clock.runFor(2100)
  await expect.poll(() => requests).toBeGreaterThan(before)
  await expect(page.locator('.detail-state-label')).toHaveText(
    '오픈 상태 확인 중',
  )
  await expect(page.locator('.buy-form')).toHaveCount(0)
  const error = page.locator('.detail-summary .detail-refresh-error')
  await expect(error).toBeVisible()
  await expect(error.getByRole('button', { name: '다시 시도' })).toBeEnabled()
  const state = page.locator('.detail-state')
  const errorBox = (await error.boundingBox())!
  const stateBox = (await state.boundingBox())!
  expect(errorBox.y + errorBox.height).toBeLessThanOrEqual(stateBox.y)
  expect(stateBox.y - errorBox.y - errorBox.height).toBeLessThanOrEqual(24)
  failure = false
  opened = true
  await error.getByRole('button', { name: '다시 시도' }).click()
  await page.clock.runFor(2100)
  await expect(page.locator('.buy-form')).toBeVisible()
  await expect(page.locator('.detail-countdown')).toHaveCount(0)
})
