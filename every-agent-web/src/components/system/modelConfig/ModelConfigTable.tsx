/**
 * 设置页「模型配置」表格(独立组件):展示已连 worker 的模型配置列表。
 *
 * 列:id、模型名、模型厂家、baseUrl、fullUrl、apiKey(仅掩码)、接口协议。
 * apiKey 由 worker 经 config.get 掩码返回(前4 + … + 后4),明文不出 worker。
 * 行为单选(radio):选中一行后,外层「删除」「编辑」按钮据此操作该条配置;
 * 双击行可直接打开编辑对话框(由调用方 onEdit 驱动,容灾池等不可编辑的行由
 * 调用方自行忽略)。
 */
import React from 'react'
import { Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ModelConfigInfo } from '@/hub/modelConfigs'

const ellipsisStyle: React.CSSProperties = {
  maxWidth: 320,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  display: 'inline-block',
  verticalAlign: 'bottom',
}

const monoStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
}

export interface ModelConfigTableProps {
  models: ModelConfigInfo[]
  selectedConfigId: string
  onSelect: (configId: string) => void
  /** 双击行触发编辑(调用方判断是否可编辑,如容灾池行直接忽略)。 */
  onEdit?: (record: ModelConfigInfo) => void
}

export default function ModelConfigTable({ models, selectedConfigId, onSelect, onEdit }: ModelConfigTableProps) {
  const columns: ColumnsType<ModelConfigInfo> = [
    {
      title: 'id',
      dataIndex: 'configId',
      key: 'configId',
      width: 140,
      ellipsis: true,
      render: (v: string) => <span style={monoStyle} title={v}>{v || '—'}</span>,
    },
    {
      title: '模型名',
      dataIndex: 'model',
      key: 'model',
      width: 160,
      ellipsis: true,
      render: (v: string) => <span style={ellipsisStyle} title={v}>{v || '—'}</span>,
    },
    {
      title: '模型厂家',
      dataIndex: 'provider',
      key: 'provider',
      width: 120,
      ellipsis: true,
      render: (v: string) => v || '—',
    },
    {
      title: 'baseUrl',
      dataIndex: 'baseUrl',
      key: 'baseUrl',
      width: 200,
      ellipsis: true,
      render: (v?: string | null) => (
        <span style={{ ...monoStyle, ...ellipsisStyle }} title={v ?? ''}>
          {v || '—'}
        </span>
      ),
    },
    {
      title: 'fullUrl',
      dataIndex: 'fullUrl',
      key: 'fullUrl',
      width: 240,
      ellipsis: true,
      render: (v?: string | null) => (
        <span style={{ ...monoStyle, ...ellipsisStyle }} title={v ?? ''}>
          {v || '—'}
        </span>
      ),
    },
    {
      title: 'apiKey',
      dataIndex: 'apiKey',
      key: 'apiKey',
      width: 160,
      ellipsis: true,
      render: (v?: string | null) => (
        <span style={monoStyle} title="worker 仅返回掩码,明文不出 worker">
          {v || '—'}
        </span>
      ),
    },
    {
      title: '接口协议',
      key: 'protocol',
      width: 110,
      render: () => 'OpenAI',
    },
  ]

  return (
    <Table<ModelConfigInfo>
      size="small"
      rowKey="configId"
      columns={columns}
      dataSource={models}
      pagination={false}
      scroll={{ x: 'max-content' }}
      rowSelection={{
        type: 'radio',
        selectedRowKeys: selectedConfigId ? [selectedConfigId] : [],
        onChange: (keys) => onSelect(keys[0] as string),
      }}
      onRow={(record) => ({
        onClick: () => onSelect(record.configId),
        onDoubleClick: () => onEdit?.(record),
      })}
      locale={{ emptyText: '该 worker 暂无模型配置,点击上方「添加」新增' }}
    />
  )
}
