'use strict';
/**
 * 11 个命令的编译与执行:命令 → op 序列(经 sendOp 下发扩展),会话 tabId 记账,
 * close 安全边界(只能关本会话 open 打开的标签)。
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const scripts = require('./scripts');

/** aria 超阈值:行数/字符数(对齐 playwright skill 约定)。 */
const ARIA_MAX_LINES = 300;
const ARIA_MAX_CHARS = 15000;

/**
 * @param {object} opts
 * @param {(type:string, args:object, timeoutMs?:number)=>Promise<object>} opts.sendOp
 * @param {string} opts.tmpDir aria 超阈值 fullFile 落盘目录
 * @param {(ms:number)=>void} [opts.onIdleOverride] open --timeout 覆盖守护闲置时长
 */
function createCommands({ sendOp, tmpDir, onIdleOverride }) {

    /** 本会话经 open 打开的 tabId 集合(close 安全边界的记账)。 */
    const sessionTabs = new Set();

    /** 页面脚本执行(exec op + data.ok 检查 + value JSON 解析)。 */
    async function exec(tabId, js, awaitPromise) {
        const res = await sendOp('exec', { tabId, js, awaitPromise: !!awaitPromise, world: 'ISOLATED' });
        let value = null;
        if (res && res.value != null) {
            try {
                value = JSON.parse(res.value);
            } catch {
                value = null;
            }
        }
        if (value && value.ok === false) {
            throw new Error(value.error || '页面脚本执行失败');
        }
        return value;
    }

    async function waitReady(tabId, timeoutMs) {
        await sendOp('tab.waitReady', { tabId, timeoutMs });
    }

    function requireTabId(args) {
        if (!args || !args.tabId) {
            throw new Error('缺少 --tab <tabId> 参数(除 open/tabs/close 外的命令都必须指定标签页)');
        }
        return args.tabId;
    }

    /** open:开标签 → 等就绪 → 记账。 */
    async function open(args) {
        if (!args || !args.url) { throw new Error('缺少 url 参数:open <url>'); }
        const created = await sendOp('tab.create', { url: args.url, active: true });
        const tabId = created && created.tabId;
        if (tabId == null) { throw new Error('扩展未返回 tabId'); }
        await waitReady(tabId, 30000);
        sessionTabs.add(tabId);
        if (args.timeoutSec && onIdleOverride) {
            onIdleOverride(Number(args.timeoutSec) * 1000);
        }
        return { tabId };
    }

    /** goto:当前标签导航。 */
    async function goto(args) {
        const tabId = requireTabId(args);
        if (!args.url) { throw new Error('缺少 url 参数:goto <url> --tab <tabId>'); }
        await sendOp('tab.update', { tabId, url: args.url, active: true });
        await waitReady(tabId, 30000);
        return { tabId, url: args.url };
    }

    /** back/forward。 */
    async function history(args, direction) {
        const tabId = requireTabId(args);
        await exec(tabId, scripts.historyScript(direction), false);
        await waitReady(tabId, 10000);
        return { tabId };
    }

    /** click。 */
    async function click(args) {
        const tabId = requireTabId(args);
        if (!args.selector) { throw new Error('缺少 selector 参数:click <selector> --tab <tabId>'); }
        const v = await exec(tabId, scripts.clickScript(args.selector, 10000), true);
        return { clicked: true, tabId };
    }

    /** fill。 */
    async function fill(args) {
        const tabId = requireTabId(args);
        if (!args.selector) { throw new Error('缺少 selector 参数:fill <selector> <text> --tab <tabId>'); }
        const v = await exec(tabId, scripts.fillScript(args.selector, args.text || '', 10000), true);
        return { filled: true, tabId };
    }

    /** query:收集元素字段袋。 */
    async function query(args) {
        const tabId = requireTabId(args);
        if (!args.selector) { throw new Error('缺少 selector 参数:query <selector> --tab <tabId>'); }
        const v = await exec(tabId,
                scripts.queryScript(args.selector, args.limit || 20, !!args.full), false);
        return { elements: (v && v.elements) || [], total: (v && v.total) || 0,
            truncations: (v && v.truncations) || [] };
    }

    /** aria:DSL 生成 + 阈值分流(超阈值/ --save 落文件只返回统计)。 */
    async function aria(args) {
        const tabId = requireTabId(args);
        const v = await exec(tabId, scripts.ariaScript(), false);
        const lines = (v && v.lines) || [];
        const filter = (v && v.filter) || { totalLinks: 0, keptLinks: 0 };
        const text = lines.join('\n');
        const totalChars = text.length;
        const base = { totalLines: lines.length, totalChars, filter };
        if (args.save) {
            const file = path.resolve(String(args.save));
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, text + '\n');
            return Object.assign(base, { savedTo: file });
        }
        if (lines.length > ARIA_MAX_LINES || totalChars > ARIA_MAX_CHARS) {
            const file = path.join(tmpDir, 'aria-' + crypto.randomBytes(4).toString('hex') + '.txt');
            fs.writeFileSync(file, text + '\n');
            return Object.assign(base, { fullFile: file });
        }
        return Object.assign(base, { dsl: text });
    }

    /** screenshot:扩展截图 → 解码写文件。 */
    async function screenshot(args) {
        const tabId = requireTabId(args);
        if (!args.savePath) { throw new Error('缺少 path 参数:screenshot <path> --tab <tabId>'); }
        const res = await sendOp('screenshot', { tabId }, 90000);
        const dataUrl = res && res.dataUrl;
        if (!dataUrl || !dataUrl.startsWith('data:')) { throw new Error('扩展未返回截图数据'); }
        const b64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
        const buf = Buffer.from(b64, 'base64');
        const file = path.resolve(String(args.savePath));
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, buf);
        return { path: file, bytes: buf.length };
    }

    /** tabs:列出全部标签(标注本会话开的)。 */
    async function tabs() {
        const res = await sendOp('tabs.list', {});
        return { tabs: (res && res.tabs) || [], sessionTabIds: [...sessionTabs] };
    }

    /** close --tab(仅会话内) / close(全部会话标签 + 守护退出)。 */
    async function close(args) {
        const tabId = args && args.tabId;
        if (tabId != null && tabId !== '') {
            if (!sessionTabs.has(tabId)) {
                throw new Error('安全边界:只能关闭本会话通过 open 打开的标签页(收到 tabId '
                        + tabId + ' 不在会话内;用户自己的标签页不会被触碰)');
            }
            await sendOp('tab.close', { tabId });
            sessionTabs.delete(tabId);
            return { closed: tabId, remaining: sessionTabs.size };
        }
        let closed = 0;
        for (const t of [...sessionTabs]) {
            try {
                await sendOp('tab.close', { tabId: t });
                closed++;
            } catch {
                /* 单个关闭失败不阻断整体收尾 */
            }
            sessionTabs.delete(t);
        }
        return { shutdown: true, closedTabs: closed };
    }

    /** 命令分发入口(守护进程 /cli/cmd 调用)。 */
    async function execute(type, args) {
        switch (type) {
            case 'open': return open(args);
            case 'goto': return goto(args);
            case 'back': return history(args, 'back');
            case 'forward': return history(args, 'forward');
            case 'click': return click(args);
            case 'fill': return fill(args);
            case 'query': return query(args);
            case 'aria': return aria(args);
            case 'screenshot': return screenshot(args);
            case 'tabs': return tabs();
            case 'close': return close(args);
            default:
                throw new Error('未知命令: ' + type + '(支持: open/goto/back/forward/click/fill/query/aria/screenshot/tabs/close)');
        }
    }

    return {
        execute,
        sessionTabs,
        /** 守护退出前 best-effort 关闭会话标签(不等待结果)。 */
        closeAllSessionTabsBestEffort() {
            for (const t of [...sessionTabs]) {
                sendOp('tab.close', { tabId: t }, 3000).catch(() => {});
                sessionTabs.delete(t);
            }
        },
    };
}

module.exports = { createCommands, ARIA_MAX_LINES, ARIA_MAX_CHARS };
