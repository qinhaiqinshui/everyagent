#!/usr/bin/env node
'use strict';
/**
 * CLI 入口:命令解析 → 守护进程自检/拉起 → POST /cli/cmd → YAML 信封输出。
 *
 * 用法(对齐 playwright-browser skill):
 *   node browser.js open <url> [--timeout 秒]
 *   node browser.js goto <url> --tab <tabId>
 *   node browser.js back|forward --tab <tabId>
 *   node browser.js click <selector> --tab <tabId>
 *   node browser.js fill <selector> <text> --tab <tabId>
 *   node browser.js query <selector> --tab <tabId> [--limit n] [--full]
 *   node browser.js aria --tab <tabId> [--save <path>]
 *   node browser.js screenshot <path> --tab <tabId>
 *   node browser.js tabs
 *   node browser.js close [--tab <tabId>]
 */
const { spawn } = require('node:child_process');
const path = require('node:path');
const paths = require('./lib/paths');
const yaml = require('./lib/yaml');

const HELP = `用法: node browser.js <command> [args] [options]
命令: open/goto/back/forward/click/fill/query/aria/screenshot/tabs/close
示例: node browser.js open https://example.com
      node browser.js click "text=登录" --tab 123
选项: --tab <tabId>  --timeout <秒>  --limit <n>  --full  --save <path>  --viewport`;

/** 失败信封 + exit 1(置 exitCode 自然退出,避免 keep-alive 句柄竞态)。 */
function fail(msg) {
    process.stdout.write(yaml.envelope({ success: false, message: msg }));
    process.exitCode = 1;
}

/** Node 版本自检(需 ≥18:全局 fetch / node:test 等)。 */
function nodeCheck() {
    const major = Number(process.versions.node.split('.')[0]);
    if (!Number.isFinite(major) || major < 18) {
        fail('当前 Node 版本 ' + process.versions.node + ' 过低,需要 Node ≥ 18。'
                + '请到 https://nodejs.org 安装或升级后重试。');
        return false;
    }
    return true;
}

/** 端口健康检查(短连接,用完即关)。 */
async function health(port, timeoutMs = 2000) {
    const ac = new AbortController();
    const t = setTimeout(() => ac.abort(), timeoutMs);
    try {
        const r = await fetch('http://127.0.0.1:' + port + '/health',
                { signal: ac.signal, headers: { Connection: 'close' } });
        return r.ok;
    } catch {
        return false;
    } finally {
        clearTimeout(t);
        ac.abort();
    }
}

/** 自检守护进程,不在则 detached 拉起并等就绪。 */
async function ensureDaemon() {
    let rt = paths.readRuntime();
    if (rt && rt.port && (await health(rt.port))) {
        return rt;
    }
    const daemonJs = path.join(__dirname, 'daemon.js');
    const child = spawn(process.execPath, [daemonJs], {
        detached: true,
        stdio: 'ignore',
        cwd: __dirname,
    });
    child.unref();
    for (let i = 0; i < 25; i++) {
        await new Promise((r) => setTimeout(r, 200));
        rt = paths.readRuntime();
        if (rt && rt.port && (await health(rt.port, 1000))) {
            return rt;
        }
    }
    fail('守护进程启动失败:node ' + daemonJs + '(请确认 Node ≥ 18 且用户目录可写)');
    return null;
}

/** 解析 argv:位置参数 + 选项。 */
function parseArgv(rest) {
    const pos = [];
    const opt = { tab: undefined, timeout: undefined, limit: undefined,
        full: false, save: undefined, viewport: false };
    for (let i = 0; i < rest.length; i++) {
        const a = rest[i];
        if (a === '--tab') { opt.tab = rest[++i]; }
        else if (a === '--timeout') { opt.timeout = Number(rest[++i]); }
        else if (a === '--limit') { opt.limit = Number(rest[++i]); }
        else if (a === '--full') { opt.full = true; }
        else if (a === '--save') { opt.save = rest[++i]; }
        else if (a === '--viewport') { opt.viewport = true; }
        else if (a === '-h' || a === '--help') { opt.help = true; }
        else { pos.push(a); }
    }
    return { pos, opt };
}

/** 命令 + 选项 → {type, args}(路径类参数在此解析为绝对路径,守护 cwd 可能不同)。 */
function buildCommand(cmd, pos, opt) {
    switch (cmd) {
        case 'open':
            return { type: 'open', args: { url: pos[0], timeoutSec: opt.timeout } };
        case 'goto':
            return { type: 'goto', args: { url: pos[0], tabId: opt.tab } };
        case 'back':
            return { type: 'back', args: { tabId: opt.tab } };
        case 'forward':
            return { type: 'forward', args: { tabId: opt.tab } };
        case 'click':
            return { type: 'click', args: { selector: pos[0], tabId: opt.tab } };
        case 'fill':
            return { type: 'fill', args: { selector: pos[0], text: pos[1], tabId: opt.tab } };
        case 'query':
            return { type: 'query', args: { selector: pos[0], tabId: opt.tab,
                limit: opt.limit, full: opt.full } };
        case 'aria':
            return { type: 'aria', args: { tabId: opt.tab,
                save: opt.save ? path.resolve(opt.save) : undefined } };
        case 'screenshot':
            return { type: 'screenshot', args: { tabId: opt.tab,
                savePath: pos[0] ? path.resolve(pos[0]) : undefined, viewport: opt.viewport } };
        case 'tabs':
            return { type: 'tabs', args: {} };
        case 'close':
            return { type: 'close', args: { tabId: opt.tab } };
        default:
            return null;
    }
}

/** 打印结果:aria 带 dsl → 双文档;其余信封。 */
function printResult(type, ok, data, error) {
    if (!ok) {
        process.stdout.write(yaml.envelope({ success: false, message: error }));
        return;
    }
    if (type === 'aria' && data && typeof data.dsl === 'string') {
        process.stdout.write(yaml.twoDocs(
            { message: '页面结构 DSL(超阈值或 --save 时只返回统计)', data: {
                totalLines: data.totalLines, totalChars: data.totalChars,
                filter: undefinedAsNull(data.filter), snapshot: '@next',
            } },
            data.dsl,
        ));
        return;
    }
    process.stdout.write(yaml.envelope({
        success: true,
        message: type + ' 完成',
        data,
    }));
}

function undefinedAsNull(v) { return v === undefined ? null : v; }

async function main() {
    if (!nodeCheck()) {
        return;
    }
    const [, , cmd, ...rest] = process.argv;
    if (!cmd || cmd === '-h' || cmd === '--help' || cmd === 'help') {
        process.stdout.write(HELP + '\n');
        if (!cmd) { process.exitCode = 1; }
        return;
    }
    const { pos, opt } = parseArgv(rest);
    if (opt.help) {
        process.stdout.write(HELP + '\n');
        return;
    }
    const command = buildCommand(cmd, pos, opt);
    if (!command) {
        fail('未知命令: ' + cmd + '\n' + HELP);
        return;
    }
    const rt = await ensureDaemon();
    if (!rt) {
        return; // ensureDaemon 已打印失败信封并置 exitCode
    }
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), 120000);
    let res;
    try {
        const r = await fetch('http://127.0.0.1:' + rt.port + '/cli/cmd', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'x-ea-token': rt.token, Connection: 'close' },
            body: JSON.stringify({ token: rt.token, type: command.type, args: command.args }),
            signal: ac.signal,
        });
        res = await r.json();
    } catch (e) {
        fail('与守护进程通信失败: ' + ((e && e.message) || e));
        return;
    } finally {
        clearTimeout(timer);
        ac.abort();
    }
    printResult(command.type, !!res.ok, res.data, res.error);
    process.exitCode = res.ok ? 0 : 1;
}

main().catch((e) => fail(((e && e.message) || String(e))));
