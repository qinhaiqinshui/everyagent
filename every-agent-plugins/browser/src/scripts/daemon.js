#!/usr/bin/env node
'use strict';
/**
 * 守护进程:回环命令服务器,桥接 CLI(POST /cli/cmd)与浏览器扩展(GET /ext/poll)。
 *
 * 生命周期:120s 无 CLI 命令自动退出(每条 CLI 命令刷新;open --timeout 可覆盖),
 * 退出前 best-effort 关闭会话标签;close 命令(无 --tab)主动优雅退出。
 * 运行时信息(port/token/pid)原子写 ~/.everyagent/browser-automation/runtime.json。
 */
const crypto = require('node:crypto');
const paths = require('./lib/paths');
const { startHttpServer, sendJson } = require('./lib/protocol');
const { createCommands } = require('./lib/commands');

/** 命令入队后这么久仍无扩展来取 → 判定扩展离线。 */
const EXT_OFFLINE_MS = 8000;
/** 单个 op 硬超时。 */
const OP_TIMEOUT_MS = 60000;
/** 默认闲置自动退出时长。 */
const DEFAULT_IDLE_MS = 120000;

/**
 * 组装并启动守护进程(listen 127.0.0.1 随机端口)。
 * @param {object} [opts]
 * @param {number} [opts.idleMs] 闲置退出时长(测试可缩短)
 * @param {number} [opts.pollTimeoutMs] poll 挂起时长(测试可缩短)
 * @param {()=>void} [opts.onShutdownRequest] 收到 close(全部)时应答后的退出钩子
 * @returns {Promise<{server, port, token, sendOp, commands, state, close, resetIdle}>}
 */
function start(opts = {}) {
    const idleMs = opts.idleMs || DEFAULT_IDLE_MS;
    const extOfflineMs = opts.extOfflineMs || EXT_OFFLINE_MS;
    const token = crypto.randomBytes(24).toString('hex');

    const state = {
        ext: null,            // {extVersion, browser, lastSeen}
        queue: [],            // 待下发 op:{opId,type,args}
        pending: new Map(),   // opId → entry {settle,done,taken}
        waiters: [],          // 挂起的 /ext/poll:{res, timer}
        idleTimer: null,
    };

    /** 唤醒挂起的 poll:队列有货就派发。 */
    function flushWaiters() {
        while (state.queue.length && state.waiters.length) {
            const op = state.queue.shift();
            const waiter = state.waiters.shift();
            clearTimeout(waiter.timer);
            const entry = state.pending.get(op.opId);
            if (entry) { entry.taken = true; }
            sendJson(waiter.res, 200, { cmds: [op] });
        }
    }

    /** 下发一个 op 并等待扩展应答(离线 8s / 硬超时双保险)。 */
    function sendOp(type, args, timeoutMs = OP_TIMEOUT_MS) {
        return new Promise((resolve, reject) => {
            const opId = crypto.randomUUID();
            const entry = { done: false, taken: false };
            entry.settle = (v, isErr) => {
                if (entry.done) { return; }
                entry.done = true;
                clearTimeout(offlineTimer);
                clearTimeout(hardTimer);
                state.pending.delete(opId);
                isErr ? reject(v) : resolve(v);
            };
            state.pending.set(opId, entry);
            const offlineTimer = setTimeout(() => {
                if (!entry.done && !entry.taken) {
                    entry.settle(new Error('扩展未连接,请按 skill 安装指引安装并配置浏览器扩展'), true);
                }
            }, extOfflineMs);
            const hardTimer = setTimeout(() => {
                if (!entry.done) {
                    entry.settle(new Error('op 执行超时: ' + type), true);
                }
            }, timeoutMs);
            state.queue.push({ opId, type, args });
            flushWaiters();
        });
    }

    const commands = createCommands({
        sendOp,
        tmpDir: paths.tmpDir(),
        onIdleOverride: (ms) => {
            state.idleMsCurrent = ms;
            resetIdle();
        },
    });
    state.idleMsCurrent = idleMs;

    function resetIdle() {
        if (state.idleTimer) { clearTimeout(state.idleTimer); }
        state.idleTimer = setTimeout(() => {
            // 闲置退出:best-effort 关会话标签后退出(1.5s 内不强等)。
            commands.closeAllSessionTabsBestEffort();
            setTimeout(() => process.exit(0), 1500);
        }, state.idleMsCurrent);
    }

    function touchExt(info) {
        state.ext = Object.assign({}, info, { lastSeen: Date.now() });
    }

    const server = startHttpServer({
        token,
        pollTimeoutMs: opts.pollTimeoutMs || 25000,
        async onHello(body) {
            touchExt({ extVersion: body.extVersion || '', browser: body.browser || '' });
            return { ok: true, serverTime: Date.now() };
        },
        onPollWait(res, pollTimeoutMs) {
            touchExt({});
            const waiter = { res, timer: null };
            waiter.timer = setTimeout(() => {
                const i = state.waiters.indexOf(waiter);
                if (i >= 0) { state.waiters.splice(i, 1); }
                sendJson(res, 200, { cmds: [] });
            }, pollTimeoutMs);
            state.waiters.push(waiter);
            flushWaiters();
        },
        async onExtResult(body) {
            const entry = state.pending.get(body.opId);
            if (entry) {
                if (body.ok) {
                    entry.settle(body.data || {}, false);
                } else {
                    entry.settle(new Error(body.error || '扩展执行失败'), true);
                }
            }
            touchExt({});
            return { ok: true };
        },
        async onCliCmd(body) {
            resetIdle();
            try {
                const data = await commands.execute(body.type, body.args || {});
                if (data && data.shutdown && opts.onShutdownRequest) {
                    setTimeout(opts.onShutdownRequest, 100);
                }
                return { ok: true, data };
            } catch (e) {
                return { ok: false, error: String((e && e.message) || e) };
            }
        },
    });

    return new Promise((resolve) => {
        server.listen(0, '127.0.0.1', () => {
            const port = server.address().port;
            resetIdle();
            resolve({
                server, port, token, sendOp, commands, state, resetIdle,
                close() {
                    if (state.idleTimer) { clearTimeout(state.idleTimer); }
                    server.close();
                },
            });
        });
    });
}

/** 独立运行入口:起服务 + 写 runtime.json + 优雅退出钩子。 */
async function main() {
    paths.ensureDirs();
    paths.clearTmp();
    const d = await start({
        onShutdownRequest() {
            // close(全部会话标签)已应答 → 优雅退出。
            d.close();
            setTimeout(() => process.exit(0), 200);
        },
    });
    paths.atomicWriteJson(paths.runtimeFile(), {
        port: d.port,
        token: d.token,
        pid: process.pid,
        startedAt: new Date().toISOString(),
    });
    process.on('SIGTERM', () => process.exit(0));
    process.on('SIGINT', () => process.exit(0));
    process.stderr.write('[browser-automation daemon] listening on 127.0.0.1:' + d.port + '\n');
}

module.exports = { start, EXT_OFFLINE_MS, OP_TIMEOUT_MS, DEFAULT_IDLE_MS };

if (require.main === module) {
    main().catch((e) => {
        process.stderr.write('[browser-automation daemon] 启动失败: ' + (e && e.stack || e) + '\n');
        process.exit(1);
    });
}
