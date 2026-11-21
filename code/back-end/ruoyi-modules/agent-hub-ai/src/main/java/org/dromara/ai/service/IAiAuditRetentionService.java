package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;

/**
 * 审计与运行数据保留策略 Service。
 *
 * <p>保留天数由运行期配置（Redis {@code agenthub:ai:runtime:audit-retention}）决定，默认取
 * {@code agenthub.ai.retention.audit-days}（180 天）；每日 03:30 由 {@code AiAuditRetentionTask}
 * 自动清理一次，页面「清理过期数据」保留为人工立即清理入口。保留天数配为 0 时自动清理跳过、
 * 手动清理被拒（等价「不限」）。</p>
 *
 * <p>清理范围为运行 / 审计明细 6 表：{@code ai_trace}、{@code ai_tool_call_log}、
 * {@code ai_permission_decision_log}、{@code ai_agent_task_run}、{@code ai_route_run}、
 * {@code ai_route_expert_call}；**不含会话正文 {@code ai_message}**。</p>
 *
 * @author AgentHub
 */
public interface IAiAuditRetentionService {

    /**
     * 查询当前保留策略与各表超期行数（超期行数供页面在清理前展示体量）。
     *
     * @return 保留策略视图
     */
    AiAuditRetentionVo getRetention();

    /**
     * 保存保留策略（写入运行期配置，下一次清理即按新天数执行）。
     *
     * <p>非 0 取值不得低于 30 天（§12.4 保留期下限），否则回业务错误；
     * {@code 0} 是显式的「不限」，不受下限约束，但自动清理会跳过、手动清理会被拒。</p>
     *
     * @param bo 保留策略
     */
    void saveRetention(AiAuditRetentionBo bo);

    /**
     * 按当前保留策略清理超期数据（物理删除），返回逐表实际删除行数。
     *
     * <p>保留天数为 0 时抛出业务异常（页面需提示「先设置保留天数」）。</p>
     *
     * @return 清理结果视图
     */
    AiAuditRetentionVo purge();

    /**
     * 按当前保留策略清理超期数据；保留天数为 0（不限）时**跳过且不报错**，供定时任务调用。
     *
     * @return 清理结果视图；跳过时返回 {@code null}
     */
    AiAuditRetentionVo purgeIfConfigured();

}
