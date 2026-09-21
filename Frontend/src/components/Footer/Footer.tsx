import { Link } from 'react-router-dom'
import wordmark from '../../assets/images/grab-wordmark.png'

export default function Footer() {
  return (
    <footer>
      <Link className="footer-brand" to="/wish" aria-label="GRAB 홈">
        <img className="footer-wordmark" src={wordmark} alt="GRAB" />
      </Link>
      <p>A small drop, a big tomorrow.</p>
      <small>© 2026 GRAB · MVP FRONTEND DRAFT</small>
    </footer>
  )
}
