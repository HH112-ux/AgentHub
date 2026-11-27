#!/usr/bin/env node
// dist 占位密钥复验：dist 构建产物里检出 .env.production 的占位符或示例 RSA 密钥即失败。
// 门禁来源：AGENTS.md「密钥只在服务端」与设计稿 §2.3 前后端密钥契约——把占位符打进 dist 会导致登录解密失败。
// 仓库为 public 且 CI 日志公网可见：只报位置与键名，不回显值。
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const ENV_PATH = join(REPO_ROOT, 'code/front-end/.env.production');
const DIST_ROOT = join(REPO_ROOT, 'code/front-end/dist');
const TEXT_EXTENSIONS = new Set(['.js', '.css', '.html', '.json']);

function readPlaceholderKeys() {
  const keys = {};
  for (const line of readFileSync(ENV_PATH, 'utf8').split('\n')) {
    const m = line.match(/^\s*VITE_APP_RSA_(PUBLIC_KEY|PRIVATE_KEY)\s*=\s*(.+?)\s*$/);
    if (m) {
      const value = m[2].trim().replace(/^['"]/, '').replace(/['"]$/, '');
      if (value) keys[m[1]] = value;
    }
  }
  return keys;
}

function collectTextFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      collectTextFiles(full, out);
    } else {
      const dot = name.lastIndexOf('.');
      if (dot !== -1 && TEXT_EXTENSIONS.has(name.slice(dot))) out.push(full);
    }
  }
  return out;
}

const keys = readPlaceholderKeys();
if (!keys.PUBLIC_KEY || !keys.PRIVATE_KEY) {
  console.error('✗ dist 占位密钥复验失败：未从 code/front-end/.env.production 解析到 RSA 密钥，无法比对');
  process.exit(1);
}

let files;
try {
  files = collectTextFiles(DIST_ROOT);
} catch {
  console.log('✓ dist 占位密钥复验通过（跳过：code/front-end/dist 不存在，未构建或未入仓）');
  process.exit(0);
}

const violations = [];
for (const file of files) {
  const content = readFileSync(file, 'utf8');
  for (const [name, value] of Object.entries(keys)) {
    if (content.includes(value)) {
      // 只报位置与键名，不回显命中内容——CI 日志公网可见。
      violations.push(`${relative(REPO_ROOT, file)} 检出占位或示例 VITE_APP_RSA_${name}`);
    }
  }
}

if (violations.length) {
  console.error(`\n✗ dist 占位密钥复验失败 ${violations.length} 项（占位符或示例密钥构建的 dist 无法与生产密钥配对）：`);
  for (const v of violations) console.error(`  - ${v}`);
  console.error('  修复：构建时注入自有密钥对，如 VITE_APP_RSA_PUBLIC_KEY=… VITE_APP_RSA_PRIVATE_KEY=… pnpm build');
  process.exit(1);
}
console.log(`✓ dist 占位密钥复验通过（${files.length} 个文本产物不含占位或示例密钥）`);
