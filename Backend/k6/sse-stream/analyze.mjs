// GR-157: 변경 알림 전달 지연(REALTIME_STOCK.md 13.1 "DB 커밋 완료 → drop-changed 수신")을 계산한다.
// sse-clients.mjs 결과 JSON과 order-load.js 콘솔 로그(ORDER <응답 수신 ms> <dropId>)를 대응시킨다.
//
//   node Backend/k6/sse-stream/analyze.mjs <sse-result.json> <orders.log>
//
// 주문 1건 × 그 상품을 구독 중인 연결 1개마다, 주문 응답 수신 이후 처음 받은 같은 상품의 drop-changed까지를 지연으로 본다.
// ponytail: 커밋 시각 대신 주문 응답 수신 시각을 쓴 근사값이다. 커밋→응답 수신 구간(수 ms)만큼 짧게 잡히고,
// 그 사이에 도착한 알림은 다음 알림으로 대응돼 최대 병합 창(1초)만큼 길게 잡힐 수 있다. 정밀 측정이 필요하면
// 서버에 커밋 시각 기록을 추가해야 한다. 두 프로세스가 같은 머신 시계를 쓴다는 전제다(도커 VM 시계 오차는 기록).
//
// GR-180: sse-clients.mjs를 REFETCH=1로 돌린 결과면 두 값을 더 낸다.
// - 화면 반영 지연(13.1 "커밋 완료 → 변경 이후 성공 조회·화면 반영"): 위 알림 수신 이후 처음 시작해 성공한 재조회의 완료 시각까지.
// - 재조회 응답시간: 첫 주문부터 마지막 주문까지(변경이 실제로 일어난 구간)에 시작한 요청만 API별로 집계한다.
//
// GR-200:
// - 미확정 알림: 주문 응답 이후 알림이 없지만 요청 시작~응답 사이에 같은 상품 알림이 있으면, 그 알림이 이번 주문의 것인지
//   같은 상품의 이전 주문 것인지 가릴 수 없다(주문과 알림을 잇는 커밋 표식이 없음). 유실로도 성공으로도 세지 않고
//   unresolved로 따로 센다. missing은 요청 시작 이후 같은 상품 알림이 전혀 없는 쌍만이다. 주문 로그에 요청 시작 시각이
//   있을 때만 구분한다(없으면 이전처럼 응답 이후 알림이 없으면 missing).
// - 재고값 최신성: 재조회 시작 시각보다 STALE_MARGIN_MS 먼저 응답을 받은 주문은 이미 커밋됐다. 응답의 가용 재고가
//   "시작 재고 − 그 주문 수"보다 크면 그 주문을 반영하지 못한 옛 값이다. 커밋됐지만 응답이 아직 안 온 주문은 세지 않으므로
//   옛 값을 놓칠 수는 있어도 잘못 옛 값으로 세지는 않는다(주문 1건 = 수량 1, 측정 중 만료·취소 없음 전제: 결제 대기 10분).
import { readFileSync } from 'node:fs';

const [ssePath, ordersPath] = process.argv.slice(2);
if (!ssePath || !ordersPath) {
    console.error('사용법: node analyze.mjs <sse-result.json> <orders.log>');
    process.exit(1);
}

// 측정 종료 직전 주문은 다음 알림을 받기 전에 연결이 끊길 수 있어 표본에서 뺀다(병합 창 1초 + 여유).
const TAIL_MS = 3000;
// 호스트(Node)와 도커 VM(k6) 시계 차는 약 1ms로 쟀다. 여유를 두고 이만큼 먼저 끝난 주문만 커밋된 것으로 본다.
const STALE_MARGIN_MS = 5;
// 화면 반영은 알림 뒤 최대 2초(키별 간격)를 더 기다리고 응답까지 받아야 해서 더 넉넉히 뺀다.
const REFLECT_TAIL_MS = 8000;

const { summary, endedAt, conns, requests } = JSON.parse(readFileSync(ssePath, 'utf8'));
const refetchOn = Boolean(summary.refetch);
const orders = [...readFileSync(ordersPath, 'utf8').matchAll(/ORDER (\d+) (\d+)(?: (\d+))?/g)]
    .map((m) => [Number(m[1]), Number(m[2]), m[3] ? Number(m[3]) : null]);

// 연결별·상품별 수신 시각(정렬됨)
const received = conns.filter((c) => c.readyAt).map((c) => {
    const byDrop = new Map();
    for (const [t, dropId] of c.changes) {
        if (!byDrop.has(dropId)) byDrop.set(dropId, []);
        byDrop.get(dropId).push(t);
    }
    // 성공한 재조회만 [시작, 끝]. 연결마다 순서대로 실행하므로 시작 시각 순으로 정렬돼 있다.
    const refetches = (c.refetches ?? []).filter((r) => r[2] === 200);
    return { conn: c, end: c.closedAt ?? endedAt, byDrop, refetchStarts: refetches.map((r) => r[0]), refetches };
});

function indexAtOrAfter(times, t) {
    let lo = 0;
    let hi = times.length;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (times[mid] < t) lo = mid + 1;
        else hi = mid;
    }
    return lo < times.length ? lo : null;
}

function firstAtOrAfter(times, t) {
    const i = indexAtOrAfter(times, t);
    return i === null ? null : times[i];
}

const latencies = [];
const reflects = [];
let missing = 0;
let unresolved = 0;
let reflectMissing = 0;
let sampledOrders = 0;
for (const [t, dropId, sentAt] of orders) {
    let sampled = false;
    for (const { conn, end, byDrop, refetchStarts, refetches } of received) {
        if (!conn.dropIds.includes(dropId) || conn.readyAt > t || end - TAIL_MS < t) continue;
        sampled = true;
        const times = byDrop.get(dropId) ?? [];
        const at = firstAtOrAfter(times, t);
        if (at === null || at > end) {
            const early = sentAt !== null ? firstAtOrAfter(times, sentAt) : null;
            if (early !== null && early < t) unresolved++;
            else missing++;
            continue;
        }
        latencies.push(at - t);
        // 목록 재조회를 끈 회차(REFETCH_LIST=0)의 목록 연결은 화면 반영 표본에서 뺀다.
        if (!refetchOn || end - REFLECT_TAIL_MS < t || (!conn.detail && summary.refetchList === false)) continue;
        const i = indexAtOrAfter(refetchStarts, at);
        if (i === null || refetches[i][1] > end) reflectMissing++;
        else reflects.push(refetches[i][1] - t);
    }
    if (sampled) sampledOrders++;
}

const pct = (sorted, p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] : null);
const dist = (values) => {
    const sorted = values.slice().sort((a, b) => a - b);
    return { p50: pct(sorted, 50), p95: pct(sorted, 95), p99: pct(sorted, 99), max: sorted.at(-1) ?? null };
};

const result = {
    orders: orders.length,
    sampledOrders,
    pairs: latencies.length + missing + unresolved,
    missing,
    unresolved,
    deliveryLatencyMs: dist(latencies),
};
if (refetchOn) {
    result.reflectPairs = reflects.length + reflectMissing;
    result.reflectMissing = reflectMissing;
    result.reflectLatencyMs = dist(reflects);
    // 변경이 일어난 구간(첫 주문 ~ 마지막 주문)에 시작한 재조회 요청만 본다.
    const from = orders.length ? Math.min(...orders.map((o) => o[0])) : 0;
    const to = orders.length ? Math.max(...orders.map((o) => o[0])) : 0;
    const sec = Math.max(1, (to - from) / 1000);
    result.refetchWindowSec = Number(sec.toFixed(1));
    result.refetch = {};
    let total = 0;
    let errors = 0;
    for (const [kind, rows] of Object.entries(requests)) {
        const inWindow = rows.filter((r) => r[0] >= from && r[0] <= to);
        const failed = inWindow.filter((r) => r[2] !== 200).length;
        total += inWindow.length;
        errors += failed;
        const d = dist(inWindow.map((r) => r[1]));
        result.refetch[kind] = {
            count: inWindow.length, perSec: Number((inWindow.length / sec).toFixed(1)), errors: failed,
            p50: d.p50 && Number(d.p50.toFixed(1)), p95: d.p95 && Number(d.p95.toFixed(1)),
            p99: d.p99 && Number(d.p99.toFixed(1)), max: d.max && Number(d.max.toFixed(1)),
        };
    }
    result.refetchTotal = { count: total, perSec: Number((total / sec).toFixed(1)), errorRate: total ? errors / total : 0 };
    result.eventLoopDelayMs = summary.eventLoopDelayMs;
    if (summary.initialStock) result.staleness = staleness();
}

// 재고값 최신성: 상품별 주문 응답 시각(정렬)으로 "재조회 시작 전에 확실히 커밋된 주문 수"를 세어 관측 재고와 비교한다.
function staleness() {
    const ends = new Map();
    for (const [t, dropId] of orders) {
        if (!ends.has(dropId)) ends.set(dropId, []);
        ends.get(dropId).push(t);
    }
    for (const list of ends.values()) list.sort((a, b) => a - b);
    const committedBefore = (dropId, time) => {
        const list = ends.get(dropId);
        if (!list) return 0;
        const i = indexAtOrAfter(list, time);
        return i === null ? list.length : i;
    };
    const out = { stock: { checked: 0, stale: 0, maxBehind: 0 }, list: { checked: 0, stale: 0, maxBehind: 0 } };
    for (const c of conns) {
        for (const r of c.refetches ?? []) {
            if (r[2] !== 200 || r[4] == null) continue;
            const observedByDrop = c.detail ? { [c.dropIds[0]]: r[4] } : r[4];
            const bucket = c.detail ? out.stock : out.list;
            for (const [id, value] of Object.entries(observedByDrop)) {
                const initial = summary.initialStock[id];
                if (initial == null) continue;
                bucket.checked++;
                const behind = value - (initial - committedBefore(Number(id), r[0] - STALE_MARGIN_MS));
                if (behind > 0) {
                    bucket.stale++;
                    bucket.maxBehind = Math.max(bucket.maxBehind, behind);
                }
            }
        }
    }
    for (const b of Object.values(out)) b.staleRate = b.checked ? b.stale / b.checked : 0;
    return out;
}
console.log(JSON.stringify(result, null, 2));
