package org.dromara.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.contract.AiPermissionKeys;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.mapper.AiAuditRetentionMapper;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 审计与运行数据保留策略 Service 业务层处理。
 *
 * <p>保留天数取运行期配置（Redis，默认 180 天）；每日 03:30 由 {@code AiAuditRetentionTask}
 * 自动清理，页面按钮保留为人工立即清理。清理逐表按 {@code create_time < 截止时间} 物理删除
 * 并回带逐表行数。</p>
 *
 * <p><b>口径</b>：① 0＝不限（自动清理跳过、手动被拒），且改 0 需要 {@code ai:audit:retention:unlimited}；
 * ② 非 0 天数下限 {@value #MIN_RETENTION_DAYS} 天（§12.4），低于下限一律拒绝清理——删除不可撤销，
 * 配置读错时宁可少删；③ 只清运行 / 审计明细 6 表，不含会话正文 {@code ai_message}；
 * ④ 分批删除，单批 {@value #BATCH_ROWS} 行（§12.4「单事务 ≤5000 行」留余量），一条删除语句只覆盖一批，
 * 不存在跨批长事务；逐批累计的行数写进清理记录（手动清理另由底座操作日志留痕）；⑤ 父表与子表按父行联动删除
 * （§12.7 I-7）：{@code ai_route_expert_call} 跟 {@code ai_route_run}、{@code ai_trace} 的子链路跟父链路，
 * 同一批里先删子再删父，不再依赖六张表的清理顺序。</p>
 *
 * @author AgentHub
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiAuditRetentionServiceImpl implements IAiAuditRetentionService {

    /**
     * 保留天数下限（§12.4 冻结）：{@code 0}（不限）之外不得更短
     */
    public static final int MIN_RETENTION_DAYS = 30;

    /**
     * 保留天数上限，与 BO 的 {@code @Max} 同值；BO 注解已拦一道，这里兜住直读运行期配置的路径
     */
    private static final int MAX_RETENTION_DAYS_CEILING = 3650;

    /**
     * 单批删除行数：§12.4 的「单事务 ≤5000 行」是上限，取 {@value} 给 {@code in (…)} 的参数个数与
     * 语句解析留余量，同时让每批的行数累计能及时落进清理记录。
     */
    static final int BATCH_ROWS = 1000;

    /**
     * 单表批次上限（护栏）：正常清理到不了这个量级，触发即说明有依赖在挡住删除，剩下的行留到下一轮
     */
    static final int MAX_ROUNDS_PER_TABLE = 20_000;

    /**
     * 超级管理员通配权限（与 {@code AiToolSupport.PERM_ALL} 同一个底座约定）
     */
    private static final String PERM_ALL = "*:*:*";

    /**
     * 清理触发方式：页面手动
     */
    static final String TRIGGER_MANUAL = "manual";

    /**
     * 清理触发方式：每日定时任务
     */
    static final String TRIGGER_AUTO = "auto";

    /**
     * 截止时间展示格式
     */
    private static final DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final AiAuditRetentionMapper retentionMapper;

    private final PermissionService permissionService;

    @Override
    public AiAuditRetentionVo getRetention() {
        return buildVo(runtimeConfigStore.auditRetention(), null);
    }

    @Override
    public void saveRetention(AiAuditRetentionBo bo) {
        int days = bo.getRetentionDays();
        if (days < 0 || days > MAX_RETENTION_DAYS_CEILING) {
            throw new ServiceException("保留天数取值非法：" + days + "（0＝不限，其余为 1~" + MAX_RETENTION_DAYS_CEILING + "）");
        }
        if (days == 0) {
            assertUnlimitedGranted();
        } else if (days < MIN_RETENTION_DAYS) {
            throw new ServiceException("保留天数不能少于 " + MIN_RETENTION_DAYS + " 天；确需永不清理，请把天数设为 0（不限）"
                + "，该操作需要「审计保留不限」权限 " + AiPermissionKeys.AI_AUDIT_RETENTION_UNLIMITED);
        }
        runtimeConfigStore.updateAuditRetention(bo);
    }

    @Override
    public AiAuditRetentionVo purge() {
        AiAuditRetentionBo bo = runtimeConfigStore.auditRetention();
        if (bo.getRetentionDays() <= 0) {
            throw new ServiceException("保留天数为 0（不限），未执行清理；请先设置保留天数并保存");
        }
        assertNotBelowFloor(bo.getRetentionDays());
        return purge(bo, TRIGGER_MANUAL, currentUserIdOrNull());
    }

    @Override
    public AiAuditRetentionVo purgeIfConfigured() {
        AiAuditRetentionBo bo = runtimeConfigStore.auditRetention();
        if (bo.getRetentionDays() <= 0) {
            log.info("审计数据自动清理跳过：保留天数为 0（不限）");
            return null;
        }
        if (bo.getRetentionDays() < MIN_RETENTION_DAYS) {
            // 自动清理不能替一个越界的配置执行不可撤销的删除：03:30 那轮直接跳过，等人在页面上处理
            log.warn("审计数据自动清理跳过：保留天数 {} 低于下限 {} 天", bo.getRetentionDays(), MIN_RETENTION_DAYS);
            return null;
        }
        return purge(bo, TRIGGER_AUTO, null);
    }

    /**
     * 按给定保留策略分批清理。
     *
     * @param bo         保留策略（天数已保证不低于下限）
     * @param trigger    触发方式（{@value #TRIGGER_MANUAL} / {@value #TRIGGER_AUTO}）
     * @param operatorId 操作人（自动清理为 {@code null}）
     * @return 清理结果视图
     */
    private AiAuditRetentionVo purge(AiAuditRetentionBo bo, String trigger, Long operatorId) {
        LocalDateTime cutoff = cutoffOf(bo.getRetentionDays());
        Map<String, Long> purged = zeroedCounters();
        int rounds = 0;
        for (TablePurge table : tables()) {
            rounds += purgeBatched(table, cutoff, purged);
        }
        long total = purged.values().stream().mapToLong(Long::longValue).sum();
        log.info("审计数据清理完成：cutoff={}, trigger={}, total={}, batches={}, detail={}",
            cutoff, trigger, total, rounds, purged);
        writePurgeRecord(bo, cutoff, trigger, operatorId, purged, total, rounds);
        return buildVo(bo, purged);
    }

    /**
     * 单表分批删除：取一批主键 → 级联删子记录 → 删本批，直到取不到行为止。
     *
     * <p>「取到 id 却删掉 0 行」立即中止本表：继续循环就是死循环（同一批 id 会一直被取出来），
     * 中止后剩下的行留到下一轮定时清理，不静默吞掉——日志与清理记录里的行数会如实偏小。</p>
     *
     * @param table  清理任务
     * @param cutoff 截止时间
     * @param purged 逐表累计行数（就地累加，级联删掉的子行记在子表名下）
     * @return 本表用掉的批次数
     */
    private int purgeBatched(TablePurge table, LocalDateTime cutoff, Map<String, Long> purged) {
        int rounds = 0;
        while (true) {
            List<Long> ids = table.selectIds().apply(cutoff, BATCH_ROWS);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            if (table.deleteChildren() != null) {
                add(purged, table.childLabel(), table.deleteChildren().apply(ids));
            }
            int removed = table.deleteByIds().apply(ids);
            if (removed <= 0) {
                log.warn("审计清理中止：表 {} 取到 {} 条主键却删除 {} 行，剩余行留到下一轮",
                    table.label(), ids.size(), removed);
                break;
            }
            add(purged, table.label(), removed);
            if (++rounds >= MAX_ROUNDS_PER_TABLE) {
                log.warn("审计清理达到单表批次上限：表 {} 已删 {} 批（每批上限 {} 行），剩余行留到下一轮",
                    table.label(), rounds, BATCH_ROWS);
                break;
            }
        }
        return rounds;
    }

    /**
     * 清理记录落运行期配置（设计稿 §12.4「上次清理时间与各类型删除行数」）；写失败不影响已完成的删除。
     */
    private void writePurgeRecord(AiAuditRetentionBo bo, LocalDateTime cutoff, String trigger, Long operatorId,
                                  Map<String, Long> purged, long total, int rounds) {
        AiAuditRetentionVo.LastPurge record = new AiAuditRetentionVo.LastPurge();
        record.setAt(LocalDateTime.now().format(CUTOFF_FORMAT));
        record.setCutoff(cutoff.format(CUTOFF_FORMAT));
        record.setRetentionDays(bo.getRetentionDays());
        record.setTrigger(trigger);
        record.setOperatorId(operatorId);
        record.setPurged(purged);
        record.setTotal(total);
        record.setBatches(rounds);
        runtimeConfigStore.recordAuditPurge(record);
    }

    /**
     * 「不限」是永不清理的开关，按 §12.4 需要独立权限，不跟「修改保留天数」共用一个按钮权限。
     */
    private void assertUnlimitedGranted() {
        Long userId = currentUserIdOrNull();
        if (userId == null) {
            throw new ServiceException("未取到登录用户，拒绝把保留天数设为 0（不限）");
        }
        Set<String> permissions = permissionService.getMenuPermission(userId);
        boolean granted = permissions != null && (permissions.contains(PERM_ALL)
            || permissions.contains(AiPermissionKeys.AI_AUDIT_RETENTION_UNLIMITED));
        if (!granted) {
            throw new ServiceException("把保留天数设为 0（不限）需要「审计保留不限」权限："
                + AiPermissionKeys.AI_AUDIT_RETENTION_UNLIMITED);
        }
    }

    /**
     * 低于下限的天数一律拒绝清理：运行期配置（Redis）与 {@code agenthub.ai.retention.audit-days}
     * 都在本项之前没有下限校验，存量值可能是 7 天这类越界数，删除不可撤销，宁可拒绝。
     */
    private void assertNotBelowFloor(int days) {
        if (days < MIN_RETENTION_DAYS) {
            throw new ServiceException("保留天数 " + days + " 低于下限 " + MIN_RETENTION_DAYS + " 天，拒绝清理；"
                + "请在「审计数据保留」页重新设置");
        }
    }

    /**
     * 当前登录用户；定时线程没有请求上下文，取不到时返回 {@code null} 而不是让清理失败。
     */
    private Long currentUserIdOrNull() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            log.debug("未取到登录用户（自动清理线程属正常情况）：{}", e.getMessage());
            return null;
        }
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
     * 构造视图：回带保留天数、截止时间、各表超期行数，以及（可选）本次删除行数与上次清理记录。
     *
     * @param bo     当前保留策略
     * @param purged 本次删除行数（查询时为 null）
     * @return 视图对象
     */
    private AiAuditRetentionVo buildVo(AiAuditRetentionBo bo, Map<String, Long> purged) {
        AiAuditRetentionVo vo = new AiAuditRetentionVo();
        vo.setRetentionDays(bo.getRetentionDays());
        vo.setLastPurge(runtimeConfigStore.auditPurgeRecord());
        Map<String, Long> pending = zeroedCounters();
        if (bo.getRetentionDays() > 0) {
            LocalDateTime cutoff = cutoffOf(bo.getRetentionDays());
            vo.setCutoff(cutoff.format(CUTOFF_FORMAT));
            for (TablePurge table : tables()) {
                pending.put(table.label(), table.count().apply(cutoff));
            }
        } else {
            // 不限：清空占位，页面据此不显示一排 0 行的表格
            pending.clear();
        }
        vo.setPending(pending);
        vo.setPurged(purged);
        return vo;
    }

    /**
     * 六表行数计数器，键序即页面展示顺序。
     */
    private Map<String, Long> zeroedCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        for (TablePurge table : tables()) {
            counters.put(table.label(), 0L);
        }
        return counters;
    }

    private static void add(Map<String, Long> counters, String label, long rows) {
        counters.merge(label, rows, Long::sum);
    }

    /**
     * 清理任务清单（顺序即页面展示顺序；父表排在其子表之前，同批先删子再删父）。
     *
     * @return 逐表计数 / 分批删除任务
     */
    private List<TablePurge> tables() {
        return List.of(
            new TablePurge("ai_trace", "ai_trace", retentionMapper::countTrace, retentionMapper::selectTraceIds,
                retentionMapper::deleteTraceByIds, retentionMapper::deleteTraceChildrenByIds),
            new TablePurge("ai_tool_call_log", null, retentionMapper::countToolCallLog,
                retentionMapper::selectToolCallLogIds, retentionMapper::deleteToolCallLogByIds, null),
            new TablePurge("ai_permission_decision_log", null, retentionMapper::countPermissionDecisionLog,
                retentionMapper::selectPermissionDecisionLogIds, retentionMapper::deletePermissionDecisionLogByIds,
                null),
            new TablePurge("ai_agent_task_run", null, retentionMapper::countAgentTaskRun,
                retentionMapper::selectAgentTaskRunIds, retentionMapper::deleteAgentTaskRunByIds, null),
            new TablePurge("ai_route_run", "ai_route_expert_call", retentionMapper::countRouteRun,
                retentionMapper::selectRouteRunIds, retentionMapper::deleteRouteRunByIds,
                retentionMapper::deleteRouteExpertCallByRunIds),
            // 子表自己也要按时间清理：父表未超期但明细被单独改晚写的行不常见，反过来（明细超期、父行较新）会在这里删
            new TablePurge("ai_route_expert_call", null, retentionMapper::countRouteExpertCall,
                retentionMapper::selectRouteExpertCallIds, retentionMapper::deleteRouteExpertCallByIds, null));
    }

    /**
     * 单表清理任务。
     *
     * @param label          表名（同时作为视图里的键）
     * @param childLabel     级联子表的键名，无子表时为 {@code null}
     * @param count          超期行数统计（口径须与取 id 的谓词一致，否则预检与实删对不上）
     * @param selectIds      取一批超期主键（截止时间，单批上限）
     * @param deleteByIds    按主键批量物理删除
     * @param deleteChildren 按本批父主键删除子记录，无子表时为 {@code null}
     */
    private record TablePurge(String label, String childLabel, Function<LocalDateTime, Long> count,
                              BiFunction<LocalDateTime, Integer, List<Long>> selectIds,
                              Function<List<Long>, Integer> deleteByIds,
                              Function<List<Long>, Integer> deleteChildren) {
    }

}
