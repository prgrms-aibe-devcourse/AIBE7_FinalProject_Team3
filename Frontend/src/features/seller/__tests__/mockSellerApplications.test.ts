import { describe, expect, it } from 'vitest'
import {
  demoSellerApplications,
  newSellerApplication,
  reviewSellerApplication,
} from '../mock/mockSellerApplications'

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

  it('대기 신청만 심사하고 반려 사유는 필수로 다룬다', () => {
    const unchanged = reviewSellerApplication(
      demoSellerApplications,
      'application-1',
      'REJECTED',
      ' ',
    )
    expect(unchanged).toBe(demoSellerApplications)
    const reviewed = reviewSellerApplication(
      demoSellerApplications,
      'application-1',
      'REJECTED',
      '  정보 부족  ',
    )
    expect(reviewed[0]).toMatchObject({
      status: 'REJECTED',
      rejectedReason: '정보 부족',
    })
    expect(
      reviewSellerApplication(reviewed, 'application-1', 'APPROVED'),
    ).toEqual(reviewed)
  })
})
