package org.dromara.ai.contract;

// 降级原因（ai_route_run.degrade_reason，varchar(500)）的受控词表：取值为实现里 route.degradeReason =
// 赋入的中文文案前缀。该列给人读、也给运维筛选用，因此按前缀登记；拼接型原因只冻结其固定前缀，
// 变量部分（异常摘要、Token 数）随现场变化不入契约。异常兜底路径直接落 rootCauseMessage(e)，
// 无固定前缀，不属本词表。由 e2e/http/L00-contract-consistency.mjs 校验
// 「代码字面量前缀 ⊆ 本词表」，改动须按设计稿 §6 走契约变更流程。

public enum AiDegradeReason {
    /** 续跑时指定专家已被删除 */
    EXPERT_MISSING_ON_RESUME("专家不存在或已被删除，无法续跑"),
    /** 专家调用失败/超时/空正文，后接现场摘要 */
    EXPERT_RESULT_UNAVAILABLE("专家结论不可用："),
    /** 断点恢复后仍取不到专家结论，后接现场摘要 */
    RESUMED_RESULT_UNAVAILABLE("恢复后专家结论不可用："),
    /** 决策点名的专家不在本轮候选清单 */
    EXPERT_NOT_IN_CANDIDATES("指定专家不存在或已被删除"),
    /** 结构化决策的 action 取值越界，后接原值 */
    DECISION_ACTION_INVALID("决策参数非法：action="),
    /** 结构化决策的候选专家非法 */
    DECISION_CANDIDATE_INVALID("决策参数非法：候选专家不在本轮清单内"),
    /** 编排侧抛异常，后接异常摘要 */
    ORCHESTRATION_EXCEPTION("编排异常："),
    /** Token 或墙钟超预算，括号内为超限明细 */
    BUDGET_EXCEEDED("超出编排预算（");

    private final String prefix;

    AiDegradeReason(String prefix) { this.prefix = prefix; }

    /** 落库文案的固定前缀；拼接型原因形如「前缀 + 现场摘要」，归类用 startsWith。 */
    public String prefix() { return prefix; }
}
