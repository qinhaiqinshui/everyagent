'use strict';
/**
 * HTTP 协议层:路由 / token 鉴权 / CORS / JSON 收发。
 * 与命令逻辑解耦——挂起语义(poll)由调用方经 onPollWait 注入。
 */
const { createServer } = require('node:http');

/** 请求体上限:截图 dataUrl 可达数 MB。 */
const MAX_BODY = 32 * 1024 * 1024;

/** 统一 CORS 头(扩展跨源调用)。 */
function cors(res) {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type,x-ea-token');
}

/** JSON 应答(带 CORS)。 */
function sendJson(res, status, obj) {
    cors(res);
    const body = JSON.stringify(obj);
    res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
    });
    res.end(body);
}

/** 读 JSON 请求体(带上限保护)。 */
function readBody(req) {
    return new Promise((resolve, reject) => {
        let size = 0;
        const chunks = [];
        req.on('data', (c) => {
            size += c.length;
            if (size > MAX_BODY) {
                reject(new Error('请求体过大'));
                req.destroy();
                return;
            }
            chunks.push(c);
        });
        req.on('end', () => {
            try {
                const raw = Buffer.concat(chunks).toString('utf8');
                resolve(raw ? JSON.parse(raw) : {});
            } catch (e) {
                reject(new Error('JSON 解析失败: ' + e.message));
            }
        });
        req.on('error', reject);
    });
}

/** 取 token:query `token` 或 header `x-ea-token`。 */
function tokenOf(req, url) {
    const h = req.headers['x-ea-token'];
    if (h) { return String(h); }
    return url.searchParams.get('token') || '';
}

/**
 * 起 HTTP 服务(不 listen,由调用方决定)。
 * token 鉴权口径(冻结契约):poll 走 query;hello/result 走 body + x-ea-token 头双保险。
 * 因此 POST 端点先读 body,再在 query/header/body 三处取 token 比对。
 * @param {object} h 处理器:{token, pollTimeoutMs, onHello(body), onPollWait(res, timeoutMs),
 *                           onExtResult(body), onCliCmd(body)}(除 token 外全部 async/void)
 */
function startHttpServer(h) {
    return createServer(async (req, res) => {
        const url = new URL(req.url, 'http://127.0.0.1');
        try {
            if (req.method === 'OPTIONS') {
                cors(res);
                res.writeHead(204);
                res.end();
                return;
            }
            if (req.method === 'GET' && url.pathname === '/health') {
                return sendJson(res, 200, { ok: true });
            }
            const isPost = req.method === 'POST';
            const postPath = isPost && (url.pathname === '/ext/hello'
                    || url.pathname === '/ext/result' || url.pathname === '/cli/cmd');
            let body = null;
            if (postPath) {
                body = await readBody(req);
            }
            const presented = tokenOf(req, url) || (body && body.token) || '';
            if (presented !== h.token) {
                return sendJson(res, 401, { ok: false, error: 'token 无效' });
            }
            if (postPath && url.pathname === '/ext/hello') {
                return sendJson(res, 200, await h.onHello(body));
            }
            if (req.method === 'GET' && url.pathname === '/ext/poll') {
                return h.onPollWait(res, h.pollTimeoutMs || 25000);
            }
            if (postPath && url.pathname === '/ext/result') {
                return sendJson(res, 200, await h.onExtResult(body));
            }
            if (postPath && url.pathname === '/cli/cmd') {
                return sendJson(res, 200, await h.onCliCmd(body));
            }
            return sendJson(res, 404, { ok: false, error: '未知端点: ' + url.pathname });
        } catch (e) {
            return sendJson(res, 500, { ok: false, error: String((e && e.message) || e) });
        }
    });
}

module.exports = { startHttpServer, sendJson, cors, readBody, tokenOf };
