package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiHealthProbeBo;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.domain.vo.AiHealthReportVo;

/**
 * 平台健康自检 Service 接口。
 *
 * <p>把「开箱第一公里」与日常运维自查收敛为一屏：密钥是否配齐、向量维度是否一致、Milvus / MCP
 * 是否可用、时区是否错位、出厂密钥是否未收口等。**只读**——不提供一键修复。</p>
 *
 * @author AgentHub
 */
public interface IAiHealthCheckService {

    /**
     * 执行一次全量自检（读 60 秒结果缓存，缓存缺失时才真跑）。
     *
     * <p><b>纯本地判定，绝不发起外网调用</b>；需要真实连接的项返回
     * {@code UNKNOWN}（并标记支持探测），由 {@link #probe(AiHealthProbeBo)} 按需验证。</p>
     *
     * @return 体检报告
     */
    AiHealthReportVo check();

    /**
     * 执行一次全量自检，可选择穿透结果缓存。
     *
     * <p>缓存只是把「进页面就重算 15 项」压到 60 秒一次；无论走哪条路，返回值都会被写回缓存并
     * 重置 TTL，因此连续刷新不会把 Redis 打穿。</p>
     *
     * @param refresh {@code true} 时忽略缓存重新体检（页面「重新自检」按钮），{@code false} 时优先复用缓存
     * @return 体检报告
     */
    AiHealthReportVo check(boolean refresh);

    /**
     * 对单个体检项做真实连接探测（会发起外网 / 建连）。
     *
     * @param bo 探测目标与对象主键
     * @return 该项的探测结果（PASS / FAIL）
     */
    AiHealthCheckVo probe(AiHealthProbeBo bo);

}
