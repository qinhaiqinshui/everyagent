'use strict';
/** 命令编译/执行单测:fake sendOp 记录 op 序列,验证会话记账与 close 安全边界。 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createCommands, ARIA_MAX_LINES } = require('../lib/commands');

/** 造一个 fake sendOp:记录调用并按 type 返回预设结果。 */
function fakeSend(log, canned) {
    return async (type, args) => {
        log.push({ type, args });
        const fn = canned[type];
        return fn ? fn(args) : {};
    };
}

function makeCommands(canned) {
    const log = [];
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ea-cmd-'));
    const idle = [];
    const commands = createCommands({
        sendOp: fakeSend(log, canned),
        tmpDir,
        onIdleOverride: (ms) => idle.push(ms),
    });
    return { commands, log, tmpDir, idle };
}

test('open:tab.create → tab.waitReady 序列 + 会话记账 + timeout 覆盖', async () => {
    const { commands, log, idle } = makeCommands({
        'tab.create': () => ({ tabId: 42 }),
    });
    const data = await commands.execute('open', { url: 'https://x', timeoutSec: 600 });
    assert.deepStrictEqual(data, { tabId: 42 });
    assert.deepStrictEqual(log.map(o => o.type), ['tab.create', 'tab.waitReady']);
    assert.deepStrictEqual([...commands.sessionTabs], [42]);
    assert.deepStrictEqual(idle, [600000]);
});

test('close --tab:仅允许会话内 tabId(安全边界)', async () => {
    const { commands } = makeCommands({
        'tab.create': () => ({ tabId: 7 }),
    });
    await assert.rejects(
        () => commands.execute('close', { tabId: 999 }),
        /安全边界/,
    );
    await commands.execute('open', { url: 'https://x' });
    const data = await commands.execute('close', { tabId: 7 });
    assert.strictEqual(data.closed, 7);
    assert.strictEqual(commands.sessionTabs.size, 0);
});

test('close(无 --tab):关全部会话标签 + shutdown 信号', async () => {
    let seq = 0;
    const { commands } = makeCommands({
        'tab.create': () => ({ tabId: ++seq }),
    });
    await commands.execute('open', { url: 'https://a' });
    await commands.execute('open', { url: 'https://b' });
    const data = await commands.execute('close', {});
    assert.strictEqual(data.shutdown, true);
    assert.strictEqual(data.closedTabs, 2);
});

test('缺 --tab / 缺 url / 未知命令给可读中文错误', async () => {
    const { commands } = makeCommands({});
    await assert.rejects(() => commands.execute('goto', { url: 'https://x' }), /--tab/);
    await assert.rejects(() => commands.execute('open', {}), /url/);
    await assert.rejects(() => commands.execute('nope', {}), /未知命令/);
});

test('exec 结果 data.ok=false 转命令失败(click 无匹配)', async () => {
    const { commands } = makeCommands({
        exec: () => ({ value: JSON.stringify({ ok: false, error: '未找到匹配元素: #x' }) }),
    });
    await assert.rejects(
        () => commands.execute('click', { selector: '#x', tabId: 1 }),
        /未找到匹配元素: #x/,
    );
});

test('query:元素/truncations 透传', async () => {
    const { commands } = makeCommands({
        exec: () => ({ value: JSON.stringify({ ok: true,
            elements: [{ tag: 'a', text: 't' }], total: 1, truncations: [{ index: 0, textLength: 999 }] }) }),
    });
    const data = await commands.execute('query', { selector: 'a', tabId: 1 });
    assert.strictEqual(data.elements.length, 1);
    assert.strictEqual(data.truncations[0].textLength, 999);
});

test('aria 超阈值:只返回统计 + fullFile 落 tmp 目录', async () => {
    const lines = Array.from({ length: ARIA_MAX_LINES + 1 }, (_, i) => '<a url="/x' + i + '">链接' + i + '</a>');
    const { commands, tmpDir } = makeCommands({
        exec: () => ({ value: JSON.stringify({ ok: true, lines,
            filter: { totalLinks: 500, keptLinks: 301 } }) }),
    });
    const data = await commands.execute('aria', { tabId: 1 });
    assert.strictEqual(data.totalLines, ARIA_MAX_LINES + 1);
    assert.ok(data.fullFile.startsWith(tmpDir), data.fullFile);
    assert.ok(fs.existsSync(data.fullFile));
    assert.strictEqual(data.dsl, undefined);
});

test('aria --save:直写指定路径,只返回统计', async () => {
    const { commands } = makeCommands({
        exec: () => ({ value: JSON.stringify({ ok: true, lines: ['<banner>', '  <a url="/">首页</a>'],
            filter: { totalLinks: 1, keptLinks: 1 } }) }),
    });
    const save = path.join(os.tmpdir(), 'ea-aria-save-' + Date.now() + '.txt');
    const data = await commands.execute('aria', { tabId: 1, save });
    assert.strictEqual(data.savedTo, save);
    assert.ok(fs.readFileSync(save, 'utf8').includes('<banner>'));
    assert.strictEqual(data.dsl, undefined);
});

test('tabs:全部标签 + sessionTabIds 标注', async () => {
    const { commands } = makeCommands({
        'tab.create': () => ({ tabId: 5 }),
        'tabs.list': () => ({ tabs: [
            { tabId: 1, url: 'about:blank', title: '用户自己的', active: true },
            { tabId: 5, url: 'https://x', title: 'X', active: false },
        ] }),
    });
    await commands.execute('open', { url: 'https://x' });
    const data = await commands.execute('tabs', {});
    assert.strictEqual(data.tabs.length, 2);
    assert.deepStrictEqual(data.sessionTabIds, [5]);
});

test('screenshot:dataUrl 解码写文件', async () => {
    const { commands } = makeCommands({
        screenshot: () => ({ dataUrl: 'data:image/png;base64,AAAA' }),
    });
    const file = path.join(os.tmpdir(), 'ea-shot-' + Date.now() + '.png');
    const data = await commands.execute('screenshot', { tabId: 1, savePath: file });
    assert.strictEqual(data.bytes, 3);
    assert.ok(fs.existsSync(file));
});
