// AgentHub 介绍页脚本：进入系统的跳转链接 + 在线体验问答。
// 问答通道：浏览器 → 同源 /functions/v1/app/api/chat（SSE 流）→ 站点 Function → 云端 Agent。
// 对话内容只存在这一次页面停留的内存里，随刷新消失；站点侧不落库（见 functions/chat.mjs 头部说明）。
import { consumeSSE } from "./sse-client.js";


// 云服务器真实系统地址。自签名证书会让访客第一次打开时看到浏览器全屏安全警告，
// 需点「高级 → 继续访问」；换域名并挂正式证书后此地址的行为会恢复正常。
const SYSTEM_URL = "https://47.238.161.7";

const systemButtons = [
  document.getElementById("link-system"),
  document.getElementById("link-system-2"),
  document.getElementById("link-system-3"),
].filter(Boolean);

function enterSystem() {
  if (SYSTEM_URL) {
    window.open(SYSTEM_URL, "_blank", "noopener");
  } else {
    window.alert("系统入口待云服务器部署完成后开放，敬请期待。");
  }
}

for (const button of systemButtons) {
  button.addEventListener("click", enterSystem);
}

// 在线体验问答。只介绍本页已公开的产品信息，不接触业务数据，也不具备任何执行业务操作的能力。
const CHAT_ENDPOINT = "/functions/v1/app/api/chat";
// 上限必须与 functions/chat.mjs 的 LIMITS 一致，超了会被服务端以 invalid_input 拒绝。
const MAX_HISTORY_ITEMS = 8;

const chatForm = document.getElementById("chat-form");
const chatText = document.getElementById("chat-text");
const chatMessages = document.getElementById("chat-messages");
const chatSend = document.getElementById("chat-send");

const ERROR_TEXT = {
  invalid_input: "这个问题超出了问答的长度限制，请缩短后再问。",
  rate_limited: "同时提问的人有点多，请等几秒再试。",
  timeout: "这次回答超时了，请重新提问。",
  no_answer: "这次回答没有完成，请重新提问。",
  answer_too_long: "回答过长被截断了，请把问题拆小一点再问。",
  service_not_configured: "体验助手还没有就绪，请稍后再来。",
  service_unavailable: "体验助手暂时不可用，请稍后再试。",
};

// 回传上文时压缩助手回答：助手回答上限远大于提问，原样带上会把请求体顶到服务端的体积上限，
// 之后每一问都会被拒（413），这一次页面停留就再也问不动了。600 字足够接住上下文语义。
const HISTORY_ANSWER_CHARS = 600;

function clip(value, chars) {
  const all = Array.from(value);
  return all.length > chars ? `${all.slice(0, chars).join('')}…` : value;
}

// 只保留成功完成的问答轮次；失败的那次不入历史，避免下一轮带上一句没有回答的问话。
const transcript = [];
let answering = false;

function appendMessage(text, kind) {
  const node = document.createElement("div");
  node.className = `msg msg-${kind}`;
  node.textContent = text;
  chatMessages.appendChild(node);
  chatMessages.scrollTop = chatMessages.scrollHeight;
  return node;
}

function failureText(code) {
  return ERROR_TEXT[code] ?? ERROR_TEXT.service_unavailable;
}

async function ask(text) {
  const answerNode = appendMessage("", "agent");
  let draft = "";
  let settled = false;

  const showFailure = code => {
    if (settled) return;
    settled = true;
    // 已经吐出的半句保留下来并标明中断，失败原因另起一条，避免访客把半句当成完整回答。
    if (draft) answerNode.textContent = `${draft}…（回答中断）`;
    else answerNode.remove();
    appendMessage(failureText(code), "error");
  };

  try {
    const response = await fetch(CHAT_ENDPOINT, {
      method: "POST",
      headers: { "content-type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({ text, history: transcript.slice(-MAX_HISTORY_ITEMS) }),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      showFailure(body.error);
      return;
    }
    await consumeSSE(response, frame => {
      let payload;
      try {
        payload = JSON.parse(frame.data);
      } catch {
        return false;
      }
      if (frame.event === "delta") {
        draft += typeof payload.text === "string" ? payload.text : "";
        answerNode.textContent = draft;
        chatMessages.scrollTop = chatMessages.scrollHeight;
        return false;
      }
      if (frame.event === "done") {
        settled = true;
        if (typeof payload.text === "string" && payload.text) answerNode.textContent = payload.text;
        transcript.push({ role: "user", text }, { role: "assistant", text: clip(answerNode.textContent, HISTORY_ANSWER_CHARS) });
        return true;
      }
      if (frame.event === "error") {
        showFailure(payload.code);
        return true;
      }
      return false;
    });
    // 流结束却没有收到任何终态（例如上游代理提前关闭），按失败收尾，不留访客干等。
    if (!settled) showFailure("no_answer");
  } catch {
    showFailure("service_unavailable");
  }
}

chatForm.addEventListener("submit", async event => {
  event.preventDefault();
  if (answering) return;
  const text = chatText.value.trim();
  if (!text) return;
  appendMessage(text, "user");
  chatText.value = "";
  answering = true;
  chatSend.disabled = true;
  try {
    await ask(text);
  } finally {
    answering = false;
    chatSend.disabled = false;
    chatText.focus();
  }
});
