package org.dromara.ai.service.impl;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.mapper.AiAuditRetentionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiAuditRetentionServiceImpl} 单元测试：保留天数语义、超期行数统计与逐表分批物理清理。
 */
@DisplayName("审计数据保留策略")
class AiAuditRetentionServiceImplTest {

    /**
     * 单条 DELETE 的行数上限，与被测类的 {@code PURGE_BATCH_ROWS} 同档（§12.4 / I-6）
     */
    private static final int BATCH = 5000;

    /**
     * 每表每轮的批数上限，与被测类的 {@code PURGE_MAX_BATCHES} 同档
     */
    private static final int MAX_BATCHES = 200;

    /**
     * 清理覆盖的 6 张明细表（与 §12.4 的清单一致，不含会话正文 {@code ai_message}）
     */
    private static final List<String> PURGED_TABLES = List.of("ai_trace", "ai_tool_call_log",
        "ai_permission_decision_log", "ai_agent_task_run", "ai_route_run", "ai_route_expert_call");

    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    private final AiAuditRetentionMapper retentionMapper = mock(AiAuditRetentionMapper.class);

    private final AiAuditRetentionServiceImpl service =
        new AiAuditRetentionServiceImpl(runtimeConfigStore, retentionMapper);

    @Test
    @DisplayName("保留 0 天＝不限：不统计超期、执行清理被拒、自动清理跳过")
    void shouldSkipWhenUnlimited() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(0));

        AiAuditRetentionVo vo = service.getRetention();
        assertEquals(0, vo.getRetentionDays());
        assertNull(vo.getCutoff());
        assertTrue(vo.getPending().isEmpty());
        assertNull(vo.getPurged());

        assertThrows(ServiceException.class, () -> service.purge());
        assertNull(service.purgeIfConfigured());
        verifyNoInteractions(retentionMapper);
    }

    @Test
    @DisplayName("自动清理：保留天数大于 0 时按策略执行，不抛异常")
    void shouldAutoPurgeWhenConfigured() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(180));
        givenExpiredRows(retentionMapper, "ai_trace", 2);

        AiAuditRetentionVo vo = service.purgeIfConfigured();
        assertNotNull(vo);
        assertEquals(180, vo.getRetentionDays());
        assertEquals(2L, vo.getPurged().get("ai_trace"));
        verify(retentionMapper).deleteTraceByIds(anyList());
    }

    @Test
    @DisplayName("保留 N 天：按截止时间统计各表超期行数（6 表）")
    void shouldCountPendingRows() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.countTrace(any())).thenReturn(12L);
        when(retentionMapper.countRouteRun(any())).thenReturn(4L);

        AiAuditRetentionVo vo = service.getRetention();
        assertEquals(30, vo.getRetentionDays());
        assertNotNull(vo.getCutoff());
        assertEquals(6, vo.getPending().size());
        assertEquals(12L, vo.getPending().get("ai_trace"));
        assertEquals(4L, vo.getPending().get("ai_route_run"));
        assertEquals(0L, vo.getPending().get("ai_tool_call_log"));
        assertNull(vo.getPurged());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(retentionMapper).countTrace(cutoff.capture());
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now()));
        assertTrue(cutoff.getValue().isAfter(LocalDateTime.now().minusDays(31)));
    }

    @Test
    @DisplayName("保存保留天数写入运行期配置")
    void shouldSaveRetention() {
        AiAuditRetentionBo bo = bo(30);
        service.saveRetention(bo);
        verify(runtimeConfigStore).updateAuditRetention(bo);
    }

    @Test
    @DisplayName("保存保留天数：1~29 天低于下限被拒且不落配置")
    void shouldRejectRetentionBelowFloor() {
        ServiceException error = assertThrows(ServiceException.class, () -> service.saveRetention(bo(7)));
        assertTrue(error.getMessage().contains("30"));
        assertThrows(ServiceException.class, () -> service.saveRetention(bo(29)));
        assertThrows(ServiceException.class, () -> service.saveRetention(bo(1)));
        verify(runtimeConfigStore, never()).updateAuditRetention(any());
    }

    @Test
    @DisplayName("保存保留天数：0（不限）不受下限约束，30 天边界可保存")
    void shouldAcceptUnlimitedAndFloorRetention() {
        service.saveRetention(bo(0));
        service.saveRetention(bo(30));
        verify(runtimeConfigStore).updateAuditRetention(bo(0));
        verify(runtimeConfigStore).updateAuditRetention(bo(30));
    }

    @Test
    @DisplayName("清理：6 张表各按截止时间批删并回带逐表行数")
    void shouldPurgeByRetentionDays() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        for (String table : PURGED_TABLES) {
            givenExpiredRows(retentionMapper, table, 3);
        }

        AiAuditRetentionVo vo = service.purge();
        Map<String, Long> purged = vo.getPurged();
        assertNotNull(purged);
        assertEquals(6, purged.size());
        assertEquals(3L, purged.get("ai_trace"));
        assertEquals(3L, purged.get("ai_route_run"));
        assertEquals(3L, purged.get("ai_tool_call_log"));
        assertTrue(purged.keySet().containsAll(PURGED_TABLES));

        verify(retentionMapper).deleteTraceByIds(anyList());
        verify(retentionMapper).deleteToolCallLogByIds(anyList());
        verify(retentionMapper).deletePermissionDecisionLogByIds(anyList());
        verify(retentionMapper).deleteAgentTaskRunByIds(anyList());
        verify(retentionMapper).deleteRouteRunByIds(anyList());
        verify(retentionMapper).deleteRouteExpertCallByIds(anyList());
    }

    @Test
    @DisplayName("清理对 6 张表使用同一截止时间（now − 保留天数）")
    void shouldUseSameCutoffForAllTables() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));

        service.purge();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(retentionMapper).selectTraceIds(cutoff.capture(), anyInt());
        verify(retentionMapper).selectRouteExpertCallIds(cutoff.capture(), anyInt());
        List<LocalDateTime> values = cutoff.getAllValues();
        assertEquals(2, values.size());
        assertEquals(values.get(0), values.get(1));
        assertTrue(values.get(0).isBefore(LocalDateTime.now()));
        assertTrue(values.get(0).isAfter(LocalDateTime.now().minusDays(31)));
    }

    @Test
    @DisplayName("分批：一片取满 5000 行就继续取下一片，逐批行数累计成逐表总数")
    void shouldLoopUntilSliceIsNotFull() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.selectTraceIds(any(), anyInt()))
            .thenReturn(ids(BATCH), ids(BATCH), ids(10), List.of());
        when(retentionMapper.deleteTraceByIds(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());

        AiAuditRetentionVo vo = service.purge();

        assertEquals(10010L, vo.getPurged().get("ai_trace"));
        assertEquals(3, vo.getPurgedBatches().get("ai_trace"));
        assertTrue(vo.getPartialTables().isEmpty());
        verify(retentionMapper, times(3)).deleteTraceByIds(anyList());
    }

    @Test
    @DisplayName("分批：每片取数与单条删除的行数上限都是 5000（§12.4 单事务 ≤5000 行）")
    void shouldCapRowsPerTransaction() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));

        service.purge();

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(retentionMapper).selectTraceIds(any(), limit.capture());
        verify(retentionMapper).selectRouteExpertCallIds(any(), limit.capture());
        List<Integer> limits = limit.getAllValues();
        assertEquals(2, limits.size());
        assertTrue(limits.stream().allMatch(item -> item == BATCH), "分片行数不得放开：" + limits);
    }

    @Test
    @DisplayName("分批：没有超期行时不发任何 DELETE")
    void shouldNotDeleteWhenNothingExpired() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));

        service.purge();

        verify(retentionMapper, never()).deleteTraceByIds(anyList());
        verify(retentionMapper, never()).deleteRouteRunByIds(anyList());
    }

    @Test
    @DisplayName("分批：单表触到每轮批数上限时记为未清完，剩余留到下一轮")
    void shouldReportTableLeftForNextRound() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.selectTraceIds(any(), anyInt())).thenReturn(ids(BATCH));
        when(retentionMapper.deleteTraceByIds(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());

        AiAuditRetentionVo vo = service.purge();

        verify(retentionMapper, times(MAX_BATCHES)).deleteTraceByIds(anyList());
        assertEquals((long) MAX_BATCHES * BATCH, vo.getPurged().get("ai_trace"));
        assertEquals(MAX_BATCHES, vo.getPurgedBatches().get("ai_trace"));
        assertEquals(List.of("ai_trace"), vo.getPartialTables());
    }

    /**
     * 让某张表在清理时「有 {@code rows} 行超期」：取片返回对应条数的主键，批删按传入条数回执。
     *
     * @param mapper 被测的保留清理 Mapper
     * @param table  表名（与 {@code AiAuditRetentionServiceImpl.tables()} 的键一致）
     * @param rows   超期行数（须小于分片行数）
     */
    private static void givenExpiredRows(AiAuditRetentionMapper mapper, String table, int rows) {
        List<Long> slice = ids(rows);
        switch (table) {
            case "ai_trace" -> {
                when(mapper.selectTraceIds(any(), anyInt())).thenReturn(slice);
                when(mapper.deleteTraceByIds(anyList())).thenReturn(rows);
            }
            case "ai_tool_call_log" -> {
                when(mapper.selectToolCallLogIds(any(), anyInt())).thenReturn(slice);
                when(mapper.deleteToolCallLogByIds(anyList())).thenReturn(rows);
            }
            case "ai_permission_decision_log" -> {
                when(mapper.selectPermissionDecisionLogIds(any(), anyInt())).thenReturn(slice);
                when(mapper.deletePermissionDecisionLogByIds(anyList())).thenReturn(rows);
            }
            case "ai_agent_task_run" -> {
                when(mapper.selectAgentTaskRunIds(any(), anyInt())).thenReturn(slice);
                when(mapper.deleteAgentTaskRunByIds(anyList())).thenReturn(rows);
            }
            case "ai_route_run" -> {
                when(mapper.selectRouteRunIds(any(), anyInt())).thenReturn(slice);
                when(mapper.deleteRouteRunByIds(anyList())).thenReturn(rows);
            }
            case "ai_route_expert_call" -> {
                when(mapper.selectRouteExpertCallIds(any(), anyInt())).thenReturn(slice);
                when(mapper.deleteRouteExpertCallByIds(anyList())).thenReturn(rows);
            }
            default -> throw new IllegalArgumentException("未登记的清理表：" + table);
        }
    }

    /**
     * 造一片主键
     *
     * @param count 条数
     * @return 主键列表
     */
    private static List<Long> ids(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add((long) i);
        }
        return ids;
    }

    /**
     * 构造保留策略对象
     *
     * @param days 保留天数
     * @return 保留策略
     */
    private static AiAuditRetentionBo bo(int days) {
        AiAuditRetentionBo bo = new AiAuditRetentionBo();
        bo.setRetentionDays(days);
        return bo;
    }

}
