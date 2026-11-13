'use strict';
/**
 * 注入页面 JS 生成器(纯函数)。
 *
 * 每段脚本自包含(共享 MATCH_PRELUDE),经扩展 exec op 在页面 ISOLATED world 执行;
 * 返回值经 JSON 序列化回传({ok:true,...} 或 {ok:false,error})。
 * 安全红线:text= 匹配用 textContent 比较;用户文本一律 JSON.stringify 嵌入,
 * 不做字符串拼接 eval。
 */
const { parseSelector } = require('./selectors');

/** 匹配/收集/等待 prelude(嵌入每段需要选择器的脚本)。 */
const MATCH_PRELUDE = `
function __eaMatches(el, spec) {
  if (spec.kind === 'css') { try { return el.matches(spec.css); } catch (e) { return false; } }
  var t = (el.textContent || '').trim();
  if (!t || t.indexOf(spec.text) === -1) { return false; }
  for (var i = 0; i < el.children.length; i++) {
    var c = (el.children[i].textContent || '').trim();
    if (c && c.indexOf(spec.text) !== -1) { return false; }
  }
  return true;
}
function __eaCollect(spec) {
  var out = [];
  var all = document.querySelectorAll('*');
  for (var i = 0; i < all.length; i++) { if (__eaMatches(all[i], spec)) { out.push(all[i]); } }
  return out;
}
function __eaWaitFor(spec, timeoutMs) {
  var deadline = Date.now() + (timeoutMs || 10000);
  return new Promise(function (resolve) {
    (function tick() {
      var els = __eaCollect(spec);
      if (els.length) { return resolve(els); }
      if (Date.now() >= deadline) { return resolve([]); }
      setTimeout(tick, 200);
    })();
  });
}
`;

/** back/forward 导航脚本。 */
function historyScript(direction) {
    return 'history.' + (direction === 'forward' ? 'forward' : 'back') + '();\nreturn { ok: true };\n';
}

/** 点击脚本:等待元素(默认 10s)→ scrollIntoView → 真实鼠标事件序列。 */
function clickScript(selector, timeoutMs) {
    const spec = parseSelector(selector);
    const label = JSON.stringify(String(selector));
    return MATCH_PRELUDE + `
return __eaWaitFor(${JSON.stringify(spec)}, ${Number(timeoutMs) || 10000}).then(function (els) {
  if (!els.length) { return { ok: false, error: '未找到匹配元素: ' + ${label} }; }
  var el = els[0];
  el.scrollIntoView({ block: 'center', inline: 'nearest' });
  var r = el.getBoundingClientRect();
  var opts = { bubbles: true, cancelable: true, view: window,
    clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
  el.dispatchEvent(new MouseEvent('mouseover', opts));
  el.dispatchEvent(new MouseEvent('mousedown', opts));
  el.dispatchEvent(new MouseEvent('mouseup', opts));
  if (typeof el.click === 'function') { el.click(); }
  else { el.dispatchEvent(new MouseEvent('click', opts)); }
  return { ok: true, clicked: true };
});
`;
}

/** 填写脚本:等待元素(默认 10s)→ 原生 value setter + input/change 事件;contenteditable 兜底。 */
function fillScript(selector, text, timeoutMs) {
    const spec = parseSelector(selector);
    const label = JSON.stringify(String(selector));
    const value = JSON.stringify(String(text == null ? '' : text));
    return MATCH_PRELUDE + `
return __eaWaitFor(${JSON.stringify(spec)}, ${Number(timeoutMs) || 10000}).then(function (els) {
  if (!els.length) { return { ok: false, error: '未找到匹配元素: ' + ${label} }; }
  var el = els[0];
  el.scrollIntoView({ block: 'center', inline: 'nearest' });
  if (el.isContentEditable) {
    el.textContent = ${value};
    el.dispatchEvent(new InputEvent('input', { bubbles: true }));
    return { ok: true, filled: true };
  }
  var tag = el.tagName;
  var proto = tag === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype
            : tag === 'INPUT' ? window.HTMLInputElement.prototype : null;
  if (!proto) { return { ok: false, error: '元素不是输入框(textarea/input/contenteditable): ' + ${label} }; }
  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
  if (desc && desc.set) { desc.set.call(el, ${value}); } else { el.value = ${value}; }
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return { ok: true, filled: true };
});
`;
}

/** 收集脚本:匹配元素的字段袋(仅有值的字段);text 默认截断 200 字,--full 完整。 */
function queryScript(selector, limit, full) {
    const spec = parseSelector(selector);
    return MATCH_PRELUDE + `
var els = __eaCollect(${JSON.stringify(spec)});
var limit = ${Number(limit) || 20};
var full = ${full ? 'true' : 'false'};
var out = [];
var truncations = [];
for (var i = 0; i < els.length && out.length < limit; i++) {
  var el = els[i];
  var text = (el.textContent || '').trim().replace(/\\s+/g, ' ');
  var item = { tag: el.tagName.toLowerCase() };
  if (!full && text.length > 200) { truncations.push({ index: out.length, textLength: text.length }); text = text.slice(0, 200); }
  if (text) { item.text = text; }
  item.textLength = (el.textContent || '').trim().length;
  var attrs = { href: 'href', value: 'value', name: 'name', id: 'id', type: 'type',
    placeholder: 'placeholder', role: 'role', ariaLabel: 'aria-label' };
  for (var k in attrs) {
    var v = el.getAttribute(attrs[k]);
    if (v != null && v !== '') { item[k] = v; }
  }
  out.push(item);
}
return { ok: true, elements: out, total: els.length, truncations: truncations };
`;
}

/**
 * aria 脚本:遍历 DOM 生成 HTML DSL(语义 role→标签映射、缩进层级、同 URL 链接去重)。
 * 返回 {ok:true, lines:string[], filter:{totalLinks, keptLinks}}。
 * 输出形态对齐 playwright skill 约定:如 <a url="...">新闻</a>、<input>搜索词</input>。
 */
function ariaScript() {
    return `
var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, META: 1, LINK: 1, HEAD: 1, TITLE: 1 };
var IMPLICIT = { A: 'link', BUTTON: 'button', TEXTAREA: 'textbox',
  NAV: 'navigation', MAIN: 'main', HEADER: 'banner', FOOTER: 'contentinfo',
  ASIDE: 'complementary', SECTION: 'region', UL: 'list', OL: 'list', LI: 'listitem', IMG: 'img' };
function roleOf(el) {
  var explicit = el.getAttribute('role');
  if (explicit) { return explicit; }
  var tag = el.tagName;
  if (tag === 'INPUT') {
    var t = (el.getAttribute('type') || 'text').toLowerCase();
    if (t === 'button' || t === 'submit' || t === 'reset') { return 'button'; }
    if (t === 'checkbox') { return 'checkbox'; }
    if (t === 'radio') { return 'radio'; }
    return 'textbox';
  }
  if (/^H[1-6]$/.test(tag)) { return 'heading'; }
  return IMPLICIT[tag] || null;
}
function tagOf(el, role) {
  if (role === 'link') { return 'a'; }
  if (role === 'textbox') { return 'input'; }
  if (role === 'button') { return 'button'; }
  if (role === 'heading') { return 'h' + el.tagName.charAt(1); }
  if (role === 'combobox') { return 'combobox'; }
  return role;
}
function visible(el) {
  if (!el.getClientRects().length) { return false; }
  var st = window.getComputedStyle(el);
  return st.visibility !== 'hidden' && st.display !== 'none';
}
function ownText(el) {
  var s = '';
  for (var i = 0; i < el.childNodes.length; i++) {
    var n = el.childNodes[i];
    if (n.nodeType === 3) { s += n.nodeValue; }
  }
  return s.trim().replace(/\\s+/g, ' ');
}
function nameOf(el) {
  return el.getAttribute('aria-label') || el.getAttribute('title') || ownText(el)
    || (el.tagName === 'IMG' ? el.getAttribute('alt') || '' : '');
}
function clip(s, n) { s = s || ''; return s.length > n ? s.slice(0, n) : s; }
var lines = [];
var linksByUrl = {};
var linkCount = { total: 0, kept: 0 };
function walk(el, depth) {
  if (el.nodeType !== 1) { return; }
  if (SKIP[el.tagName]) { return; }
  var kids = el.children;
  for (var i = 0; i < kids.length; i++) { walk(kids[i], depth + 1); }
  if (!visible(el)) { return; }
  var role = roleOf(el);
  if (!role) { return; }
  var tag = tagOf(el, role);
  var indent = new Array(depth + 1).join('  ');
  if (role === 'link') {
    var href = el.getAttribute('href') || '';
    linkCount.total++;
    var name = clip(nameOf(el), 80);
    if (href) {
      var prev = linksByUrl[href];
      if (prev !== undefined && prev.length >= name.length) { return; }
      if (prev !== undefined) {
        lines[prev.idx] = null;
      } else { linkCount.kept++; }
      linksByUrl[href] = { length: name.length, idx: lines.length };
    } else { linkCount.kept++; }
    lines.push(indent + '<a' + (href ? ' url="' + clip(href, 120).replace(/"/g, '&quot;') + '"' : '') + '>'
      + (name || '(未命名)') + '</a>');
    return;
  }
  if (role === 'textbox' || role === 'combobox' || role === 'checkbox' || role === 'radio') {
    var ph = el.getAttribute('placeholder') || '';
    lines.push(indent + '<' + tag + '>' + clip(ph || nameOf(el), 80) + '</' + tag + '>');
    return;
  }
  if (role === 'heading') {
    lines.push(indent + '<' + tag + '>' + clip(nameOf(el), 80) + '</' + tag + '>');
    return;
  }
  if (role === 'img') {
    var alt = el.getAttribute('alt') || '';
    lines.push(indent + '<img' + (alt ? ' alt="' + clip(alt, 80).replace(/"/g, '&quot;') + '"' : '') + '>');
    return;
  }
  if (role === 'button') {
    lines.push(indent + '<button>' + clip(nameOf(el), 80) + '</button>');
    return;
  }
  var t = clip(ownText(el), 80);
  lines.push(t ? indent + '<' + tag + '>' + t + '</' + tag + '>' : indent + '<' + tag + '>');
}
walk(document.body, 0);
lines = lines.filter(function (l) { return l !== null; });
return { ok: true, lines: lines, filter: { totalLinks: linkCount.total, keptLinks: linkCount.kept } };
`;
}

module.exports = { MATCH_PRELUDE, historyScript, clickScript, fillScript, queryScript, ariaScript };
