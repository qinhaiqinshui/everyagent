import React from 'react'
import { createLogger } from '@/utils/logger'
import Button from '@/components/shared/ui/Button'

const errorBoundaryLogger = createLogger('ErrorBoundary')

interface Props {
  children: React.ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

/**
 * 全局 React 错误边界：捕获子树渲染异常，写入运行日志并展示错误页，
 * 避免整页白屏。`componentDidCatch` 记录错误后，用户可点击「重试」或
 * 「重新加载」恢复。
 *
 * 放在 main.tsx 中包裹整个 `<Layout />`，作为渲染异常的最后一道防线。
 */
export class GlobalErrorBoundary extends React.Component<Props, State> {
  state: State = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    try {
      errorBoundaryLogger.error('React 组件渲染异常', {
        name: error.name,
        message: error.message,
        stack: error.stack,
        componentStack: info.componentStack,
      })
    } catch {
      // 兜底，绝不抛
    }
  }

  handleReload = (): void => {
    window.location.reload()
  }

  handleReset = (): void => {
    this.setState({ hasError: false, error: null })
  }

  render(): React.ReactNode {
    if (!this.state.hasError) {
      return this.props.children
    }
    const error = this.state.error
    return (
      <div style={{
        minHeight: '100dvh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background: 'var(--bg-primary)',
        boxSizing: 'border-box',
      }}>
        <div style={{
          maxWidth: 560,
          width: '100%',
          padding: 24,
          background: 'var(--bg-secondary)',
          border: '1px solid var(--border-primary)',
          borderRadius: 'var(--radius-md)',
          boxShadow: 'var(--shadow-md)',
        }}>
          <h2 style={{ margin: '0 0 8px', fontSize: 18, color: 'var(--text-primary)' }}>
            界面渲染出错
          </h2>
          <p style={{ margin: '0 0 16px', fontSize: 14, color: 'var(--text-secondary)' }}>
            应用遇到了一个未捕获的渲染错误。错误已记录到运行日志，可重试或重新加载。
          </p>
          {error && (
            <pre style={{
              margin: '0 0 16px',
              padding: 12,
              background: 'var(--bg-tertiary)',
              borderRadius: 'var(--radius-sm)',
              fontSize: 12,
              color: 'var(--text-secondary)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              maxHeight: 240,
              overflow: 'auto',
            }}>
              {error.message}
              {error.stack ? `\n\n${error.stack}` : ''}
            </pre>
          )}
          <div style={{ display: 'flex', gap: 8 }}>
            <Button
              type="button"
              onClick={this.handleReset}
              variant="secondary"
              style={{
                padding: '8px 16px',
                fontSize: 14,
                borderRadius: 'var(--radius-sm)',
                border: '1px solid var(--border-primary)',
                background: 'var(--bg-tertiary)',
                color: 'var(--text-primary)',
                cursor: 'pointer',
              }}
            >
              重试
            </Button>
            <Button
              type="button"
              onClick={this.handleReload}
              variant="primary"
              style={{
                padding: '8px 16px',
                fontSize: 14,
                borderRadius: 'var(--radius-sm)',
                border: 'none',
                background: 'var(--accent-primary)',
                color: 'var(--text-on-accent)',
                cursor: 'pointer',
              }}
            >
              重新加载
            </Button>
          </div>
        </div>
      </div>
    )
  }
}
