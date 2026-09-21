import { Link } from 'react-router-dom'

type Props = {
  title: string
  link: string
  label: string
  page?: boolean
}

export default function EmptyState({ title, link, label, page = false }: Props) {
  return (
    <div className={`empty-state ${page ? 'page' : 'compact'}`}>
      <strong>{title}</strong>
      <Link className={page ? 'primary-button' : 'secondary-button'} to={link}>{label}</Link>
    </div>
  )
}
