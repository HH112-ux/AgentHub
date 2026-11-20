#!/usr/bin/env node
// gitignore 卫生：源码路径不得被忽略规则吞掉。
// 零依赖纯 ESM，本地与 CI 同一入口：node scripts/checks/gitignore-hygiene.mjs
// 为什么需要：仓库根 `.gitignore` 曾写未锚定的 `data/`，把前端 `src/api/system/dict/data/`
// 整目录挡在仓外——导入时看不出来，直到构建期解析不到 `getDicts` 才暴露。这类丢失不会报错，
// 只会让「已在仓内的代码」永远缺一个模块，必须做成门禁。
// 仓库 public，只报违规位置与命中的规则，不回显文件内容。
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));

// 只在源码根里较真：这些前缀下的文件一旦缺失，运行期与构建期都会踩到。
const SOURCE_ROOTS = ['code/back-end/', 'code/front-end/', 'scripts/', 'e2e/', 'deploy/', 'docs/'];
const SOURCE_EXT = /\.(java|kt|ts|tsx|vue|js|mjs|cjs|sql|yml|yaml|json|xml|properties|md)$/i;

// 明确允许被忽略的目录段：构建产物、依赖、底座解包件与本地证书。
// 出处：AGENTS.md「`.env`、`deploy/*/cert/`、`vendor/` 全部 gitignore」与底座构建约定。
const ALLOWED_SEGMENTS = ['node_modules/', '/dist/', '/target/', 'vendor/', 'deploy/', '/cert/', '/.venv/', '__pycache__/'];

// 编译期生成的类型声明：unplugin-auto-import 与 unplugin-vue-components 每次 dev/build 重写，
// 入库只会让每次构建都产生 diff。出处：`code/front-end/.gitignore`「编译生成的文件」段。
const GENERATED_BASENAMES = ['auto-imports.d.ts', 'components.d.ts'];

// 只信 git 自己的判定，不重复实现忽略语法。
function ignoredFiles() {
  try {
    return execFileSync('git', ['ls-files', '--others', '--ignored', '--exclude-standard', '-d'], {
      cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
    }).split('\n').filter(Boolean);
  } catch (e) {
    console.error(`✗ 无法读取 git 忽略清单：${e.message.split('\n')[0]}`);
    console.error('  （门禁依赖 git 命令，不在无 git 环境里放宽规则）');
    process.exit(1);
  }
}

function matchedRule(file) {
  try {
    // `check-ignore -v` 输出「源:行:模式<TAB>路径」，只取命中规则那一段。
    return execFileSync('git', ['check-ignore', '-v', '--no-index', '--', file], { cwd: ROOT, encoding: 'utf8' }).trim().split('\t')[0];
  } catch {
    return '<未知规则>';
  }
}

const violations = [];
for (const file of ignoredFiles()) {
  const p = relative(ROOT, join(ROOT, file)).replace(/\\/g, '/');
  if (!SOURCE_ROOTS.some((r) => p.startsWith(r))) continue;
  if (!SOURCE_EXT.test(p)) continue;
  // 点开头的文件不是源码（`.flattened-pom.xml` 是 flatten-maven-plugin 的构建产物）。
  const base = p.split('/').pop();
  if (base.startsWith('.')) continue;
  if (GENERATED_BASENAMES.includes(base)) continue;
  if (ALLOWED_SEGMENTS.some((seg) => p.includes(seg))) continue;
  violations.push(`${p} ← ${matchedRule(file)}`);
}

// 未锚定的目录规则是这次事故的成因：`data/` 会命中任意层级的 data 目录。
// 只报「可能吃掉源码」的模式，构建产物类（dist/、target/、node_modules/）保持原样不干涉。
const GITIGNORE = join(ROOT, '.gitignore');
const BROAD_SOURCE_PATTERNS = [];
if (existsSync(GITIGNORE)) {
  const KNOWN_OUTPUT = new Set(['node_modules', 'dist', 'target', 'vendor', 'build', 'out', 'coverage']);
  readFileSync(GITIGNORE, 'utf8').split('\n').forEach((line, i) => {
    const raw = line.trim();
    if (!raw || raw.startsWith('#') || raw.startsWith('!')) return;
    if (raw.startsWith('/')) return;
    const dir = raw.replace(/\/$/, '');
    if (!dir || KNOWN_OUTPUT.has(dir)) return;
    if (dir.startsWith('.')) return; // 点开头是环境文件与系统文件，本就是全树忽略
    if (!/^[a-zA-Z0-9._*-]+$/.test(dir)) return;
    if (raw.includes('*')) return;
    BROAD_SOURCE_PATTERNS.push({ where: `.gitignore:${i + 1}`, raw, dir });
  });
}

if (violations.length || BROAD_SOURCE_PATTERNS.length) {
  console.error('\n✗ gitignore 卫生失败：');
  for (const v of violations) console.error(`  - 源码被忽略规则挡在仓外：${v}`);
  for (const p of BROAD_SOURCE_PATTERNS) {
    console.error(`  - 过宽的忽略模式：${p.where}「${p.raw}」会命中任意层级的 ${p.dir} 目录，请锚定为 /${p.dir}/ 或写具体路径`);
  }
  console.error('\n修法：把模式锚定到根目录（`/data/`）或改用具体路径；确需忽略源码请在门禁豁免处写明出处。');
  process.exit(1);
}

console.log('✓ gitignore 卫生通过（无源码被忽略规则挡在仓外，无未锚定的目录级模式）');
