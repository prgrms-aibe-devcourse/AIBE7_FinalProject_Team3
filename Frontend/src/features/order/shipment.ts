// 길이 제한은 API 명세(ORDER.md 2.4)와 같다. 서버가 거부하기 전에 같은 기준으로 막는다.
const CARRIER_MAX = 50
const TRACKING_MAX = 100

export const validateShipment = (carrier: string, trackingNumber: string) => {
  const name = carrier.trim()
  const tracking = trackingNumber.trim()
  if (!name) return '택배사를 입력하세요.'
  if (name.length > CARRIER_MAX) return `택배사는 ${CARRIER_MAX}자 이하여야 합니다.`
  if (!tracking) return '송장번호를 입력하세요.'
  if (tracking.length > TRACKING_MAX)
    return `송장번호는 ${TRACKING_MAX}자 이하여야 합니다.`
  return null
}
