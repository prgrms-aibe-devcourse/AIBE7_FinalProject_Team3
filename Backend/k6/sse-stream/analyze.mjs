// GR-157: 변경 알림 전달 지연(REALTIME_STOCK.md 13.1 "DB 커밋 완료 → drop-changed 수신")을 계산한다.
// sse-clients.mjs 결과 JSON과 order-load.js 콘솔 로그(ORDER <응답 수신 ms> <dropId>)를 대응시킨다.
//
//   node Backend/k6/sse-stream/analyze.mjs <sse-result.json> <orders.log>
//
// 주문 1건 × 그 상품을 구독 중인 연결 1개마다, 주문 응답 수신 이후 처음 받은 같은 상품의 drop-changed까지를 지연으로 본다.
// ponytail: 커밋 시각 대신 주문 응답 수신 시각을 쓴 근사값이다. 커밋→응답 수신 구간(수 ms)만큼 짧게 잡히고,
// 그 사이에 도착한 알림은 다음 알림으로 대응돼 최대 병합 창(1초)만큼 길게 잡힐 수 있다. 정밀 측정이 필요하면
// 서버에 커밋 시각 기록을 추가해야 한다. 두 프로세스가 같은 머신 시계를 쓴다는 전제다(도커 VM 시계 오차는 기록).
import { readFileSync } from 'node:fs';

const [ssePath, ordersPath] = process.argv.slice(2);
if (!ssePath || !ordersPath) {
    console.error('사용법: node analyze.mjs <sse-result.json> <orders.log>');
    process.exit(1);
}

// 측정 종료 직전 주문은 다음 알림을 받기 전에 연결이 끊길 수 있어 표본에서 뺀다(병합 창 1초 + 여유).
const TAIL_MS = 3000;

const { endedAt, conns } = JSON.parse(readFileSync(ssePath, 'utf8'));
const orders = [...readFileSync(ordersPath, 'utf8').matchAll(/ORDER (\d+) (\d+)/g)]
    .map((m) => [Number(m[1]), Number(m[2])]);

// 연결별·상품별 수신 시각(정렬됨)
const received = conns.filter((c) => c.readyAt).map((c) => {
    const byDrop = new Map();
    for (const [t, dropId] of c.changes) {
        if (!byDrop.has(dropId)) byDrop.set(dropId, []);
        byDrop.get(dropId).push(t);
    }
    return { conn: c, end: c.closedAt ?? endedAt, byDrop };
});

function firstAtOrAfter(times, t) {
    let lo = 0;
    let hi = times.length;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (times[mid] < t) lo = mid + 1;
        else hi = mid;
    }
    return lo < times.length ? times[lo] : null;
}

const latencies = [];
let missing = 0;
let sampledOrders = 0;
for (const [t, dropId] of orders) {
    let sampled = false;
    for (const { conn, end, byDrop } of received) {
        if (!conn.dropIds.includes(dropId) || conn.readyAt > t || end - TAIL_MS < t) continue;
        sampled = true;
        const at = firstAtOrAfter(byDrop.get(dropId) ?? [], t);
        if (at === null || at > end) missing++;
        else latencies.push(at - t);
    }
    if (sampled) sampledOrders++;
}

latencies.sort((a, b) => a - b);
const pct = (p) => (latencies.length ? latencies[Math.min(latencies.length - 1, Math.ceil((p / 100) * latencies.length) - 1)] : null);
console.log(JSON.stringify({
    orders: orders.length,
    sampledOrders,
    pairs: latencies.length + missing,
    missing,
    deliveryLatencyMs: { p50: pct(50), p95: pct(95), p99: pct(99), max: latencies.at(-1) ?? null },
}, null, 2));
