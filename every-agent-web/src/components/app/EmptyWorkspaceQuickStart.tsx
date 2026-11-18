/**
 * 空工作区快捷入口(主界面标签页全关后的空白页「开始」区,VSCode 欢迎页风格)。
 *
 * 四张横向卡片(左图标 + 右标题/灰色说明,整卡可点)。本组件**只做导航**:点击跳转到
 * 对应页面,操作在目标页面内完成——不弹对话框、不发 RPC、不前置校验 worker 状态
 * (空态/未连接态由目标页面各自展示)。
 * 1. 新建任务 → 新建任务草稿页(输入框 + 工作区选择器,首次发送即建任务);
 * 2. 新建工作区(项目)→ 新建任务草稿页,并自动打开工作区选择器的目录浏览弹窗
 *    (经 taskWorkspaceBrowseIntent 通知草稿页,弹窗与选目录逻辑仍归草稿页所有);
 * 3. 添加模型配置 → 设置页「模型配置」段(段内「添加」按钮);
 * 4. 导入 skill → 设置页「Skills」段(段内「导入 zip」按钮)。
 */
import React from 'react'
import {
  BrainIcon,
  FolderPlusIcon,
  PlusIcon,
  UploadIcon,
} from '@/components/shared/AppGlyphs'
import { SETTINGS_SECTION_ID } from '@/components/system/settingsSectionIds'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { requestOpenSettingsPage } from '@/settings/settingsNavigation'
import { DRAFT_TASK_ID } from '@/components/task/taskChatDraft'
import { requestDraftWorkspaceBrowse } from '@/components/taskComposer/taskWorkspaceBrowseIntent'

export default function EmptyWorkspaceQuickStart() {
  const { openTab } = useWorkspaceShell()

  /**
   * 打开设置页并定位到指定段:先 openTab 保证设置页标签存在/聚焦,再记录跳转意图
   * (顺序与 TaskModelControls / Layout 的 openSettingsPage 桥一致,不可倒置)。
   */
  const openSettingsSection = (sectionId: string) => {
    openTab('page:settings')
    requestOpenSettingsPage(sectionId)
  }

  /** 打开唯一的新建任务草稿标签(已打开则聚焦,草稿文本保留)。 */
  const openDraftTask = () => {
    openTab('task', { taskId: DRAFT_TASK_ID }, '新任务')
  }

  /**
   * 新建工作区:先打开草稿标签,再发浏览意图由草稿页的工作区选择器弹目录浏览框
   * (顺序不可倒置——先保证标签存在/可见,弹窗才落在用户看得见的页面上)。
   */
  const openDraftTaskWithWorkspaceBrowse = () => {
    openDraftTask()
    requestDraftWorkspaceBrowse()
  }

  return (
    <div style={listStyle}>
      <QuickStartCard
        icon={<PlusIcon size={22} style={{ color: 'var(--accent-blue)' }} />}
        title="新建任务"
        description="选择工作区后对话,首次发送即建任务"
        onClick={openDraftTask}
      />
      <QuickStartCard
        icon={<FolderPlusIcon size={22} style={{ color: 'var(--accent-blue)' }} />}
        title="新建工作区(项目)"
        description="选择目录注册为工作区"
        onClick={openDraftTaskWithWorkspaceBrowse}
      />
      <QuickStartCard
        icon={<BrainIcon size={22} style={{ color: 'var(--accent-blue)' }} />}
        title="添加模型配置"
        description="没有模型配置无法运行任务"
        onClick={() => openSettingsSection(SETTINGS_SECTION_ID.MODELS)}
      />
      <QuickStartCard
        icon={<UploadIcon size={22} style={{ color: 'var(--accent-blue)' }} />}
        title="导入 skill"
        description="从 zip 导入技能包,立即生效"
        onClick={() => openSettingsSection(SETTINGS_SECTION_ID.SKILLS)}
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