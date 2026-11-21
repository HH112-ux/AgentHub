package org.dromara.ai.core.audit;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.log.enums.BusinessStatus;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiAuditRetentionTask} 单元测试：多副本抢锁、跳过语义与自动清理记录投递（§12.4 / §12.7 I-6）。
 */
@DisplayName("审计数据自动清理任务")
class AiAuditRetentionTaskTest {

    private final AiProperties aiProperties = new AiProperties();

    private final IAiAuditRetentionService retentionService = mock(IAiAuditRetentionService.class);

    private final AiAuditRetentionTask task = new AiAuditRetentionTask(aiProperties, retentionService);

    @BeforeAll
    static void initStaticContext() {
        // RedisUtils / JsonUtils 的静态字段需要容器里的 Bean，否则静态 mock 会失败并污染 matcher 栈
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("清理完成后投递一条操作日志：逐表行数与批数整体进返回参数")
    void shouldRecordPurgeRun() {
        AiAuditRetentionVo vo = new AiAuditRetentionVo();
        vo.setRetentionDays(180);
        vo.setCutoff("2026-05-25 03:30:00");
        vo.setPurged(Map.of("ai_trace", 10010L, "ai_route_run", 3L));
        vo.setPurgedBatches(Map.of("ai_trace", 3, "ai_route_run", 1));
        vo.setPartialTables(List.of("ai_trace"));
        when(retentionService.purgeIfConfigured()).thenReturn(vo);

        ApplicationContext context = mock(ApplicationContext.class);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class);
             MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            acquireLock(redis);
            spring.when(SpringUtils::context).thenReturn(context);

            task.runNow();
        }

        OperLogEvent record = capturePublished(context);
        assertEquals(BusinessType.CLEAN.ordinal(), record.getBusinessType());
        assertEquals("system", record.getOperName());
        assertEquals(BusinessStatus.SUCCESS.ordinal(), record.getStatus());
        assertTrue(record.getJsonResult().contains("10010"), "逐表累计行数要进清理记录：" + record.getJsonResult());
        assertTrue(record.getJsonResult().contains("ai_route_run"), "清理记录需按类型可查：" + record.getJsonResult());
        assertTrue(record.getJsonResult().contains("partialTables"), "触顶未清完的表要进清理记录：" + record.getJsonResult());
    }

    @Test
    @DisplayName("保留天数为 0（跳过清理）时不投递清理记录")
    void shouldNotRecordWhenSkipped() {
        when(retentionService.purgeIfConfigured()).thenReturn(null);

        ApplicationContext context = mock(ApplicationContext.class);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class);
             MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            acquireLock(redis);
            spring.when(SpringUtils::context).thenReturn(context);

            task.runNow();
        }

        verify(context, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("其它实例持锁时本轮不执行清理，也不投递记录")
    void shouldYieldToOtherInstance() {
        ApplicationContext context = mock(ApplicationContext.class);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class);
             MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);
            spring.when(SpringUtils::context).thenReturn(context);

            task.runNow();
        }

        verify(retentionService, never()).purgeIfConfigured();
        verify(context, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("清理抛异常：记录按异常状态投递后原样抛出，锁照常释放")
    void shouldRecordFailureAndReleaseLock() {
        when(retentionService.purgeIfConfigured()).thenThrow(new ServiceException("库不可用"));

        ApplicationContext context = mock(ApplicationContext.class);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class);
             MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            acquireLock(redis);
            spring.when(SpringUtils::context).thenReturn(context);

            assertThrows(ServiceException.class, () -> task.runNow());

            OperLogEvent record = capturePublished(context);
            assertEquals(BusinessStatus.FAIL.ordinal(), record.getStatus());
            assertTrue(record.getErrorMsg().contains("库不可用"));
            // 锁必须在同一个静态 mock 作用域内校验，否则 deleteObject 落到真实 RedisUtils
            redis.verify(() -> RedisUtils.deleteObject("agenthub:ai:audit:retention-lock"));
        }
    }

    /**
     * 抢到分布式执行锁。
     *
     * @param redis RedisUtils 静态 mock
     */
    private static void acquireLock(MockedStatic<RedisUtils> redis) {
        redis.when(() -> RedisUtils.setObjectIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
    }

    /**
     * 取容器上投递的那条清理记录。
     *
     * @param context 被 mock 的 Spring 容器
     * @return 投递的操作日志事件
     */
    private static OperLogEvent capturePublished(ApplicationContext context) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(context).publishEvent(captor.capture());
        return (OperLogEvent) captor.getValue();
    }

}
