#!/usr/bin/env node
// 菜单 component 路径与前端页面文件一一对应；零依赖纯 ESM，本地与 CI 同一入口复跑。
// 门禁来源：AGENTS.md「新增接口必须登记 ai:{模块}:{动作} 权限标识和菜单脚本」、设计稿 §3.2 前端页面命名行。
// 为什么需要：菜单里的 component 是运行期拼出来的字符串，写错只会在点开菜单时白屏，
// 而 SQL 与前端目录分处两条任务线，改名时极易只改一边——只有机械断言拦得住。
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const MENU_SQL = 'code/back-end/script/sql/%s/agenthub-ai-menu.sql';
const VIEWS_ROOT = 'code/front-end/src/views';
const DIALECTS = ['postgres', 'mysql'];

const failures = [];
const checked = new Set();

// 与 CI 的工件探测同口径（`ci.yml` 的 `frontend=code/front-end/package.json`）：
// 前端未入仓时无从比对，显式跳过而不是让本地跑出一个 CI 不复现的红。
if (!existsSync(`${REPO_ROOT}code/front-end/package.json`)) {
  console.log('⚠ 跳过菜单 component 校验：code/front-end/package.json 未入仓，无页面可比对');
  process.exit(0);
}

for (const dialect of DIALECTS) {
  const rel = MENU_SQL.replace('%s', dialect);
  if (!existsSync(`${REPO_ROOT}${rel}`)) {
    failures.push(`${rel}：菜单脚本缺失`);
    continue;
  }
  const text = readFileSync(`${REPO_ROOT}${rel}`, 'utf8');
  for (const [i, line] of text.split('\n').entries()) {
    if (!/insert\s+into\s+sys_menu/i.test(line)) continue;
    for (const [m, component] of line.matchAll(/'((?:ai|system|monitor|workflow)\/[a-z0-9/._-]+\/index)'/gi)) {
      checked.add(component);
      const file = `${VIEWS_ROOT}/${component}.vue`;
      if (!existsSync(`${REPO_ROOT}${file}`)) {
        failures.push(`${rel}:${i + 1}：菜单 component「${component}」没有对应页面文件 ${file}`);
      }
      void m;
    }
  }
}

if (failures.length) {
  console.error(`\n✗ 菜单 component 校验失败 ${failures.length} 项：`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`✓ 菜单 component 校验通过（${checked.size} 个 AI/系统页面路径全部命中前端页面文件）`);
