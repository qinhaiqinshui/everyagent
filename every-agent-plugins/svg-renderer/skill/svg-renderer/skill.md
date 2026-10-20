---
name: SVG 渲染
description: 当你需要输出 SVG 图形（流程图、架构图、数据示意图等）时，使用本技能约定标签。
---

# SVG 渲染

当你需要在回复中输出 SVG 图形（如流程图、架构图、数据示意图、图标等）时，使用 `<svg-image>` 标签包裹完整的 SVG 源码。渲染器会自动识别该标签并将其渲染为可交互的 SVG 图形。

## 基本用法

直接用 `<svg-image>` 标签包裹完整的 SVG 源码：

```
<svg-image>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200">
  <rect x="10" y="10" width="380" height="180" rx="8" fill="#f0f4ff" stroke="#4a6cf7" stroke-width="2"/>
  <circle cx="200" cy="100" r="40" fill="#4a6cf7"/>
  <text x="200" y="106" text-anchor="middle" fill="#ffffff" font-size="16">示例</text>
</svg>
</svg-image>
```

**关键规则：**

- 标签内**直接放 SVG 源码**，以 `<svg>` 开头、`</svg>` 结尾
- **不要加 ` ``` ` 代码围栏**——围栏内的内容不会被识别为 SVG

## 流式渲染友好写法（重要）

渲染器支持**流式渐进渲染**：AI 输出过程中，SVG 图形会自上而下逐步加载显示（无需等待全部输出完成）。为获得最佳渐进观感，书写时遵守：

- **`<defs>` 前置**：`<marker>`（箭头）、渐变等定义必须放在 `<svg>` 的开头（第一个子元素），**不要放在结尾**。后置的 defs 在渐进渲染期间引用会暂时失效（`url(#arrow)` 找不到定义），导致箭头、渐变直到最后才突然出现，破坏渐进观感。
- **`viewBox` 必写且放在 `<svg>` 起始标签内**：保证图形在输出过程中任意时刻都按正确比例显示，容器高度稳定不跳动。
- **元素按视觉顺序自上而下书写**：渐进加载时图形从顶部逐渐呈现，与阅读顺序一致。

## 完整示例

下面是一个简单流程图的完整输出示例（注意 `<defs>` 前置）：

用户问：「帮我画一个用户登录流程图」

你的回复：

```
以下是用户登录流程图：

<svg-image>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 280">
  <defs>
    <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
      <polygon points="0,0 8,3 0,6" fill="#333"/>
    </marker>
  </defs>
  <rect x="100" y="20" width="120" height="40" rx="6" fill="#4a6cf7"/>
  <text x="160" y="45" text-anchor="middle" fill="#fff" font-size="14">开始</text>
  <line x1="160" y1="60" x2="160" y2="90" stroke="#333" stroke-width="1.5" marker-end="url(#arrow)"/>
  <rect x="80" y="90" width="160" height="40" rx="6" fill="#e8ecf8" stroke="#4a6cf7" stroke-width="1.5"/>
  <text x="160" y="115" text-anchor="middle" fill="#333" font-size="13">输入账号密码</text>
  <line x1="160" y1="130" x2="160" y2="160" stroke="#333" stroke-width="1.5" marker-end="url(#arrow)"/>
  <polygon points="160,160 240,200 160,240 80,200" fill="#fff8e1" stroke="#f59e0b" stroke-width="1.5"/>
  <text x="160" y="205" text-anchor="middle" fill="#333" font-size="13">验证通过?</text>
  <line x1="160" y1="240" x2="160" y2="270" stroke="#333" stroke-width="1.5" marker-end="url(#arrow)"/>
  <rect x="100" y="260" width="120" height="0" rx="6" fill="#22c55e"/>
</svg>
</svg-image>

流程说明：用户输入账号密码后，系统进行验证，验证通过则登录成功。
```

## 注意事项

1. **必须包含命名空间**：SVG 根元素必须包含 `xmlns="http://www.w3.org/2000/svg"`，否则渲染可能失败。

2. **建议设置 viewBox**：建议为 SVG 设置 `viewBox` 属性（如 `viewBox="0 0 400 200"`），以保证图形在不同尺寸下正确缩放。同时配合 `width`/`height` 或使用百分比宽度。

3. **禁止使用 `<script>` 标签**：出于安全考虑，SVG 中的 `<script>` 标签会被过滤移除。

4. **禁止 `on*` 事件属性**：如 `onclick`、`onload`、`onmouseover` 等事件属性会被安全过滤。

5. **禁止外部引用**：不要在 SVG 中使用指向外部资源的 `href="http://..."` 或 `xlink:href="http://..."` 引用，这些外部链接会被过滤。

6. **标签外内容不受影响**：`<svg-image>` 标签之外的正常文字内容不受影响，会照常以 Markdown 渲染输出。
