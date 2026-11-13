// options 页逻辑：从 chrome.storage.local 加载/保存配置，并支持调用 /ext/hello 测试连接
'use strict';

const $ = (id) => document.getElementById(id);
const HELLO_PATH = '/ext/hello';

// 归一化服务地址：去首尾空白与结尾斜杠
function normalizeUrl(raw) {
  return String(raw || '').trim().replace(/\/+$/, '');
}

// 状态条：true=成功（绿）/ false=失败（红）/ null=进行中（灰）
function setStatus(kind, text) {
  const el = $('status');
  el.className = kind === true ? 'ok' : kind === false ? 'err' : 'pending';
  el.textContent = text;
}

// 初始化：把已保存的配置回填输入框
async function loadConfig() {
  const { serverUrl, token } = await chrome.storage.local.get(['serverUrl', 'token']);
  if (serverUrl) $('serverUrl').value = serverUrl;
  if (token) $('token').value = token;
}

// 保存配置（chrome.storage.local，键 serverUrl/token）
async function saveConfig() {
  const serverUrl = normalizeUrl($('serverUrl').value);
  const token = $('token').value.trim();
  if (!serverUrl || !token) {
    setStatus(false, '服务地址与 token 均不能为空。');
    return;
  }
  await chrome.storage.local.set({ serverUrl, token });
  setStatus(true, `已保存。扩展将自动连接 ${serverUrl} 并开始轮询命令。`);
}

// 测试连接：按输入框当前内容调 /ext/hello（无需先保存）
async function testConnection() {
  const serverUrl = normalizeUrl($('serverUrl').value);
  const token = $('token').value.trim();
  if (!serverUrl || !token) {
    setStatus(false, '请先填写服务地址与 token。');
    return;
  }
  setStatus(null, '测试中…');
  try {
    const resp = await fetch(serverUrl + HELLO_PATH, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-ea-token': token },
      body: JSON.stringify({
        token,
        extVersion: chrome.runtime.getManifest().version,
        browser: navigator.userAgent,
      }),
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    if (!data || data.ok !== true) throw new Error('服务端返回 ok:false（token 可能不正确）');
    setStatus(true, `连接成功，服务器时间：${data.serverTime ?? new Date().toLocaleString()}`);
  } catch (e) {
    setStatus(false, `连接失败：${e && e.message ? e.message : String(e)}`);
  }
}

$('save').addEventListener('click', saveConfig);
$('test').addEventListener('click', testConnection);
loadConfig();
