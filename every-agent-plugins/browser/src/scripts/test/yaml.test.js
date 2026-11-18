'use strict';
/** YAML 输出器单测。 */
const test = require('node:test');
const assert = require('node:assert/strict');
const yaml = require('../lib/yaml');

test('平面对象 data 用行内 flow 风格', () => {
    const out = yaml.envelope({ success: true, message: 'ok', data: { tabId: 123, url: 'https://a.b' } });
    assert.ok(out.includes('success: true'));
    assert.ok(out.includes('data: {tabId: 123, url: "https://a.b"}'), out);
});

test('字符串含引号/反斜杠正确转义', () => {
    const out = yaml.envelope({ success: false, message: '未找到匹配元素: "a\'b\\c"' });
    assert.ok(out.includes('message: "未找到匹配元素: \\"a\'b\\\\c\\""'), out);
});

test('数组与嵌套对象用 block 风格,数组项 flow', () => {
    const out = yaml.envelope({
        success: true, message: 'tabs 完成',
        data: { tabs: [{ tabId: 1, url: 'u' }, { tabId: 2, url: 'v' }], sessionTabIds: [1] },
    });
    assert.ok(out.includes('data:'), out);
    assert.ok(out.includes('  tabs:'), out);
    assert.ok(out.includes('    - {tabId: 1, url: "u"}'), out);
    assert.ok(out.includes('  sessionTabIds:'), out);
    assert.ok(out.includes('    - 1'), out);
});

test('undefined/null 标量输出 null', () => {
    const out = yaml.envelope({ success: true, message: '', data: { a: null, b: undefined } });
    assert.ok(out.includes('{a: null, b: null}'), out);
});

test('twoDocs 双文档:元信息 + --- + DSL 原文', () => {
    const out = yaml.twoDocs(
        { message: 'm', data: { totalLines: 2, snapshot: '@next' } },
        '<banner>\n  <a url="/x">首页</a>');
    const docs = out.split('---\n');
    assert.strictEqual(docs.length, 2);
    assert.ok(docs[0].includes('snapshot: "@next"'));
    assert.ok(docs[1].includes('<a url="/x">首页</a>'));
    assert.ok(out.trimEnd().endsWith('</a>'));
});
