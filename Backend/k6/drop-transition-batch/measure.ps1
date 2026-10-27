# PERF(GR-171): DROP 상태 전이 배치 부하 측정. k6를 쓰지 않는다 — 측정 대상이
# HTTP 트래픽이 아니라 DropTransitionScheduler(10초 주기) 한 번이 DropTransitionService
# (BATCH_SIZE=500)·DropTransitionBatchService(FOR UPDATE SKIP LOCKED)로 수백 건을
# 한 번에 전이시키는 DB 배치
# 처리 시간이라, seed 실행 -> DB 상태(WISH->GRAB) 폴링만 하면 충분하다.
#
# 사전 준비: docker compose up -d --build backend, common/accounts-seed.sql 1회 실행.
#   Backend/ 디렉터리에서 실행:
#   powershell -File k6/drop-transition-batch/measure.ps1                       # 기준선(WISH 없음)
#   powershell -File k6/drop-transition-batch/measure.ps1 -WithWish             # 비교군(메일 트리거 포함)
#
# 측정 방법: drops.updated_at은 스케줄러의 논리적 now로 찍혀 실제 처리 시각을 못 재므로,
# 대상 DROP이 WISH -> GRAB으로 바뀐 개수를 짧은 간격으로 직접 폴링해 "첫 전이 확인 시각"과
# "전부 전이 완료 시각"의 차이로 배치 소요 시간을 역산한다.

param(
    [switch]$WithWish,
    [int]$ExpectedCount = 600,
    [int]$TimeoutSeconds = 60
)

# seed.sql/seed-with-wish.sql이 sale_starts_at을 "now() + interval '20 seconds'"로
# 고정해 두므로, 이 값은 파라미터로 받지 않고 그 상수를 그대로 따라간다(두 파일을
# 함께 바꾸지 않으면 이 숫자만 바꿔도 아무 효과가 없다).
$SaleStartDelaySeconds = 20

$ErrorActionPreference = 'Stop'
# BOM 있는 UTF8Encoding(.UTF8 정적 인스턴스)을 쓰면 파이프 시작에 BOM 바이트가 섞여 들어가
# psql이 "syntax error at or near ..."로 실패한다. BOM 없는 인코딩을 명시한다.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false

$SeedFile = if ($WithWish) { 'seed-with-wish.sql' } else { 'seed.sql' }
$DropName = if ($WithWish) { 'k6 배치전이 WISH포함' } else { 'k6 배치전이 기준' }

function Get-GrabCount {
    param([string]$Name)
    $escaped = $Name.Replace("'", "''")
    $sql = "SELECT count(*) FROM drops WHERE name = '$escaped' AND status = 'GRAB';"
    $out = docker compose exec -T postgres psql -t -A -U grab -d grab -c $sql
    if ($LASTEXITCODE -ne 0) {
        # exec/psql 실패 시 $out은 보통 빈 문자열이라, 체크 없이 [int]로 변환하면 "GRAB 0건"으로
        # 오인해 "아직 전이 안 됨"과 구별이 안 된다. 조회 실패는 측정 자체를 중단시킨다.
        throw "DROP 상태 조회 실패(exit $LASTEXITCODE): $sql"
    }
    return [int]($out -join '').Trim()
}

Write-Host "1) $SeedFile 실행 중 (DROP ${ExpectedCount}개, WithWish=$WithWish)..."
Get-Content "$PSScriptRoot/$SeedFile" -Raw -Encoding UTF8 | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U grab -d grab -f -
if ($LASTEXITCODE -ne 0) {
    throw "$SeedFile 실행 실패(exit $LASTEXITCODE). 측정을 중단합니다."
}
$seedDoneAt = Get-Date
Write-Host "   sale_starts_at (예상): $($seedDoneAt.AddSeconds($SaleStartDelaySeconds))"
Write-Host "2) 전이 시작/완료를 폴링 중 (최대 ${TimeoutSeconds}초)..."

$startedAt = $null
$completedAt = $null
$deadline = $seedDoneAt.AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 300
    $count = Get-GrabCount -Name $DropName
    if ($count -gt 0 -and -not $startedAt) {
        $startedAt = Get-Date
    }
    if ($count -ge $ExpectedCount) {
        $completedAt = Get-Date
        break
    }
}

Write-Host ""
Write-Host "===== 결과 ($DropName) ====="
if ($startedAt -and $completedAt) {
    Write-Host ("전이 첫 확인: {0:HH:mm:ss.fff}" -f $startedAt)
    Write-Host ("전이 완료 확인: {0:HH:mm:ss.fff}" -f $completedAt)
    Write-Host ("배치 처리 소요(폴링 해상도 300ms 오차 포함): {0:N2}초" -f ($completedAt - $startedAt).TotalSeconds)
}
else {
    $final = Get-GrabCount -Name $DropName
    Write-Host "경고: 제한 시간(${TimeoutSeconds}초) 안에 전부 전이되지 않음: $final / $ExpectedCount"
    Write-Host "다음 스케줄러 주기로 밀렸을 수 있다 — 백엔드 로그의 DropTransitionService 라인을 확인하세요."
    exit 1
}
Write-Host "내부 배치 왕복 횟수(500+100 등)와 락 경합 여부는 백엔드 로그로 교차 확인한다:"
Write-Host "  docker compose logs backend --since 2m | Select-String 'DropTransitionService|deadlock|lock'"
