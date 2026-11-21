package org.dromara.ai.core.chat;

import org.dromara.ai.config.AiProperties;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiAttachmentReclaimTask} 单元测试：一轮内「先暂存件、后孤立件」的顺序与抢锁语义（§12.6 / §12.7 I-10）。
 *
 * <p>顺序之所以是契约而不是实现细节：{@code PENDING} 占着作者的上传配额，配额堵住的是「这位用户还能不能上传」，
 * 而 {@code ORPHANED} 只是对象文件晚一点释放。</p>
 */
@DisplayName("附件到期回收任务")
class AiAttachmentReclaimTaskTest {

    private static final AiAttachmentReclaimer.ReclaimResult PENDING_RUN = new AiAttachmentReclaimer.ReclaimResult(7, 5, 0, 2);

    private static final AiAttachmentReclaimer.ReclaimResult ORPHAN_RUN = new AiAttachmentReclaimer.ReclaimResult(3, 0, 3, 0);

    private final AiProperties aiProperties = new AiProperties();

    private final AiAttachmentReclaimer reclaimer = mock(AiAttachmentReclaimer.class);

    private final AiAttachmentReclaimTask task = new AiAttachmentReclaimTask(aiProperties, reclaimer);

    @Test
    @DisplayName("抢到锁：先收到期暂存件再收到期孤立件，统计相加返回")
    void shouldReclaimPendingBeforeOrphans() {
        when(reclaimer.reclaimPending(anyInt(), anyInt())).thenReturn(PENDING_RUN);
        when(reclaimer.reclaimOrphans(anyInt())).thenReturn(ORPHAN_RUN);

        AiAttachmentReclaimer.ReclaimResult result;
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            acquireLock(redis);
            result = task.runNow();
        }

        InOrder order = inOrder(reclaimer);
        order.verify(reclaimer).reclaimPending(aiProperties.getChat().getPendingAttachmentTtlHours(), 20);
        order.verify(reclaimer).reclaimOrphans(20);
        assertEquals(10, result.scanned(), "两遍扫描的条数要相加，运维接口按合计判断本轮做了多少活");
        assertEquals(5, result.deleted());
        assertEquals(3, result.recycled());
        assertEquals(2, result.failed());
    }

    @Test
    @DisplayName("暂存阈值关闭（<=0）时孤立件那一遍照跑，且阈值原样下传")
    void shouldStillReclaimOrphansWhenPendingTtlDisabled() {
        aiProperties.getChat().setPendingAttachmentTtlHours(0);
        when(reclaimer.reclaimPending(anyInt(), anyInt())).thenReturn(new AiAttachmentReclaimer.ReclaimResult(0, 0, 0, 0));
        when(reclaimer.reclaimOrphans(anyInt())).thenReturn(ORPHAN_RUN);

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            acquireLock(redis);
            task.runNow();
        }

        ArgumentCaptor<Integer> ttl = ArgumentCaptor.forClass(Integer.class);
        verify(reclaimer).reclaimPending(ttl.capture(), anyInt());
        assertEquals(0, ttl.getValue(), "开关由回收器按同一个阈值判，任务侧不得自行改成「已关闭」的别的值");
        // 会话删完后不再产生暂存件，把两遍捆在一个开关上会让已孤立的对象永远留在桶里
        verify(reclaimer).reclaimOrphans(anyInt());
    }

    @Test
    @DisplayName("其它实例持锁：本轮两遍都不执行，返回 null")
    void shouldYieldToOtherInstance() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.setObjectIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);
            assertNull(task.runNow());
        }
        verifyNoInteractions(reclaimer);
    }

    @Test
    @DisplayName("回收抛异常：锁照常释放，异常原样上抛")
    void shouldReleaseLockOnFailure() {
        when(reclaimer.reclaimPending(anyInt(), anyInt())).thenThrow(new IllegalStateException("库不可用"));

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            acquireLock(redis);
            assertThrows(IllegalStateException.class, () -> task.runNow());
            // 释锁必须校验在同一个静态 mock 作用域内，否则 deleteObject 落到真实 RedisUtils
            redis.verify(() -> RedisUtils.deleteObject("agenthub:ai:chat:attachment-reclaim-lock"));
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

}
