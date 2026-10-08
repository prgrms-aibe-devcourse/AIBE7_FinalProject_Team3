import { Link } from 'react-router-dom'

type Props = {
  title: string
  description?: string
  link?: string
  label?: string
  // block: 단독 영역(목록 빈 결과), compact: 패널 안, page: 페이지 전체
  variant?: 'block' | 'compact' | 'page'
}

export default function EmptyState({
  title,
  description,
  link,
  label,
  variant = 'compact',
}: Props) {
  return (
    <div
      className={variant === 'block' ? 'empty-state' : `empty-state ${variant}`}
    >
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
