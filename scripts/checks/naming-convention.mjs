#!/usr/bin/env node
// 文件与目录命名规范校验（设计稿 §3.2 强制项）；零依赖纯 ESM。
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));

// 生态强制名：改名即失效，不受 kebab-case 约束。
const ECOSYSTEM_NAMES = new Set([
  'AGENTS.md',
  'README.md',
  'LICENSE',
  '.gitignore',
  '.dockerignore',
  'Dockerfile',
  'Makefile',
  // GitHub 识别 PR 模板靠固定名，不认 kebab 变体；官方文档亦给出下划线形式。
  'pull_request_template.md',
  'PULL_REQUEST_TEMPLATE.md',
  'issue_template.md',
  'ISSUE_TEMPLATE.md',
  'security.md',
  'SECURITY.md',
]);
// §3.2 明示豁免「面向业务读者的基线文档」，保持原名不动。
const BASELINE_DOCS = new Set(['开发总览手册.md']);
// §3.2 接口 e2e 行自身规定 `L{nn}-{kebab-scope}.mjs`：大写 L 为手册层号前缀，属规范内命名。
const PRESCRIBED_PATTERNS = [/^e2e\/http\/L\d{2}-[a-z0-9._-]+\.mjs$/];

const files = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], {
  cwd: REPO_ROOT,
  encoding: 'utf8',
})
  .split('\0')
  .filter(Boolean);

// 出处：设计稿 §3.2「Java 源文件」行 + JLS §7.6——.java 叶子名必须与 public 类型名逐字一致，
// PascalCase 属语言强制，与 Dockerfile 同类。故只对叶子名换用类型名规则，包目录段仍按本表全小写校验。
const JAVA_TYPE_NAME = /^[A-Z][A-Za-z0-9]*\.java$/;

const violations = [];
for (const path of files) {
  const segs = path.split('/');
  const leaf = segs[segs.length - 1];
  if (ECOSYSTEM_NAMES.has(leaf) || BASELINE_DOCS.has(leaf)) continue;
  if (PRESCRIBED_PATTERNS.some((re) => re.test(path))) continue;

  if (leaf.endsWith('.java') && !JAVA_TYPE_NAME.test(leaf)) {
    violations.push(`${path}：Java 源文件名须与 public 类型名一致（JLS §7.6），「${leaf}」不符合 PascalCase`);
  }
  for (const segment of leaf.endsWith('.java') ? segs.slice(0, -1) : segs) {
    if (/[A-Z]/.test(segment)) {
      violations.push(`${path}：段「${segment}」含大写字母——§3.2 要求 ASCII 小写 kebab-case`);
    } else if (/[^\x00-\x7F]/.test(segment)) {
      violations.push(`${path}：段「${segment}」含非 ASCII 字符——§3.2 要求新建文件名一律 ASCII（基线文档除外）`);
    } else if (/[\s_]/.test(segment)) {
      violations.push(`${path}：段「${segment}」含空格或下划线——§3.2 要求 kebab-case 连字符`);
    }
  }
}

if (violations.length) {
  console.error(`\n✗ 命名规范校验失败 ${violations.length} 项：`);
  for (const v of violations) console.error(`  - ${v}`);
  console.error('\n如属生态强制名或基线文档，请在 scripts/checks/naming-convention.mjs 的豁免表中登记并写明出处。');
  process.exit(1);
}
console.log(`✓ 命名规范校验通过（${files.length} 个文件纳入检查）`);
