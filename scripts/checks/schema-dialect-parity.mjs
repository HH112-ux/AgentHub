#!/usr/bin/env node
// 双方言 SQL 一致性与幂等校验；零依赖纯 ESM，本地与 CI 同一入口复跑。
// 门禁来源：AGENTS.md「修改数据库结构或种子数据时同步维护 PostgreSQL 与 MySQL 脚本并保持幂等」、设计稿 §5 与 §10.2。
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const SQL_ROOT = `${REPO_ROOT}code/back-end/script/sql`;
const DIALECTS = ['postgres', 'mysql'];
const DDL = 'agenthub-ai-ddl.sql';
// 设计稿 §5 与 §12.3 把「40 表齐备」定为 FAIL 级健康项，故此处为契约期望值而非观测值。
const EXPECTED_TABLES = 40;

const failures = [];
const skipped = [];

const statementsOf = (relPath) =>
  readFileSync(`${SQL_ROOT}/${relPath}`, 'utf8')
    .split(/;\s*(?:\n|$)/)
    .map((s) => s.replace(/^\s*--[^\n]*\n/gm, '').trim())
    .filter(Boolean);

const parse = (relPath) => {
  const tables = [];
  const inserts = [];
  for (const [i, s] of statementsOf(relPath).entries()) {
    const stmt = s.replace(/\s+/g, ' ');
    const line = `${relPath} #${i + 1}`;

    if (/^create\s+table\b/i.test(stmt)) {
      const idempotent = /^create\s+table\s+if\s+not\s+exists\b/i.test(stmt);
      const name = (stmt.match(/^create\s+table\s+(?:if\s+not\s+exists\s+)?[`"[]?([a-z0-9_.]+)/i) || [])[1];
      tables.push({ line, name: name ? name.toLowerCase().split('.').pop() : undefined, idempotent });
    }
    if (/^create\s+(unique\s+)?index\b/i.test(stmt)) inserts.push({ line, kind: 'index', stmt });
    if (/^insert\b/i.test(stmt)) inserts.push({ line, kind: 'insert', stmt });
    if (/^\s*(drop|alter|truncate|delete\s+from)\b/i.test(stmt)) inserts.push({ line, kind: 'destructive', stmt });
  }
  return { tables, inserts };
};

if (!existsSync(SQL_ROOT)) {
  skipped.push('code/back-end/script/sql 不存在——底座脚本尚未入仓，本门禁在其出现后自动生效');
} else {
  const perDialect = {};
  for (const dialect of DIALECTS) {
    const dir = `${SQL_ROOT}/${dialect}`;
    if (!existsSync(dir)) {
      failures.push(`${dialect}：方言目录缺失`);
      continue;
    }
    perDialect[dialect] = {};
    for (const file of readdirSync(dir).filter((f) => f.endsWith('.sql'))) {
      const rel = `${dialect}/${file}`;
      const { tables, inserts } = parse(rel);
      perDialect[dialect][file] = tables;

      for (const t of tables) {
        if (!t.name) failures.push(`${t.line}：CREATE TABLE 无法解析表名`);
        else if (!t.idempotent) failures.push(`${t.line}：表 ${t.name} 缺少 IF NOT EXISTS，重复执行会失败`);
      }
      for (const { line, kind, stmt } of inserts) {
        if (kind === 'destructive') {
          failures.push(`${line}：${stmt.split(' ')[0].toUpperCase()} 破坏性语句——脚本必须可重复执行`);
        } else if (kind === 'index') {
          if (dialect === 'postgres' && !/if\s+not\s+exists/i.test(stmt)) {
            failures.push(`${line}：CREATE INDEX 缺少 IF NOT EXISTS`);
          }
          if (dialect === 'mysql') {
            failures.push(`${line}：MySQL 侧索引须写在 CREATE TABLE 内的 KEY / UNIQUE KEY，独立 CREATE INDEX 不幂等`);
          }
        } else if (!/on\s+conflict|insert\s+ignore|on\s+duplicate\s+key/i.test(stmt)) {
          failures.push(`${line}：INSERT 无幂等守卫（PG 用 ON CONFLICT，MySQL 用 INSERT IGNORE / ON DUPLICATE KEY UPDATE）`);
        }
      }
    }
  }

  for (const [dialect, files] of Object.entries(perDialect)) {
    const names = new Set((files[DDL] ?? []).map((t) => t.name).filter(Boolean));
    if (names.size !== EXPECTED_TABLES) {
      failures.push(`${dialect}：DDL 解析到 ${names.size} 张表，设计稿 §5 期望 ${EXPECTED_TABLES} 张`);
    }
    perDialect[dialect].__set = names;
  }

  const [pg, my] = [perDialect.postgres?.__set, perDialect.mysql?.__set];
  if (pg && my) {
    for (const [a, b, an, bn] of [
      [pg, my, 'postgres', 'mysql'],
      [my, pg, 'mysql', 'postgres'],
    ]) {
      for (const name of [...a].filter((n) => !b.has(n))) failures.push(`${an} 有表 ${name}，${bn} 没有——双方言必须同步`);
    }
  }

  for (const file of ['agenthub-ai-seed.sql', 'agenthub-ai-menu.sql']) {
    if (!perDialect.postgres?.[file] || !perDialect.mysql?.[file]) continue;
    const count = (dialect) => statementsOf(`${dialect}/${file}`).filter((s) => /^insert\b/i.test(s.replace(/\s+/g, ' '))).length;
    const [a, b] = [count('postgres'), count('mysql')];
    if (a !== b) failures.push(`${file}：postgres ${a} 条 INSERT vs mysql ${b} 条——种子与菜单数据必须双方言等量`);
  }
}

for (const s of skipped) console.log(`· SKIP ${s}`);
if (failures.length) {
  console.error(`\n✗ 双方言 SQL 校验失败 ${failures.length} 项：`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`✓ 双方言 SQL 校验通过（${skipped.length ? '跳过' : `${EXPECTED_TABLES} 表一致、幂等守卫齐备`}）`);
