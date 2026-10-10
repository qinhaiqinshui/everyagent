{
  "name": "{{pluginId}}",
  "version": "{{version}}",
  "private": true,
  "description": "{{description}}",
  "author": "{{author}}",
  "license": "UNLICENSED",
  "type": "module",
  "scripts": {
    "build": "node scripts/build.mjs"
  },
  "devDependencies": {
    "esbuild": "^0.21.5",
    "typescript": "^5.5.4",
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1"
  }
}
