package org.dromara.ai.core.health;

import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * {@link AiTaskHeartbeat} 单元测试（设计稿 §12.3 WARN 档「Reaper 心跳」的数据来源）。
 */
@DisplayName("后台任务心跳")
class AiTaskHeartbeatTest {

    private static final String KEY = "agenthub:ai:heartbeat:run-reaper";

    private MockedStatic<RedisUtils> redisUtils;

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        redisUtils = mockStatic(RedisUtils.class);
    }

    @AfterEach
    void tearDown() {
        redisUtils.close();
    }

    @Test
    @DisplayName("写心跳用带 TTL 的键，TTL 为数个扫描周期且不低于 60 秒")
    void shouldWriteHeartbeatWithTtl() {
        long before = System.currentTimeMillis();

        AiTaskHeartbeat.beat("run-reaper", 300);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        redisUtils.verify(() -> RedisUtils.setCacheObject(eq(KEY), value.capture(), eq(Duration.ofSeconds(1200))));
        assertTrue(Long.parseLong(value.getValue()) >= before, "心跳值应是写入时刻的毫秒时间戳");
    }

    @Test
    @DisplayName("扫描周期极短时 TTL 兜到 60 秒，避免键在下一次心跳之前就先过期")
    void shouldFloorTtlAtSixtySeconds() {
        AiTaskHeartbeat.beat("run-reaper", 10);

        redisUtils.verify(() -> RedisUtils.setCacheObject(anyString(), any(), eq(Duration.ofSeconds(60))));
    }

    @Test
    @DisplayName("Redis 写不动时静默跳过：心跳不能把业务任务本身带崩")
    void shouldSwallowWriteFailure() {
        redisUtils.when(() -> RedisUtils.setCacheObject(anyString(), any(), any(Duration.class)))
            .thenThrow(new IllegalStateException("Unable to connect to Redis"));

        assertDoesNotThrow(() -> AiTaskHeartbeat.beat("run-reaper", 300));
    }

    @Test
    @DisplayName("读回上一次心跳的秒数")
    void shouldReadBeatAge() {
        redisUtils.when(() -> RedisUtils.getCacheObject(KEY))
            .thenReturn(String.valueOf(System.currentTimeMillis() - 45_000L));

        assertEquals(45L, AiTaskHeartbeat.beatAgeSeconds("run-reaper"));
    }

    @Test
    @DisplayName("键不存在时返回 null，与「读不到」区分开")
    void shouldReturnNullWhenKeyMissing() {
        redisUtils.when(() -> RedisUtils.getCacheObject(KEY)).thenReturn(null);

        assertNull(AiTaskHeartbeat.beatAgeSeconds("run-reaper"));
        redisUtils.verify(() -> RedisUtils.getCacheObject(KEY));
        redisUtils.verify(() -> RedisUtils.setCacheObject(anyString(), any(), any(Duration.class)), never());
    }

    @Test
    @DisplayName("读取异常原样抛出：调用方要据此判 UNKNOWN 而不是当成「从未心跳」")
    void shouldPropagateReadFailure() {
        redisUtils.when(() -> RedisUtils.getCacheObject(KEY))
            .thenThrow(new IllegalStateException("connection reset"));

        assertThrows(IllegalStateException.class, () -> AiTaskHeartbeat.beatAgeSeconds("run-reaper"));
    }
}
