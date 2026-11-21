package org.dromara.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.mapper.AiAuditRetentionMapper;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 审计与运行数据保留策略 Service 业务层处理。
 *
 * <p>保留天数取运行期配置（Redis，默认 180 天）；每日 03:30 由 {@code AiAuditRetentionTask}
 * 自动清理，页面按钮保留为人工立即清理。清理逐表按 {@code create_time < 截止时间} **物理删除**
 * 并回带逐表行数。</p>
 *
 * <p><b>口径</b>：① 0＝不清理（自动跳过、手动被拒），非 0 时不得低于 30 天（§12.4，保存侧硬拦）；② 只清运行 / 审计明细 6 表，不含会话正文
 * {@code ai_message}；③ 逐表分批删除，<b>单条 DELETE 封顶 {@code PURGE_BATCH_ROWS} 行</b>（§12.4 / §12.7 I-6），
 * 每批各自原子、不加跨表事务（避免长事务持锁）；④ 子表按<b>父记录</b>的截止时间联动删除并认领孤儿
 * （§12.7 I-7），因此逐表顺序不再影响结果；⑤ 逐批行数在本方法内累计成逐表总数，由调用侧写清理记录
 * （手动＝切面的操作日志，自动＝{@code AiAuditRetentionTask} 投递的操作日志事件）。</p>
 *
 * @author AgentHub
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiAuditRetentionServiceImpl implements IAiAuditRetentionService {

    /**
     * 截止时间展示格式
     */
    private static final DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 保留期下限（§12.4 越权约束）：低于 30 天等于把审计留档窗口缩到不足一个月，
     * 事后回溯还没开始就结束了；因此 1~29 天一律拒绝保存。
     * {@code 0} 是显式的「不限」（自动清理跳过、手动清理被拒），不走下限。
     */
    private static final int MIN_RETENTION_DAYS = 30;

    /**
     * 单条 DELETE 的行数上限（§12.4「单事务 ≤5000 行避免长锁」/ §12.7 I-6）。
     * 超期数据再多也按片循环，一片一个事务，不会把百万行压进一条语句。
     */
    private static final int PURGE_BATCH_ROWS = 5000;

    /**
     * 每表每轮的批数上限：{@code PURGE_BATCH_ROWS × 本值} 即单轮单表最多删除行数。
     * 定时任务的 Redis 锁租约只有 1 小时，积压特别大时宁可留到明天 03:30 继续，
     * 也不能让调度线程一路删下去；触顶的表会计入视图 {@code partialTables} 并打 WARN。
     */
    private static final int PURGE_MAX_BATCHES = 200;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final AiAuditRetentionMapper retentionMapper;

    @Override
    public AiAuditRetentionVo getRetention() {
        return buildVo(runtimeConfigStore.auditRetention(), null, null, null);
    }

    @Override
    public void saveRetention(AiAuditRetentionBo bo) {
        int days = bo.getRetentionDays();
        if (days > 0 && days < MIN_RETENTION_DAYS) {
            throw new ServiceException("保留天数不能少于 " + MIN_RETENTION_DAYS
                + " 天；如需长期保留请设为 0（不限，自动清理将跳过）");
        }
        runtimeConfigStore.updateAuditRetention(bo);
    }

    @Override
    public AiAuditRetentionVo purge() {
        AiAuditRetentionBo bo = runtimeConfigStore.auditRetention();
        if (bo.getRetentionDays() <= 0) {
            throw new ServiceException("保留天数为 0（不限），未执行清理；请先设置保留天数并保存");
        }
        return purge(bo);
    }

    @Override
    public AiAuditRetentionVo purgeIfConfigured() {
        AiAuditRetentionBo bo = runtimeConfigStore.auditRetention();
        if (bo.getRetentionDays() <= 0) {
            log.info("审计数据自动清理跳过：保留天数为 0（不限）");
            return null;
        }
        return purge(bo);
    }

    /**
     * 按给定保留策略执行清理。
     *
     * @param bo 保留策略（天数已保证大于 0）
     * @return 清理结果视图
     */
    private AiAuditRetentionVo purge(AiAuditRetentionBo bo) {
        LocalDateTime cutoff = cutoffOf(bo.getRetentionDays());
        Map<String, Long> purged = new LinkedHashMap<>();
        Map<String, Integer> batches = new LinkedHashMap<>();
        List<String> partialTables = new ArrayList<>();
        long total = 0L;
        for (TableOps ops : tables()) {
            TablePurge result = purgeTable(ops, cutoff);
            purged.put(ops.label(), result.rows());
            batches.put(ops.label(), result.batches());
            if (!result.completed()) {
                partialTables.add(ops.label());
                log.warn("审计数据清理触顶：table={}, rows={}, batches={}，剩余留待下一轮", ops.label(), result.rows(), result.batches());
            }
            total += result.rows();
        }
        log.info("审计数据清理完成：cutoff={}, total={}, detail={}, batches={}", cutoff, total, purged, batches);
        return buildVo(bo, purged, batches, partialTables);
    }

    /**
     * 逐批清理单表：取一片主键 → 删这一片 → 累计行数，直到取不满一片。
     *
     * <p>取数与删除同用一条谓词（子表谓词还带父记录联动），因此「父先删完、子还没删」时
     * 子记录会以孤儿身份被下一片捞走，逐表顺序不影响最终结果（§12.7 I-7）。</p>
     *
     * @param ops    单表清理任务
     * @param cutoff 保留截止时间
     * @return 本表本轮的累计行数、批数与是否清完
     */
    private TablePurge purgeTable(TableOps ops, LocalDateTime cutoff) {
        long rows = 0L;
        int batches = 0;
        while (batches < PURGE_MAX_BATCHES) {
            List<Long> ids = ops.selectIds().apply(cutoff, PURGE_BATCH_ROWS);
            if (ids.isEmpty()) {
                return new TablePurge(rows, batches, true);
            }
            rows += ops.purge().apply(ids);
            batches++;
            if (ids.size() < PURGE_BATCH_ROWS) {
                return new TablePurge(rows, batches, true);
            }
        }
        return new TablePurge(rows, batches, false);
    }

    /**
     * 按保留天数计算截止时间（早于该时间的行会被清理）。
     *
     * @param days 保留天数
     * @return 截止时间
     */
    private LocalDateTime cutoffOf(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    /**
     * 构造视图：回带保留天数、截止时间、各表超期行数，以及（可选）本次删除行数与批数。
     *
     * @param bo            当前保留策略
     * @param purged        本次删除行数（查询时为 null）
     * @param batches       本次删除批数（查询时为 null）
     * @param partialTables 本轮触顶未清完的表（查询时为 null）
     * @return 视图对象
     */
    private AiAuditRetentionVo buildVo(AiAuditRetentionBo bo, Map<String, Long> purged,
                                       Map<String, Integer> batches, List<String> partialTables) {
        AiAuditRetentionVo vo = new AiAuditRetentionVo();
        vo.setRetentionDays(bo.getRetentionDays());
        Map<String, Long> pending = new LinkedHashMap<>();
        if (bo.getRetentionDays() > 0) {
            LocalDateTime cutoff = cutoffOf(bo.getRetentionDays());
            vo.setCutoff(cutoff.format(CUTOFF_FORMAT));
            for (TableOps ops : tables()) {
                pending.put(ops.label(), ops.count().apply(cutoff));
            }
        }
        vo.setPending(pending);
        vo.setPurged(purged);
        vo.setPurgedBatches(batches);
        vo.setPartialTables(partialTables);
        return vo;
    }

    /**
     * 清理任务清单（顺序即页面展示顺序；父子联动后逐表顺序不再影响结果）。
     *
     * @return 逐表计数 / 取片 / 批删任务
     */
    private List<TableOps> tables() {
        return List.of(
            new TableOps("ai_trace", retentionMapper::countTrace, retentionMapper::selectTraceIds,
                retentionMapper::deleteTraceByIds),
            new TableOps("ai_tool_call_log", retentionMapper::countToolCallLog, retentionMapper::selectToolCallLogIds,
                retentionMapper::deleteToolCallLogByIds),
            new TableOps("ai_permission_decision_log", retentionMapper::countPermissionDecisionLog,
                retentionMapper::selectPermissionDecisionLogIds, retentionMapper::deletePermissionDecisionLogByIds),
            new TableOps("ai_agent_task_run", retentionMapper::countAgentTaskRun, retentionMapper::selectAgentTaskRunIds,
                retentionMapper::deleteAgentTaskRunByIds),
            new TableOps("ai_route_run", retentionMapper::countRouteRun, retentionMapper::selectRouteRunIds,
                retentionMapper::deleteRouteRunByIds),
            new TableOps("ai_route_expert_call", retentionMapper::countRouteExpertCall,
                retentionMapper::selectRouteExpertCallIds, retentionMapper::deleteRouteExpertCallByIds));
    }

    /**
     * 单表清理任务（表名 + 计数 + 取一片主键 + 按主键批删）。
     *
     * @param label     表名（同时作为视图里的键）
     * @param count     计数函数
     * @param selectIds 取一片待删主键的函数
     * @param purge     按主键批删函数
     */
    private record TableOps(String label, Function<LocalDateTime, Long> count,
                            BiFunction<LocalDateTime, Integer, List<Long>> selectIds,
                            Function<List<Long>, Integer> purge) {
    }

    /**
     * 单表本轮清理结果。
     *
     * @param rows      累计删除行数
     * @param batches   实际执行的批数
     * @param completed 是否已清完（{@code false} 表示触顶，剩余留待下一轮）
     */
    private record TablePurge(long rows, int batches, boolean completed) {
    }

}
