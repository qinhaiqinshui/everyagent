'use strict';
/**
 * YAML 输出器(纯函数):命令信封 + 平面对象行内 flow 风格(节省字符)。
 *
 * 输出形态对齐 playwright-browser skill 的约定:
 *   success: true/false
 *   message: "..."
 *   data: {k: v, ...}          ← 平面对象用行内 flow
 *   data:                       ← 含数组/嵌套时用 block 风格
 *     k: v
 *     list:
 *       - {a: 1, b: "x"}
 * aria 未超阈值时由 CLI 拼双 YAML 文档(元信息 + '---' + DSL 正文)。
 */

/** 标量 → YAML 字面量;字符串统一 JSON.stringify(双引号转义,合法 YAML)。 */
function scalar(v) {
    if (v === null || v === undefined) {
        return 'null';
    }
    if (typeof v === 'boolean' || typeof v === 'number') {
        return String(v);
    }
    return JSON.stringify(String(v));
}

/** 平面对象 → 行内 flow:{k: v, k2: v2}。 */
function flow(obj) {
    const parts = Object.entries(obj || {}).map(([k, v]) => k + ': ' + scalar(v));
    return '{' + parts.join(', ') + '}';
}

/** 判断值是否为「可直接 flow 的平面对象」(全部成员是标量)。 */
function isFlatObject(v) {
    return v !== null && typeof v === 'object' && !Array.isArray(v)
            && Object.values(v).every(x => x === null || x === undefined
                    || typeof x !== 'object');
}

/** block 风格递归渲染(返回带前导换行的行)。 */
function renderBlock(v, indent) {
    if (Array.isArray(v)) {
        return v.map(item => isFlatObject(item)
                ? '\n' + indent + '- ' + flow(item)
                : '\n' + indent + '- ' + scalar(item)).join('');
    }
    if (isFlatObject(v)) {
        return Object.entries(v).map(([k, val]) => '\n' + indent + k + ': ' + scalar(val)).join('');
    }
    return Object.entries(v || {}).map(([k, val]) => {
        if (isFlatObject(val)) {
            return '\n' + indent + k + ': ' + flow(val);
        }
        if (val !== null && typeof val === 'object') {
            return '\n' + indent + k + ':' + renderBlock(val, indent + '  ');
        }
        return '\n' + indent + k + ': ' + scalar(val);
    }).join('');
}

/**
 * 命令信封。
 * @param {{success:boolean, message:string, data?:object}} envelope
 * @returns {string} YAML 文本(以换行结尾)
 */
function envelope({ success, message, data }) {
    let out = 'success: ' + (success ? 'true' : 'false') + '\nmessage: ' + scalar(message || '');
    if (data === undefined || data === null) {
        return out + '\n';
    }
    if (isFlatObject(data)) {
        return out + '\ndata: ' + flow(data) + '\n';
    }
    return out + '\ndata:' + renderBlock(data, '  ') + '\n';
}

/**
 * 双 YAML 文档(aria 未超阈值时):第一文档元信息(含 snapshot: "@next" 指针),第二文档 DSL 原文。
 * @param {{message?:string, data:object}} meta 第一文档(data 内应含 snapshot 字段)
 * @param {string} dslText DSL 正文(原样输出)
 */
function twoDocs(meta, dslText) {
    return envelope({ success: true, message: meta.message || '', data: meta.data })
            + '---\n' + dslText + '\n';
}

module.exports = { scalar, flow, envelope, twoDocs, isFlatObject };
