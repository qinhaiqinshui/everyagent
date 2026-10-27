/**
 * 设置页「模型配置」区块(独立组件;表格见 ModelConfigTable,添加对话框见 AddModelConfigDialog)。
 *
 * - 数据源:modelConfigs 服务(按 worker 分份,config.get 校准;apiKey 为掩码);
 *   增删成功后 worker 广播 config.changed{keys:["models"]},列表自动刷新;
 * - 模型配置按 worker 各自维护(写 worker 侧 application-worker.yaml 的 worker.models 段),
 *   故按 worker 分组展示;每组表格上方有「添加」「删除」两个按钮:
 *   - 添加:弹 AddModelConfigDialog(厂家预设自动带出 baseUrl;baseUrl/fullUrl 二选一);
 *   - 删除:选中表格一行(radio)后删除,确认后 rpc config.model.remove;
 * - 「重新读取」按钮:手动热加载(应用外部手改 application-worker.yaml 的场景);
 * - 所有变更仅影响后续新建任务,运行中任务使用创建时冻结的快照。
 */
import React from 'react'
import { Button } from '@/components/shared/ui'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import { useHub } from '@/hub/HubProvider'
import { hubSession } from '@/hub/session'
import { modelConfigs } from '@/hub/modelConfigs'
import { useAppUi } from '@/components/app/AppUiContext'
import { workerDisplayName } from '@/settings/workerAliases'
import ModelConfigTable from './ModelConfigTable'
import ModelConfigFormDialog, { type ModelConfigFormValues } from './ModelConfigFormDialog'
import type { ModelConfigInfo } from '@/hub/modelConfigs'

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return typeof error === 'string' ? error : '未知错误'
}

/** 单台 worker 的模型配置分组(组头 + 添加/删除按钮 + 表格)。 */
function WorkerModelGroup({ workerId, workerLabel }: { workerId: string; workerLabel: string }) {
  const { showToast } = useAppUi()
  const [models, setModels] = React.useState(() => modelConfigs.currentFor(workerId))
  const [selectedId, setSelectedId] = React.useState('')
  const [addOpen, setAddOpen] = React.useState(false)
  const [submitting, setSubmitting] = React.useState(false)
  const [deleting, setDeleting] = React.useState(false)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = React.useState(false)
  const [editingModel, setEditingModel] = React.useState<ModelConfigInfo | null>(null)
  const [editOpen, setEditOpen] = React.useState(false)
  const [editSubmitting, setEditSubmitting] = React.useState(false)

  React.useEffect(() => modelConfigs.subscribeFor(workerId, setModels), [workerId])

  // 选中的条目被删/热加载消失后清空选中态。
  React.useEffect(() => {
    if (selectedId && !models.some((m) => m.configId === selectedId)) {
      setSelectedId('')
    }
  }, [models, selectedId])

  const handleAdd = async (values: ModelConfigFormValues) => {
    if (submitting) return
    setSubmitting(true)
    try {
      await hubSession.rpcTo(workerId, 'config.model.add', {
        configId: values.configId,
        provider: values.provider,
        baseUrl: values.baseUrl ?? null,
        fullUrl: values.fullUrl ?? null,
        model: values.model,
        apiKey: values.apiKey,
      })
      showToast('已添加模型配置「' + values.configId + '」', 'success')
      setAddOpen(false)
      // config.changed 广播会触发 modelConfigs 自动校准;此处定向再拉一次兜底(幂等去重)。
      void modelConfigs.refreshFor(workerId)
    } catch (error) {
      showToast('添加模型配置失败:' + errorMessage(error), 'error')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    setDeleteConfirmOpen(false)
    if (!selectedId || deleting) return
    setDeleting(true)
    try {
      await hubSession.rpcTo(workerId, 'config.model.remove', { configId: selectedId })
      showToast('已删除模型配置「' + selectedId + '」', 'success')
      setSelectedId('')
      void modelConfigs.refreshFor(workerId)
    } catch (error) {
      showToast('删除模型配置失败:' + errorMessage(error), 'error')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div style={groupStyle}>
      <div style={groupHeaderStyle}>
        <span style={groupTitleStyle}>{workerLabel}</span>
        <span style={groupSubStyle} title={workerId}>{workerId}</span>
        <span style={groupMetaStyle}>{models.length + ' 项'}</span>
      </div>
      <div style={toolbarStyle}>
        <Button type="button" variant="primary" size="sm" onClick={() => setAddOpen(true)}>
          添加
        </Button>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={!selectedId || deleting}
          onClick={() => setDeleteConfirmOpen(true)}
        >
          删除
        </Button>
      </div>
      <ModelConfigTable models={models} selectedConfigId={selectedId} onSelect={setSelectedId} />
      <AddModelConfigDialog
        open={addOpen}
        existingIds={models.map((m) => m.configId)}
        submitting={submitting}
        onCancel={() => setAddOpen(false)}
        onSubmit={handleAdd}
      />
      <ConfirmDialog
        open={deleteConfirmOpen}
        title="删除模型配置"
        message={'将删除「' + selectedId + '」并写回 application-worker.yaml。运行中任务使用创建时冻结的快照,不受影响。确定删除?'}
        confirmLabel="删除"
        danger
        onConfirm={() => void handleDelete()}
        onCancel={() => setDeleteConfirmOpen(false)}
      />
    </div>
  )
}

export default function ModelConfigSection() {
  const hub = useHub()
  const [reloading, setReloading] = React.useState(false)
  const [reloadHint, setReloadHint] = React.useState('')
  const [reloadTone, setReloadTone] = React.useState<'ok' | 'error'>('ok')

  const connectedWorkerIds = React.useMemo(
    () => hub.directory.filter((w) => w.connected).map((w) => w.workerId).sort(),
    [hub.directory],
  )

  /** 「重新读取」:手动热加载全部已连 worker 的模型配置(应用外部手改配置文件的场景)。 */
  const handleReloadAll = async () => {
    if (reloading) return
    if (connectedWorkerIds.length === 0) {
      setReloadHint('无已连接的 worker')
      setReloadTone('error')
      return
    }
    setReloading(true)
    setReloadHint('')
    try {
      const results = await Promise.allSettled(
        connectedWorkerIds.map((id) => hubSession.rpcTo(id, 'config.reload')),
      )
      const failed: string[] = []
      results.forEach((r, i) => {
        if (r.status === 'rejected') failed.push(connectedWorkerIds[i])
      })
      if (failed.length === 0) {
        setReloadHint('已重新读取 ' + connectedWorkerIds.length + ' 台 worker 的模型配置')
        setReloadTone('ok')
      } else {
        setReloadHint(
          '已重新读取 ' + (connectedWorkerIds.length - failed.length) + ' 台,'
          + failed.length + ' 台失败(' + failed.join(', ') + ')',
        )
        setReloadTone('error')
      }
    } finally {
      setReloading(false)
    }
  }

  if (connectedWorkerIds.length === 0) {
    return <p style={hintStyle}>无已连接的 worker——连接后即可查看与维护该 worker 的模型配置。</p>
  }

  return (
    <div style={wrapStyle}>
      <div style={toolbarStyle}>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => void handleReloadAll()}
          disabled={reloading}
        >
          {reloading ? '重新读取中…' : '重新读取模型配置'}
        </Button>
        {reloadHint ? (
          <span style={reloadTone === 'error' ? errorStyle : okStyle}>{reloadHint}</span>
        ) : null}
      </div>
      {connectedWorkerIds.map((workerId) => {
        const info = hub.directory.find((w) => w.workerId === workerId)
        return (
          <WorkerModelGroup
            key={workerId}
            workerId={workerId}
            workerLabel={info ? workerDisplayName(info) : workerId}
          />
        )
      })}
    </div>
  )
}

// ---- 样式(与 SkillListTable 分组风格一致,复用设计令牌) ----

const wrapStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  maxWidth: 1080,
}

const groupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
}

const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 8,
  minWidth: 0,
}

const groupTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  whiteSpace: 'nowrap',
}

const groupSubStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
}

const groupMetaStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  whiteSpace: 'nowrap',
  flexShrink: 0,
}

const toolbarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
}

const hintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const okStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-green)',
}

const errorStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
}
