/**
 * 空工作区快捷入口(主界面标签页全关后的空白页「开始」区,VSCode 欢迎页风格)。
 *
 * 三张横向卡片(左图标 + 右标题/灰色说明,整卡可点),面向普通用户的冷启动引导:
 * 1. 新建工作区(项目):弹 WorkspaceBrowserModal 选目录 → workspaces.add 注册;
 * 2. 添加模型配置:弹 ModelConfigFormDialog(添加模式)→ config.model.add;
 * 3. 导入 skill:pickZipFile 选 zip → ConfirmDialog 确认 → skill.import(立即生效)。
 *
 * Worker 选择(三入口共用):取目录中**首个** connected worker 作为操作目标;
 * 无已连接 worker 时点击任一入口 → toast + 打开设置页并定位「Hub 连接」段引导连接。
 */
import React from 'react'
import {
  BrainIcon,
  FolderPlusIcon,
  UploadIcon,
} from '@/components/shared/AppGlyphs'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import WorkspaceBrowserModal from '@/components/shared/WorkspaceBrowserModal'
import ModelConfigFormDialog, {
  type ModelConfigFormValues,
} from '@/components/system/modelConfig/ModelConfigFormDialog'
import { SETTINGS_SECTION_ID } from '@/components/system/settingsSectionIds'
import { useHub } from '@/hub/HubProvider'
import { hubSession } from '@/hub/session'
import { modelConfigs } from '@/hub/modelConfigs'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { requestOpenSettingsPage } from '@/settings/settingsNavigation'
import { workerDisplayName } from '@/settings/workerAliases'
import {
  MAX_IMPORT_ZIP_BYTES,
  ZIP_RPC_TIMEOUT_MS,
  bytesToBase64,
  pickZipFile,
} from '@/utils/skillZip'

export default function EmptyWorkspaceQuickStart() {
  const hub = useHub()
  const { showToast } = useAppUi()
  const { openTab } = useWorkspaceShell()

  /** 打开中的「新建工作区」目录选择框(记录打开时锁定的目标 worker)。 */
  const [wsDialog, setWsDialog] = React.useState<{ workerId: string } | null>(null)
  /** 打开中的「添加模型配置」框(记录打开时锁定的目标 worker)。 */
  const [modelDialog, setModelDialog] = React.useState<{ workerId: string } | null>(null)
  const [modelSubmitting, setModelSubmitting] = React.useState(false)
  /** 待确认的 skill 导入(打开时锁定的目标 worker + 已选 zip 文件)。 */
  const [importDialog, setImportDialog] = React.useState<{ workerId: string; file: File } | null>(null)
  const [importing, setImporting] = React.useState(false)

  /**
   * 三个入口共用的 worker 前置检查:取首个 connected worker;
   * 无已连接 worker 时 toast 并跳设置页「Hub 连接」段,返回 null 中断本次点击。
   */
  const ensureConnectedWorker = (): string | null => {
    const connected = hub.directory.find((w) => w.connected)
    if (!connected) {
      showToast('尚未连接 worker,请先在设置中连接', 'error')
      // 先 openTab 保证设置页标签存在/聚焦,再记录意图直达「Hub 连接」段
      // (顺序与 TaskModelControls / Layout 的 openSettingsPage 桥一致,不可倒置)。
      openTab('page:settings')
      requestOpenSettingsPage(SETTINGS_SECTION_ID.HUB_CONNECTION)
      return null
    }
    // 多台已连接 worker:本次取第一台;后续可扩展为在各弹窗内选择目标 worker。
    return connected.workerId
  }

  // ---- 入口 1:新建工作区 ----

  const handleWorkspaceClick = () => {
    const workerId = ensureConnectedWorker()
    if (!workerId) return
    setWsDialog({ workerId })
  }

  const handleWorkspaceConfirm = async (absPath: string) => {
    if (!wsDialog) return
    const { workerId } = wsDialog
    try {
      await workspaceRegistry.add(workerId, absPath)
      setWsDialog(null)
      showToast('已注册工作区「' + lastPathSegment(absPath) + '」', 'success')
    } catch (error) {
      showToast('注册工作区失败:' + errorMessage(error), 'error')
    }
  }

  // ---- 入口 2:添加模型配置 ----

  const handleModelClick = () => {
    const workerId = ensureConnectedWorker()
    if (!workerId) return
    setModelDialog({ workerId })
  }

  const handleModelSubmit = async (values: ModelConfigFormValues) => {
    if (!modelDialog || modelSubmitting) return
    const { workerId } = modelDialog
    setModelSubmitting(true)
    try {
      await hubSession.rpcTo(workerId, 'config.model.add', {
        configId: values.configId,
        provider: values.provider,
        baseUrl: values.baseUrl ?? null,
        fullUrl: values.fullUrl ?? null,
        model: values.model,
        apiKey: values.apiKey,
      })
      setModelDialog(null)
      showToast('已添加模型配置「' + values.configId + '」', 'success')
      // config.changed 广播会触发校准;此处再定向拉一次兜底(幂等,与设置页同姿态)。
      void modelConfigs.refreshFor(workerId)
    } catch (error) {
      showToast('添加模型配置失败:' + errorMessage(error), 'error')
    } finally {
      setModelSubmitting(false)
    }
  }

  // ---- 入口 3:导入 skill ----

  const handleImportClick = async () => {
    // pickZipFile 须在用户手势调用栈内触发,前置检查保持同步、不 await。
    const workerId = ensureConnectedWorker()
    if (!workerId) return
    const file = await pickZipFile()
    if (!file) return
    if (file.size > MAX_IMPORT_ZIP_BYTES) {
      showToast(
        'zip 超过 ' + Math.floor(MAX_IMPORT_ZIP_BYTES / 1024 / 1024) + 'MB 上限(当前 '
        + Math.ceil(file.size / 1024 / 1024) + 'MB)',
        'error',
      )
      return
    }
    setImportDialog({ workerId, file })
  }

  const handleImportConfirm = async () => {
    if (!importDialog || importing) return
    const { workerId, file } = importDialog
    setImporting(true)
    try {
      const content = new Uint8Array(await file.arrayBuffer())
      await hubSession.rpcTo(workerId, 'skill.import', {
        contentBase64: bytesToBase64(content),
      }, { timeoutMs: ZIP_RPC_TIMEOUT_MS })
      setImportDialog(null)
      showToast('已导入「' + file.name + '」', 'success')
    } catch (error) {
      showToast('导入失败:' + errorMessage(error), 'error')
    } finally {
      setImporting(false)
    }
  }

  /** 弹窗标题用的 worker 显示名(别名 > hostname > workerId,与 Worker 列表同口径)。 */
  const workerNameOf = (workerId: string): string => {
    const info = hub.directory.find((w) => w.workerId === workerId)
    return info ? workerDisplayName(info) : workerId
  }

  return (
    <div style={listStyle}>
      <QuickStartCard
        icon={<FolderPlusIcon size={22} style={{ color: 'var(--accent-blue)' }} />}
        title="新建工作区(项目)"
        description="选择一个目录注册为工作区"
        onClick={handleWorkspaceClick}
      />
      <QuickStartCard
        icon={<BrainIcon size={22} style={{ color: 'var(--accent-blue)' }} />}
        title="添加模型配置"
        description="没有模型配置无法运行任务"
        onClick={handleModelClick}
      />
      <QuickStartCard
        icon={<UploadIcon size={22} style={{ color: 'var(--accent-blue)' }} />}
        title="导入 skill"
        description="从 zip 导入技能包,立即生效"
        onClick={() => void handleImportClick()}
      />

      <WorkspaceBrowserModal
        open={Boolean(wsDialog)}
        workerId={wsDialog?.workerId}
        title="新建工作区(选择目录)"
        onCancel={() => setWsDialog(null)}
        onConfirm={(absPath) => void handleWorkspaceConfirm(absPath)}
      />
      <ModelConfigFormDialog
        open={Boolean(modelDialog)}
        editing={null}
        existingIds={
          modelDialog ? modelConfigs.currentFor(modelDialog.workerId).map((m) => m.configId) : []
        }
        submitting={modelSubmitting}
        onCancel={() => {
          if (!modelSubmitting) setModelDialog(null)
        }}
        onSubmit={handleModelSubmit}
      />
      <ConfirmDialog
        open={Boolean(importDialog)}
        title={'导入 skill zip:' + (importDialog ? workerNameOf(importDialog.workerId) : '')}
        subtitle="zip 将解压进该 worker 的 skill 目录:同名 skill 目录会被覆盖(其余不受影响),导入的 skill 立即生效、无需重启 worker。注意同 id 的内置/插件包 skill 重启 worker 后会被源内容重新物化。"
        confirmLabel={importing ? '导入中…' : '导入'}
        hideDefaultMessageBlock
        body={importDialog ? (
          <p style={hintStyle}>
            文件:{importDialog.file.name}({Math.ceil(importDialog.file.size / 1024)} KB)
          </p>
        ) : null}
        onConfirm={() => void handleImportConfirm()}
        onCancel={() => {
          if (!importing) setImportDialog(null)
        }}
      />
    </div>
  )
}

/**
 * 单张快捷入口卡片(VSCode 欢迎页「开始」区风格:横向宽条——左图标、右标题 + 一句
 * 灰色说明;整卡可点,hover/focus 有背景反馈)。hover 态用局部 state 控制
 * (内联样式无法表达 :hover),焦点高亮保证键盘可达。
 */
function QuickStartCard({
  icon,
  title,
  description,
  onClick,
}: {
  icon: React.ReactNode
  title: string
  description: string
  onClick: () => void
}) {
  const [hovered, setHovered] = React.useState(false)
  return (
    <button
      type="button"
      style={{ ...cardStyle, background: hovered ? 'var(--bg-hover)' : 'transparent' }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setHovered(true)}
      onBlur={() => setHovered(false)}
      onClick={onClick}
    >
      <span style={cardIconStyle}>{icon}</span>
      <span style={cardTextStyle}>
        <span style={cardTitleStyle}>{title}</span>
        <span style={cardDescStyle}>{description}</span>
      </span>
    </button>
  )
}

/** 路径末段(剥尾部斜杠后的最后一段;整串为分隔符等异常输入回落原样)。 */
function lastPathSegment(path: string): string {
  const norm = path.replace(/[\\/]+$/, '')
  const segment = norm.split(/[\\/]/).pop()
  return segment && segment !== '' ? segment : path
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return typeof error === 'string' ? error : '未知错误'
}

// ---- 样式(复用设计令牌;宽度/间距按 VSCode 欢迎页「开始」区观感) ----

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  width: 480,
  maxWidth: '100%',
}

const cardStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  width: '100%',
  padding: '12px 14px',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
  fontFamily: 'inherit',
  fontSize: 'var(--text-sm)',
  textAlign: 'left',
  cursor: 'pointer',
  transition: 'background 120ms ease',
}

const cardIconStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: 34,
  height: 34,
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-secondary, transparent)',
  flexShrink: 0,
}

const cardTextStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  minWidth: 0,
}

const cardTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 600,
  color: 'var(--text-primary)',
  lineHeight: 1.4,
}

const cardDescStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  lineHeight: 1.5,
}

const hintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}
