import React from 'react'
import type { IconProps } from './types'

type AppSvgProps = React.PropsWithChildren<IconProps>

const baseSvgStyle: React.CSSProperties = {
  display: 'block',
}

const APP_SVG_OUTER_SIZE = 22
const APP_SVG_DRAWING_VIEWBOX_SIZE = 16
const APP_SVG_CANVAS_LAYOUT_SIZE = 20
const APP_SVG_CANVAS_OFFSET = (APP_SVG_OUTER_SIZE - APP_SVG_CANVAS_LAYOUT_SIZE) / 2
const APP_SVG_STROKE_WIDTH = 1.6

export default function AppSvg({ size = 22, color, className, children }: AppSvgProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${APP_SVG_OUTER_SIZE} ${APP_SVG_OUTER_SIZE}`}
      aria-hidden="true"
      className={className}
      style={{
        ...baseSvgStyle,
        ...(color ? { color } : null),
      }}
    >
      <svg
        x={APP_SVG_CANVAS_OFFSET}
        y={APP_SVG_CANVAS_OFFSET}
        width={APP_SVG_CANVAS_LAYOUT_SIZE}
        height={APP_SVG_CANVAS_LAYOUT_SIZE}
        viewBox={`0 0 ${APP_SVG_DRAWING_VIEWBOX_SIZE} ${APP_SVG_DRAWING_VIEWBOX_SIZE}`}
        preserveAspectRatio="xMidYMid meet"
        fill="none"
        stroke="currentColor"
        strokeWidth={APP_SVG_STROKE_WIDTH}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {children}
      </svg>
    </svg>
  )
}
