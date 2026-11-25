/**
 * 画布节点几何常量——节点矩形尺寸的唯一事实源。
 *
 * 从 WorkflowCanvas 抽出（原先就地 export）：「自动布局」算法（autoLayout.ts）
 * 需要按节点尺寸推导间距，但算法模块保持纯 TS、零 React 依赖（便于脱离 DOM
 * 自测与复用），因此尺寸常量落到本无依赖模块，画布与布局两侧共用。
 */
export const NODE_W = 180
export const NODE_H = 64
