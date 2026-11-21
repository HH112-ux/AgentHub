package org.dromara.ai.core.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.digest.DailyDigestPublishedEvent;
import org.dromara.ai.domain.vo.AiHealthReportVo;
import org.dromara.ai.service.IAiHealthCheckService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 运行日报落库后串一次健康自检（§12.3 触发时机 / §12.7 I-4）。
 *
 * <p>§12.3 给体检定了三个触发时机：管理页手动刷新、发布流程中执行、**每日 08:00 日报生成后自动执行**。
 * 前两个早已在架，第三个缺失的后果是「日报说运行平稳、体检页还是三天前的绿」——两份对外的运行结论
 * 各自过期，看的人无从判断哪份是新的。</p>
 *
 * <p>走事件而不是直接调 {@code DailyDigestPublisher}：日报是模型回合里的一次写工具调用，
 * 体检最多要花完 30 秒整屏预算（§12.7 I-14），同步串进去等于把日报工具的耗时抬高一个量级，
 * 还会让日报落库依赖上健康模块。{@code @Async} 与底座操作日志监听同一写法。</p>
 *
 * <p>穿透结果缓存（{@code check(true)}）而不是复用：缓存存在的意义是压住页面连点，
 * 而这里的语义是「以日报落库这一刻的状态重算一份」，报告本身仍由 {@code check} 写回缓存，
 * 页面随后打开看到的就是这一份。</p>
 *
 * @author AgentHub
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyDigestHealthTrigger {

    private final IAiHealthCheckService healthCheckService;

    /**
     * 日报落库后跑一次全量体检，结果写运行健康。
     *
     * <p>体检失败只告警：日报已经落库，不能因为一次自查没跑成而把它算成异常。</p>
     *
     * @param event 日报落库事件
     */
    @Async
    @EventListener
    public void onDigestPublished(DailyDigestPublishedEvent event) {
        try {
            AiHealthReportVo report = healthCheckService.check(true);
            log.info("运行日报落库后自动体检完成：digestDate={}, overall={}, total={}, fail={}, warn={}",
                event.digestDate(), report.getOverall(), report.getTotal(), report.getFailCount(), report.getWarnCount());
        } catch (Exception e) {
            log.warn("运行日报落库后自动体检失败：digestDate={}, error={}", event.digestDate(), e.getMessage());
        }
    }

}
