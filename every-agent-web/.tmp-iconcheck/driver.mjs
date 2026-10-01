// .tmp-iconcheck/driver.tsx
import React from "react";
import { writeFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";

// ../every-agent-plugins/git/web/icons.tsx
import { jsx, jsxs } from "react/jsx-runtime";
var baseSvgStyle = {
  display: "block"
};
var APP_SVG_OUTER_SIZE = 22;
var APP_SVG_DRAWING_VIEWBOX_SIZE = 16;
var APP_SVG_CANVAS_LAYOUT_SIZE = 20;
var APP_SVG_CANVAS_OFFSET = (APP_SVG_OUTER_SIZE - APP_SVG_CANVAS_LAYOUT_SIZE) / 2;
var APP_SVG_STROKE_WIDTH = 1.6;
function AppSvg({ size = 22, color, className, children }) {
  return /* @__PURE__ */ jsx(
    "svg",
    {
      width: size,
      height: size,
      viewBox: `0 0 ${APP_SVG_OUTER_SIZE} ${APP_SVG_OUTER_SIZE}`,
      "aria-hidden": "true",
      className,
      style: {
        ...baseSvgStyle,
        ...color ? { color } : null
      },
      children: /* @__PURE__ */ jsx(
        "svg",
        {
          x: APP_SVG_CANVAS_OFFSET,
          y: APP_SVG_CANVAS_OFFSET,
          width: APP_SVG_CANVAS_LAYOUT_SIZE,
          height: APP_SVG_CANVAS_LAYOUT_SIZE,
          viewBox: `0 0 ${APP_SVG_DRAWING_VIEWBOX_SIZE} ${APP_SVG_DRAWING_VIEWBOX_SIZE}`,
          preserveAspectRatio: "xMidYMid meet",
          fill: "none",
          stroke: "currentColor",
          strokeWidth: APP_SVG_STROKE_WIDTH,
          strokeLinecap: "round",
          strokeLinejoin: "round",
          children
        }
      )
    }
  );
}
function GitIcon({ size = 22, color, className }) {
  return /* @__PURE__ */ jsxs(AppSvg, { size, color, className, children: [
    /* @__PURE__ */ jsx("circle", { cx: "3.2", cy: "3.3", r: "1.4" }),
    /* @__PURE__ */ jsx("circle", { cx: "12.8", cy: "7.2", r: "1.4" }),
    /* @__PURE__ */ jsx("circle", { cx: "3.2", cy: "12.7", r: "1.4" }),
    /* @__PURE__ */ jsx("path", { d: "M3.2 4.7V11.3" }),
    /* @__PURE__ */ jsx("path", { d: "M4.5 4.2L11.3 6.9" }),
    /* @__PURE__ */ jsx("path", { d: "M4.4 11.8L11 8.3" })
  ] });
}

// ../every-agent-plugins/plugin-manager/web/icons.tsx
import { jsx as jsx2 } from "react/jsx-runtime";
var baseSvgStyle2 = {
  display: "block"
};
var APP_SVG_OUTER_SIZE2 = 22;
var APP_SVG_DRAWING_VIEWBOX_SIZE2 = 16;
var APP_SVG_CANVAS_LAYOUT_SIZE2 = 20;
var APP_SVG_CANVAS_OFFSET2 = (APP_SVG_OUTER_SIZE2 - APP_SVG_CANVAS_LAYOUT_SIZE2) / 2;
var APP_SVG_STROKE_WIDTH2 = 1.6;
function AppSvg2({ size = 22, color, className, children }) {
  return /* @__PURE__ */ jsx2(
    "svg",
    {
      width: size,
      height: size,
      viewBox: `0 0 ${APP_SVG_OUTER_SIZE2} ${APP_SVG_OUTER_SIZE2}`,
      "aria-hidden": "true",
      className,
      style: {
        ...baseSvgStyle2,
        ...color ? { color } : null
      },
      children: /* @__PURE__ */ jsx2(
        "svg",
        {
          x: APP_SVG_CANVAS_OFFSET2,
          y: APP_SVG_CANVAS_OFFSET2,
          width: APP_SVG_CANVAS_LAYOUT_SIZE2,
          height: APP_SVG_CANVAS_LAYOUT_SIZE2,
          viewBox: `0 0 ${APP_SVG_DRAWING_VIEWBOX_SIZE2} ${APP_SVG_DRAWING_VIEWBOX_SIZE2}`,
          preserveAspectRatio: "xMidYMid meet",
          fill: "none",
          stroke: "currentColor",
          strokeWidth: APP_SVG_STROKE_WIDTH2,
          strokeLinecap: "round",
          strokeLinejoin: "round",
          children
        }
      )
    }
  );
}
function ExtensionIcon({ size = 22, color, className }) {
  return /* @__PURE__ */ jsx2(AppSvg2, { size, color, className, children: /* @__PURE__ */ jsx2("g", { transform: "translate(8 8) scale(1.16667) translate(-8 -8)", fill: "currentColor", stroke: "none", children: /* @__PURE__ */ jsx2(
    "path",
    {
      d: "M2 2.5a.5.5 0 0 1 .5-.5h4.379a.5.5 0 0 1 .353.146l1.06 1.061a.5.5 0 0 0 .708 0l1.06-1.06A.5.5 0 0 1 10.5 2H13.5a.5.5 0 0 1 .5.5v3.379a.5.5 0 0 1-.146.353l-1.061 1.06a.5.5 0 0 0 0 .708l1.061 1.06A.5.5 0 0 1 14 9.621V13.5a.5.5 0 0 1-.5.5h-3.379a.5.5 0 0 1-.353-.146l-1.06-1.061a.5.5 0 0 0-.708 0l-1.06 1.061A.5.5 0 0 1 6.621 14H2.5a.5.5 0 0 1-.5-.5V9.621a.5.5 0 0 1 .146-.353l1.061-1.06a.5.5 0 0 0 0-.708L2.146 6.439A.5.5 0 0 1 2 6.086V2.5Z"
    }
  ) }) });
}

// .tmp-iconcheck/driver.tsx
var gitSvg = renderToStaticMarkup(React.createElement(GitIcon));
var extSvg = renderToStaticMarkup(React.createElement(ExtensionIcon));
writeFileSync(".tmp-iconcheck/icons.html", `<!doctype html><html><head><meta charset="utf-8"><style>
body{margin:0;background:#1e1e1e;color:#ccc;font-family:sans-serif}
.bar{display:flex;flex-direction:column;gap:6px;align-items:center;padding:10px 0;width:44px}
.iconwrap{display:inline-flex;align-items:center;justify-content:center;font-size:20px;line-height:1}
</style></head><body><div class="bar">
<div class="iconwrap" id="git">${gitSvg}</div>
<div class="iconwrap" id="ext">${extSvg}</div>
</div></body></html>`);
console.log("GIT SVG:", gitSvg);
console.log("EXT SVG:", extSvg);
