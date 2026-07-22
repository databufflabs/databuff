#!/usr/bin/env node
/**
 * Copy mermaid UMD build into public/vendor for runtime <script> load.
 * Kept out of Vite's dependency graph (avoids AntV manualChunks cycles).
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const src = path.join(root, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js')
const destDir = path.join(root, 'public', 'vendor')
const dest = path.join(destDir, 'mermaid.min.js')

if (!fs.existsSync(src)) {
  console.error(`[copy-mermaid-vendor] missing ${src}; run yarn install first`)
  process.exit(1)
}

fs.mkdirSync(destDir, { recursive: true })
fs.copyFileSync(src, dest)
console.log(`[copy-mermaid-vendor] ${path.relative(root, dest)} (${fs.statSync(dest).size} bytes)`)
