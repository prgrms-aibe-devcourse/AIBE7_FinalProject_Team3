export type SellerApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export type SellerApplication = {
  id: string
  applicantEmail: string
  brandName: string
  contactEmail: string
  description: string
  status: SellerApplicationStatus
  appliedAt: string
  rejectedReason: string | null
}

export type SellerApplicationInput = Pick<
  SellerApplication,
  'brandName' | 'contactEmail' | 'description'
>

export const demoSellerApplications: SellerApplication[] = [
  {
    id: 'application-1',
    applicantEmail: 'gildong@example.com',
    brandName: '길동상점',
    contactEmail: 'seller@example.com',
    description: '한정판 상품 판매점',
    status: 'PENDING',
    appliedAt: '2026-09-18T14:00:00+09:00',
    rejectedReason: null,
  },
  {
    id: 'application-2',
    applicantEmail: 'second@example.com',
    brandName: '두 번째 브랜드',
    contactEmail: 'contact@example.com',
    description: '일상의 작은 즐거움',
    status: 'PENDING',
    appliedAt: '2026-09-17T12:00:00+09:00',
    rejectedReason: null,
  },
  {
    id: 'application-3',
    applicantEmail: 'third@example.com',
    brandName: '세 번째 브랜드',
    contactEmail: 'third@example.com',
    description: '계절 한정 상품',
    status: 'PENDING',
    appliedAt: '2026-09-16T15:00:00+09:00',
    rejectedReason: null,
  },
  {
    id: 'application-4',
    applicantEmail: 'approved@example.com',
    brandName: '오늘의 공방',
    contactEmail: 'hello@example.com',
    description: '손으로 만드는 소품',
    status: 'APPROVED',
    appliedAt: '2026-09-16T11:00:00+09:00',
    rejectedReason: null,
  },
  {
    id: 'application-5',
    applicantEmail: 'rejected@example.com',
    brandName: '메이드 스튜디오',
    contactEmail: 'made@example.com',
    description: '맞춤 상품',
    status: 'REJECTED',
    appliedAt: '2026-09-15T10:00:00+09:00',
    rejectedReason: '사업자 정보 확인이 필요합니다.',
  },
]

export function newSellerApplication(
  applicantEmail: string,
  input: SellerApplicationInput,
): SellerApplication {
  return {
    id: crypto.randomUUID(),
    applicantEmail,
    brandName: input.brandName.trim(),
    contactEmail: input.contactEmail.trim(),
    description: input.description.trim(),
    status: 'PENDING',
    appliedAt: new Date().toISOString(),
    rejectedReason: null,
  }
}

export function reviewSellerApplication(
  applications: SellerApplication[],
  id: string,
  status: 'APPROVED' | 'REJECTED',
  reason = '',
): SellerApplication[] {
  if (status === 'REJECTED' && !reason.trim()) return applications
  return applications.map((application) =>
    application.id === id && application.status === 'PENDING'
      ? {
          ...application,
          status,
          rejectedReason: status === 'REJECTED' ? reason.trim() : null,
        }
      : application,
  )
}
