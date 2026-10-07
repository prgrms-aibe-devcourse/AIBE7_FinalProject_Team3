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

Mailpit은 `compose.yaml`의 `mailpit` 서비스로 실행한다. 인증과 STARTTLS 없이 메일을 받는다.

### 3.1 실행 방식별 SMTP 접속 주소

Backend를 어디서 실행하느냐에 따라 Mailpit에 접속하는 주소가 다르다.

| 실행 방식 | `MAIL_HOST` | `MAIL_PORT` | 값을 정하는 곳 |
| --- | --- | --- | --- |
| 호스트에서 실행 (`gradlew bootRun`, IDE) | `localhost` | `1025` | `application-mail.yml` 기본값 |
| Compose의 `backend` 서비스 | `mailpit` | `1025` | `compose.yaml` 기본값 |

- 호스트에서 실행하면 Mailpit 컨테이너가 `127.0.0.1:1025`로 공개한 포트에 접속한다. 메일 환경변수를 넣지 않으면 기본값으로 동작한다.
- Compose의 `backend` 컨테이너 안에서 `localhost`는 backend 컨테이너 자신이다. 같은 Compose 네트워크의 서비스 이름 `mailpit`으로 접속한다.
- 두 방식 모두 포트는 Mailpit 컨테이너의 SMTP 포트 `1025`이고, 인증과 STARTTLS는 끈다(`MAIL_SMTP_AUTH`, `MAIL_STARTTLS`는 `false`). 발신 주소는 `no-reply@grab.local`이다.

### 3.2 환경변수 전달

- Compose의 `backend` 서비스는 `.env`의 `MAIL_*` 값을 받는다. 값이 없거나 빈 값이면 위 Mailpit 기본값으로 대체하므로, Mailpit을 쓸 때는 `.env`의 메일 키를 비워 둔다. Gmail로 시험할 때만 4절의 값을 넣는다.
- 호스트에서 실행할 때는 `.env`가 자동으로 읽히지 않는다. 다른 SMTP로 바꾸려면 실행하는 셸이나 IDE 실행 설정에 환경변수를 직접 넣는다.
- 호스트 실행에서 메일 키를 빈 값으로 넣으면 기본값 대신 빈 문자열이 적용되어 발송이 실패한다. 쓰지 않는 키는 환경변수로 넣지 않는다.

### 3.3 실행과 수신 확인

`Backend/` 디렉터리에서 실행한다. 명령은 Windows PowerShell 기준이다.

~~~powershell
# Mailpit만 실행 (호스트에서 Backend를 실행할 때)
docker compose up -d mailpit
docker compose ps mailpit   # STATUS가 healthy인지 확인

# Compose의 backend를 실행하면 Mailpit도 함께 시작된다
docker compose up -d --build backend
~~~

| 용도 | 주소 |
| --- | --- |
| 웹 화면 (받은 메일 확인) | http://127.0.0.1:8025 |
| SMTP (Backend가 발송) | `127.0.0.1:1025`, Compose 안에서는 `mailpit:1025` |

두 포트는 `127.0.0.1`에만 열려 있어 다른 PC에서 접속할 수 없다. 웹 화면에는 인증 코드가 그대로 보이므로 포트 공개 범위를 넓히지 않는다.

**수신 확인**: Backend가 보낸 메일은 웹 화면 목록에 바로 나타난다. 메일을 열어 받는 사람, 제목, HTML 본문(HTML 탭)과 텍스트 본문(Text 탭)을 확인한다. Mailpit은 받은 메일을 외부로 보내지 않으므로 받는 주소는 아무 주소나 써도 된다.

**Mailpit 동작만 확인할 때**: Backend 없이 Mailpit의 발송 API로 시험 메일을 넣을 수 있다. 이 메일은 SMTP를 거치지 않으므로 Backend의 발송 경로 확인에는 쓰지 않는다.

~~~powershell
$body = @{
  From    = @{ Email = 'no-reply@grab.local' }
  To      = @(@{ Email = 'test@grab.local' })
  Subject = 'Mailpit 시험 메일'
  Text    = '한글 본문 확인'
} | ConvertTo-Json -Depth 3
Invoke-RestMethod -Method Post http://127.0.0.1:8025/api/v1/send `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
~~~

- 받은 메일은 API로도 조회할 수 있다(`curl.exe -s http://127.0.0.1:8025/api/v1/message/latest`). Windows PowerShell 5.1의 `Invoke-RestMethod`는 이 응답의 한글을 깨뜨려 표시한다. `curl.exe`도 콘솔 인코딩이 UTF-8이 아니면 깨질 수 있으므로 한글 확인은 웹 화면에서 한다. 저장된 내용은 깨지지 않는다.
- 받은 메일 전체 삭제: 웹 화면의 삭제 버튼 또는 `curl.exe -s -X DELETE http://127.0.0.1:8025/api/v1/messages`
- 받은 메일은 컨테이너 안에만 보관한다. 컨테이너를 다시 만들면(`docker compose down`, `up --force-recreate` 등) 모두 사라진다.
- 인증 코드 요청 API가 생기기 전(GR-61)에는 Backend에서 메일을 보내는 엔드포인트가 없다. Backend 발송 경로는 테스트 코드로 `EmailSender`를 호출해 확인한다.

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

## 6. 발송 코드 사용 방법

인증 코드 메일처럼 서비스 코드에서 메일을 보낼 때의 규칙이다(GR-61 인계). 메일 발송 코드는 `domain/mail`에 있다.

| 타입 | 위치 | 용도 |
| --- | --- | --- |
| `AsyncEmailDispatcher` | `domain/mail/service` | 서비스 코드의 기본 발송 진입점. 메일 전용 스레드 풀에 넘기고 바로 반환한다(한 통은 `dispatch()`, 여러 통은 `dispatchAll()`) |
| `EmailSender` | `domain/mail/service` | 메일을 동기로 보낸다(`send()` 한 통, `sendAll()` 여러 통). 실패하면 `EmailSendException`을 던진다 |
| `EmailMessage` | `domain/mail/dto` | 받는 사람, 제목, HTML 본문, 텍스트 본문. 네 값 모두 필수다 |
| `EmailSendException` | `domain/mail/error` | 발송 실패. `BusinessException`이 아니므로 HTTP 오류 응답으로 바꾸지 않는다 |

호출하는 쪽은 위 타입만 사용하고 `JavaMailSender`, `MimeMessage` 등 Spring Mail·Jakarta Mail 타입을 직접 쓰지 않는다. 발신 주소는 구현체가 `grab.mail.from` 설정으로 채운다.

### 6.1 기본 사용

요청을 처리하는 서비스 코드는 `AsyncEmailDispatcher.dispatch()`로 보낸다. 요청 스레드가 SMTP 발송을 기다리지 않는다.

~~~java
asyncEmailDispatcher.dispatch(new EmailMessage(email, subject, htmlBody, textBody));
~~~

한 번의 알림으로 여러 사람에게 보낼 때는 `dispatchAll()`로 목록을 한 번에 넘긴다(GR-69 판매 시작 알림).

~~~java
asyncEmailDispatcher.dispatchAll(messages); // List<EmailMessage>
~~~

- 수신자 수만큼 `dispatch()`를 호출하면 대기열 용량(40)을 넘는 분량이 거부돼 그 수신자만 메일을 받지 못한다. `dispatchAll()`은 목록 전체를 한 작업으로 묶어 큐 슬롯을 하나만 쓴다.
- 묶음 안의 한 통이 실패해도 나머지는 계속 발송하고, 실패가 있으면 마지막에 `EmailSendException`을 한 번 던진다(`EmailSender.sendAll()` 계약).
- `dispatchAll()`은 `sendAll()`을 호출하므로 SMTP 연결을 한 번만 열어 목록 전체를 보낸다. 통마다 `dispatch()`를 부르면 접속·STARTTLS·인증을 수신자 수만큼 반복해, Gmail처럼 연결 비용이 큰 서버에서는 그 반복이 전체 발송 시간의 대부분을 차지한다.
- 한 스레드·한 연결로 보내므로 수신자가 아주 많으면 뒤쪽 메일이 늦게 도착한다. 목록을 메일 풀 크기(4)만큼 청크로 나눠 제출하면 그만큼 빨라지지만 SMTP 동시 연결이 4개가 되므로, Gmail 계정 잠금 위험과 함께 판단한다.
- `EmailSender.send()`를 요청 처리 중에 직접 호출하지 않는다. SMTP가 느리면 요청 스레드가 타임아웃(최대 5초씩)만큼 붙잡힌다.
- `EmailSender`는 발송 결과를 바로 알아야 하는 경우(관리 기능, 테스트 등)에만 쓴다.
- 메일 전용 스레드 풀(`MailAsyncConfig.MAIL_TASK_EXECUTOR`)은 메일 발송에만 쓴다. 다른 비동기 작업은 기본 executor를 쓴다.

### 6.2 발송 시점

- 트랜잭션 안에서 코드를 저장하는 경우 커밋된 뒤 `dispatch()`를 호출한다. 트랜잭션 안에서 보내면 롤백됐을 때 서버에 없는 코드가 메일로 나간다. Spring에서는 이벤트를 발행하고 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`에서 `dispatch()`를 호출한다.
- 인증 코드를 Redis에만 저장하고 DB 트랜잭션이 없으면, Redis 저장이 성공한 뒤 `dispatch()`를 호출한다.
- `dispatch()`는 작업을 넘기고 바로 반환하므로 응답이 발송을 기다리지 않는다. 다만 메일 스레드가 HTTP 응답 전송이 끝나기 전에 발송을 시작할 수는 있다. [MEMBER_AUTH.md](development/api-spec/MEMBER_AUTH.md) 1.2.1의 "응답 후 비동기로 발송"을 이 수준으로 구현할지, 문구를 고칠지는 GR-61에서 정한다.

### 6.3 실패 처리

| 상황 | `dispatch()` | `EmailSender.send()` |
| --- | --- | --- |
| 정상 발송 | 반환 후 메일 스레드에서 발송 | 발송 후 반환 |
| 메일 풀 대기열 초과(실행 4·대기 40 초과) | 발송하지 않고 `WARN` 로그 (`dispatchAll()`은 묶음 전체를 버리고 건수만 남긴다) | 해당 없음 |
| SMTP 연결·인증·응답 실패, 타임아웃(각 5초) | 예외를 던지지 않고 `WARN` 로그 | `EmailSendException` |
| 그 밖의 예상하지 못한 오류 | 예외를 던지지 않고 `ERROR` 로그 | 예외 그대로 |

- `dispatch()`는 발송 실패를 호출하는 쪽에 알리지 않는다. 따라서 발송 실패가 코드 요청의 응답을 바꾸지 않는다. 호출하는 쪽에서 `dispatch()`를 try-catch로 감쌀 필요가 없다.
- 실패 로그는 메일 제목과 원인 예외 이름만 남긴다(예: `메일 발송 실패: EmailMessage[subject=...], cause=MailSendException > MailConnectException > ConnectException`). 받는 주소·본문·인증 코드는 남지 않는다.
- 발송이 실패해도 재시도하지 않는다. 사용자는 재발송 간격이 지난 뒤 다시 요청한다([MEMBER_AUTH.md](development/api-spec/MEMBER_AUTH.md) 1.2.1).
- 재발송 간격과 이메일당·IP당 발송 한도는 호출하는 쪽(코드 요청 API)에서 구현한다. 발송 구현체와 메일 스레드 풀에는 요청 제한이 없다.
- 발송 성공·실패 횟수 같은 메트릭이 필요하면 호출하는 쪽에서 추가한다.

### 6.4 메일 내용 작성

- 제목에 인증 코드를 넣지 않는다. `EmailMessage.toString()`과 실패 로그에 제목이 남는다.
- HTML 본문과 텍스트 본문을 모두 채운다. HTML을 표시하지 못하는 메일 프로그램은 텍스트 본문을 보여 준다.
- 본문 작성(템플릿)은 호출하는 쪽에서 한다. 발송 구현체는 완성된 메일 한 통을 보내기만 한다.
- 받는 주소는 정규화한 이메일(앞뒤 공백 제거, 소문자)을 쓴다.

### 6.5 설정과 기동

- `grab.mail.provider`가 `smtp`가 아니거나 없으면 `EmailSender` 빈이 없어 `AsyncEmailDispatcher`를 만들 수 없고 기동이 실패한다. 메일 발송이 조용히 꺼지지 않게 하기 위함이다.
- SMTP 서버에 연결할 수 없어도 애플리케이션은 기동되고 `/actuator/health`는 메일 상태를 반영하지 않는다.

### 6.6 테스트

- 메일 발송과 관계없는 `@SpringBootTest`는 SMTP 서버 없이 실행된다. 발송을 호출하는 코드의 테스트에서 실제 발송이 필요 없으면 `@MockitoBean`으로 `AsyncEmailDispatcher`를 대체하고 `dispatch()` 호출 여부와 `EmailMessage` 내용을 확인한다.
- 실제 SMTP 수신까지 확인하려면 GreenMail을 쓴다(`com.icegreen:greenmail-junit5`, 테스트 의존성으로 추가됨). `GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())`로 빈 포트에 서버를 띄우고 `spring.mail.host`·`spring.mail.port`를 그 주소로 지정한다. 예시는 `SmtpEmailSenderGreenMailTest`, `AsyncEmailDispatcherGreenMailTest`에 있다.
- 비동기 발송은 `dispatch()` 직후에 바로 확인하지 않는다. `greenMail.waitForIncomingEmail(...)`처럼 도착을 기다린 뒤 확인한다.
- 실제 Gmail 계정은 자동 테스트에 쓰지 않는다.
