package org.dromara.ai.core.health;

import org.dromara.ai.core.digest.DailyDigestPublishedEvent;
import org.dromara.ai.domain.vo.AiHealthReportVo;
import org.dromara.ai.service.IAiHealthCheckService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DailyDigestHealthTrigger} 单元测试：日报落库后串的是**一次穿透缓存的重算**，
 * 且体检自身的失败不许外溢（§12.3 触发时机 / §12.7 I-4）。
 */
@DisplayName("运行日报落库后的自动体检")
class DailyDigestHealthTriggerTest {

    private final IAiHealthCheckService healthCheckService = mock(IAiHealthCheckService.class);

    private final DailyDigestHealthTrigger trigger = new DailyDigestHealthTrigger(healthCheckService);

    @Test
    @DisplayName("日报落库 → 穿透 60 秒缓存重算一份并写回运行健康")
    void shouldRecheckPastCacheAfterDigestLanded() {
        when(healthCheckService.check(true)).thenReturn(new AiHealthReportVo());

        trigger.onDigestPublished(new DailyDigestPublishedEvent(LocalDate.of(2026, 11, 20), "0"));

        // 复用缓存（check(false) / check()）等于把三天前的报告再读一遍，日报时间点没有任何意义。
        verify(healthCheckService).check(true);
        verify(healthCheckService, never()).check(false);
        verify(healthCheckService, never()).check();
    }

    @Test
    @DisplayName("体检抛异常不外溢：日报已落库，不能因一次自查没跑成而报错")
    void shouldSwallowCheckFailure() {
        when(healthCheckService.check(anyBoolean())).thenThrow(new IllegalStateException("检项取数炸了"));

        assertDoesNotThrow(() -> trigger.onDigestPublished(new DailyDigestPublishedEvent(LocalDate.now(), "1")));

        verify(healthCheckService).check(true);
    }

}
