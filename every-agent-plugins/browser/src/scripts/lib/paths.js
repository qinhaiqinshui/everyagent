'use strict';
/**
 * 路径与运行时状态管理(零依赖)。
 *
 * 运行时产物(port/token/pid/临时文件)一律外置到用户目录
 * ~/.everyagent/browser-automation/,skill 目录保持纯静态可重装。
 * 测试可经环境变量 EA_BROWSER_HOME 重定向根目录,避免污染真实用户目录。
 */
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');

/** 守护进程状态根目录(支持 EA_BROWSER_HOME 覆盖)。 */
function home() {
    const override = process.env.EA_BROWSER_HOME;
    return override && override.trim() ? path.resolve(override.trim())
            : path.join(os.homedir(), '.everyagent', 'browser-automation');
}

/** runtime.json 路径:{port,token,pid,startedAt}。 */
function runtimeFile() {
    return path.join(home(), 'runtime.json');
}

/** 临时文件目录(aria 超阈值 fullFile 落这里;守护启动时清空)。 */
function tmpDir() {
    return path.join(home(), 'tmp');
}

/** 建目录(根 + tmp)。 */
function ensureDirs() {
    fs.mkdirSync(home(), { recursive: true });
    fs.mkdirSync(tmpDir(), { recursive: true });
}

/** 读 runtime.json;不存在/损坏返回 null。 */
function readRuntime() {
    try {
        return JSON.parse(fs.readFileSync(runtimeFile(), 'utf8'));
    } catch {
        return null;
    }
}

/** 原子写 JSON:同目录临时文件 + rename,杜绝读到半截。 */
function atomicWriteJson(file, obj) {
    const dir = path.dirname(file);
    fs.mkdirSync(dir, { recursive: true });
    const tmp = path.join(dir, '.' + path.basename(file) + '.' + process.pid + '.tmp');
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2));
    fs.renameSync(tmp, file);
}

/** 清空并重建 tmp 目录(守护启动时调用)。 */
function clearTmp() {
    fs.rmSync(tmpDir(), { recursive: true, force: true });
    fs.mkdirSync(tmpDir(), { recursive: true });
}

module.exports = { home, runtimeFile, tmpDir, ensureDirs, readRuntime, atomicWriteJson, clearTmp };
