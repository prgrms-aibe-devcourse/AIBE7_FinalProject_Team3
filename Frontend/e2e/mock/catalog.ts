import type { Page } from '@playwright/test'

export const LONG_PRODUCT_NAME =
  '매일 오래 들고 다닐 수 있는 한정판 캔버스 토트와 데일리 스트랩 특별 구성 상품'

// Vite가 제공하는 Mock 모듈만 대체한다. 앱의 상품 데이터나 라우트에 테스트용 상태를 추가하지 않는다.
export async function installCatalogScenario(
  page: Page,
  scenario: 'many' | 'states' | 'failure',
) {
  const scripts = {
    many: `
      const sample = catalogDrops[0];
      catalogDrops.splice(0, catalogDrops.length, ...Array.from({ length: 30 }, (_, i) => ({
        ...sample, id: 2000 + i, name: '페이지 테스트 상품 ' + (i + 1), wishCount: i,
      })));
    `,
    states: `
      catalogDrops[0].name = ${JSON.stringify(LONG_PRODUCT_NAME)};
      catalogDrops[0].image = '';
      catalogDrops[3].name = ${JSON.stringify(LONG_PRODUCT_NAME)};
      catalogDrops[3].skus = catalogDrops[3].skus.map(sku => ({ ...sku, stock: 0 }));
      catalogDrops[5].skus = catalogDrops[5].skus.map(sku => ({ ...sku, active: false }));
    `,
    failure: `
      const originalName = catalogDrops[0].name;
      Object.defineProperty(catalogDrops[0], 'name', {
        configurable: true, get() { throw new Error('상품 목록 Mock 표시 실패'); },
      });
      window.restoreCatalogMock = () => Object.defineProperty(catalogDrops[0], 'name', {
        configurable: true, value: originalName,
      });
    `,
  }
  await page.route(
    '**/src/features/drop/mock/mockCatalog.ts',
    async (route) => {
      const response = await route.fetch()
      await route.fulfill({
        response,
        body: `${await response.text()}\n${scripts[scenario]}`,
      })
    },
  )
}
