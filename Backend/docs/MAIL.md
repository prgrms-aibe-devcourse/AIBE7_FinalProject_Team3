# 메일 발송 설정

## 1. 구성

이메일 인증 코드는 SMTP로 발송한다. 환경별 발송 대상은 [TECHSTACK.md](development/TECHSTACK.md) 4.3절을 따른다.

| 환경 | SMTP 서버 | 용도 |
| --- | --- | --- |
| 로컬 개발 | Mailpit | 메일을 실제로 발송하지 않고 받아 두는 개발용 SMTP 서버 |
| 자동 테스트 | GreenMail | 테스트 중 메모리에서 실행하는 SMTP 서버 |
| 시연·운영 (MVP) | Gmail SMTP | 팀 전용 Gmail 계정과 앱 비밀번호로 발송 |

설정 파일은 `src/main/resources/application-mail.yml`이다. `application.yml`이 `spring.config.import`로 항상 불러오므로 `mail` 프로필을 따로 켜지 않는다.

## 2. 환경변수

| 환경변수 | 설정 키 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `MAIL_HOST` | `spring.mail.host` | `localhost` | SMTP 서버 주소 |
| `MAIL_PORT` | `spring.mail.port` | `1025` | SMTP 서버 포트 |
| `MAIL_USERNAME` | `spring.mail.username` | 빈 값 | SMTP 로그인 계정 |
| `MAIL_PASSWORD` | `spring.mail.password` | 빈 값 | SMTP 로그인 비밀번호 |
| `MAIL_SMTP_AUTH` | `spring.mail.properties.mail.smtp.auth` | `false` | SMTP 로그인 여부 |
| `MAIL_STARTTLS` | `spring.mail.properties.mail.smtp.starttls.enable`, `.required` | `false` | STARTTLS 사용 및 강제 여부 |
| `MAIL_FROM` | `grab.mail.from` | `no-reply@grab.local` | 메일에 표시되는 발신 주소 |
| `GRAB_MAIL_PROVIDER` | `grab.mail.provider` | `smtp` | 발송 구현체 선택 |

- 기본값은 로컬 Mailpit 기준이다. 로컬에서는 환경변수를 설정하지 않아도 된다.
- `MAIL_STARTTLS`는 `starttls.enable`과 `starttls.required`를 함께 바꾼다. 켜면 서버가 STARTTLS를 제공하지 않을 때 평문으로 로그인하지 않고 발송이 실패한다.
- `grab.mail.provider`는 `EmailSender` 구현체를 고른다. 현재 구현은 `smtp`뿐이므로 모든 환경에서 기본값을 쓴다. 설정 파일에 고정값으로 두며, 바꿀 때는 `GRAB_MAIL_PROVIDER` 환경변수로 덮어쓴다. SES API처럼 다른 발송 방식을 추가하면 이 값으로 전환한다.
- `MAIL_USERNAME`은 SMTP 로그인 계정이고 `MAIL_FROM`은 메일에 표시되는 주소다. 서버에 따라 두 값이 다를 수 있다(예: SES SMTP).

다음 값은 환경과 관계없이 고정한다.

| 설정 키 | 값 | 이유 |
| --- | --- | --- |
| `spring.mail.properties.mail.smtp.connectiontimeout` | `5000` (ms) | SMTP 장애 시 연결 단계에서 무한 대기하지 않는다 |
| `spring.mail.properties.mail.smtp.timeout` | `5000` (ms) | 서버 응답을 무한정 기다리지 않는다 |
| `spring.mail.properties.mail.smtp.writetimeout` | `5000` (ms) | 데이터 전송이 멈춰도 무한 대기하지 않는다 |
| `management.health.mail.enabled` | `false` | SMTP 장애가 `/actuator/health` 전체를 DOWN으로 만들지 않는다 |

## 3. 로컬 Mailpit

| 환경변수 | 값 |
| --- | --- |
| `MAIL_HOST` | `localhost` (기본값) |
| `MAIL_PORT` | `1025` (기본값) |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | 비움 |
| `MAIL_SMTP_AUTH` | `false` (기본값) |
| `MAIL_STARTTLS` | `false` (기본값) |
| `MAIL_FROM` | `no-reply@grab.local` (기본값) |

Mailpit은 인증과 STARTTLS 없이 메일을 받으므로 기본값을 그대로 사용한다.

## 4. 운영 Gmail SMTP

| 환경변수 | 값 |
| --- | --- |
| `MAIL_HOST` | `smtp.gmail.com` |
| `MAIL_PORT` | `587` |
| `MAIL_USERNAME` | 팀 전용 Gmail 주소 |
| `MAIL_PASSWORD` | 해당 계정의 앱 비밀번호 |
| `MAIL_SMTP_AUTH` | `true` |
| `MAIL_STARTTLS` | `true` |
| `MAIL_FROM` | `MAIL_USERNAME`과 같은 주소 |

- 587 포트는 평문으로 접속한 뒤 STARTTLS로 암호화 연결로 전환한다. 접속부터 TLS를 쓰는 465 포트(SMTPS)는 사용하지 않는다.
- `MAIL_PASSWORD`에는 Google 계정 비밀번호가 아니라 앱 비밀번호를 넣는다. 앱 비밀번호는 계정에 2단계 인증을 설정해야 발급할 수 있다.
- Gmail은 로그인 계정과 다른 발신 주소를 계정 주소로 바꿔 보내므로 `MAIL_FROM`에 같은 주소를 넣는다.
- `MAIL_USERNAME`과 `MAIL_PASSWORD`는 AWS Parameter Store의 SecureString으로 관리하고 배포 시 환경변수로 주입한다. 실제 계정 정보와 앱 비밀번호는 저장소, 이 문서, `.env.example`, 이미지에 기록하지 않는다.

## 5. 로그와 민감정보

메일 본문(인증 코드 포함), 받는 주소, SMTP 비밀번호는 로그에 남기지 않는다(NFR-011).

- `EmailMessage.toString()`은 제목만 출력한다. 따라서 메일 제목에 인증 코드를 넣지 않는다.
- `EmailSendException`의 메시지는 고정 문구다. 원인 예외(cause)의 SMTP 서버 응답에는 받는 주소가 들어갈 수 있으므로, 발송 실패 로그에는 스택 트레이스와 예외 메시지 대신 원인 예외 이름만 남긴다(예: `cause=MailSendException > SocketTimeoutException`).
- 비동기 발송은 `AsyncEmailDispatcher.dispatch()`로 한다. 큐 거부와 발송 실패는 `WARN` 로그만 남기고 호출하는 쪽에 예외를 던지지 않는다. 사용자는 재발송 간격 뒤 다시 요청한다.
- JavaMail 디버그 출력(`spring.mail.properties.mail.debug=true`)은 켜지 않는다. SMTP 대화 전체가 출력되어 Base64로 인코딩된 로그인 정보와 메일 본문이 그대로 남는다.
