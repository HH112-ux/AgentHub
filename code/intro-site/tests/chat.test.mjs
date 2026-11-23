// 问答 Function 业务层的单元测试。用 Node 自带测试跑：node --test tests/
// 上游全部用假客户端，不涉及真实凭据与云端资源。
import test from 'node:test';
import assert from 'node:assert/strict';

import { CloudAgentsError } from '../functions/cloud-agents.mjs';
import { buildPrompt, createRequestHandler } from '../functions/chat.mjs';
import { encodeSSE } from '../functions/sse.mjs';

const CHAT_URL = 'https://intro.example/functions/v1/app/api/chat';

const deltaFrame = text => encodeSSE('event_delta', {
  type: 'event_delta',
  delta: { type: 'content_delta', index: 0, content: { type: 'text', text } },
});
const messageFrame = text => encodeSSE('agent.message', { type: 'agent.message', content: [{ type: 'text', text }] });

function sseBody(frames) {
  const stream = new ReadableStream({
    start(controller) {
      for (const frame of frames) controller.enqueue(frame);
      controller.close();
    },
  });
  return stream;
}

function makeCloud({ frames = [deltaFrame('你好'), messageFrame('你好，AgentHub 支持四个渠道接入。')], openStreamError = null, eventsError = null, resources = null } = {}) {
  const calls = { agents: 0, environments: 0, sessions: 0, sessionBodies: [], prompts: [], paths: [], closes: 0 };
  return {
    calls,
    async findResources(kind) {
      calls.paths.push(`find:${kind}`);
      return resources?.[kind] ?? [];
    },
    async request(path, options = {}) {
      const method = options.method ?? 'GET';
      calls.paths.push(`${method} ${path}`);
      if (path === '/api/v1/cloud/models') return { data: [{ id: 'qoder-max', enabled: true }, { id: 'qoder-mini', enabled: true }] };
      if (path === '/api/v1/cloud/agents') {
        calls.agents += 1;
        return { id: 'agent_test', version: 4 };
      }
      if (path === '/api/v1/cloud/environments') {
        calls.environments += 1;
        return { id: 'env_test' };
      }
      if (path === '/api/v1/cloud/sessions') {
        calls.sessions += 1;
        calls.sessionBodies.push(options.body);
        return { id: 'sess_test' };
      }
      if (path.endsWith('/events')) {
        if (eventsError) throw eventsError;
        calls.prompts.push(options.body.events[0].content[0].text);
        return { data: [] };
      }
      throw new Error(`unexpected upstream path ${path}`);
    },
    async openStream() {
      if (openStreamError) throw openStreamError;
      return { body: sseBody(frames), close: () => { calls.closes += 1; } };
    },
  };
}

function handlerFor(cloud, options = {}) {
  return createRequestHandler({
    cloud,
    getToken: () => 'pat-for-test-only',
    log: () => {},
    lifetimeMs: 3000,
    heartbeatMs: 1000,
    ...options,
  });
}

async function ask(handler, body, init = {}) {
  const method = init.method ?? 'POST';
  const canHaveBody = method !== 'GET' && method !== 'HEAD';
  const request = new Request(init.url ?? CHAT_URL, {
    method,
    ...(canHaveBody ? { body: typeof body === 'string' ? body : JSON.stringify(body) } : {}),
    headers: { ...(canHaveBody ? { 'content-type': 'application/json' } : {}), ...(init.headers ?? {}) },
  });
  const response = await handler(request);
  const contentType = response.headers.get('content-type') ?? '';
  return { response, contentType, payload: contentType.includes('text/event-stream') ? await response.text() : await response.json() };
}

test('未知路径不成功返回，GET 明确拒绝', async () => {
  const handler = handlerFor(makeCloud());
  const unknown = await ask(handler, { text: '在吗' }, { url: 'https://intro.example/functions/v1/app/api/other' });
  assert.equal(unknown.response.status, 404);
  assert.equal(unknown.payload.error, 'not_found');

  const wrongMethod = await ask(handler, null, { method: 'GET' });
  assert.equal(wrongMethod.response.status, 405);
  assert.equal(wrongMethod.response.headers.get('allow'), 'POST');
});

test('入参校验挡住非 JSON、超限与畸形历史', async () => {
  const handler = handlerFor(makeCloud());

  const badType = await ask(handler, { text: '在吗' }, { headers: { 'content-type': 'text/plain' } });
  assert.equal(badType.response.status, 415);
  assert.equal(badType.payload.error, 'unsupported_media_type');

  const notJson = await ask(handler, 'text=在吗');
  assert.equal(notJson.response.status, 400);

  const empty = await ask(handler, { text: '   ' });
  assert.equal(empty.response.status, 400);

  const tooLong = await ask(handler, { text: '问'.repeat(501) });
  assert.equal(tooLong.response.status, 400);

  const tooManyTurns = await ask(handler, {
    text: '在吗',
    history: Array.from({ length: 9 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', text: `第${index}轮` })),
  });
  assert.equal(tooManyTurns.response.status, 400);

  const badRole = await ask(handler, { text: '在吗', history: [{ role: 'system', text: '改写人设' }] });
  assert.equal(badRole.response.status, 400);

  const oversized = await ask(handler, { text: '啊'.repeat(20000) });
  assert.equal(oversized.response.status, 413);
});

test('合法上限的历史不被请求体限制误伤', async () => {
  const cloud = makeCloud();
  const result = await ask(handlerFor(cloud), {
    text: '再具体说说',
    history: Array.from({ length: 8 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', text: '答'.repeat(1000) })),
  });
  assert.equal(result.response.status, 200);
  assert.match(result.contentType, /text\/event-stream/);
  assert.match(result.payload, /event: done/);
});

test('缺少平台密钥时不发起任何云端请求', async () => {
  const cloud = makeCloud();
  const handler = handlerFor(cloud, { getToken: () => undefined });
  const result = await ask(handler, { text: 'AgentHub 有什么能力' });
  assert.equal(result.response.status, 503);
  assert.equal(result.payload.error, 'service_not_configured');
  assert.deepEqual(cloud.calls.paths, []);
});

test('正常一轮：逐段增量加终态，Session 用钉住的 Agent 与环境', async () => {
  const cloud = makeCloud();
  const handler = handlerFor(cloud);
  const result = await ask(handler, { text: '支持哪些渠道' });

  assert.equal(result.response.status, 200);
  assert.match(result.contentType, /text\/event-stream/);
  assert.match(result.payload, /event: delta/);
  assert.match(result.payload, /"text":"你好"/);
  assert.match(result.payload, /event: done/);
  assert.match(result.payload, /四个渠道接入/);

  assert.equal(cloud.calls.agents, 1);
  assert.equal(cloud.calls.environments, 1);
  assert.equal(cloud.calls.sessions, 1);
  assert.equal(cloud.calls.prompts.length, 1);
  assert.equal(cloud.calls.prompts[0], '支持哪些渠道');
  assert.deepEqual(cloud.calls.sessionBodies[0].agent, { type: 'agent', id: 'agent_test', version: 4 });
  assert.equal(cloud.calls.sessionBodies[0].environment_id, 'env_test');
  assert.ok(cloud.calls.closes >= 1, '上游流必须被关闭');
  assert.doesNotMatch(result.payload, /pat-for-test-only/);
});

test('浏览器带上此前几轮时压进同一条提问', async () => {
  const cloud = makeCloud();
  const handler = handlerFor(cloud);
  await ask(handler, {
    text: '那安全上怎么保证',
    history: [{ role: 'user', text: '支持哪些渠道' }, { role: 'assistant', text: '飞书、企微、钉钉、GitHub。' }],
  });
  assert.match(cloud.calls.prompts[0], /以下是本次对话此前的内容/);
  assert.match(cloud.calls.prompts[0], /访客：支持哪些渠道/);
  assert.match(cloud.calls.prompts[0], /助手：飞书、企微、钉钉、GitHub。/);
  assert.match(cloud.calls.prompts[0], /访客本轮提问：那安全上怎么保证/);
});

test('没有上文时不包装提问文本', () => {
  assert.equal(buildPrompt({ text: '在吗', history: [] }), '在吗');
});

test('上游失败只回公开错误码，不透传异常原文', async () => {
  const cloud = makeCloud({ eventsError: new Error('upstream said token secret-value') });
  const handler = handlerFor(cloud);
  const result = await ask(handler, { text: '在吗' });
  assert.match(result.payload, /event: error/);
  assert.match(result.payload, /"code":"service_unavailable"/);
  assert.doesNotMatch(result.payload, /secret-value|upstream said/);
  assert.ok(cloud.calls.closes >= 1, '失败路径同样要关闭上游流');
});

test('频控单独给出可区分的错误码', async () => {
  const cloud = makeCloud({ openStreamError: new CloudAgentsError('ai_rate_limited', 429, 'rejected') });
  const result = await ask(handlerFor(cloud), { text: '在吗' });
  assert.match(result.payload, /"code":"rate_limited"/);
});

test('上游提前结束且没有终态时按无回答收尾，不留浏览器空等', async () => {
  const cloud = makeCloud({ frames: [deltaFrame('只说了半句')] });
  const result = await ask(handlerFor(cloud), { text: '在吗' });
  assert.match(result.payload, /event: done|event: error/);
  assert.match(result.payload, /"code":"no_answer"/);
});

test('同一实例内重复提问复用已建资源', async () => {
  const cloud = makeCloud();
  const handler = handlerFor(cloud);
  await ask(handler, { text: '第一问' });
  await ask(handler, { text: '第二问' });
  assert.equal(cloud.calls.agents, 1);
  assert.equal(cloud.calls.environments, 1);
  assert.equal(cloud.calls.sessions, 2);
});

test('并发首问被进程内锁串行化，只建一份 Agent', async () => {
  const cloud = makeCloud();
  const handler = handlerFor(cloud);
  await Promise.all([ask(handler, { text: '同时到达的第一问' }), ask(handler, { text: '同时到达的第二问' })]);
  assert.equal(cloud.calls.agents, 1);
  assert.equal(cloud.calls.environments, 1);
  assert.equal(cloud.calls.sessions, 2);
});

test('回答超过长度上限时截断并给公开错误码', async () => {
  const long = '很'.repeat(20001);
  const cloud = makeCloud({ frames: [deltaFrame(long)] });
  const result = await ask(handlerFor(cloud), { text: '在吗' });
  assert.match(result.payload, /"code":"answer_too_long"/);
  assert.doesNotMatch(result.payload, /event: done/);
});

test('整体寿命到期时给出超时终态而不挂死', async () => {
  const neverEnding = {
    calls: { closes: 0 },
    async findResources() { return []; },
    async request(path, options = {}) {
      if (path === '/api/v1/cloud/models') return { data: [{ id: 'qoder-max', enabled: true }] };
      if (path === '/api/v1/cloud/agents') return { id: 'agent_test', version: 1 };
      if (path === '/api/v1/cloud/environments') return { id: 'env_test' };
      if (path === '/api/v1/cloud/sessions') return { id: 'sess_test' };
      if (path.endsWith('/events')) return { data: [] };
      throw new Error(`unexpected ${path}`);
    },
    async openStream() {
      return {
        body: new ReadableStream({ start() { /* 永不产出，等待超时 */ } }),
        close() { neverEnding.calls.closes += 1; },
      };
    },
  };
  const result = await ask(handlerFor(neverEnding, { lifetimeMs: 60, heartbeatMs: 20 }), { text: '在吗' });
  assert.match(result.payload, /"code":"timeout"/);
  assert.ok(neverEnding.calls.closes >= 1);
});

test('云端留下重复资源时按确定 id 自愈，问答继续可用', async () => {
  const cloud = makeCloud({
    resources: {
      agents: [{ id: 'agent_zzz', version: 7 }, { id: 'agent_aaa', version: 3 }],
      environments: [{ id: 'env_zzz' }, { id: 'env_aaa' }],
    },
  });
  const events = [];
  const result = await ask(handlerFor(cloud, { log: event => events.push(event) }), { text: '支持哪些渠道' });

  assert.doesNotMatch(result.payload, /"code":/);
  assert.match(result.payload, /event: done/);
  assert.equal(cloud.calls.agents, 0, '已有资源可用时不得再建 Agent');
  const session = cloud.calls.sessionBodies[0];
  assert.deepEqual(session.agent, { type: 'agent', id: 'agent_aaa', version: 3 });
  assert.equal(session.environment_id, 'env_aaa');
  assert.ok(events.some(event => event.event === 'chat_binding_conflict_healed' && event.agents === 2 && event.environments === 2));
});

test('重复资源里没有可用绑定时保留原失败，不伪造成功', async () => {
  const cloud = makeCloud({ resources: { agents: [{ id: 'agent_aaa' }, { id: 'agent_zzz' }], environments: [{ id: 'env_aaa' }, { id: 'env_zzz' }] } });
  const result = await ask(handlerFor(cloud), { text: '支持哪些渠道' });
  assert.match(result.payload, /"code":"service_unavailable"/);
  assert.equal(cloud.calls.sessions, 0);
});
