import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import logo from '../../assets/images/grab-symbol.png'

type Props = {
  mode: 'login' | 'signup'
  onLogin: () => void
  notify: (message: string) => void
}

export default function LoginPage({ mode, onLogin, notify }: Props) {
  const navigate = useNavigate()
  const signup = mode === 'signup'

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    onLogin()
    notify(signup ? '가입이 완료됐어요.' : '로그인했어요.')
    navigate('/wish')
  }

  return (
    <section className="auth-page">
      <div className="auth-copy">
        <p className="eyebrow">WELCOME TO GRAB</p>
        <h1>
          {signup ? '취향을 발견할 준비가 됐나요?' : '다시 만나 반가워요.'}
        </h1>
        <p>좋아하는 상품을 WISH하고, 한정된 순간에 GRAB하세요.</p>
        <img src={logo} alt="" />
      </div>
      <form className="auth-form" onSubmit={submit}>
        <h2>{signup ? '회원가입' : '로그인'}</h2>
        {signup && (
          <label>
            이름
            <input required autoComplete="name" placeholder="이름" />
          </label>
        )}
        <label>
          이메일
          <input
            required
            type="email"
            autoComplete="email"
            placeholder="grab@example.com"
          />
        </label>
        <label>
          비밀번호
          <input
            required
            type="password"
            minLength={8}
            autoComplete={signup ? 'new-password' : 'current-password'}
            placeholder="8자 이상 입력"
          />
        </label>
        <button className="primary-button full">
          {signup ? 'GRAB 시작하기' : '로그인'}
        </button>
        <p>
          {signup ? '이미 계정이 있나요?' : '아직 계정이 없나요?'}{' '}
          <Link to={signup ? '/login' : '/signup'}>
            {signup ? '로그인' : '회원가입'}
          </Link>
        </p>
        <small>
          현재 화면은 프론트엔드 초안으로, 입력한 정보는 저장하거나 전송하지
          않습니다.
        </small>
      </form>
    </section>
  )
}
