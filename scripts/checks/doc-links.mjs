#!/usr/bin/env node
// 文档交叉引用校验；零依赖纯 ESM，本地与 CI 同一入口复跑。
// 门禁来源：AGENTS.md「新增工具、配置项或接口时同步更新 docs/」、设计稿 §3.2 文档命名行。
// 为什么需要：仓库内文档全靠相对路径互指，一旦有人写成 file:/// 绝对路径或把文件改名，
// GitHub 页面上仍是死链，而本机 VS Code 里点开正常——只有机械断言拦得住。
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));

const failures = [];
const fail = (line, msg) => failures.push(`${line}：${msg}`);

const listMarkdown = () =>
  execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: REPO_ROOT, encoding: 'utf8' })
    .split('\0')
    .filter((f) => f.endsWith('.md'));

// 去掉围栏代码块：示例里的伪链接不该参与校验。
const stripFences = (text) => text.replace(/^```[\s\S]*?^```/gm, '');

// GitHub 锚点口径：转小写、删标点、空白转连字符；CJK 属 \p{L} 保留。
const slugify = (heading) =>
  heading
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .replace(/\s+/g, '-');

const headingsOf = (text) => new Set([...text.matchAll(/^#{1,6}\s+(.+?)\s*$/gm)].map((m) => slugify(m[1])));

// 目标允许为空：[]() 是最常见的漏填，正则若要求 ≥1 字符就会静默漏过。
const LINK_RE = /(!?)\[[^\]]*\]\(([^)\s]*)\)/g;

const checkFile = (rel) => {
  const text = readFileSync(path.join(REPO_ROOT, rel), 'utf8');
  const body = stripFences(text);
  const localHeadings = headingsOf(body);
  const headingCache = new Map();
  let links = 0;

  for (const m of body.matchAll(LINK_RE)) {
    const raw = m[2];
    links += 1;
    const line = `${rel}`;

    if (/^file:\/\//i.test(raw)) {
      fail(line, `使用了 file:// 绝对链接，他人机器上必死：${raw.slice(0, 80)}`);
      continue;
    }
    if (/^\/[A-Za-z]/.test(raw) || /^~\//.test(raw)) {
      fail(line, `使用了机器相关的绝对路径：${raw.slice(0, 80)}`);
      continue;
    }
    if (/^(https?:|mailto:|data:)/i.test(raw)) continue;

    const [target, frag] = [raw.split('#')[0], raw.split('#')[1]];
    if (!target) {
      if (!frag) {
        fail(line, '空链接目标 []()');
        continue;
      }
      if (!localHeadings.has(slugify(decodeURIComponent(frag)))) {
        fail(line, `页内锚点未命中任何标题：#${frag}`);
      }
      continue;
    }

    const abs = path.resolve(path.dirname(path.join(REPO_ROOT, rel)), decodeURIComponent(target));
    if (!existsSync(abs)) {
      fail(line, `相对死链：${target}`);
      continue;
    }
    if (frag && abs.endsWith('.md')) {
      if (!headingCache.has(abs)) headingCache.set(abs, headingsOf(stripFences(readFileSync(abs, 'utf8'))));
      if (!headingCache.get(abs).has(slugify(decodeURIComponent(frag)))) {
        fail(line, `跨文件锚点未命中：${target}#${frag}`);
      }
    }
  }
  return links;
};

const main = () => {
  const files = listMarkdown();
  if (!files.length) {
    console.log('· 仓库内无 markdown 文件，跳过');
    return;
  }
  let total = 0;
  for (const rel of files) total += checkFile(rel);

  if (failures.length) {
    console.error(`✗ 文档交叉引用校验失败（${failures.length} 项 / ${files.length} 个文件、${total} 条链接）`);
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(`✓ 文档交叉引用校验通过（${files.length} 个文件、${total} 条链接：无绝对路径、无死链、锚点全部命中）`);
};

main();
