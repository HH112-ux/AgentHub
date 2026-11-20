package org.dromara.ai.core.agent;

import io.agentscope.core.state.AgentStateStore;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiSessionStateReaper} 单元测试：闲置会话状态回收的批量推进与失败隔离。
 */
@DisplayName("会话状态闲置回收")
@ExtendWith(MockitoExtension.class)
class AiSessionStateReaperTest {

    @Mock
    private AiSessionMapper sessionMapper;

    @Mock
    private AgentStateStore aiAgentStateStore;

    private final AiProperties aiProperties = new AiProperties();

    private AiSessionStateReaper reaper() {
        return new AiSessionStateReaper(sessionMapper, aiAgentStateStore, aiProperties);
    }

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("闲置会话的模型侧记忆被清理")
    void shouldClearIdleSessionState() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, 1L), session(20L, 1L)));

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            AiSessionStateReaper.ReapResult result = reaper().reap(30);

            assertEquals(new AiSessionStateReaper.ReapResult(2, 2, 0), result);
            verify(aiAgentStateStore).delete("1", "10");
            verify(aiAgentStateStore).delete("1", "20");
            // 候选扫完即重置游标，下一轮从头开始
            redis.verify(() -> RedisUtils.deleteObject("agenthub:ai:session:state-reap-cursor"));
        }
    }

    @Test
    @DisplayName("单个会话清理失败不影响其余会话")
    void shouldContinueWhenOneClearFails() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, 1L), session(20L, 1L)));
        doThrow(new IllegalStateException("redis down")).when(aiAgentStateStore).delete("1", "10");

        try (MockedStatic<RedisUtils> ignored = mockStatic(RedisUtils.class)) {
            AiSessionStateReaper.ReapResult result = reaper().reap(30);

            assertEquals(new AiSessionStateReaper.ReapResult(2, 1, 1), result);
            verify(aiAgentStateStore).delete("1", "20");
        }
    }

    @Test
    @DisplayName("缺少归属用户的会话计为失败且不清理")
    void shouldSkipSessionWithoutUser() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, null)));

        try (MockedStatic<RedisUtils> ignored = mockStatic(RedisUtils.class)) {
            AiSessionStateReaper.ReapResult result = reaper().reap(30);

            assertEquals(new AiSessionStateReaper.ReapResult(1, 0, 1), result);
            verify(aiAgentStateStore, never()).delete(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("开关关闭时不扫描也不清理")
    void shouldSkipWhenDisabled() {
        aiProperties.getChat().setSessionStateIdleDays(0);

        AiSessionStateReaper.ReapResult result = reaper().runNow();

        assertNull(result);
        verifyNoInteractions(sessionMapper);
        verifyNoInteractions(aiAgentStateStore);
    }

    private AiSession session(Long id, Long userId) {
        AiSession session = new AiSession();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }

}
