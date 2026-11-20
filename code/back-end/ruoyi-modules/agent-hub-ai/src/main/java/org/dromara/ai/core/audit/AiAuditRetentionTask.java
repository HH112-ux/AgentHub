package org.dromara.ai.core.audit;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审计与运行明细的每日自动清理任务。
 *
 * <p>补上「保留策略默认不清理、只能人工点按钮」的缺口：应用就绪后每天 03:30（本地时区，避开业务高峰）
 * 按运行期保留天数清理 {@code ai_trace} 等 6 张明细表。保留天数为 0（不限）时跳过，不删除任何数据；
 * 页面「清理过期数据」仍保留为人工立即清理入口。</p>
 *
 * <p>多副本部署用 Redis 键抢占：同一时刻只允许一个实例执行，锁 TTL 覆盖一次清理即可，异常时由
 * TTL 自动释放。清理范围与会话正文 {@code ai_message} 无关。</p>
 *
 * @author AgentHub
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAuditRetentionTask {

    /**
     * 分布式执行锁：键存在表示本轮已被某个实例占用
     */
    private static final String LOCK_KEY = "agenthub:ai:audit:retention-lock";

    /**
     * 每日执行时刻（本地时区）
     */
    private static final LocalTime PURGE_AT = LocalTime.of(3, 30);

    /**
     * 锁的最长保留时间：覆盖一次清理即可，异常退出后由 TTL 自动释放
     */
    private static final Duration LOCK_TTL = Duration.ofHours(1);

    private final AiProperties aiProperties;

    private final IAiAuditRetentionService retentionService;

    /**
     * 周期调度器（单线程守护，避免与业务线程争抢）
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-audit-retention");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 应用就绪后启动每日自动清理。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!aiProperties.getRetention().isAutoPurgeEnabled()) {
            log.info("审计数据自动清理已关闭（agenthub.ai.retention.auto-purge-enabled=false）");
            return;
        }
        scheduler.scheduleAtFixedRate(this::runQuietly, initialDelayMillis(), Duration.ofDays(1).toMillis(),
            TimeUnit.MILLISECONDS);
        log.info("审计数据自动清理已启动：每日 {} 执行，保留天数取运行期配置", PURGE_AT);
    }

    /**
     * 执行一轮清理（幂等，可重复调用）。
     */
    public void runNow() {
        Boolean acquired = RedisUtils.setObjectIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("审计数据自动清理跳过：其它实例正在执行");
            return;
        }
        try {
            AiAuditRetentionVo vo = retentionService.purgeIfConfigured();
            if (vo != null) {
                long total = vo.getPurged() == null ? 0L
                    : vo.getPurged().values().stream().mapToLong(Long::longValue).sum();
                log.info("审计数据自动清理完成：cutoff={}, total={}", vo.getCutoff(), total);
            }
        } finally {
            RedisUtils.deleteObject(LOCK_KEY);
        }
    }

    /**
     * 距下一个执行时刻的毫秒数（当天已过则顺延一天）。
     *
     * @return 首次执行延迟
     */
    private long initialDelayMillis() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(PURGE_AT);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    /**
     * 周期任务入口：任何异常只记日志，不能让调度线程退出。
     */
    private void runQuietly() {
        try {
            runNow();
        } catch (Exception e) {
            log.warn("审计数据自动清理失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 应用关闭时停止调度器。
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

}
