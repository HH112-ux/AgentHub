// AgentHub 介绍页问答 Function 的业务层。
//
// 边界（AGENTS.md 与 docs/superpowers/specs/2026-11-20-agenthub-decomposition-design.md §5）：
//   站点只承载公开介绍内容的问答，云端 Agent 不接入业务知识库、业务工具、会话数据或 PII。
//   因此本文件：Agent 定义不含 tools 字段（即不授予任何工具）；提示词只含 functions/system-prompt.mjs
//   里已公开的文案；不落库、不写文件、不把会话内容留在进程里——对话历史只按访客当前这一次页面停留
//   由浏览器随请求带上，回答结束即丢弃。
//
// 与官方推荐路径的偏差（改动这里之前先读）：
//   sites-build-agent-app 的 createChatHandler 要求持久层，因为它的 create 承诺「刷新不得新建云端
//   Session」、send 承诺「先落投递意图」。本项目按队员决定不开站点数据库，因此这里不用
//   createChatHandler，改为「一次请求一个一次性云端 Session + 浏览器携带本轮文本」的薄层。
//   ensureCloudResources 要求的 coordinator 本应是跨实例的持久锁，这里降级成进程内锁（createProcessLocks），
//   后果是：Agent/Environment 首次创建那一瞬间若被多个实例并发命中，可能建出重复资源，之后
//   findResources 扫到多于一份会抛 ai_initialization_conflict，问答会持续失败直到人工到 Qoder 云端
//   归档多余的那份。本站点访问范围是 private、可访问人数只有项目组三人，首次创建由开发者自己触发，
//   接受此风险。若将来站点改为公开访问，必须先补站点数据库再上问答。

import { CloudAgentsError, createCloudAgentsClient, ensureCloudResources } from './cloud-agents.mjs';
import { encodeSSE, readSSE } from './sse.mjs';
import { AGENT_NAME, ENVIRONMENT_NAME, PUBLIC_SYSTEM_PROMPT } from './system-prompt.mjs';

// 取自站点工具 get_context 的 runtime.cloudAgents.apiOrigin（2026-11-23 实测）。
// 不要从站点域名、语言或官方文档示例推断；私有部署未配置该映射时问答能力不可用。
export const CLOUD_API_ORIGIN = 'https://api.qoder.com.cn';

// 稳定的资源归属标识：由站点与部署环境决定，绝不能由 Host 头、访客输入或每次冷启动的随机值推导。
export const APPLICATION_SCOPE = 'agenthub-intro-site.cn-prod';

// 钉住模型标识，避免云端模型列表变动导致 Agent 定义指纹漂移、重复建资源。
// 取值方式：部署后问一次，从 Function 日志的 chat_models_discovered 里挑一个已启用的模型 id 填进来。
// 2026-11-23 线上首问日志：模型目录未标记 enabled（fallback=true），排序后取到 'auto' 并成功建成 Agent，
// 因此钉成 'auto' 与实际已建资源一致——换成别的值会让指纹变化并在云端另建一份 Agent。
export const PINNED_MODEL_ID = 'auto';

const CHAT_PATH_SUFFIX = '/api/chat';

const LIMITS = {
  // 必须容得下「合法上限的历史」：8 条 × 1000 字全中文约 24 KB，再加 500 字提问。
  // 卡得太紧会让访客在聊长之后每一问都被 413 拒死，这一次页面停留就再也问不动了。
  bodyBytes: 32768,
  textChars: 500,
  historyItems: 8,
  historyItemChars: 1000,
  answerChars: 20000,
  frameChars: 262144,
};

const DEFAULT_LIFETIME_MS = 45000;
const DEFAULT_HEARTBEAT_MS = 10000;

class InputError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

function json(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers },
  });
}

// 只把可公开的错误分类交给浏览器，绝不透传上游响应体、异常原文或任何凭据。
function publicErrorCode(error) {
  if (error instanceof InputError) return error.code;
  if (error instanceof CloudAgentsError) {
    if (error.code === 'ai_rate_limited') return 'rate_limited';
    if (error.code === 'ai_not_configured' || error.code === 'ai_configuration_invalid') return 'service_not_configured';
    if (error.code === 'ai_request_invalid' || error.code === 'ai_request_too_large') return 'invalid_input';
  }
  return 'service_unavailable';
}

function countChars(value) {
  return Array.from(value).length;
}

// 官方 coordinator 接口的进程内实现，只保证单实例内互斥，见文件头偏差说明。
function createProcessLocks() {
  const entries = new Map();
  const records = new Map();
  return async function withLock(key, callback) {
    let entry = entries.get(key);
    if (!entry) {
      entry = { tail: Promise.resolve(), waiters: 0 };
      entries.set(key, entry);
    }
    entry.waiters += 1;
    const previous = entry.tail;
    let settle;
    entry.tail = new Promise(resolve => {
      settle = resolve;
    });
    await previous.catch(() => {});
    let held = true;
    try {
      return await callback({
        read: async () => records.get(key) ?? null,
        write: async next => {
          if (!held) throw new Error('lease_lost');
          records.set(key, next);
        },
        assertHeld: () => {
          if (!held) throw new Error('lease_lost');
        },
      });
    } finally {
      held = false;
      entry.waiters -= 1;
      if (entry.waiters === 0 && entries.get(key) === entry) entries.delete(key);
      settle();
    }
  };
}

function concat(chunks) {
  if (chunks.length === 1) return chunks[0];
  const total = chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0);
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return merged;
}

async function readJsonBody(request) {
  const contentType = request.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().startsWith('application/json')) throw new InputError(415, 'unsupported_media_type');
  const declared = Number(request.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > LIMITS.bodyBytes) throw new InputError(413, 'payload_too_large');
  const reader = request.body?.getReader();
  if (!reader) throw new InputError(400, 'invalid_input');
  const chunks = [];
  let received = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      received += value.byteLength;
      if (received > LIMITS.bodyBytes) {
        await reader.cancel().catch(() => {});
        throw new InputError(413, 'payload_too_large');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  if (!chunks.length) throw new InputError(400, 'invalid_input');

  let body;
  try {
    body = JSON.parse(new TextDecoder().decode(concat(chunks)));
  } catch {
    throw new InputError(400, 'invalid_input');
  }
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new InputError(400, 'invalid_input');

  const text = typeof body.text === 'string' ? body.text.trim() : '';
  if (!text || countChars(text) > LIMITS.textChars) throw new InputError(400, 'invalid_input');

  const rawHistory = Array.isArray(body.history) ? body.history : [];
  if (rawHistory.length > LIMITS.historyItems) throw new InputError(400, 'invalid_input');
  const history = [];
  for (const item of rawHistory) {
    const role = item?.role === 'user' || item?.role === 'assistant' ? item.role : null;
    const itemText = typeof item?.text === 'string' ? item.text.trim() : '';
    if (!role || !itemText || countChars(itemText) > LIMITS.historyItemChars) throw new InputError(400, 'invalid_input');
    history.push({ role, text: itemText });
  }
  return { text, history };
}

// 云端 Session 每次都是新建的、没有上文记忆，所以此前几轮由浏览器带上，压成一条提问文本。
export function buildPrompt({ text, history }) {
  if (!history?.length) return text;
  const transcript = history.map(item => `${item.role === 'user' ? '访客' : '助手'}：${item.text}`).join('\n');
  return `以下是本次对话此前的内容，仅用于理解上下文：\n${transcript}\n\n访客本轮提问：${text}`;
}

function isEnabledModel(model) {
  return model?.enabled === true || model?.status === 'enabled' || model?.state === 'enabled';
}

async function selectModel(rt) {
  if (PINNED_MODEL_ID) return PINNED_MODEL_ID;
  const page = await rt.cloud.request('/api/v1/cloud/models');
  const list = Array.isArray(page?.data) ? page.data : [];
  const ids = list.map(model => model?.id).filter(id => typeof id === 'string' && id).sort();
  const enabled = list.filter(isEnabledModel).map(model => model.id).filter(id => typeof id === 'string' && id).sort();
  // 目录里没有任何启用标记时退回全量列表，并把回退记进日志，避免静默选中不可用模型。
  const candidates = enabled.length ? enabled : ids;
  rt.log({ event: 'chat_models_discovered', fallback: enabled.length === 0, ids: candidates.slice(0, 40) });
  if (!candidates.length) throw new CloudAgentsError('ai_resource_not_found', 503, 'unknown');
  return candidates[0];
}

// 进程内锁挡不住多实例并发首建；一旦云端留下重复资源，官方 ensureCloudResources 会扫到多于一份
// 并抛 ai_initialization_conflict，问答会从此全量失败直到人工归档。这里按本站作用域挑一份
// 确定 id 复用（只求确定性、不求最新），把永久故障降级成继续可用；多余资源仍需人工清理。
async function healBinding(rt) {
  const [agents, environments] = await Promise.all([
    rt.cloud.findResources('agents', { qsites_scope: APPLICATION_SCOPE, qsites_kind: 'agents' }),
    rt.cloud.findResources('environments', { qsites_scope: APPLICATION_SCOPE, qsites_kind: 'environments' }),
  ]);
  const pick = list => list.map(resource => resource.id).sort()[0];
  const agent = agents.find(resource => resource.id === pick(agents));
  const environment = environments.find(resource => resource.id === pick(environments));
  if (!agent || !environment || !Number.isInteger(agent.version) || agent.version < 1) return null;
  rt.log({ event: 'chat_binding_conflict_healed', agents: agents.length, environments: environments.length });
  return { agent: { type: 'agent', id: agent.id, version: agent.version }, environment_id: environment.id };
}

async function ensureBinding(rt) {
  if (rt.binding) return rt.binding;
  return rt.withLock('intro-site-binding', async () => {
    if (rt.binding) return rt.binding;
    const model = await selectModel(rt);
    // 不提供 tools 字段＝不授予任何工具；挂业务工具属于 AGENTS.md 明令禁止的范围。
    let binding;
    try {
      binding = await ensureCloudResources({
        client: rt.cloud,
        coordinator: { withLock: rt.withLock },
        applicationScope: APPLICATION_SCOPE,
        agent: { name: AGENT_NAME, model, system: PUBLIC_SYSTEM_PROMPT, metadata: { app: 'agenthub-intro-site', purpose: 'public-faq' } },
        environment: { name: ENVIRONMENT_NAME, config: { type: 'cloud' } },
      });
    } catch (error) {
      if (!(error instanceof CloudAgentsError) || error.code !== 'ai_initialization_conflict') throw error;
      const healed = await healBinding(rt);
      if (!healed) throw error;
      binding = healed;
    }
    rt.binding = binding;
    rt.log({ event: 'chat_resources_ready', agentVersion: binding.agent?.version });
    return binding;
  });
}

function messageText(payload) {
  const blocks = Array.isArray(payload?.content) ? payload.content : Array.isArray(payload?.message?.content) ? payload.message.content : null;
  if (!blocks) return '';
  return blocks.filter(block => block?.type === 'text' && typeof block.text === 'string').map(block => block.text).join('');
}

function frameType(frame, payload) {
  const event = typeof frame.event === 'string' ? frame.event : '';
  if (event && event !== 'message') return event;
  return typeof payload?.type === 'string' ? payload.type : event;
}

async function runTurn({ emit, input, rt, signal, onUpstream }) {
  const binding = await ensureBinding(rt);
  const session = await rt.cloud.request('/api/v1/cloud/sessions', {
    method: 'POST',
    body: {
      ...binding,
      title: '介绍页问答',
      // 标题与元数据都不放访客提问内容，避免云端会话记录成为个人数据来源。
      metadata: { app: 'agenthub-intro-site', purpose: 'public-faq' },
    },
  });
  const sessionId = typeof session?.id === 'string' && /^sess_[A-Za-z0-9_-]+$/.test(session.id) ? session.id : '';
  if (!sessionId) throw new CloudAgentsError('ai_response_invalid', 503, 'unknown');

  // 先连流再发提问：反过来会丢掉回答的开头。
  const upstream = await rt.cloud.openStream(sessionId, { signal });
  onUpstream(upstream);
  try {
    await rt.cloud.request(`/api/v1/cloud/sessions/${sessionId}/events`, {
      method: 'POST',
      body: { events: [{ type: 'user.message', content: [{ type: 'text', text: buildPrompt(input) }] }] },
    });

    let draft = '';
    for await (const frame of readSSE(upstream.body, { maxFrameChars: LIMITS.frameChars, signal })) {
      let payload;
      try {
        payload = JSON.parse(frame.data);
      } catch {
        continue;
      }
      const type = frameType(frame, payload);
      if (type === 'event_start') continue;
      if (type === 'event_delta') {
        const delta = payload?.delta;
        const piece = delta?.type === 'content_delta' && delta.content?.type === 'text' && typeof delta.content.text === 'string' ? delta.content.text : '';
        if (!piece) continue;
        draft += piece;
        if (countChars(draft) > LIMITS.answerChars) {
          rt.log({ event: 'chat_answer_truncated' });
          emit('error', { code: 'answer_too_long' });
          return;
        }
        emit('delta', { text: piece });
        continue;
      }
      if (type === 'agent.message') {
        emit('done', { text: messageText(payload) || draft });
        return;
      }
      if (type.includes('error') || type.includes('failed')) {
        rt.log({ event: 'chat_upstream_failure', type });
        emit('error', { code: 'service_unavailable' });
        return;
      }
      rt.log({ event: 'chat_frame_ignored', type });
    }
    // 上游 EOF 不等于回答完成：没有终态就按失败收尾，避免浏览器一直转圈。
    rt.log({ event: 'chat_stream_eof_without_terminal' });
    emit('error', { code: 'no_answer' });
  } finally {
    onUpstream(null);
    upstream.close();
  }
}

function startStream({ request, input, rt }) {
  const controller = new AbortController();
  const ping = new TextEncoder().encode(': ping\n\n');
  const abortFromClient = () => controller.abort();
  let sink = null;
  let upstream = null;
  let closed = false;
  let deadline = null;
  let heartbeat = null;

  function emit(event, data) {
    if (closed || !sink) return;
    if (event === 'done' || event === 'error') closed = true;
    try {
      sink.enqueue(encodeSSE(event, data));
    } catch {
      // 浏览器已断开：终态写不出去，只能放弃本次投递。
      closed = true;
    }
  }

  function stop() {
    if (deadline) clearTimeout(deadline);
    if (heartbeat) clearInterval(heartbeat);
    deadline = heartbeat = null;
    request.signal?.removeEventListener('abort', abortFromClient);
    upstream?.close();
    upstream = null;
    try {
      sink?.close();
    } catch {
      // 已关闭。
    }
  }

  const stream = new ReadableStream({
    start(sinkController) {
      sink = sinkController;
      if (request.signal?.aborted) controller.abort();
      request.signal?.addEventListener('abort', abortFromClient, { once: true });
      heartbeat = setInterval(() => {
        if (closed) return;
        try {
          sink.enqueue(ping);
        } catch {
          closed = true;
        }
      }, rt.heartbeatMs);
      deadline = setTimeout(() => {
        controller.abort();
        emit('error', { code: 'timeout' });
        stop();
      }, rt.lifetimeMs);

      void (async () => {
        try {
          await runTurn({
            emit,
            input,
            rt,
            signal: controller.signal,
            onUpstream: handle => {
              upstream = handle;
            },
          });
        } catch (error) {
          const code = publicErrorCode(error);
          rt.log({ event: 'chat_turn_failed', code });
          emit('error', { code });
        } finally {
          stop();
        }
      })();
    },
    cancel() {
      closed = true;
      controller.abort();
      rt.log({ event: 'chat_client_cancelled' });
      stop();
    },
  });

  return new Response(stream, {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store, no-transform',
      'X-Accel-Buffering': 'no',
    },
  });
}

const defaultGetToken = () => globalThis.Deno?.env.get('QODER_PAT');

const logToStderr = event => {
  try {
    console.error(JSON.stringify(event));
  } catch {
    // 日志绝不能影响回答与恢复行为。
  }
};

export function createRequestHandler(options = {}) {
  const log = options.log ?? logToStderr;
  const rt = {
    cloud: options.cloud ?? createCloudAgentsClient({ origin: options.origin ?? CLOUD_API_ORIGIN, diagnostics: log }),
    getToken: options.getToken ?? defaultGetToken,
    lifetimeMs: options.lifetimeMs ?? DEFAULT_LIFETIME_MS,
    heartbeatMs: options.heartbeatMs ?? DEFAULT_HEARTBEAT_MS,
    log,
    withLock: createProcessLocks(),
    binding: null,
  };

  return async function handle(request) {
    try {
      const pathname = new URL(request.url).pathname;
      // 网关会替换函数名段，只按后缀判定，且不认识的路径一律不成功。
      if (!pathname.endsWith(CHAT_PATH_SUFFIX)) return json(404, { error: 'not_found' });
      if (request.method !== 'POST') return json(405, { error: 'method_not_allowed' }, { Allow: 'POST' });
      const input = await readJsonBody(request);
      const token = rt.getToken();
      if (typeof token !== 'string' || !token.trim()) return json(503, { error: 'service_not_configured' });
      return startStream({ request, input, rt });
    } catch (error) {
      if (error instanceof InputError) return json(error.status, { error: error.code });
      return json(503, { error: publicErrorCode(error) });
    }
  };
}
