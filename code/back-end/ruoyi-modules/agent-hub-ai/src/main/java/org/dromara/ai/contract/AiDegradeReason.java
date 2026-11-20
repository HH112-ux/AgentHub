package org.dromara.ai.contract;

// 降级原因码的唯一常量源，取值由 script/sql 双方言脚本冻结；改动须按设计稿 §6 走契约变更流程并同步三条任务线。

public enum AiDegradeReason {
    EXPERT_FAILED("EXPERT_FAILED"),
    EXPERT_TIMEOUT("EXPERT_TIMEOUT"),
    EXPERT_EMPTY("EXPERT_EMPTY"),
    ROUTE_CONTRACT_INVALID("ROUTE_CONTRACT_INVALID"),
    BUDGET_EXCEEDED("BUDGET_EXCEEDED"),
    BM25_NOT_READY("BM25_NOT_READY"),
    RERANK_UNAVAILABLE("RERANK_UNAVAILABLE"),
    SAFETY_ENGINE_UNAVAILABLE("SAFETY_ENGINE_UNAVAILABLE"),
    MEDIA_FAILED("MEDIA_FAILED"),
    ADMIN_TERMINATED("ADMIN_TERMINATED"),
    STALE_REAPED("STALE_REAPED");

    private final String code;

    AiDegradeReason(String code) { this.code = code; }

    public String code() { return code; }
}
