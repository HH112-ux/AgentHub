package org.dromara.ai.core.digest;

import org.dromara.ai.domain.AiDailyDigest;
import org.dromara.ai.mapper.AiDailyDigestMapper;
import org.dromara.ai.service.IPlatformDigestService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DailyDigestPublisher} 单元测试：日报落库后才通报体检事件，落库失败不许通报，
 * 通报失败也不许把已落库的日报反报成失败（§12.3 触发时机 / §12.7 I-4）。
 */
@DisplayName("运行日报落库与体检触发通报")
class DailyDigestPublisherTest {

    private static final LocalDate DIGEST_DATE = LocalDate.of(2026, 11, 20);

    @BeforeAll
    static void setUpClass() {
        AiTestSupport.initialize();
    }

    private final IPlatformDigestService digestService = mock(IPlatformDigestService.class);

    private final AiDailyDigestMapper digestMapper = mock(AiDailyDigestMapper.class);

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final DailyDigestPublisher publisher =
        new DailyDigestPublisher(digestService, digestMapper, eventPublisher);

    @Test
    @DisplayName("落库成功 → 按日期与结论级别通报一次（健康侧据此串体检）")
    void shouldAnnounceAfterLanding() {
        when(digestService.digest(DIGEST_DATE)).thenReturn(null);
        when(digestMapper.selectOne(any())).thenReturn(null);

        AiDailyDigest landed = publisher.publish(DIGEST_DATE, "结论级别：平稳\n用量：无数据", 5L);

        assertNotNull(landed);
        assertEquals(AiDailyDigest.LEVEL_STABLE, landed.getLevel());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(new DailyDigestPublishedEvent(DIGEST_DATE, AiDailyDigest.LEVEL_STABLE), event.getValue());
    }

    @Test
    @DisplayName("落库失败 → 不通报：没有日报就不该在后面挂一次体检")
    void shouldStaySilentWhenLandingFails() {
        when(digestService.digest(DIGEST_DATE)).thenReturn(null);
        when(digestMapper.selectOne(any())).thenThrow(new IllegalStateException("库不可用"));

        assertNull(publisher.publish(DIGEST_DATE, "结论级别：需处理", 5L));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("通报失败 → 日报仍算落库成功：不能把已写入的行反报成失败")
    void shouldKeepLandedDigestWhenAnnounceFails() {
        when(digestService.digest(DIGEST_DATE)).thenReturn(null);
        when(digestMapper.selectOne(any())).thenReturn(null);
        doThrow(new IllegalStateException("监听器起不来")).when(eventPublisher).publishEvent(any(Object.class));

        assertNotNull(publisher.publish(DIGEST_DATE, "结论级别：平稳", 5L));

        verify(digestMapper).insert(any(AiDailyDigest.class));
    }

}
