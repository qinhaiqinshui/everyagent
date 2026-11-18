// Every Agent 浏览器控制扩展 —— MV3 Service Worker（纯通用命令执行器）
// 职责：读取 options 页配置的守护进程地址与 token，注册连接（/ext/hello），
//       长轮询（/ext/poll）拉取命令，串行执行七种 op 并逐个回传结果（/ext/result）。
// 本文件零业务逻辑：选择器解析 / aria 生成 / YAML 解析等一律由服务端经 exec op 的 js 字符串下发。

/* ==================== 常量区 ==================== */

const HELLO_PATH = '/ext/hello';    // 连接注册
const POLL_PATH = '/ext/poll';      // 长轮询取命令（token 走 query）
const RESULT_PATH = '/ext/result';  // 命令结果回传（token 走 body）

const FETCH_TIMEOUT_MS = 30_000;               // fetch 统一超时（须大于服务端挂起的 25s）
const BACKOFF_MS = [1_000, 2_000, 5_000, 10_000]; // 退避表：失败次数 1/2/3/4+ 对应 1s/2s/5s/10s 封顶
const NO_CONFIG_RETRY_MS = 5_000;              // 尚未配置服务地址/token 时的定期重试间隔
const WAIT_READY_INTERVAL_MS = 200;            // tab.waitReady 轮询间隔
const WAIT_READY_SETTLE_MS = 300;              // readyState=complete 之后的稳定等待
const SCREENSHOT_ACTIVATE_MS = 150;            // 截图前激活标签页后的等待

const OP_TYPES = ['tabs.list', 'tab.create', 'tab.update', 'tab.close', 'tab.waitReady', 'exec', 'screenshot'];

/* ==================== 纯函数区（无副作用） ==================== */

// 退避时长：失败 1 次等 1s，2 次等 2s，3 次等 5s，4 次及以上封顶 10s
function backoffFor(failureCount) {
  const idx = Math.min(Math.max(failureCount, 1), BACKOFF_MS.length) - 1;
  return BACKOFF_MS[idx];
}

// tabId 合法性：非空，数字或数字字符串，且为非负整数
function hasTabId(v) {
  if (v === undefined || v === null) return false;
  const n = Number(v);
  return Number.isInteger(n) && n >= 0;
}

// op 参数校验：合法返回 null，非法返回可读错误字符串
function validateOp(type, args) {
  if (typeof type !== 'string' || !OP_TYPES.includes(type)) return `未知 op 类型：${type}`;
  const a = args && typeof args === 'object' && !Array.isArray(args) ? args : null;
  if (!a) return 'args 必须是对象';
  const needTabId = () => (hasTabId(a.tabId) ? null : `${type} 缺少合法 tabId`);
  switch (type) {
    case 'tabs.list':
      return null;
    case 'tab.create':
      return typeof a.url === 'string' && a.url ? null : 'tab.create 缺少 url（非空字符串）';
    case 'tab.update':
      return needTabId();
    case 'tab.close':
      return needTabId();
    case 'tab.waitReady':
      return needTabId()
        ?? (Number.isFinite(Number(a.timeoutMs)) && Number(a.timeoutMs) > 0 ? null : 'tab.waitReady 缺少合法 timeoutMs（正数）');
    case 'exec': {
      const e1 = needTabId();
      if (e1) return e1;
      if (typeof a.js !== 'string' || !a.js) return 'exec 缺少 js（非空字符串）';
      if (a.world !== undefined && a.world !== 'MAIN' && a.world !== 'ISOLATED') return 'exec.world 仅允许 MAIN 或 ISOLATED';
      if (a.awaitPromise !== undefined && typeof a.awaitPromise !== 'boolean') return 'exec.awaitPromise 必须是布尔值';
      return null;
    }
    case 'screenshot':
      return needTabId();
    default:
      return `未知 op 类型：${type}`;
  }
}

// 任意异常转可读字符串（用于协议 error 字段）
function toErrMsg(e) {
  if (e instanceof Error) return e.message || e.name;
  return String(e);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const toTabId = (v) => Number(v);

/* ==================== op 执行器区 ==================== */

const EXECUTORS = {
  // 1. tabs.list：全部窗口的全部标签
  async 'tabs.list'() {
    const tabs = await chrome.tabs.query({});
    return {
      tabs: tabs.map((t) => ({ tabId: t.id, url: t.url ?? '', title: t.title ?? '', active: !!t.active })),
    };
  },

  // 2. tab.create：新建标签页
  async 'tab.create'(args) {
    const tab = await chrome.tabs.create({ url: args.url, active: args.active !== false });
    return { tabId: tab.id };
  },

  // 3. tab.update：更新 url / active（只提交出现的字段）
  async 'tab.update'(args) {
    const props = {};
    if (args.url !== undefined) props.url = args.url;
    if (args.active !== undefined) props.active = !!args.active;
    await chrome.tabs.update(toTabId(args.tabId), props);
    return {};
  },

  // 4. tab.close：关闭标签页；标签已不存在等失败会抛错 → ok:false + error 描述
  async 'tab.close'(args) {
    await chrome.tabs.remove(toTabId(args.tabId));
    return {};
  },

  // 5. tab.waitReady：每 200ms 轮询 readyState，complete 后再稳定 300ms；超时抛错 → ok:false
  async 'tab.waitReady'(args) {
    const tabId = toTabId(args.tabId);
    const deadline = Date.now() + Number(args.timeoutMs);
    for (;;) {
      const state = await readReadyState(tabId);
      if (state === 'complete') {
        await sleep(WAIT_READY_SETTLE_MS);
        return {};
      }
      if (Date.now() >= deadline) {
        throw new Error(`等待页面就绪超时（${args.timeoutMs}ms，最后状态：${state ?? '未知'}）`);
      }
      await sleep(WAIT_READY_INTERVAL_MS);
    }
  },

  // 6. exec：注入自包含 wrapper，eval 服务端下发的 js；注入结果即 data
  async exec(args) {
    const world = args.world === 'MAIN' ? 'MAIN' : 'ISOLATED'; // 缺省 ISOLATED
    const [injection] = await chrome.scripting.executeScript({
      target: { tabId: toTabId(args.tabId) },
      world,
      func: execWrapper,
      args: [String(args.js), args.awaitPromise === true],
    });
    // data 即 wrapper 返回值：正常 {value: JSON字符串|null}，脚本抛异常 {ok:false,error}
    return injection && injection.result !== undefined ? injection.result : { value: null };
  },

  // 7. screenshot：激活目标标签后截取可见视口（captureVisibleTab 固有限制：仅视口）
  async screenshot(args) {
    const tabId = toTabId(args.tabId);
    const tab = await chrome.tabs.get(tabId); // 拿 windowId
    await chrome.tabs.update(tabId, { active: true });
    await sleep(SCREENSHOT_ACTIVATE_MS);
    const dataUrl = await chrome.tabs.captureVisibleTab(tab.windowId, { format: 'png' });
    return { dataUrl };
  },
};

// readyState 读取：经 chrome.scripting.executeScript 注入 () => document.readyState
async function readReadyState(tabId) {
  const [res] = await chrome.scripting.executeScript({
    target: { tabId },
    func: () => document.readyState,
  });
  return res ? res.result : undefined;
}

// exec 的自包含 wrapper：会被序列化注入页面，不得引用任何外部变量。
// 参数为原始字符串 [js, awaitPromise]；eval(js)，必要时等待 Promise；
// 结果尝试 JSON.stringify（undefined → null）；异常捕获为 {ok:false,error:String(e)}。
async function execWrapper(js, awaitPromise) {
  try {
    let value = eval(js);
    if (awaitPromise && value && typeof value.then === 'function') value = await value;
    let json;
    try {
      json = JSON.stringify(value === undefined ? null : value);
    } catch (_) {
      json = JSON.stringify(String(value)); // 循环引用等无法序列化时降级为字符串
    }
    return { value: json === undefined ? null : json };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

/* ==================== 通信区 ==================== */

// 读取并归一化配置（chrome.storage.local 的 serverUrl/token）；未配置返回 null
async function readConfig() {
  const { serverUrl, token } = await chrome.storage.local.get(['serverUrl', 'token']);
  if (!serverUrl || !token) return null;
  return { serverUrl: String(serverUrl).trim().replace(/\/+$/, ''), token: String(token).trim() };
}

// 统一 POST：token 放 body（与 daemon 约定），另在请求头带 x-ea-token 双保险；非 200 抛错
async function postJson(url, token, body) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort('fetch 超时'), FETCH_TIMEOUT_MS);
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-ea-token': token },
      body: JSON.stringify({ token, ...body }),
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return resp.json();
  } finally {
    clearTimeout(timer);
  }
}

// 长轮询：token 走 query（与 daemon 约定），超时 30s > 服务端挂起 25s；返回 cmds（可为空数组）
async function pollCmds(cfg) {
  const url = `${cfg.serverUrl}${POLL_PATH}?token=${encodeURIComponent(cfg.token)}`;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort('poll 超时'), FETCH_TIMEOUT_MS);
  try {
    const resp = await fetch(url, { headers: { 'x-ea-token': cfg.token } });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    return Array.isArray(data.cmds) ? data.cmds : [];
  } finally {
    clearTimeout(timer);
  }
}

// 连接注册：网络错误 / 非 200 / ok:false 均抛错，交由主循环统一退避
async function hello(cfg) {
  const data = await postJson(`${cfg.serverUrl}${HELLO_PATH}`, cfg.token, {
    extVersion: chrome.runtime.getManifest().version,
    browser: navigator.userAgent,
  });
  if (!data || data.ok !== true) throw new Error('hello 被服务端拒绝（ok:false，token 可能不正确）');
  return data;
}

// 单条命令：校验 → 执行 → 独立 POST result；失败也上报（error 为可读字符串），上报失败仅记日志
async function handleCmd(cfg, cmd) {
  const opId = cmd && cmd.opId;
  const type = cmd && cmd.type;
  const args = (cmd && cmd.args) || {};
  let ok = false;
  let data;
  let error;
  const verr = validateOp(type, args);
  if (verr) {
    error = verr;
  } else {
    try {
      data = await EXECUTORS[type](args);
      ok = true;
    } catch (e) {
      error = `${type} 执行失败：${toErrMsg(e)}`;
    }
  }
  try {
    await postJson(`${cfg.serverUrl}${RESULT_PATH}`, cfg.token, ok ? { opId, ok, data } : { opId, ok, error });
  } catch (e) {
    // 结果上报失败不打断主循环（该命令由服务端超时兜底）
    console.warn(`[ea-ext] op ${opId} 结果上报失败：`, toErrMsg(e));
  }
}

/* ==================== 主循环 ==================== */

let loopRunning = false; // 防止同一 SW 生命周期内重复起循环（SW 被回收后模块重载会自动复位）

async function runLoop() {
  if (loopRunning) return;
  loopRunning = true;
  let failureCount = 0;
  for (;;) {
    const cfg = await readConfig();
    if (!cfg) {
      // 尚未配置：不抛错退出，等 storage 变化唤醒 SW，同时定期重试
      await sleep(NO_CONFIG_RETRY_MS);
      continue;
    }
    try {
      await hello(cfg);
      failureCount = 0; // 成功即复位退避
      console.info(`[ea-ext] 已连接 ${cfg.serverUrl}，开始轮询命令`);
      for (;;) {
        const cmds = await pollCmds(cfg); // cmds 为空数组 → 立即进入下一轮 poll
        failureCount = 0;
        for (const cmd of cmds) {
          await handleCmd(cfg, cmd); // 一批命令按顺序串行执行，逐个上报
        }
      }
    } catch (e) {
      failureCount += 1;
      const delay = backoffFor(failureCount);
      console.warn(`[ea-ext] 与服务端通信失败（第 ${failureCount} 次），${delay}ms 后重试：`, toErrMsg(e));
      await sleep(delay); // 1s→2s→5s→10s 封顶，成功后复位
    }
  }
}

/* ==================== 事件注册区（MV3 回收后由事件唤醒，重新进入主循环） ==================== */

chrome.runtime.onInstalled.addListener(() => console.info('[ea-ext] 扩展已安装/更新'));
chrome.runtime.onStartup.addListener(() => console.info('[ea-ext] 浏览器已启动'));
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && (changes.serverUrl || changes.token)) {
    console.info('[ea-ext] 配置已变更，主循环将使用新配置重连');
  }
});
// 点击工具栏图标打开 options 页
chrome.action.onClicked.addListener(() => chrome.runtime.openOptionsPage());

// 启动（含被 MV3 回收后事件唤醒）即读配置开跑，无需人工触发
void runLoop();
