import { Link } from 'react-router-dom'
import logo from '../../../asset/Logo.png'

export default function Footer() {
  return (
    <footer>
      <Link className="brand" to="/wish"><img src={logo} alt="" /><span>GRAB</span></Link>
      <p>A small drop, a big tomorrow.</p>
      <small>© 2026 GRAB · MVP FRONTEND DRAFT</small>
    </footer>
  )
}
