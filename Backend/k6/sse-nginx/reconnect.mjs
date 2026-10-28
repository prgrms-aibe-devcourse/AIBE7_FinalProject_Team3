// GR-180 Nginx 경유 검증용 SSE 클라이언트. sse-stream/sse-clients.mjs와 달리 프론트처럼 끊기면 다시 접속한다.
// 측정 중에 Nginx reload·backend 재시작을 직접 일으키고, 연결이 어떻게 끝났는지(종료 안내 / 안내 없는 종료 / 오류)와
// 끊긴 뒤 다시 ready가 되기까지 걸린 시간을 기록한다. heartbeat 사이 간격으로 Nginx가 프레임을 모아 보내지 않는지(flush)도 본다.
//
// 재접속 지연은 REALTIME_STOCK.md 12절을 따른다.
// - 정상 종료 안내(RECONNECT_REQUIRED): 직전 연결 시작부터 최소 5초 + 균등 0~5초
// - 실패(안내 없는 종료·오류·429/503): n번째 실패 후 균등(0.5b, b)초, b = min(60, 5 × 2^(n-1)). ready가 되면 n을 0으로
//
//   BASE_URL=http://localhost:8088 CONNECTIONS=200 DURATION_SEC=300 OUT=k6/sse-nginx/out/reload.json node k6/sse-nginx/reconnect.mjs
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8088';
const CONNECTIONS = Number(process.env.CONNECTIONS || 100);
const RAMP_SEC = Number(process.env.RAMP_SEC || 10);
const DURATION_SEC = Number(process.env.DURATION_SEC || 300);
const OUT = process.env.OUT || 'k6/sse-nginx/out/reconnect.json';
const KEYWORD = 'k6 SSE 상품';

const listRes = await fetch(`${BASE_URL}/api/v1/drops?keyword=${encodeURIComponent(KEYWORD)}&size=100`);
if (listRes.status !== 200) throw new Error(`시드 데이터 조회 실패: status=${listRes.status}. sse-stream/seed.sql을 먼저 실행하세요.`);
const dropId = (await listRes.json()).data.content.map((d) => d.dropId).sort((a, b) => a - b)[0];

const controller = new AbortController();
let stopping = false;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function runConnection(conn) {
    let failures = 0;
    let lastStart = -Infinity;
    while (!stopping) {
        const session = { start: Date.now(), status: null, readyAt: null, end: null, kind: null, reason: null, heartbeats: [] };
        conn.sessions.push(session);
        lastStart = session.start;
        await openSession(session);
        if (stopping) break;
        let delay;
        if (session.kind === 'event' && session.reason === 'RECONNECT_REQUIRED') {
            failures = 0;
            delay = Math.max(0, lastStart + 5000 - Date.now()) + Math.random() * 5000;
        } else {
            failures++;
            const b = Math.min(60, 5 * 2 ** (failures - 1)) * 1000;
            delay = b / 2 + Math.random() * (b / 2);
        }
        await sleep(delay);
    }
}

async function openSession(session) {
    let res;
    try {
        res = await fetch(`${BASE_URL}/api/v1/drops/stock-stream?dropIds=${dropId}`, {
            headers: { Accept: 'text/event-stream' },
            signal: controller.signal,
        });
    } catch (error) {
        session.end = Date.now();
        session.kind = stopping ? 'stopped' : 'error';
        session.reason = error.cause?.code || error.name;
        return;
    }
    session.status = res.status;
    if (res.status !== 200) {
        await res.body?.cancel();
        session.end = Date.now();
        session.kind = 'http';
        return;
    }
    const decoder = new TextDecoder();
    let buffer = '';
    try {
        for await (const chunk of res.body) {
            buffer += decoder.decode(chunk, { stream: true });
            let end;
            while ((end = buffer.indexOf('\n\n')) >= 0) {
                handleFrame(session, buffer.slice(0, end));
                buffer = buffer.slice(end + 2);
            }
        }
        session.kind ??= 'eof';
    } catch (error) {
        session.kind ??= stopping ? 'stopped' : 'error';
        session.reason ??= error.cause?.code || error.name;
    }
    session.end = Date.now();
}

function handleFrame(session, frame) {
    let event = 'message';
    let data = '';
    for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
        else if (line.startsWith(':')) session.heartbeats.push(Date.now());
    }
    if (event === 'ready') session.readyAt = Date.now();
    else if (event === 'stream-closed') {
        session.kind = 'event';
        session.reason = JSON.parse(data).reason;
    }
}

function dist(values) {
    const sorted = values.slice().sort((a, b) => a - b);
    const pct = (p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] : null);
    return { n: sorted.length, p50: pct(50), p95: pct(95), max: sorted.at(-1) ?? null };
}

const conns = Array.from({ length: CONNECTIONS }, (_, id) => ({ id, sessions: [] }));
console.log(`${BASE_URL} 연결 ${CONNECTIONS}개(상품 ${dropId})를 ${RAMP_SEC}초에 걸쳐 열고 ${DURATION_SEC}초 유지합니다. 끊기면 재접속합니다.`);
const tasks = [];
for (const conn of conns) {
    tasks.push(runConnection(conn));
    await sleep((RAMP_SEC * 1000) / CONNECTIONS);
}
const progress = setInterval(() => {
    const open = conns.filter((c) => { const s = c.sessions.at(-1); return s?.readyAt && !s.end; }).length;
    const sessions = conns.reduce((sum, c) => sum + c.sessions.length, 0);
    console.log(`[${new Date().toISOString()}] ready 연결 ${open} / 누적 세션 ${sessions}`);
}, 5000);
await sleep(DURATION_SEC * 1000);
stopping = true;
controller.abort();
await Promise.all(tasks);
clearInterval(progress);

const sessions = conns.flatMap((c) => c.sessions);
const ended = sessions.filter((s) => s.kind && s.kind !== 'stopped');
const countBy = (values) => values.reduce((acc, v) => ({ ...acc, [v]: (acc[v] || 0) + 1 }), {});
// 끊긴 세션마다 같은 연결의 다음 ready까지 걸린 시간(재접속 대기 포함)
const recover = [];
let unrecovered = 0;
for (const conn of conns) {
    conn.sessions.forEach((s, i) => {
        if (!s.readyAt || !s.kind || s.kind === 'stopped') return;
        const next = conn.sessions.slice(i + 1).find((n) => n.readyAt);
        if (next) recover.push(next.readyAt - s.end);
        else unrecovered++;
    });
}
// ready 이후 heartbeat 사이 간격. 15초 주기가 그대로 오면 Nginx가 프레임을 버퍼에 모으지 않고 바로 흘려보낸 것이다.
const gaps = sessions.flatMap((s) => s.heartbeats.slice(1).map((t, i) => t - s.heartbeats[i]));
const summary = {
    baseUrl: BASE_URL,
    connections: CONNECTIONS,
    durationSec: DURATION_SEC,
    readyAtEnd: conns.filter((c) => c.sessions.at(-1)?.readyAt && c.sessions.at(-1).kind === 'stopped').length,
    sessions: sessions.length,
    firstStatus: countBy(conns.map((c) => c.sessions[0]?.status ?? 'none')),
    endings: countBy(ended.map((s) => `${s.kind}${s.reason ? `:${s.reason}` : ''}${s.status && s.status !== 200 ? `:${s.status}` : ''}`)),
    recoverMs: dist(recover),
    unrecovered,
    heartbeatGapMs: dist(gaps),
};
console.log(JSON.stringify(summary, null, 2));
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify({ summary, conns }));
console.log(`결과 저장: ${OUT}`);
