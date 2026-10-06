<p align="center">
  <img src="./Frontend/src/assets/images/grab-wordmark.png" alt="GRAB" width="260" />
</p>

<h1 align="center">GRAB</h1>

<p align="center">
  기다림은 짧게, 기회는 단 한 번.<br />
  WISH부터 구매와 배송까지 연결하는 한정 판매 DROP 커머스
</p>

<div align="center">
  <table>
    <tr>
      <td align="center" width="160">
        <a href="https://github.com/wlsdn020416">
          <img src="https://github.com/wlsdn020416.png?size=120" alt="wlsdn020416" width="80" height="80" />
          <br />
          <img src="https://img.shields.io/badge/wlsdn020416-181717?style=flat-square&logo=github&logoColor=white" alt="wlsdn020416" />
        </a>
      </td>
      <td align="center" width="160">
        <a href="https://github.com/ImJhoon">
          <img src="https://github.com/ImJhoon.png?size=120" alt="ImJhoon" width="80" height="80" />
          <br />
          <img src="https://img.shields.io/badge/ImJhoon-181717?style=flat-square&logo=github&logoColor=white" alt="ImJhoon" />
        </a>
      </td>
      <td align="center" width="160">
        <a href="https://github.com/jmin4078">
          <img src="https://github.com/jmin4078.png?size=120" alt="jmin4078" width="80" height="80" />
          <br />
          <img src="https://img.shields.io/badge/jmin4078-181717?style=flat-square&logo=github&logoColor=white" alt="jmin4078" />
        </a>
      </td>
      <td align="center" width="160">
        <a href="https://github.com/sang9831">
          <img src="https://github.com/sang9831.png?size=120" alt="sang9831" width="80" height="80" />
          <br />
          <img src="https://img.shields.io/badge/sang9831-181717?style=flat-square&logo=github&logoColor=white" alt="sang9831" />
        </a>
      </td>
    </tr>
  </table>
</div>

<p align="center">
  <a href="./Backend/docs/README.md">프로젝트 문서</a>
  ·
  <a href="./Backend/docs/development/REQUIREMENTS.md">요구사항</a>
  ·
  <a href="./Backend/docs/development/api-spec/README.md">API 명세</a>
  ·
  <a href="./Backend/docs/development/ERD.md">ERD</a>
</p>

## 핵심 포인트

- 정해진 시간에 한정 수량 상품을 판매하는 WISH·GRAB 흐름
- PostgreSQL 트랜잭션과 행 잠금을 통한 주문 생성·재고 선점 정합성 보장
- 멱등 키와 요청 해시를 활용한 주문·결제·배송 중복 처리 방지
- 토스페이먼츠 테스트 환경을 이용한 결제 승인과 결과 불명 상태 보정
- Spring Security, JWT, Redis를 활용한 인증·인가와 토큰 관리
- Docker, GitHub Actions, Actuator, Prometheus 기반의 실행·검증·모니터링 구성

## 사용자 핵심 흐름

```mermaid
flowchart LR
    A["회원가입 / 로그인"] --> B["DROP 탐색"]
    B --> C["WISH 등록"]
    C --> D["GRAB 시작"]
    D --> E["옵션 선택 / 주문"]
    E --> F["결제"]
    F --> G["배송 / 수령"]
```

## Tech Stack

### Backend

![Java](https://img.shields.io/badge/Java_17-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)

### Frontend

![TypeScript](https://img.shields.io/badge/TypeScript_5-3178C6?style=for-the-badge&logo=typescript&logoColor=white)
![React](https://img.shields.io/badge/React_19-61DAFB?style=for-the-badge&logo=react&logoColor=black)
![Vite](https://img.shields.io/badge/Vite_8-646CFF?style=for-the-badge&logo=vite&logoColor=white)
![React Router](https://img.shields.io/badge/React_Router_7-CA4245?style=for-the-badge&logo=reactrouter&logoColor=white)
![Vitest](https://img.shields.io/badge/Vitest_4-6E9F18?style=for-the-badge&logo=vitest&logoColor=white)

### Infra / External

![PostgreSQL](https://img.shields.io/badge/PostgreSQL_18-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis_8-FF4438?style=for-the-badge&logo=redis&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white)
![Toss Payments](https://img.shields.io/badge/Toss_Payments-0064FF?style=for-the-badge)

## 상세 문서

| 문서 | 링크 |
| --- | --- |
| 전체 문서 안내 | [Backend/docs/README.md](./Backend/docs/README.md) |
| 요구사항 정의서 | [Backend/docs/development/REQUIREMENTS.md](./Backend/docs/development/REQUIREMENTS.md) |
| 기술 스택 | [Backend/docs/development/TECHSTACK.md](./Backend/docs/development/TECHSTACK.md) |
| API 명세 | [Backend/docs/development/api-spec/README.md](./Backend/docs/development/api-spec/README.md) |
| ERD | [Backend/docs/development/ERD.md](./Backend/docs/development/ERD.md) |
| 코딩 컨벤션 | [Backend/docs/collaboration/CODING_CONVENTION.md](./Backend/docs/collaboration/CODING_CONVENTION.md) |
| PR 컨벤션 | [Backend/docs/collaboration/PR_CONVENTION.md](./Backend/docs/collaboration/PR_CONVENTION.md) |
| 모니터링 | [Backend/docs/MONITORING.md](./Backend/docs/MONITORING.md) |

## 실행

### Backend

```bash
cd Backend
cp .env.example .env
docker compose up --build
```

### Frontend

```bash
cd Frontend
npm install
npm run dev
```

> Frontend는 현재 Mock 데이터를 사용하는 화면 초안이며 백엔드 API 연동을 진행할 예정입니다.
