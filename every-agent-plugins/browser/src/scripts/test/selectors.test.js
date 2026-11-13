'use strict';
/** selector 解析单测。 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseSelector } = require('../lib/selectors');

test('CSS 选择器原样透传', () => {
    assert.deepStrictEqual(parseSelector('#login-btn'), { kind: 'css', css: '#login-btn' });
    assert.deepStrictEqual(parseSelector(' .submit '), { kind: 'css', css: '.submit' });
    assert.deepStrictEqual(parseSelector('div.a > b[x="1"]'), { kind: 'css', css: 'div.a > b[x="1"]' });
});

test('text= 前缀(大小写不敏感)解析为文本匹配', () => {
    assert.deepStrictEqual(parseSelector('text=登录'), { kind: 'text', text: '登录' });
    assert.deepStrictEqual(parseSelector('TEXT=登录'), { kind: 'text', text: '登录' });
    assert.deepStrictEqual(parseSelector('text=hello world'), { kind: 'text', text: 'hello world' });
});

test('空 selector / 空 text= 抛错', () => {
    assert.throws(() => parseSelector(''));
    assert.throws(() => parseSelector('   '));
    assert.throws(() => parseSelector('text='));
    assert.throws(() => parseSelector(undefined));
});
