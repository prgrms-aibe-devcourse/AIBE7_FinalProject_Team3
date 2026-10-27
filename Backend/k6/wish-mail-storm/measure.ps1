# PERF(GR-170): WISH 판매 알림 메일 폭주 측정. k6를 쓰지 않는다 — 이 시나리오는 HTTP 부하가
# 아니라 DropTransitionScheduler(10초 주기)가 발행한 이벤트 하나가 noticeMailTaskExecutor
# (pool=1, MailAsyncConfig)에서 BCC 메일을 순차 발송하는 내부 큐 처리 시간을 재는 것이라,
# "seed 실행 -> Mailpit 수신 폴링"만 하면 충분하다.
#
# 사전 준비: docker compose up -d --build backend (Mailpit 포함), common/accounts-seed.sql 1회 실행.
#   Backend/ 디렉터리에서 실행:
#   powershell -File k6/wish-mail-storm/measure.ps1
#
# 주의(실측으로 확인됨): seed.sql은 sale_starts_at을 30초 뒤로 고정하는데, 그 값이 판매 임박
# 알림의 리드타임(10분)보다 짧아 "판매 임박"과 "판매 시작" 두 알림이 모두 발송된다(각각 DROP당
# BCC 30통, 총 120통). 유휴시간(더 이상 메일이 안 늘어나는 시간)으로 종료를 판단하면 두 알림
# 사이 공백(수십 초, 스케줄러 tick 타이밍에 따라 달라짐)에 속아 먼저 끝난 "판매 임박" 알림만 세고
# 조기 종료할 수 있다. 그래서 유휴 판단 대신 고정 대기 시간을 쓴다.

$ErrorActionPreference = 'Stop'

$MailpitBase = 'http://127.0.0.1:8025'
$SaleStartDelaySeconds = 30
$ExpectedMessages = 120          # (3000명 WISH / 100명(BCC 상한)) * DROP 2개 * 알림 2종(임박+시작)
$WaitSeconds = 90                # sale_starts_at + 발송 소요 + 스케줄러 지연을 넉넉히 덮는 고정 대기

function Get-MailpitTotal {
    $res = Invoke-RestMethod -Method Get -Uri "$MailpitBase/api/v1/messages?limit=1"
    return [int]$res.total
}

Write-Host "1) Mailpit 받은 메일함 비우는 중..."
Invoke-RestMethod -Method Delete -Uri "$MailpitBase/api/v1/messages" | Out-Null

Write-Host "2) seed.sql 실행 중 (DROP 2개, WISH 3000명)..."
# seed.sql은 UTF-8로 저장돼 있다. 콘솔 출력 인코딩을 UTF-8로 맞추지 않으면 네이티브 명령으로
# 넘어가는 파이프 입력에서 한글 리터럴이 깨져(seed.sql의 DROP 이름 등) WHERE 절이 기존 행과
# 매치되지 않는 재실행 실패로 이어질 수 있다.
$OutputEncoding = [System.Text.Encoding]::UTF8
Get-Content "$PSScriptRoot/seed.sql" -Raw -Encoding UTF8 | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f -
if ($LASTEXITCODE -ne 0) {
    throw "seed.sql 실행 실패(exit $LASTEXITCODE). 측정을 중단합니다."
}
$seedDoneAt = Get-Date
$saleStartsAt = $seedDoneAt.AddSeconds($SaleStartDelaySeconds)
Write-Host "   sale_starts_at (예상): $saleStartsAt"
Write-Host "3) 판매 임박 + 판매 시작 두 알림이 모두 끝날 때까지 ${WaitSeconds}초 고정 대기..."
Start-Sleep -Seconds $WaitSeconds

$total = Get-MailpitTotal
Write-Host ""
Write-Host "===== 결과 ====="
Write-Host "최종 수신 메일 수: $total / 기대값 $ExpectedMessages"
if ($total -eq 0) {
    Write-Host "메일을 한 통도 받지 못했습니다 — 유실 가능성. 백엔드 로그를 확인하세요."
}
elseif ($total -lt $ExpectedMessages) {
    Write-Host "경고: 기대값보다 적게 도착했습니다. 큐 거부(WARN 로그: '메일 발송 대기열이 가득 차') 여부를 확인하세요."
}
Write-Host "정확한 타이밍(첫/마지막 메일 시각)은 Mailpit API로 제목별 Created 시각을 비교해 구한다:"
Write-Host "  curl -s `"$MailpitBase/api/v1/messages?limit=$ExpectedMessages`""
Write-Host "또는 백엔드 로그의 WishSaleNoticeMailListener/DropTransitionService 타임스탬프와 대조한다."
