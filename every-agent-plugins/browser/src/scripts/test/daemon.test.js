'use strict';
/**
 * 守护进程协议层集成单测:真实 HTTP 服务 + fetch 模拟扩展(poll/result),零浏览器依赖。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// 隔离运行时目录,避免污染真实 ~/.everyagent。
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'ea-daemon-'));
process.env.EA_BROWSER_HOME = home;

const { start } = require('../daemon');

async function postJson(url, body, headers = {}) {
    const r = await fetch(url, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, headers),
        body: JSON.stringify(body),
    });
    return { status: r.status, headers: r.headers, json: await r.json() };
}

test('全链路:hello → /cli/cmd(tabs) → 扩展 poll 取 op → result 回填 → CLI 应答', async () => {
    const d = await start({ idleMs: 60000, pollTimeoutMs: 300 });
    const base = 'http://127.0.0.1:' + d.port;
    try {
        // health 免鉴权
        const h = await fetch(base + '/health');
        assert.strictEqual(h.status, 200);
        assert.strictEqual((await h.json()).ok, true);

        // 扩展注册
        const hello = await postJson(base + '/ext/hello',
                { token: d.token, extVersion: '0.1.0', browser: 'Chrome/1' },
                { 'x-ea-token': d.token });
        assert.strictEqual(hello.json.ok, true);
        assert.ok(d.state.ext.lastSeen > 0);

        // 错 token 401
        const bad = await fetch(base + '/ext/poll?token=wrong');
        assert.strictEqual(bad.status, 401);

        // CLI 命令与扩展 poll 并发
        const cmdPromise = postJson(base + '/cli/cmd', { token: d.token, type: 'tabs', args: {} },
                { 'x-ea-token': d.token });

        let cmd = null;
        for (let i = 0; i < 40 && !cmd; i++) {
            const r = await fetch(base + '/ext/poll?token=' + d.token);
            assert.strictEqual(r.headers.get('access-control-allow-origin'), '*', 'CORS 头缺失');
            const j = await r.json();
            if (j.cmds && j.cmds.length) { cmd = j.cmds[0]; }
        }
        assert.ok(cmd, 'poll 应取到 tabs.list op');
        assert.strictEqual(cmd.type, 'tabs.list');

        // 扩展回填结果 → CLI 应答解锁
        const result = await postJson(base + '/ext/result',
                { token: d.token, opId: cmd.opId, ok: true,
                    data: { tabs: [{ tabId: 9, url: 'https://x', title: 'X', active: true }] } },
                { 'x-ea-token': d.token });
        assert.strictEqual(result.json.ok, true);

        const cmdRes = await cmdPromise;
        assert.strictEqual(cmdRes.json.ok, true);
        assert.strictEqual(cmdRes.json.data.tabs[0].tabId, 9);
        assert.deepStrictEqual(cmdRes.json.data.sessionTabIds, []);
    } finally {
        d.close();
    }
});

test('poll 空闲超时返回空 cmds;OPTIONS 预检 204', async () => {
    const d = await start({ idleMs: 60000, pollTimeoutMs: 200 });
    const base = 'http://127.0.0.1:' + d.port;
    try {
        const t0 = Date.now();
        const r = await fetch(base + '/ext/poll?token=' + d.token);
        const j = await r.json();
        assert.deepStrictEqual(j.cmds, []);
        assert.ok(Date.now() - t0 >= 150, '应挂起至超时');
        const pre = await fetch(base + '/ext/poll?token=' + d.token, { method: 'OPTIONS' });
        assert.strictEqual(pre.status, 204);
    } finally {
        d.close();
    }
});

test('扩展离线:extOfflineMs 内无人 poll → 命令返回「扩展未连接」', async () => {
    const d = await start({ idleMs: 60000, pollTimeoutMs: 300, extOfflineMs: 250 });
    const base = 'http://127.0.0.1:' + d.port;
    try {
        const t0 = Date.now();
        const r = await postJson(base + '/cli/cmd', { token: d.token, type: 'tabs', args: {} },
                { 'x-ea-token': d.token });
        assert.strictEqual(r.json.ok, false);
        assert.ok(r.json.error.includes('扩展未连接'), r.json.error);
        assert.ok(Date.now() - t0 < 5000);
    } finally {
        d.close();
    }
});

test('open 全链路:create + waitReady 两个 op 依次下发;runtime 目录就位', async () => {
    const d = await start({ idleMs: 60000, pollTimeoutMs: 300 });
    const base = 'http://127.0.0.1:' + d.port;
    try {
        const cmdPromise = postJson(base + '/cli/cmd',
                { token: d.token, type: 'open', args: { url: 'https://x' } },
                { 'x-ea-token': d.token });

        // op 是串行的:waitReady 要等 tab.create 应答后才入队——逐个取、逐个回填。
        async function takeOne() {
            for (let i = 0; i < 40; i++) {
                const r = await fetch(base + '/ext/poll?token=' + d.token);
                const j = await r.json();
                if (j.cmds && j.cmds.length) { return j.cmds[0]; }
            }
            return null;
        }
        const first = await takeOne();
        assert.ok(first, '应取到第一个 op');
        assert.strictEqual(first.type, 'tab.create');
        await postJson(base + '/ext/result', { token: d.token, opId: first.opId, ok: true, data: { tabId: 3 } });

        const second = await takeOne();
        assert.ok(second, '应取到第二个 op');
        assert.strictEqual(second.type, 'tab.waitReady');
        await postJson(base + '/ext/result', { token: d.token, opId: second.opId, ok: true, data: {} });

        const res = await cmdPromise;
        assert.strictEqual(res.json.ok, true);
        assert.strictEqual(res.json.data.tabId, 3);

        // runtime.json 未由 start() 写(那是 main() 职责)——此处仅验证目录可用
        fs.writeFileSync(path.join(home, 'probe.txt'), 'ok');
        assert.ok(fs.existsSync(path.join(home, 'probe.txt')));
    } finally {
        d.close();
    }
});
