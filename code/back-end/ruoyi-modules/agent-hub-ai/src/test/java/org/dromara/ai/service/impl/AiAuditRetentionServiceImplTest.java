package org.dromara.ai.service.impl;

import org.dromara.ai.contract.AiPermissionKeys;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.mapper.AiAuditRetentionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiAuditRetentionServiceImpl} 单元测试：保留天数口径（下限 / 不限权限）、
 * 分批删除、父子表联动删除与清理留痕。
 */
@DisplayName("审计数据保留策略")
class AiAuditRetentionServiceImplTest {

    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    private final AiAuditRetentionMapper retentionMapper = mock(AiAuditRetentionMapper.class);

    private final PermissionService permissionService = mock(PermissionService.class);

    private final AiAuditRetentionServiceImpl service =
        new AiAuditRetentionServiceImpl(runtimeConfigStore, retentionMapper, permissionService);

    // ---------------------------------------------------------------- 口径：0＝不限

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

    // ---------------------------------------------------------------- 口径：非 0 不得低于下限

    @Test
    @DisplayName("低于下限的天数：保存被拒、手动清理被拒、自动清理跳过且不碰任何表")
    void shouldRejectDaysBelowFloor() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(7));

        assertThrows(ServiceException.class, () -> service.saveRetention(bo(7)));
        assertThrows(ServiceException.class, () -> service.purge());
        assertNull(service.purgeIfConfigured());

        verify(runtimeConfigStore, never()).updateAuditRetention(any());
        verifyNoInteractions(retentionMapper);
    }

    @Test
    @DisplayName("取值非法（负数 / 超上限）的保存被拒")
    void shouldRejectOutOfRangeDays() {
        assertThrows(ServiceException.class, () -> service.saveRetention(bo(-1)));
        assertThrows(ServiceException.class, () -> service.saveRetention(bo(3651)));
        verify(runtimeConfigStore, never()).updateAuditRetention(any());
    }

    @Test
    @DisplayName("保存合法保留天数写入运行期配置")
    void shouldSaveRetention() {
        AiAuditRetentionBo bo = bo(45);
        service.saveRetention(bo);
        verify(runtimeConfigStore).updateAuditRetention(bo);
    }

    // ---------------------------------------------------------------- 口径：「不限」需独立权限

    @Test
    @DisplayName("把天数改成 0（不限）需要「审计保留不限」权限")
    void shouldRequirePermissionForUnlimited() {
        when(permissionService.getMenuPermission(9L)).thenReturn(Set.of("ai:audit:retention:edit"));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);
            assertThrows(ServiceException.class, () -> service.saveRetention(bo(0)));
        }
        verify(runtimeConfigStore, never()).updateAuditRetention(any());
    }

    @Test
    @DisplayName("持有「审计保留不限」或超管通配权限时允许设为 0")
    void shouldAllowUnlimitedWhenGranted() {
        AiAuditRetentionBo unlimited = bo(0);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);

            when(permissionService.getMenuPermission(9L))
                .thenReturn(Set.of(AiPermissionKeys.AI_AUDIT_RETENTION_UNLIMITED));
            service.saveRetention(unlimited);

            when(permissionService.getMenuPermission(9L)).thenReturn(Set.of("*:*:*"));
            service.saveRetention(unlimited);
        }
        verify(runtimeConfigStore, times(2)).updateAuditRetention(unlimited);
    }

    @Test
    @DisplayName("取不到登录用户时拒绝设为不限（删除不可撤销，宁可不改）")
    void shouldRejectUnlimitedWithoutLogin() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenThrow(new IllegalStateException("no login context"));
            assertThrows(ServiceException.class, () -> service.saveRetention(bo(0)));
        }
        verify(runtimeConfigStore, never()).updateAuditRetention(any());
    }

    // ---------------------------------------------------------------- I-6：分批删除

    @Test
    @DisplayName("分批删除：逐批取主键直到取空，行数按批累计")
    void shouldPurgeInBatches() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.selectTraceIds(any(), eq(AiAuditRetentionServiceImpl.BATCH_ROWS)))
            .thenReturn(ids(1000), ids(200), List.of());
        when(retentionMapper.deleteTraceByIds(any())).thenAnswer(call -> size(call.getArgument(0)));

        AiAuditRetentionVo vo = service.purge();
        assertEquals(1200L, vo.getPurged().get("ai_trace"));
        // §12.4 冻结的是「单事务 ≤5000 行」，一条删除语句只覆盖一批，批大小就是单事务行数上限。
        assertTrue(AiAuditRetentionServiceImpl.BATCH_ROWS <= 5000,
            "单批删除行数不得突破 §12.4 的 5000 行上限");

        verify(retentionMapper, times(3))
            .selectTraceIds(any(LocalDateTime.class), eq(AiAuditRetentionServiceImpl.BATCH_ROWS));
        verify(retentionMapper).deleteTraceByIds(ids(1000));
        verify(retentionMapper).deleteTraceByIds(ids(200));
    }

    @Test
    @DisplayName("单表批次上限：删到上限即中止，不陷入死循环")
    void shouldStopAtBatchCeiling() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.selectToolCallLogIds(any(), anyInt())).thenReturn(List.of(1L));
        when(retentionMapper.deleteToolCallLogByIds(any())).thenReturn(1);

        AiAuditRetentionVo vo = service.purge();
        assertEquals((long) AiAuditRetentionServiceImpl.MAX_ROUNDS_PER_TABLE, vo.getPurged().get("ai_tool_call_log"));
        verify(retentionMapper, times(AiAuditRetentionServiceImpl.MAX_ROUNDS_PER_TABLE))
            .selectToolCallLogIds(any(), anyInt());
    }

    @Test
    @DisplayName("取到主键却删掉 0 行：中止本表并把剩余行留到下一轮，其余表照常清理")
    void shouldAbortWhenNothingDeleted() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.selectToolCallLogIds(any(), anyInt())).thenReturn(List.of(1L, 2L));
        when(retentionMapper.deleteToolCallLogByIds(any())).thenReturn(0);
        when(retentionMapper.selectRouteRunIds(any(), anyInt())).thenReturn(List.of(3L), List.of());
        when(retentionMapper.deleteRouteRunByIds(any())).thenReturn(1);

        AiAuditRetentionVo vo = service.purge();
        assertEquals(0L, vo.getPurged().get("ai_tool_call_log"));
        assertEquals(1L, vo.getPurged().get("ai_route_run"));
        verify(retentionMapper, times(1)).selectToolCallLogIds(any(), anyInt());
    }

    // ---------------------------------------------------------------- I-7：父子表联动删除

    @Test
    @DisplayName("父子联动：同一批先删子记录再删父记录，且用的是同一批父主键")
    void shouldDeleteChildrenBeforeParentWithinBatch() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        List<Long> runIds = List.of(11L, 12L);
        List<Long> traceIds = List.of(21L, 22L);
        when(retentionMapper.selectRouteRunIds(any(), anyInt())).thenReturn(runIds, List.of());
        when(retentionMapper.deleteRouteRunByIds(runIds)).thenReturn(2);
        when(retentionMapper.selectTraceIds(any(), anyInt())).thenReturn(traceIds, List.of());
        when(retentionMapper.deleteTraceByIds(traceIds)).thenReturn(2);
        when(retentionMapper.deleteTraceChildrenByIds(traceIds)).thenReturn(3);
        when(retentionMapper.deleteRouteExpertCallByRunIds(runIds)).thenReturn(7);

        AiAuditRetentionVo vo = service.purge();

        InOrder order = inOrder(retentionMapper);
        order.verify(retentionMapper).selectTraceIds(any(), anyInt());
        order.verify(retentionMapper).deleteTraceChildrenByIds(traceIds);
        order.verify(retentionMapper).deleteTraceByIds(traceIds);
        order.verify(retentionMapper).selectRouteRunIds(any(), anyInt());
        order.verify(retentionMapper).deleteRouteExpertCallByRunIds(runIds);
        order.verify(retentionMapper).deleteRouteRunByIds(runIds);

        // 级联删掉的子行记在子表名下：ai_trace 的子链路同表计入，专家调用单独成项
        assertEquals(5L, vo.getPurged().get("ai_trace"));
        assertEquals(7L, vo.getPurged().get("ai_route_expert_call"));
        assertEquals(2L, vo.getPurged().get("ai_route_run"));
    }

    @Test
    @DisplayName("六表清理使用同一截止时间（now − 保留天数）")
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

    // ---------------------------------------------------------------- 预检计数

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
    @DisplayName("查询回带上次清理记录")
    void shouldCarryLastPurgeRecord() {
        AiAuditRetentionVo.LastPurge lastPurge = new AiAuditRetentionVo.LastPurge();
        lastPurge.setTrigger("auto");
        lastPurge.setTotal(3L);
        when(runtimeConfigStore.auditPurgeRecord()).thenReturn(lastPurge);
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));

        assertSame(lastPurge, service.getRetention().getLastPurge());
    }

    // ---------------------------------------------------------------- 清理留痕

    @Test
    @DisplayName("手动清理落留痕：触发方式 manual、操作人、逐表行数与批次数")
    void shouldWriteManualPurgeRecord() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(30));
        when(retentionMapper.selectTraceIds(any(), anyInt())).thenReturn(ids(1000), List.of());
        when(retentionMapper.deleteTraceByIds(any())).thenAnswer(call -> size(call.getArgument(0)));

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);
            service.purge();
        }

        ArgumentCaptor<AiAuditRetentionVo.LastPurge> record =
            ArgumentCaptor.forClass(AiAuditRetentionVo.LastPurge.class);
        verify(runtimeConfigStore).recordAuditPurge(record.capture());
        AiAuditRetentionVo.LastPurge purge = record.getValue();
        assertEquals("manual", purge.getTrigger());
        assertEquals(Long.valueOf(9L), purge.getOperatorId());
        assertEquals(30, purge.getRetentionDays());
        assertEquals(1000L, purge.getTotal());
        assertEquals(1, purge.getBatches());
        assertEquals(6, purge.getPurged().size());
        assertEquals(1000L, purge.getPurged().get("ai_trace"));
        assertEquals(0L, purge.getPurged().get("ai_route_run"));
        assertNotNull(purge.getAt());
        assertNotNull(purge.getCutoff());
    }

    @Test
    @DisplayName("自动清理落留痕：触发方式 auto、无操作人")
    void shouldWriteAutoPurgeRecord() {
        when(runtimeConfigStore.auditRetention()).thenReturn(bo(180));
        when(retentionMapper.selectRouteExpertCallIds(any(), anyInt())).thenReturn(List.of(5L), List.of());
        when(retentionMapper.deleteRouteExpertCallByIds(any())).thenReturn(1);

        AiAuditRetentionVo vo = service.purgeIfConfigured();
        assertNotNull(vo);

        ArgumentCaptor<AiAuditRetentionVo.LastPurge> record =
            ArgumentCaptor.forClass(AiAuditRetentionVo.LastPurge.class);
        verify(runtimeConfigStore).recordAuditPurge(record.capture());
        assertEquals("auto", record.getValue().getTrigger());
        assertNull(record.getValue().getOperatorId());
        assertEquals(1L, record.getValue().getPurged().get("ai_route_expert_call"));
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

    /**
     * 构造一批连续主键（值从 1 开始，便于与另一批同长度列表按 equals 比对）
     *
     * @param count 主键个数
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
     * 删除行数＝本批主键个数
     *
     * @param ids 主键列表
     * @return 删除行数
     */
    private static int size(List<Long> ids) {
        return ids.size();
    }

}
