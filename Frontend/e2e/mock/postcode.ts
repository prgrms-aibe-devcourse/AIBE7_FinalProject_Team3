import type { Page } from '@playwright/test'

// 다음(카카오) 우편번호 서비스 스크립트(addressSearch.ts). e2e는 외부 요청을 막으므로
// 실제 위젯 대신, embed() 호출 즉시 고정된 결과로 완료 콜백을 부르는 가짜를 둔다.
const FAKE_POSTCODE_SCRIPT = `
(() => {
  function Postcode(options) {
    return {
      embed: (container) => {
        setTimeout(() => {
          options.oncomplete({
            zonecode: '06236',
            address: '서울시 강남구 테헤란로 152',
            roadAddress: '서울시 강남구 테헤란로 152',
            jibunAddress: '서울시 강남구 역삼동 737',
            userSelectedType: 'R',
          })
        }, 0)
      },
    }
  }
  window.daum = Object.assign({}, window.daum, { Postcode })
})()
`

export async function installFakePostcode(page: Page) {
  await page.route(
    'https://t1.daumcdn.net/mapjsapi/bundle/postcode/**',
    (route) =>
      route.fulfill({
        contentType: 'application/javascript',
        body: FAKE_POSTCODE_SCRIPT,
      }),
  )
}
