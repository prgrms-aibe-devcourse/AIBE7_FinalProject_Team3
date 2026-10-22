import { Link } from 'react-router-dom'

type Props = {
  title: string
  description?: string
  link?: string
  label?: string
  // block: 단독 영역(목록 빈 결과), compact: 패널 안, page: 페이지 전체
  variant?: 'block' | 'compact' | 'page'
  // 첫 조회 중 표시. aria-busy가 있는 동안 CSS가 푸터를 숨겨 짧은 본문 때문에 푸터가 올라오지 않게 한다
  loading?: boolean
}

export default function EmptyState({
  title,
  description,
  link,
  label,
  variant = 'compact',
  loading = false,
}: Props) {
  return (
    <div
      className={variant === 'block' ? 'empty-state' : `empty-state ${variant}`}
      aria-busy={loading || undefined}
    >
      {loading && <span className="spinner" aria-hidden="true" />}
      <strong>{title}</strong>
      {description && <p>{description}</p>}
      {link && label && (
        <Link
          className={variant === 'page' ? 'primary-button' : 'secondary-button'}
          to={link}
        >
          {label}
        </Link>
      )}
    </div>
  )
}
