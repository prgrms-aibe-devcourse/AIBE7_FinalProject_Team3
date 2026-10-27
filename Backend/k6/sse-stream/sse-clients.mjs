// GR-157: stock-stream SSE 동시 연결 부하. k6 기본 이미지는 SSE 스트림을 읽지 못해 Node로 연결을 유지한다.
// 주문 트래픽(재고 변경 원천)은 같은 폴더의 order-load.js(k6)로 따로 건다.
//
// 연결 구성은 REALTIME_STOCK.md 13.2를 따른다: 상세 구독 70%(인기 상품 1개), 목록 구독 30%(24개 전체).
// 자동 재연결은 하지 않는다. 종료 후 서버 쪽 연결·구독이 0으로 정리되는지 확인해야 하기 때문이다.
// 쿠키를 보내지 않는다(공개 API, 무효 쿠키면 401).
//
//   node Backend/k6/sse-stream/sse-clients.mjs
//   CONNECTIONS=200 DURATION_SEC=600 OUT=Backend/k6/sse-stream/out/sse-200.json node ...
//
// 결과 JSON(OUT)은 analyze.mjs가 order-load.js 로그와 대응시켜 전달 지연을 계산한다.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const CONNECTIONS = Number(process.env.CONNECTIONS || 100);
const DETAIL_RATIO = Number(process.env.DETAIL_RATIO || 0.7);
const RAMP_SEC = Number(process.env.RAMP_SEC || 20);
const DURATION_SEC = Number(process.env.DURATION_SEC || 300);
const OUT = process.env.OUT || 'Backend/k6/sse-stream/out/sse-result.json';
const KEYWORD = 'k6 SSE 상품';

async function loadDropIds() {
    const res = await fetch(`${BASE_URL}/api/v1/drops?keyword=${encodeURIComponent(KEYWORD)}&size=100`);
    if (res.status !== 200) {
        throw new Error(`시드 데이터 조회 실패: status=${res.status}. sse-stream/seed.sql을 먼저 실행하세요.`);
    }
    const content = (await res.json()).data.content;
    if (content.length < 2) {
        throw new Error('sse-stream/seed.sql이 만든 DROP이 부족합니다.');
    }
    const sorted = content.slice().sort((a, b) => a.name.localeCompare(b.name));
    return { hot: sorted[0].dropId, all: sorted.map((d) => d.dropId) };
}

// 연결 하나를 열고 이벤트 수신 시각을 기록한다. 끝날 때까지 resolve하지 않는다.
async function connect(conn, signal) {
    conn.startedAt = Date.now();
    let res;
    try {
        res = await fetch(`${BASE_URL}/api/v1/drops/stock-stream?dropIds=${conn.dropIds.join(',')}`, {
            headers: { Accept: 'text/event-stream' },
            signal,
        });
    } catch (error) {
        conn.error = error.name === 'AbortError' ? null : error.cause?.code || error.name;
        return;
    }
    conn.status = res.status;
    if (res.status !== 200) {
        await res.body?.cancel();
        return;
    }
    const decoder = new TextDecoder();
    let buffer = '';
    try {
        for await (const chunk of res.body) {
            buffer += decoder.decode(chunk, { stream: true });
            let end;
            while ((end = buffer.indexOf('\n\n')) >= 0) {
                handleFrame(conn, buffer.slice(0, end));
                buffer = buffer.slice(end + 2);
            }
        }
        // 서버가 스트림을 닫았다(수명 만료·종료 안내 등).
        conn.closedAt ??= Date.now();
    } catch (error) {
        if (error.name !== 'AbortError') {
            conn.error = error.cause?.code || error.name;
            conn.closedAt ??= Date.now();
        }
    }
}

function handleFrame(conn, frame) {
    const now = Date.now();
    let event = 'message';
    let data = '';
    for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
        else if (line.startsWith(':')) conn.heartbeats++;
    }
    if (!data) return;
    const payload = JSON.parse(data);
    if (event === 'ready') {
        conn.readyAt = now;
    } else if (event === 'drop-changed') {
        conn.changes.push([now, payload.dropId]);
    } else if (event === 'stream-closed') {
        conn.closeReason = payload.reason;
        conn.closedAt = now;
    }
}

function percentile(sorted, p) {
    if (sorted.length === 0) return null;
    return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

function countBy(values) {
    const counts = {};
    for (const value of values) counts[value] = (counts[value] || 0) + 1;
    return counts;
}

const ids = await loadDropIds();
const controller = new AbortController();
const detailCount = Math.round(CONNECTIONS * DETAIL_RATIO);
const conns = Array.from({ length: CONNECTIONS }, (_, i) => ({
    id: i,
    dropIds: i < detailCount ? [ids.hot] : ids.all,
    startedAt: null, status: null, readyAt: null, closedAt: null, closeReason: null, error: null,
    heartbeats: 0, changes: [],
}));

console.log(`연결 ${CONNECTIONS}개(상세 ${detailCount} / 목록 ${CONNECTIONS - detailCount})를 ${RAMP_SEC}초에 걸쳐 열고 ${DURATION_SEC}초 유지합니다.`);
const tasks = [];
const gapMs = (RAMP_SEC * 1000) / CONNECTIONS;
for (const conn of conns) {
    tasks.push(connect(conn, controller.signal));
    await new Promise((resolve) => setTimeout(resolve, gapMs));
}

const progress = setInterval(() => {
    const open = conns.filter((c) => c.readyAt && !c.closedAt).length;
    const changes = conns.reduce((sum, c) => sum + c.changes.length, 0);
    console.log(`[${new Date().toISOString()}] 열린 연결 ${open} / drop-changed 누적 ${changes}`);
}, 10_000);

const stop = () => controller.abort();
process.once('SIGINT', stop);
const timer = setTimeout(stop, DURATION_SEC * 1000);
await Promise.all(tasks);
const endedAt = Date.now();
clearTimeout(timer);
clearInterval(progress);

const readyLatencies = conns.filter((c) => c.readyAt).map((c) => c.readyAt - c.startedAt).sort((a, b) => a - b);
const summary = {
    baseUrl: BASE_URL,
    connections: CONNECTIONS,
    detailConnections: detailCount,
    durationSec: DURATION_SEC,
    hotDropId: ids.hot,
    statuses: countBy(conns.map((c) => c.status ?? 'none')),
    ready: readyLatencies.length,
    readyLatencyMs: { p50: percentile(readyLatencies, 50), p95: percentile(readyLatencies, 95), max: readyLatencies.at(-1) ?? null },
    // 측정 종료(abort) 전에 서버 쪽에서 끊긴 연결. 10분 수명 만료나 전송 정체(5초) 종료가 여기에 잡힌다.
    closedByServer: conns.filter((c) => c.closedAt && c.closedAt < endedAt).length,
    closeReasons: countBy(conns.filter((c) => c.closeReason).map((c) => c.closeReason)),
    errors: countBy(conns.filter((c) => c.error).map((c) => c.error)),
    dropChanged: conns.reduce((sum, c) => sum + c.changes.length, 0),
    heartbeats: conns.reduce((sum, c) => sum + c.heartbeats, 0),
};
console.log(JSON.stringify(summary, null, 2));

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify({ summary, endedAt, conns }));
console.log(`결과 저장: ${OUT}`);
