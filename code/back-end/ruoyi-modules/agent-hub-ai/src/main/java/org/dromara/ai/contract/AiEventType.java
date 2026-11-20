package org.dromara.ai.contract;

// SSE 事件类型的唯一常量源，取值为实现里 AiChatFacade / AiStreamEventBridge / AiRouteOrchestrator /
// AiTurnFinisher / AiPhaseSupport 各 EVENT_* 常量的线上值；前端 api/ai/chat/types.ts 的同名联合须与本枚举一致，
// 由 e2e/http/L00-contract-consistency.mjs 校验。改动须按设计稿 §6 走契约变更流程。

public enum AiEventType {
    /** 本轮开始，回 assistant 消息 id */
    START("start"),
    /** 正文增量（入口或专家） */
    TEXT_DELTA("text_delta"),
    /** 工具调用开始，回 toolCallId 与入参 */
    TOOL_CALL_START("tool_call_start"),
    /** 工具入参流式增量 */
    TOOL_CALL_DELTA("tool_call_delta"),
    /** 工具执行结果 */
    TOOL_RESULT("tool_result"),
    /** 写操作待确认，用户点确认后走 /confirm */
    TOOL_CONFIRM("tool_confirm"),
    /** 本轮引用的知识库分片 */
    REFERENCES("references"),
    /** 编排决策（self / route / pinned 与命中专家） */
    ROUTE_DECISION("route_decision"),
    /** 专家接管开始 */
    EXPERT_START("expert_start"),
    /** 专家本轮结论与状态 */
    EXPERT_RESULT("expert_result"),
    /** 编排回落自答，附降级原因 */
    ROUTE_FALLBACK("route_fallback"),
    /** 正文作废（回落或改口时通知前端清空已渲染片段） */
    ANSWER_RESET("answer_reset"),
    /** 阶段进度，取值见 AiPhaseSupport */
    PHASE("phase"),
    /** 本轮正常收尾 */
    DONE("done"),
    /** 本轮异常收尾 */
    ERROR("error"),
    /** 被中止（用户停止或管理员终止） */
    ABORTED("aborted");

    private final String wire;

    AiEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
