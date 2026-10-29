declare global {
  interface Window {
    daum?: {
      Postcode: new (options: {
        oncomplete: (data: DaumPostcodeData) => void
        width?: string | number
        height?: string | number
      }) => { embed: (container: HTMLElement) => void }
    }
  }
}

type DaumPostcodeData = {
  zonecode: string
  address: string
  roadAddress: string
  jibunAddress: string
  // 사용자가 결과 목록에서 도로명('R')·지번('J') 중 고른 쪽. 항상 도로명을 우선하면
  // 사용자가 일부러 고른 지번 주소를 무시하게 된다.
  userSelectedType: 'R' | 'J'
}

export type AddressSearchResult = {
  postalCode: string
  address1: string
}

const SCRIPT_SRC =
  'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'

let scriptPromise: Promise<void> | null = null

// 다음(카카오) 우편번호 서비스 스크립트를 한 번만 불러온다. API 키가 필요 없는 무료 서비스다.
function loadPostcodeScript() {
  if (window.daum?.Postcode) return Promise.resolve()
  if (!scriptPromise) {
    scriptPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = SCRIPT_SRC
      script.onload = () => resolve()
      script.onerror = () => {
        scriptPromise = null
        reject(new Error('주소 검색 스크립트를 불러오지 못했어요.'))
      }
      document.head.appendChild(script)
    })
  }
  return scriptPromise
}

/*
 * container 안에 주소 검색 위젯을 그린다(iframe 삽입). open()의 새 창(window.open) 대신 embed를 쓰는 이유는,
 * 스크립트를 처음 불러올 때 await 뒤에 open()을 호출하면 클릭의 사용자 동작(user activation)이 끊겨
 * 브라우저 팝업 차단에 걸리기 때문이다. embed는 같은 문서 안에 iframe을 꽂을 뿐이라 차단되지 않는다.
 */
export async function embedPostcode(
  container: HTMLElement,
  onComplete: (result: AddressSearchResult) => void,
) {
  await loadPostcodeScript()
  new window.daum!.Postcode({
    oncomplete: (data) => {
      const preferred =
        data.userSelectedType === 'J' ? data.jibunAddress : data.roadAddress
      onComplete({
        postalCode: data.zonecode,
        address1:
          preferred || data.roadAddress || data.jibunAddress || data.address,
      })
    },
    width: '100%',
    height: '100%',
  }).embed(container)
}
