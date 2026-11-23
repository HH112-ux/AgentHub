// AgentHub 介绍页脚本：跳转链接与在线体验问答（问答通道由云端 Agent 接入后启用）。

// 系统（云服务器）地址：由队长在云服务器与域名到位后填入，如 "https://system.example.com"。
// 留空时「进入系统」按钮给出提示，不产生跳转。
const SYSTEM_URL = "";

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

// 在线体验问答：体验通道接入中。接入后由云端 Agent（QCA）经本站点后端转发回答，
// 问答只接触公开介绍内容与平台使用文档，不接触任何业务数据。
const chatForm = document.getElementById("chat-form");
const chatText = document.getElementById("chat-text");
const chatMessages = document.getElementById("chat-messages");
const chatSend = document.getElementById("chat-send");
const chatNote = document.getElementById("chat-note");

function appendMessage(text, kind) {
  const node = document.createElement("div");
  node.className = `msg msg-${kind}`;
  node.textContent = text;
  chatMessages.appendChild(node);
  chatMessages.scrollTop = chatMessages.scrollHeight;
}

chatForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const text = chatText.value.trim();
  if (!text) {
    return;
  }
  appendMessage(text, "user");
  chatText.value = "";
  chatSend.disabled = true;
  appendMessage("体验通道接入中，暂时无法回答。请稍后再来，或直接点击「进入系统」体验完整功能。", "error");
  chatSend.disabled = false;
  chatText.focus();
});
