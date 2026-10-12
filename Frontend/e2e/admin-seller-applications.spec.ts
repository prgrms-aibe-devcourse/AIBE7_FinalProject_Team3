import { expect, test } from './fixtures'

async function login(page: import('@playwright/test').Page, admin: boolean) {
  await page.goto('/#/admin/seller-applications')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  if (admin) await page.getByLabel('LOCAL 결과 예시').selectOption('admin')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
}

test('운영자 전용 진입과 상태 필터·페이지 이동', async ({ page }) => {
  await login(page, false)
  await expect(page.getByText('접근 권한이 없어요.')).toBeVisible()
  await page.goto('/#/login')
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByLabel('LOCAL 결과 예시').selectOption('admin')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.goto('/#/admin/seller-applications')
  await expect(page.getByRole('button', { name: /길동상점/ })).toBeVisible()
  await page.getByRole('button', { name: '다음' }).click()
  await expect(page).toHaveURL(/status=PENDING.*page=1/)
  await expect(
    page.getByRole('button', { name: /세 번째 브랜드/ }),
  ).toBeVisible()
  await page.getByRole('button', { name: '승인', exact: true }).click()
  await expect(page).toHaveURL(/status=APPROVED.*page=0/)
  await expect(page.getByRole('button', { name: /오늘의 공방/ })).toBeVisible()
})

test('승인·반려는 확인 뒤 Mock 상태만 바꾸고 실패 시 유지한다', async ({
  page,
}) => {
  await login(page, true)
  await page.getByLabel('심사 결과 예시').selectOption('FAILURE')
  await page.getByRole('button', { name: '승인 확인' }).click()
  await page.getByRole('button', { name: 'Mock 승인 결과 보기' }).click()
  await expect(page.getByRole('alert')).toHaveText(/저장하지 못했어요/)
  await page.getByLabel('심사 결과 예시').selectOption('SUCCESS')
  await page.getByRole('button', { name: '승인 확인' }).click()
  await page.getByRole('button', { name: 'Mock 승인 결과 보기' }).click()
  await expect(
    page.getByRole('button', { name: /두 번째 브랜드/ }),
  ).toBeVisible()
  await page.getByLabel('반려 사유 (필수)').fill('정보 부족')
  await page.getByRole('button', { name: '반려 확인' }).click()
  await page.getByRole('button', { name: 'Mock 반려 결과 보기' }).click()
  await page.getByRole('button', { name: '반려', exact: true }).click()
  await expect(
    page.getByRole('button', { name: /두 번째 브랜드/ }),
  ).toBeVisible()
  await expect(page.getByText('이미 반려 처리된 신청입니다.')).toBeVisible()
})
