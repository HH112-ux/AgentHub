// 本地预览：静态页 + 问答 Function 业务层，只绑 127.0.0.1，不含任何真实凭据。
//   node dev/local-server.mjs   →   http://127.0.0.1:8000/
// 上游用固定回放的假云端客户端，因此这里能验的是页面接线、SSE 分帧、增量渲染和错误文案，
// 不能验真实云端回答（那必须在部署后的站点上做）。
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

import { createRequestHandler } from "../functions/chat.mjs";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const WEB = join(ROOT, "dist");
const HOST = "127.0.0.1";
const PORT = Number(process.env.PORT ?? 8000);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
};

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

let lastFixturePrompt = "";

// 假云端：Agent/Environment 一律「已存在」，避免本地跑动时创建任何真实资源。
const fixtureCloud = {
  async findResources(kind) {
    return kind === "agents" ? [{ id: "agent_fixture", version: 1 }] : [{ id: "env_fixture" }];
  },
  async request(path, options = {}) {
    if (path === "/api/v1/cloud/models") return { data: [{ id: "fixture-model", enabled: true }] };
    if (path === "/api/v1/cloud/sessions") return { id: "sess_fixture" };
    if (path.endsWith("/events")) {
      lastFixturePrompt = options.body?.events?.[0]?.content?.[0]?.text ?? "";
      return { data: [] };
    }
    throw new Error(`本地假云端不支持的请求：${path}`);
  },
  async openStream() {
    const encoder = new TextEncoder();
    return {
      body: new ReadableStream({
        async start(controller) {
          // 提问在 openStream 之后才发出，而 start() 在流构造时就开跑：先等一帧再读回显内容。
          await delay(120);
          const answer = `【本地回放，非真实回答】收到的提问是：${lastFixturePrompt.slice(0, 160)}。这段文字用于验证流式渲染与错误通道，真实回答只在部署后的站点上产生。`;
          const parts = answer.match(/.{1,12}/gu) ?? [answer];
          for (const part of parts) {
            await delay(60);
            controller.enqueue(encoder.encode(`event: event_delta\ndata: ${JSON.stringify({
              type: "event_delta",
              delta: { type: "content_delta", index: 0, content: { type: "text", text: part } },
            })}\n\n`));
          }
          controller.enqueue(encoder.encode(`event: agent.message\ndata: ${JSON.stringify({
            type: "agent.message",
            content: [{ type: "text", text: answer }],
          })}\n\n`));
          controller.close();
        },
      }),
      close() {},
    };
  },
};

const handle = createRequestHandler({
  cloud: fixtureCloud,
  getToken: () => "local-fixture-token",
  log: event => console.log("[function]", event.event ?? event),
});

async function toWebRequest(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const headers = {};
  for (const [key, value] of Object.entries(req.headers)) {
    if (typeof value === "string") headers[key] = value;
  }
  return new Request(`http://${HOST}:${PORT}${req.url}`, {
    method: req.method,
    headers,
    body: req.method === "GET" || req.method === "HEAD" ? undefined : Buffer.concat(chunks),
  });
}

async function serveStatic(pathname, res) {
  const relative = normalize(pathname === "/" ? "index.html" : pathname.slice(1));
  if (relative.startsWith("..") || relative.includes("\0")) {
    res.writeHead(403).end("forbidden");
    return;
  }
  try {
    const file = await readFile(join(WEB, relative));
    res.writeHead(200, { "Content-Type": MIME[extname(relative)] ?? "application/octet-stream" }).end(file);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" }).end("not found");
  }
}

const server = createServer(async (req, res) => {
  const { pathname } = new URL(req.url, `http://${HOST}`);
  if (!pathname.endsWith("/api/chat")) {
    await serveStatic(pathname, res);
    return;
  }
  const response = await handle(await toWebRequest(req));
  const headers = Object.fromEntries(response.headers);
  // 必须逐块转发，整体 await 掉流会让浏览器看不到任何增量输出。
  res.writeHead(response.status, headers);
  if (response.body) {
    for await (const chunk of response.body) res.write(chunk);
  }
  res.end();
});

server.listen(PORT, HOST, () => {
  console.log(`本地预览（问答为假云端回放）： http://${HOST}:${PORT}/`);
});
