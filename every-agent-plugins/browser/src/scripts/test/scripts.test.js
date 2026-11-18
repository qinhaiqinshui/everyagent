'use strict';
/** 注入 JS 生成器单测(静态断言,不执行页面脚本)。 */
const test = require('node:test');
const assert = require('node:assert/strict');
const scripts = require('../lib/scripts');

test('clickScript:含等待/事件序列,selector spec 经 JSON 序列化嵌入', () => {
    const js = scripts.clickScript('text=登 录', 5000);
    assert.ok(js.includes('__eaWaitFor'));
    assert.ok(js.includes('"kind":"text"'));
    assert.ok(js.includes('"text":"登 录"'));
    assert.ok(js.includes('5000'));
    assert.ok(js.includes('scrollIntoView'));
    assert.ok(js.includes("new MouseEvent('click'"));
});

test('clickScript:CSS selector 透传,恶意文本不破坏脚本结构', () => {
    const evil = 'text="; throw 1; //';
    const js = scripts.clickScript(evil, 1000);
    // 恶意文本只应出现在 JSON 字符串字面量内,不允许裸拼进代码
    assert.ok(js.includes(JSON.stringify({ kind: 'text', text: '"; throw 1; //' })));
    assert.ok(!js.includes("spec.text == '\""));
});

test('fillScript:原生 value setter + input/change 事件 + contenteditable 兜底', () => {
    const js = scripts.fillScript('#q', 'a"b\\c', 3000);
    assert.ok(js.includes('getOwnPropertyDescriptor'));
    assert.ok(js.includes("new Event('input'"));
    assert.ok(js.includes("new Event('change'"));
    assert.ok(js.includes('isContentEditable'));
    assert.ok(js.includes(JSON.stringify('a"b\\c')));
});

test('queryScript:字段袋 + 截断 200 字 + limit 嵌入', () => {
    const js = scripts.queryScript('.item', 5, false);
    assert.ok(js.includes('limit = 5'));
    assert.ok(js.includes('full = false'));
    assert.ok(js.includes('slice(0, 200)'));
    assert.ok(js.includes('aria-label'));
    assert.ok(js.includes('truncations'));
    const jsFull = scripts.queryScript('.item', 20, true);
    assert.ok(jsFull.includes('full = true'));
});

test('ariaScript:role 映射/链接去重统计/缩进/可见性', () => {
    const js = scripts.ariaScript();
    assert.ok(js.includes("A: 'link'"));
    assert.ok(js.includes("'navigation'"));
    assert.ok(js.includes('heading'));
    assert.ok(js.includes('totalLinks'));
    assert.ok(js.includes('keptLinks'));
    assert.ok(js.includes('getClientRects'));
    assert.ok(js.includes('linksByUrl'));
});

test('historyScript:back/forward', () => {
    assert.ok(scripts.historyScript('back').includes('history.back()'));
    assert.ok(scripts.historyScript('forward').includes('history.forward()'));
});
