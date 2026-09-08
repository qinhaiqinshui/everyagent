import React from 'react'
import { createPortal } from 'react-dom'
import { Button } from '@/components/shared/ui'
import { ChevronDownIcon, CheckIcon } from '../shared/AppGlyphs'

/** 下拉选项。 */
export type InlineDropdownOption = {
  /** 选项值。 */
  value: string
  /** 选项显示文案。 */
  label: string
}

export default function InlineDropdown({
  value,
  options,
  placeholder,
  disabled = false,
  onChange,
  triggerLabel,
}: {
  value: string
  options: InlineDropdownOption[]
  placeholder: string
  disabled?: boolean
  onChange: (value: string) => void
  /** 可选：未展开时触发态显示的文字，覆盖选中项的 label（用于折叠态只显示厂商等精简文案）。 */
  triggerLabel?: string
}) {
  const rootRef = React.useRef<HTMLDivElement | null>(null)
  const triggerRef = React.useRef<HTMLButtonElement | null>(null)
  const menuRef = React.useRef<HTMLDivElement | null>(null)
  const [open, setOpen] = React.useState(false)
  const [menuPosition, setMenuPosition] = React.useState<{ top: number; left: number } | null>(null)

  const selectedOption = options.find((item) => item.value === value) ?? null
  const visibleLabel = triggerLabel ?? selectedOption?.label ?? placeholder

  React.useLayoutEffect(() => {
    if (!open) {
      setMenuPosition(null)
      return
    }

    const updateMenuPosition = () => {
      const trigger = triggerRef.current
      const menu = menuRef.current
      if (!trigger || !menu) return

      const triggerRect = trigger.getBoundingClientRect()
      const menuRect = menu.getBoundingClientRect()
      const viewportHeight = window.innerHeight
      const horizontalPadding = 10
      const verticalGap = 6
      const nextLeft = Math.max(horizontalPadding, triggerRect.left)
      const preferBottomTop = triggerRect.bottom + verticalGap
      const preferTopTop = triggerRect.top - menuRect.height - verticalGap
      const nextTop = preferBottomTop + menuRect.height <= viewportHeight - horizontalPadding
        ? preferBottomTop
        : Math.max(horizontalPadding, preferTopTop)

      setMenuPosition({
        top: nextTop,
        left: nextLeft,
      })
    }

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (!rootRef.current?.contains(target) && !menuRef.current?.contains(target)) {
        setOpen(false)
      }
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }

    updateMenuPosition()
    window.addEventListener('pointerdown', handlePointerDown)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('resize', updateMenuPosition)
    window.addEventListener('scroll', updateMenuPosition, true)

    return () => {
      window.removeEventListener('pointerdown', handlePointerDown)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('resize', updateMenuPosition)
      window.removeEventListener('scroll', updateMenuPosition, true)
    }
  }, [open])

  return (
    <div ref={rootRef} style={dropdownRootStyle}>
      <SelectedValueDisplay
        triggerRef={triggerRef}
        label={visibleLabel}
        open={open}
        disabled={disabled}
        onToggle={() => setOpen((current) => !current)}
      />
      {open && typeof document !== 'undefined' ? createPortal(
        <OptionsListContainer
          menuRef={menuRef}
          position={menuPosition}
          options={options}
          selectedValue={value}
          onSelect={(nextValue) => {
            setOpen(false)
            if (nextValue !== value) {
              onChange(nextValue)
            }
          }}
        />,
        document.body,
      ) : null}
    </div>
  )
}

function SelectedValueDisplay({
  triggerRef,
  label,
  open,
  disabled,
  onToggle,
}: {
  triggerRef: React.RefObject<HTMLButtonElement>
  label: string
  open: boolean
  disabled: boolean
  onToggle: () => void
}) {
  return (
    <Button
      ref={triggerRef}
      type="button"
      variant="ghost"
      aria-haspopup="listbox"
      aria-expanded={open}
      onClick={() => {
        if (disabled) return
        onToggle()
      }}
      disabled={disabled}
      style={{
        ...triggerStyle,
        opacity: disabled ? 0.55 : 1,
        cursor: disabled ? 'not-allowed' : triggerStyle.cursor,
      }}
    >
      <span style={triggerLabelStyle}>{label}</span>
      <ChevronDownIcon
        size={14}
        style={{
          color: 'var(--text-muted)',
          transform: open ? 'rotate(180deg)' : 'rotate(0deg)',
          transition: 'transform 0.18s ease',
        }}
      />
    </Button>
  )
}

function OptionsListContainer({
  menuRef,
  position,
  options,
  selectedValue,
  onSelect,
}: {
  menuRef: React.RefObject<HTMLDivElement>
  position: { top: number; left: number } | null
  options: InlineDropdownOption[]
  selectedValue: string
  onSelect: (value: string) => void
}) {
  return (
    <div
      ref={menuRef}
      role="listbox"
      style={{
        ...menuContainerStyle,
        top: position?.top ?? 0,
        left: position?.left ?? 0,
        visibility: position ? 'visible' : 'hidden',
      }}
    >
      {options.map((option) => {
        const active = option.value === selectedValue
        return (
          <Button
            key={option.value}
            type="button"
            variant="ghost"
            role="option"
            aria-selected={active}
            onClick={() => onSelect(option.value)}
            style={{
              ...menuOptionStyle,
              background: active ? 'color-mix(in srgb, var(--accent-blue-dim) 65%, transparent)' : 'transparent',
              color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
            }}
          >
            <span style={menuOptionLabelStyle}>{option.label}</span>
            <span style={{ opacity: active ? 1 : 0 }}>
              <CheckIcon size={14} style={{ color: 'var(--accent-blue)' }} />
            </span>
          </Button>
        )
      })}
    </div>
  )
}

const dropdownRootStyle: React.CSSProperties = {
  position: 'relative',
  display: 'inline-flex',
}

const triggerStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '4px 0',
  border: 'none',
  background: 'transparent',
  color: 'var(--text-primary)',
  cursor: 'pointer',
  textAlign: 'left',
  whiteSpace: 'nowrap',
}

const triggerLabelStyle: React.CSSProperties = {
  whiteSpace: 'nowrap',
  fontSize: 'var(--text-xs)',
  fontWeight: 400,
  lineHeight: 1.4,
}

const menuContainerStyle: React.CSSProperties = {
  position: 'fixed',
  zIndex: 120,
  display: 'inline-flex',
  flexDirection: 'column',
  gap: 2,
  padding: 6,
  borderRadius: '14px',
  border: '1px solid color-mix(in srgb, var(--accent-blue) 12%, var(--border))',
  background: 'color-mix(in srgb, var(--task-launcher-surface-bg) 96%, white)',
  boxShadow: '0 18px 42px rgba(0, 0, 0, 0.14)',
  backdropFilter: 'blur(12px)',
}

const menuOptionStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 12,
  padding: '9px 10px',
  border: 'none',
  borderRadius: 10,
  cursor: 'pointer',
  textAlign: 'left',
  whiteSpace: 'nowrap',
}

const menuOptionLabelStyle: React.CSSProperties = {
  whiteSpace: 'nowrap',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.45,
}
