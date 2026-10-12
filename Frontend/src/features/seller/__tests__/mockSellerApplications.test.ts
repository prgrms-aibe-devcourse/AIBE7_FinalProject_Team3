import { describe, expect, it } from 'vitest'
import { newSellerApplication } from '../mock/mockSellerApplications'

describe('판매자 신청 Mock', () => {
  it('신규 신청은 입력을 정리해 대기 상태로 만든다', () => {
    const application = newSellerApplication('grab@example.com', {
      brandName: ' 길동상점 ',
      contactEmail: ' seller@example.com ',
      description: ' 브랜드 소개 ',
    })
    expect(application).toMatchObject({
      applicantEmail: 'grab@example.com',
      brandName: '길동상점',
      contactEmail: 'seller@example.com',
      description: '브랜드 소개',
      status: 'PENDING',
      rejectedReason: null,
    })
  })
})
