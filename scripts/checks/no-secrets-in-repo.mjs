#!/usr/bin/env node
// 密钥入库防护（仓库为 public，CI 日志公网可见）；门禁来源：设计稿 §8.3「密钥不入库不入仓」与 AGENTS.md。
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const BINARY_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.pdf', '.zip', '.gz', '.jar', '.woff', '.woff2', '.ttf', '.mp3', '.mp4']);
// 特征串以拼接构造，避免本脚本扫描自身时误报。
const SIGNATURES = [
  { name: 'GitHub token', re: new RegExp(`gh[pousr]${'_'}[0-9A-Za-z]{36,}`) },
  { name: 'GitHub fine-grained token', re: new RegExp(`github${'_'}pat${'_'}[0-9A-Za-z_]{20,}`) },
  { name: 'AWS access key id', re: /AKIA[0-9A-Z]{16}/ },
  { name: '私钥文件体', re: /-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----/ },
  { name: 'Slack token', re: /xox[baprs]-[0-9A-Za-z-]{10,}/ },
];

// 出处：Vite 按 `--mode` 固定加载 `.env.[mode]`，本仓库的构建脚本写死 `vite build --mode production`，
// 文件名改名即失效。这两个文件只承载页面标题、接口前缀等构建期配置；RSA 私钥等秘密必须由 CI Secret
// 在构建时注入，不得以真实值提交。免检仅限文件名，下方的内容特征扫描照常执行。
const ECOSYSTEM_ENV_FILES = new Set(['code/front-end/.env.development', 'code/front-end/.env.production']);

const files = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], {
  cwd: REPO_ROOT,
  encoding: 'utf8',
})
  .split('\0')
  .filter(Boolean);

const violations = [];
for (const path of files) {
  const base = path.split('/').pop();
  const ext = base.includes('.') ? base.slice(base.lastIndexOf('.')) : '';

  if (['.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.kdbx', '.asc'].includes(ext)) {
    violations.push(`${path}：密钥库/私钥扩展名 ${ext} 不得入库`);
    continue;
  }
  if (/^\.env/i.test(base) && !/\.(example|sample|template|txt)$/.test(base) && !ECOSYSTEM_ENV_FILES.has(path)) {
    violations.push(`${path}：环境密钥文件不得入库，请放 .env.local（已 gitignore）或走页面密文落库`);
    continue;
  }
  if (BINARY_EXTENSIONS.has(ext.toLowerCase())) continue;
  // 校验脚本自身含特征串构造，跳过自扫。
  if (path.startsWith('scripts/checks/')) continue;

  let content;
  try {
    content = readFileSync(`${REPO_ROOT}${path}`, 'utf8');
  } catch {
    continue;
  }
  for (const [i, line] of content.split('\n').entries()) {
    for (const sig of SIGNATURES) {
      if (sig.re.test(line)) {
        // 只报位置与类型，不回显命中内容——CI 日志公网可见。
        violations.push(`${path}:${i + 1} 命中「${sig.name}」特征，请改用环境变量或页面密文配置`);
      }
    }
  }
}

if (violations.length) {
  console.error(`\n✗ 密钥入库防护失败 ${violations.length} 项：`);
  for (const v of violations) console.error(`  - ${v}`);
  process.exit(1);
}
console.log(`✓ 密钥入库防护通过（${files.length} 个文件纳入扫描）`);
