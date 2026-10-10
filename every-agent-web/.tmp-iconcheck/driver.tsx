import React from 'react'
import { writeFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { GitIcon } from '../../every-agent-plugins/git/web/icons'
import { ExtensionIcon } from '../../every-agent-plugins/plugin-manager/web/icons'

const gitSvg = renderToStaticMarkup(React.createElement(GitIcon))
const extSvg = renderToStaticMarkup(React.createElement(ExtensionIcon))
writeFileSync('.tmp-iconcheck/icons.html', `<!doctype html><html><head><meta charset="utf-8"><style>
body{margin:0;background:#1e1e1e;color:#ccc;font-family:sans-serif}
.bar{display:flex;flex-direction:column;gap:6px;align-items:center;padding:10px 0;width:44px}
.iconwrap{display:inline-flex;align-items:center;justify-content:center;font-size:20px;line-height:1}
</style></head><body><div class="bar">
<div class="iconwrap" id="git">${gitSvg}</div>
<div class="iconwrap" id="ext">${extSvg}</div>
</div></body></html>`)
console.log('GIT SVG:', gitSvg)
console.log('EXT SVG:', extSvg)
