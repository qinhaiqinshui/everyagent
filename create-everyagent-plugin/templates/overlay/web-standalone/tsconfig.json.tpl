// ea: standalone 形态 TS 配置：paths 把 @everyagent/plugin-api 指向 vendor 类型副本
{
  "compilerOptions": {
    // 关键项与宿主 every-agent-web/tsconfig.json 对齐：moduleResolution bundler / jsx react-jsx / strict。
    // target 提到 ES2022，与本工程 esbuild 打包 target 一致。
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    // react / react-dom 运行时由宿主 window.__EA_* 全局提供（esbuild external 不打包），
    // 但 tsc/编辑器仍需类型：devDependencies 已预置 @types/react(-dom)，paths 在此映射，
    // 使 npm install && npx tsc --noEmit 开箱全绿。antd/@ant-design/icons 模板未用到，
    // 若你的插件要 import 它们，请自行安装对应包（宿主运行时同名单全局可用）。
    "types": [],
    "baseUrl": ".",
    "paths": {
      // @everyagent/plugin-api 未发布到 npm，standalone 工程自带类型副本
      // （vendor 下该文件逐字节复制自 every-agent-plugin-api/js/index.ts；升级 API 时请重新复制）。
      "@everyagent/plugin-api": ["./vendor/@everyagent/plugin-api/index.d.ts"],
      "react": ["./node_modules/@types/react"],
      "react-dom": ["./node_modules/@types/react-dom"],
      "react/jsx-runtime": ["./node_modules/@types/react/jsx-runtime"]
    }
  },
  "include": ["web"]
}
