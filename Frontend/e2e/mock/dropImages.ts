import type { Page } from '@playwright/test'

export async function installDropImageUploads(page: Page) {
  let sequence = 0
  await page.route('**/api/v1/uploads/images/presigned-url', (route) => {
    const imageId = `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`
    return route.fulfill({
      json: {
        success: true,
        data: {
          imageId,
          uploadUrl: `https://storage.grab.test/upload/${imageId}?token=fixture`,
          imageUrl: `https://storage.grab.test/images/${imageId}.png`,
          expiresAt: '2026-10-01T12:00:00+09:00',
        },
      },
    })
  })
  await page.route('https://storage.grab.test/upload/**', (route) =>
    route.fulfill({
      status: 200,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Content-Type',
        'Access-Control-Allow-Methods': 'PUT',
      },
      json: {},
    }),
  )
}
