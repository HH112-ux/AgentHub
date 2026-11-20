package org.dromara.ai.contract;

// SSE 事件类型的唯一常量源，取值由 script/sql 双方言脚本冻结；改动须按设计稿 §6 走契约变更流程并同步三条任务线。

public enum AiEventType {
    TEXT_DELTA("text_delta"),
    ROUTE_HINT("route_hint"),
    EXPERT_START("expert_start"),
    EXPERT_RESULT("expert_result"),
    TOOL_CALL_START("tool_call_start"),
    TOOL_CALL_END("tool_call_end"),
    CONFIRM_REQUEST("confirm_request"),
    CONFIRM_RESULT("confirm_result"),
    PROGRESS("progress"),
    SOURCES("sources"),
    STOPPED("stopped"),
    TURN_DONE("turn_done"),
    TURN_ERROR("turn_error");

    private final String wire;

    AiEventType(String wire) { this.wire = wire; }

    public String wire() { return wire; }
}
