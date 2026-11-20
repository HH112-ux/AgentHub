#!/usr/bin/env node
// 部署编排静态校验；零依赖纯 ESM，本地与 CI 同一入口复跑。
// 门禁来源：设计稿 §2.1 各层位置、§2.3 部署与安全约束、§3.2 部署命名行，手册 §10.1 端口口径。
// 定位：行为类实测（真实 initdb、SSE 不缓冲、跳转与缓存）需要 Docker 与证书，口径见 docs/configuration.md §4；
// 本脚本守的是「compose / nginx 被改过之后，清单与关键行为断言是否漂移」，不起容器、不连真库。
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const DEPLOY_ROOT = `${REPO_ROOT}deploy`;

// 手册 §10.1 冻结的双栈入口与后端端口；两栈同机并存全靠它区分，新增栈必须在此登记一行。
const STACKS = {
  'deploy-pg': { webHttp: '80', webHttps: '443', app: '8080' },
  'deploy-mysql': { webHttp: '81', webHttps: '8443', app: '8081' },
};

const failures = [];
const notes = [];
const fail = (line, msg) => failures.push(`${line}：${msg}`);

const read = (rel) => {
  const p = `${DEPLOY_ROOT}/${rel}`;
  if (!existsSync(p)) {
    fail(rel, '文件缺失');
    return '';
  }
  return readFileSync(p, 'utf8');
};

// 抽出某个 location 块（花括号配平），便于断言只作用于该路径的指令。
const locationBlock = (conf, pathRegex) => {
  const m = new RegExp(`location\\s+${pathRegex}\\s*\\{`).exec(conf);
  if (!m) return '';
  let depth = 0;
  for (let i = conf.indexOf('{', m.index); i < conf.length; i += 1) {
    if (conf[i] === '{') depth += 1;
    if (conf[i] === '}') depth -= 1;
    if (depth === 0) return conf.slice(m.index, i + 1);
  }
  return '';
};

// ── 1. compose 引用的变量必须在 .env.example 有交代 ──────────────────
// 口径：${VAR} 与 ${VAR:?提示} 属外部输入，必须登记；${VAR:-默认} 自带兜底，不强制登记。
// 上游镜像强制读取的名字（POSTGRES_*/MYSQL_*/REDIS_PASSWORD/…）是 §3.1 前缀规则的例外，这里一视同仁要求登记。
const checkEnvCoverage = (stack, compose, env) => {
  const declared = new Set(
    env
      .split('\n')
      .filter((l) => /^\s*[A-Za-z_][A-Za-z0-9_]*=/.test(l))
      .map((l) => l.trim().split('=')[0]),
  );
  const required = new Set();
  for (const m of compose.matchAll(/\$\{([A-Za-z_][A-Za-z0-9_]*)(:-[^}]*)?\}/g)) {
    if (m[2]) continue; // 带默认值
    required.add(m[1]);
  }
  const missing = [...required].filter((v) => !declared.has(v)).sort();
  if (missing.length) fail(`${stack}/docker-compose.yml`, `.env.example 未登记必需变量：${missing.join(', ')}`);
  return { required: required.size, declared: declared.size };
};

// ── 2. 端口与暴露面口径 ─────────────────────────────────────────────
// compose 里宿主端口一律写成 ${VAR:-默认}，口径看的是默认值而非变量名（手册 §10.1 定的是默认值）。
const blockPorts = (body) =>
  [...body.matchAll(/^\s*-\s*"?\$\{[A-Za-z_][A-Za-z0-9_]*:-([0-9]+)\}:([0-9]+)"?/gm)].map((m) => ({ host: m[1], container: m[2] }));

const serviceBlocks = (compose) => {
  const head = /^services:[ \t]*$/m.exec(compose);
  if (!head) return [];
  const rest = compose.slice(head.index + head[0].length);
  const end = rest.search(/^\S/m); // 下一个顶层键（volumes:/networks:/…）
  const body = end < 0 ? rest : rest.slice(0, end);
  // 不用 /m 的 ^$ 锚定：$ 在 m 下是「行尾」，会把每个服务体截成空串。
  const re = /\n {2}([\w-]+):([\s\S]*?)(?=\n {2}[\w-]+:|$)/g;
  return [...body.matchAll(re)].map((m) => ({ name: m[1], body: m[2] }));
};

const checkPorts = (stack, compose, expect) => {
  const services = serviceBlocks(compose);
  if (!services.length) {
    fail(`${stack}/docker-compose.yml`, '未解析到 services 块');
    return 0;
  }
  const byName = Object.fromEntries(services.map((s) => [s.name, s]));
  for (const [svcName, wantHost, wantContainer] of [
    ['web', expect.webHttp, '80'],
    ['web', expect.webHttps, '443'],
    ['app', expect.app, expect.app],
  ]) {
    const svc = byName[svcName];
    if (!svc) {
      fail(`${stack}/docker-compose.yml`, `缺 ${svcName} 服务`);
      continue;
    }
    const ports = blockPorts(svc.body);
    const hit = ports.find((p) => p.host === wantHost);
    if (!hit) {
      fail(`${stack}/docker-compose.yml`, `${svcName} 未发布宿主端口 ${wantHost}（${wantHost}=${wantContainer}），实际：${ports.map((p) => `${p.host}:${p.container}`).join(' ') || '无'}`);
    }
  }
  // app 容器内监听端口必须等于该栈口径，否则 Nginx upstream 与探针同时错位。
  const appPorts = byName.app ? blockPorts(byName.app.body) : [];
  const appContainer = appPorts.find((p) => p.host === expect.app);
  if (appContainer && appContainer.container !== expect.app) {
    fail(`${stack}/docker-compose.yml`, `app 容器内端口应为 ${expect.app}，实际 ${appContainer.container}`);
  }
  // 中间件一律不得发布宿主端口（§2.3.9 公网暴露收敛），进公网的只有 Nginx。
  for (const svc of services) {
    if (['redis', 'etcd', 'minio', 'milvus', 'db'].includes(svc.name) && /^\s{4}ports:/m.test(svc.body)) {
      fail(`${stack}/docker-compose.yml`, `${svc.name} 服务不得发布宿主端口（§2.3.9）`);
    }
  }
  return services.length;
};

// ── 3. Nginx 关键行为断言 ───────────────────────────────────────────
// 逐条对应 docs/configuration.md §4 已实测项：这些指令被删或被挪位后行为会静默退化，不报错，只能静态守。
const checkNginx = (stack, conf, expect) => {
  if (!conf) return;
  const httpCtx = conf.slice(0, conf.search(/\bserver\s*\{/));
  for (const zone of ['perip', 'perserver']) {
    if (!new RegExp(`limit_conn_zone\\s+[^;]*zone=${zone}:\\d+m`).test(httpCtx)) {
      fail(`${stack}/nginx`, `http 上下文缺 limit_conn_zone zone=${zone}:<size>（写进 server 内是非法指令，nginx -t 才报）`);
    }
  }
  const prodApi = locationBlock(conf, '\\/prod-api\\/');
  if (!prodApi) fail(`${stack}/nginx`, '缺 /prod-api/ location');
  for (const [re, msg] of [
    [/proxy_buffering\s+off/, '/prod-api 未关 proxy_buffering，SSE 会被整段缓冲'],
    [/proxy_http_version\s+1\.1/, '/prod-api 未用 HTTP/1.1，Upgrade 头无效'],
    [/proxy_read_timeout\s+86400s/, '/prod-api 未放宽 proxy_read_timeout，长流会被掐'],
  ]) {
    if (prodApi && !re.test(prodApi)) fail(`${stack}/nginx`, msg);
  }
  for (const [re, msg] of [
    [/return\s+308\s+https:/, 'http server 未做 308 跳转'],
    [/location\s*=\s*\/healthz\b/, '缺无鉴权探针 /healthz（/ai/health/check 带鉴权，不能当容器探针）'],
    [/actuator[^{]*\{[^}]*return\s+403/s, '未拦 actuator 路径'],
    [/ssl_certificate\s+\/etc\/nginx\/cert\/agenthub\.crt/, '证书路径须固定 /etc/nginx/cert/agenthub.crt（§2.3.1）'],
    [/ssl_certificate_key\s+\/etc\/nginx\/cert\/agenthub\.key/, '私钥路径须固定 /etc/nginx/cert/agenthub.key'],
    [/server_tokens\s+off/, '未关 server_tokens'],
    [/client_max_body_size\s+100m/, '未设 client_max_body_size 100m'],
    [/try_files\s+\$uri\s+\$uri\/\s+\/index\.html/, 'SPA 未回落 index.html'],
    [/proxy_pass\s+http:\/\/agenthub_app/, '未反代到 upstream agenthub_app'],
    [/server\s+app:/, 'upstream 未指向栈内 app 服务'],
    [/expires\s+[^;]+;\s*add_header\s+Cache-Control[^;]*immutable/, 'hash 资源缺 immutable 长缓存（缺失时应 404，不能回落 index.html）'],
    [/location\s*=\s*\/index\.html\b[^}]*no-store/s, 'index.html 缺 no-store（改版后浏览器会拿旧壳）'],
  ]) {
    if (!re.test(conf)) fail(`${stack}/nginx`, msg);
  }
  if (!new RegExp(`server\\s+app:${expect.app};`).test(conf)) fail(`${stack}/nginx`, `upstream 应指向 app:${expect.app}`);
  for (const p of [expect.webHttp, expect.webHttps]) {
    if (!new RegExp(`listen\\s+${p}[\\s;]`).test(conf)) fail(`${stack}/nginx`, `缺少 listen ${p}`);
  }
};

// ── 4. MySQL 栈字符集前置 ───────────────────────────────────────────
// 官方镜像 initdb 每个脚本另起客户端进程，默认按 latin1 解析；缺这一条整条初始化链在底座基线处中断。
const checkMysqlCharset = () => {
  const rel = 'deploy-mysql/conf.d/agenthub.cnf';
  if (!existsSync(`${DEPLOY_ROOT}/${rel}`)) return fail(rel, '缺失：MySQL 栈必须有 [client] default-character-set = utf8mb4');
  const cnf = readFileSync(`${DEPLOY_ROOT}/${rel}`, 'utf8');
  if (!/\[client\][\s\S]*default-character-set\s*=\s*utf8mb4/.test(cnf)) fail(rel, '未声明 [client] default-character-set = utf8mb4');
  const compose = read('deploy-mysql/docker-compose.yml');
  if (compose && !/character-set-server=utf8mb4/.test(compose)) fail('deploy-mysql/docker-compose.yml', '服务端未显式 --character-set-server=utf8mb4');
  if (compose && !/conf\.d\/agenthub\.cnf:\/etc\/mysql\/conf\.d\//.test(compose)) fail('deploy-mysql/docker-compose.yml', 'conf.d 未挂进 /etc/mysql/conf.d');
};

// ── 5. 覆盖机制接线不得丢 ───────────────────────────────────────────
// 底座明文默认值只有靠「后置 profile + 额外挂载目录」才压得动，这两行被删就等于带仓库私钥上线。
const checkProfileWiring = (stack, compose) => {
  if (!/SPRING_PROFILES_ACTIVE[^\n]*prod,agenthub/.test(compose)) {
    fail(`${stack}/docker-compose.yml`, 'profile 顺序必须写 prod,agenthub，否则压不动底座明文默认值');
  }
  if (!/SPRING_CONFIG_ADDITIONAL_LOCATION[^\n]*\/config\//.test(compose)) {
    fail(`${stack}/docker-compose.yml`, '缺 SPRING_CONFIG_ADDITIONAL_LOCATION，映射文件不会被加载');
  }
  if (!/:\/config:ro/.test(compose)) fail(`${stack}/docker-compose.yml`, 'config/ 目录未只读挂载到 /config');
  // 映射文件必须把 AGENTHUB_* 落到底座已有的属性路径上；路径写错不会报错，只会静默用回底座明文默认值。
  const mapping = read(`${stack}/config/application-agenthub.yml`);
  if (!mapping) return;
  const driver = stack === 'deploy-mysql' ? 'com.mysql.cj.jdbc.Driver' : 'org.postgresql.Driver';
  for (const [re, msg] of [
    [/^\s*datasource:\s*$/m, '未映射 spring.datasource.dynamic.datasource.*'],
    [/url:\s*\$\{AGENTHUB_DATASOURCE_URL/, '未映射数据源 url'],
    [/password:\s*\$\{AGENTHUB_DATASOURCE_PASSWORD/, '未映射数据源口令'],
    [new RegExp(`driver-class-name:\\s*${driver.replace(/\./g, '\\.')}`), `数据源驱动应为 ${driver}`],
    [/redis:[\s\S]{0,200}?host:\s*\$\{AGENTHUB_REDIS_HOST/, '未映射 spring.data.redis.host'],
    [/jwt-secret-key:\s*\$\{AGENTHUB_SA_TOKEN_JWT_SECRET_KEY/, '未映射 sa-token.jwt-secret-key'],
    [/privateKey:\s*\$\{AGENTHUB_API_DECRYPT_PRIVATE_KEY/, '未映射 api-decrypt 私钥（环境变量压不动这条属性路径，必须走映射文件）'],
  ]) {
    if (!re.test(mapping)) fail(`${stack}/config/application-agenthub.yml`, msg);
  }
};

// ── 6. 应用镜像与栈端口接线 ─────────────────────────────────────────
// app 服务是 build 出来的，容器里进程监听哪个端口只有 Dockerfile 说了算：底座 yml 写死 8080，
// 少了 SERVER_PORT 这一环，MySQL 栈的 Nginx upstream 与健康探针会同时错位——
// 静态三处（compose / nginx / Dockerfile）必须对齐同一个端口口径，否则起栈时才在日志里见分晓。
const checkAppImage = (stack, compose, expect) => {
  const rel = `${stack}/docker-compose.yml`;
  const dfName = (compose.match(/dockerfile:\s*\$\{AGENTHUB_APP_DOCKERFILE:-([^}]+)\}/) || [, 'Dockerfile'])[1];
  const dfRel = `code/back-end/${dfName}`;
  const dfPath = `${REPO_ROOT}${dfRel}`;
  if (!existsSync(dfPath)) return fail(dfRel, `缺失：${rel} 的 app.build 指向它，镜像建不出来栈就起不来`);
  const df = readFileSync(dfPath, 'utf8');

  if (!new RegExp(`SERVER_PORT:\\s*"${expect.app}"`).test(compose)) {
    fail(rel, `app 环境必须注入 SERVER_PORT: "${expect.app}"（本栈端口口径），否则容器仍按底座 yml 的 8080 监听`);
  }
  if (!/-Dserver\.port=\$\{SERVER_PORT\}/.test(df)) fail(dfRel, 'ENTRYPOINT 未把 SERVER_PORT 传给 -Dserver.port');
  // 探针 `timeout 3 bash -c '</dev/tcp/127.0.0.1/<port>'` 要 bash + coreutils：jre-alpine 里没有，web 会永远等不到 healthy。
  if (!/FROM\s+eclipse-temurin:\d+-jre(?![\w-])/.test(df)) fail(dfRel, '运行期镜像应为 eclipse-temurin:<n>-jre（compose 的容器探针依赖 bash 与 coreutils；-jre-alpine 里没有）');

  // JDK 口径只由仓库内声明决定，与 ci.yml 的「解析 JDK 版本」同源，避免镜像与 CI 各跑一个版本。
  const pomPath = `${REPO_ROOT}code/back-end/pom.xml`;
  const pomJava = existsSync(pomPath) ? (readFileSync(pomPath, 'utf8').match(/<java\.version>(\d+)/) || [])[1] : '';
  const imgJava = (df.match(/eclipse-temurin-(\d+)/) || [])[1];
  if (!pomJava || !imgJava) {
    fail(dfRel, `未同时解析到 pom <java.version>（${pomJava || '缺'}）与构建镜像 temurin 版本（${imgJava || '缺'}），版本口径不能靠记忆`);
  } else if (pomJava !== imgJava) {
    fail(dfRel, `构建镜像 JDK ${imgJava} 与 pom <java.version> ${pomJava} 不一致`);
  }

  // 少装 AI 模块不会报错，只会让前端 34 个 AI 页面全部拿不到接口。
  if (!/-pl\s+ruoyi-admin\s+-am/.test(df)) fail(dfRel, '构建命令需为 `-pl ruoyi-admin -am`：缺 -am 时同级模块在容器本地仓库里解析不到');
  const adminPom = `${REPO_ROOT}code/back-end/ruoyi-admin/pom.xml`;
  if (!existsSync(adminPom) || !/<artifactId>agent-hub-ai<\/artifactId>/.test(readFileSync(adminPom, 'utf8'))) {
    fail('code/back-end/ruoyi-admin/pom.xml', '未依赖 agent-hub-ai，镜像里不会带 AI 能力域');
  }

  // 落盘目录必须挂命名卷：bind 会跟着 compose 目录走，两栈同机时容易指到同一份。
  for (const [path, why] of [['/app/logs', 'logback 的 log.path=./logs'], ['/app/data', '长期记忆与工作区 workspace-root=./data/agent-workspace']]) {
    const m = new RegExp(`^\\s*-\\s*([^:\\s]+):${path}$`, 'm').exec(compose);
    if (!m) {
      fail(rel, `app 未挂载 ${path}（${why}）——写在容器可写层里，一次 recreate 就清零`);
    } else if (m[1].includes('/') || m[1].startsWith('.')) {
      fail(rel, `${path} 应挂命名卷，实际是 bind 源 ${m[1]}`);
    }
  }

  const diPath = `${REPO_ROOT}code/back-end/.dockerignore`;
  if (!existsSync(diPath)) {
    fail('code/back-end/.dockerignore', '缺失：本地 mvn 留下的 target/ 会整层进构建上下文，每次构建重传几十 MB 且 COPY 进去的是过期产物');
  } else if (!/^\*\*\/target\/\s*$/m.test(readFileSync(diPath, 'utf8'))) {
    fail('code/back-end/.dockerignore', '未排除 **/target/');
  }
};

const main = () => {
  if (!existsSync(DEPLOY_ROOT)) {
    console.log('· deploy/ 未入仓，跳过');
    return;
  }
  const dirs = readdirSync(DEPLOY_ROOT, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name.startsWith('deploy-'))
    .map((d) => d.name);
  const unknown = dirs.filter((d) => !STACKS[d]);
  if (unknown.length) fail('deploy/', `未登记端口口径的栈：${unknown.join(', ')}（新增栈须在本脚本 STACKS 补一行）`);

  let checked = 0;
  for (const stack of dirs) {
    if (!STACKS[stack]) continue;
    const compose = read(`${stack}/docker-compose.yml`);
    if (!compose) continue;
    const env = read(`${stack}/.env.example`);
    const conf = read(`${stack}/nginx/agenthub.conf`);
    const { required, declared } = checkEnvCoverage(stack, compose, env);
    const services = checkPorts(stack, compose, STACKS[stack]);
    checkNginx(stack, conf, STACKS[stack]);
    checkProfileWiring(stack, compose);
    checkAppImage(stack, compose, STACKS[stack]);
    notes.push(`${stack}: 服务 ${services} 个，必需变量 ${required} 个 / 清单登记 ${declared} 个`);
    checked += 1;
  }
  if (existsSync(`${DEPLOY_ROOT}/deploy-mysql`)) checkMysqlCharset();

  // docker 在场时追加一次真实渲染，兑现「复制 .env.example 就能起」；缺 docker 明确记为未执行，不静默通过。
  const hasDocker = spawnSync('docker', ['version'], { encoding: 'utf8' }).status === 0;
  if (hasDocker) {
    for (const stack of dirs) {
      if (!STACKS[stack]) continue;
      const r = spawnSync('docker', ['compose', '--env-file', `${stack}/.env.example`, '-f', `${stack}/docker-compose.yml`, 'config', '--quiet'], {
        cwd: DEPLOY_ROOT,
        encoding: 'utf8',
      });
      if (r.status !== 0) {
        fail(`${stack}/docker-compose.yml`, `compose 渲染失败：${(r.stderr || r.stdout || '').trim().split('\n')[0] || `exit ${r.status}`}`);
      } else {
        notes.push(`${stack}: compose 以 .env.example 渲染 rc=0`);
      }
    }
  } else {
    notes.push('本机无 docker，未执行 compose 真实渲染（静态断言已全部执行）');
  }

  for (const n of notes) console.log(`  · ${n}`);
  if (failures.length) {
    console.error(`✗ 部署编排校验失败（${failures.length} 项，覆盖 ${checked} 个栈）`);
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(`✓ 部署编排校验通过（${checked} 个栈：端口口径 / 变量清单 / Nginx 行为断言 / 字符集前置 / 覆盖接线 / 镜像接线）`);
};

main();
