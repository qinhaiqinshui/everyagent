'use strict';
/**
 * selector 解析(纯函数):支持 CSS 选择器与 text= 可见文本包含匹配。
 *
 * text=xxx 的语义对齐 playwright:匹配「最深包含该文本」的元素
 * (子元素也完整包含该文本时,当前元素不算终点)。
 * 匹配逻辑在注入 JS 里用 textContent 比较实现,不做 eval 拼 DOM。
 */

/**
 * 解析 selector 字符串。
 * @param {string} raw 原始 selector
 * @returns {{kind:'css', css:string} | {kind:'text', text:string}}
 * @throws {Error} 空串/空文本
 */
function parseSelector(raw) {
    if (typeof raw !== 'string' || !raw.trim()) {
        throw new Error('selector 不能为空');
    }
    const s = raw.trim();
    if (s.toLowerCase().startsWith('text=')) {
        const text = s.slice(5);
        if (!text.trim()) {
            throw new Error('text= 选择器的文本不能为空');
        }
        return { kind: 'text', text };
    }
    return { kind: 'css', css: s };
}

module.exports = { parseSelector };
