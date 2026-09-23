package dev.everyagent.worker.proto;

/**
 * worker 侧 RPC 方法名(业务域,归 worker;contract 只留信封)。
 */
public final class RpcMethods {

    public static final String TASKS_LIST = "tasks.list";
    /** 运行任务:不传 taskId=新建(workspace 必填),传 taskId=载入老任务历史续跑(运行中则入队)。 */
    public static final String TASK_RUN = "task.run";
    /** 任务流纯拉取:按 seq 增量/翻页拉取任务事件,支持长轮询挂起等待新事件(参数见前端 task-poll 类型)。 */
    public static final String TASK_POLL = "task.poll";
    /** 任务轮次索引拉取:一次返回 rounds.jsonl 全部轮 + 运行中未闭合轮(open);旧任务首次调用惰性全量生成落盘。 */
    public static final String TASK_ROUNDS = "task.rounds";
    /** 轮尾一次性拉取:按轮起点(startSeq)取该轮末尾 limit 条事件用于初始渲染(磁盘∪内存,同 seq 以内存为准、同 seq 组不拆批)。 */
    public static final String TASK_ROUND_TAIL = "task.roundTail";
    /** 单轮文件变更全文拉取:参数 taskId+roundId,返回 file-changes/<roundId>.json 的 {changes:[...]}。 */
    public static final String TASK_FILE_CHANGES = "task.fileChanges";
    /** 子 agent 台账一次性拉取:前端打开任务详情建子 agent 胶囊列表的唯一取数口;
     * 台账 agents.json 独立落盘,meta.agents 仅旧任务回退。 */
    public static final String TASK_AGENTS = "task.agents";
    public static final String TASK_CANCEL = "task.cancel";
    public static final String TASK_DELETE = "task.delete";
    /** 删除某条队列输入(参数 taskId, index)。 */
    public static final String TASK_QUEUE_REMOVE = "task.queueRemove";
    /** 移动(重排)某条队列输入(参数 taskId, fromIndex, toIndex)。 */
    public static final String TASK_QUEUE_MOVE = "task.queueMove";
    public static final String CONFIG_GET = "config.get";
    /** 重新读取模型配置(重新解析 worker.models,应用用户在外部 YAML 中的修改)。 */
    public static final String CONFIG_RELOAD = "config.reload";
    /** 重新扫描外部 skill 列表(用户在系统技能目录下增删 skill 目录后热加载)。 */
    public static final String SKILL_RELOAD = "skill.reload";
    public static final String WORKSPACES_LIST = "workspaces.list";
    public static final String WORKSPACES_ADD = "workspaces.add";
    public static final String WORKSPACES_REMOVE = "workspaces.remove";
    /** 注册工作区外部授权根(参数 workspace + path;目录→自身、文件→父目录,过宽根拒收,去重/包含吸收)。 */
    public static final String WORKSPACES_ADD_EXTERNAL_ROOT = "workspaces.addExternalRoot";
    /** 启动自检缺失工作区落定:action=delete(删除注册+级联任务数据)/redirect(纠正到新目录)。 */
    public static final String WORKSPACES_RESOLVE_MISSING = "workspaces.resolveMissing";
    public static final String FS_LIST = "fs.list";
    /** 定位文件/目录(懒加载):沿路径逐段 stat 返回节点链,旁支零查找。 */
    public static final String FS_REVEAL = "fs.reveal";
    /** 在运行 worker 的宿主系统文件管理器中选中目标(对标 VSCode Reveal in File Explorer)。 */
    public static final String FS_REVEAL_IN_OS = "fs.revealInOs";
    public static final String FS_READ = "fs.read";
    public static final String FS_WRITE = "fs.write";
    public static final String FS_MKDIR = "fs.mkdir";
    public static final String FS_MOVE = "fs.move";
    public static final String FS_DELETE = "fs.delete";
    /** 浏览目录(方案 B:列盘符/根,再逐层列子目录;不经 workspace 沙箱,依赖 worker 进程权限)。 */
    public static final String FS_BROWSE = "fs.browse";

    // ---- 内嵌终端(§7.18) ----

    /** 打开终端会话:workspace+termId+path+cols+rows+可选 shell,返回 {termId, pid}。 */
    public static final String TERM_OPEN = "term.open";
    /** 写入终端:termId+data(base64)。 */
    public static final String TERM_INPUT = "term.input";
    /** 调整终端尺寸:termId+cols+rows。 */
    public static final String TERM_RESIZE = "term.resize";
    /** 关闭终端会话:termId。 */
    public static final String TERM_CLOSE = "term.close";
    /** 工作区文本内容搜索(内置 rg,架构 §5.10):jailed 到工作区根,JSON lines 解析为
     * 结构化结果;大结果复用 fs.read 的 rpc.data 分批 + 末帧 ok 汇总。 */
    public static final String FS_SEARCH = "fs.search";
    /** 任务内容搜索(内置 rg + worker 后处理):按 workspaceId 枚举任务,搜索 rounds.jsonl
     * 轮次索引,解析 JSON 后对 user/finalReply 干净文本二次匹配消除字段名噪音。 */
    public static final String TASK_SEARCH = "task.search";
    /** 斜杠命令清单(动态注册,数据来源下沉 worker;前端只负责渲染与插入)。 */
    public static final String SLASH_LIST = "slash.list";
    /** 斜杠命令选中:携带 token 与 taskId 触发条目 selectHandler(taskId 可空=草稿态,不写任务 meta)。 */
    public static final String SLASH_SELECT = "slash.select";
    /** 斜杠命令取消:胶囊/⌧(内联✕)移除时触发条目 cancelHandler(taskId 可空=草稿取消/内联✕,不写任务 meta)。 */
    public static final String SLASH_CANCEL = "slash.cancel";
    /** 把任务相关 opaque token 以其 payload 注入(按 payload 落地/还原 token 携带的数据)。 */
    public static final String SLASH_TASK_TOKENS_APPLY = "slash.taskTokens.apply";
    /** @ 文件搜索(后端做子序列模糊匹配 + 隐藏规则 + 截断 10 条,前端零递归)。 */
    public static final String MENTION_QUERY = "mention.query";
    public static final String GIT_STATUS = "git.status";
    public static final String GIT_LOG = "git.log";
    public static final String GIT_DIFF = "git.diff";
    /** 读取某次提交的变更文件清单与全文(历史详情/恢复此版本;必带 commit)。 */
    public static final String GIT_SHOW = "git.show";
    public static final String GIT_COMMIT = "git.commit";
    public static final String GIT_PULL = "git.pull";
    public static final String GIT_PUSH = "git.push";
    /** 放弃指定路径的更改(恢复为 HEAD 内容;未跟踪/已暂存新增跳过)。 */
    public static final String GIT_DISCARD = "git.discard";
    public static final String GIT_CLONE = "git.clone";
    public static final String GIT_INIT = "git.init";
    public static final String GIT_REMOTE_ADD = "git.remote.add";
    public static final String GIT_REMOTE_LIST = "git.remote.list";
    /** 保存 git 远端凭证(加密落盘工作区 .git-credentials.enc;仅写不读回)。 */
    public static final String GIT_CREDENTIAL_SAVE = "git.credential.save";
    public static final String SYS_METHODS = "sys.methods";
    public static final String SYS_INFO = "sys.info";

    private RpcMethods() {
    }
}
