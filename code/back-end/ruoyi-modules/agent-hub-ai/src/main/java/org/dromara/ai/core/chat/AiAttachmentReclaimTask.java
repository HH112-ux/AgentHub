package org.dromara.ai.core.chat;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 未发送暂存附件的自动回收任务。
 *
 * <p>补上「上传后未发送也未移除的附件永久残留」的缺口：应用就绪后每小时扫描一次
 * {@code ai_message_attachment} 中 {@code message_id} 为空且超过 TTL 的行，先删对象存储文件、
 * 再物理删行。TTL 由 {@code agenthub.ai.chat.pending-attachment-ttl-hours} 控制，&lt;=0 时关闭自动回收。</p>
 *
 * <p>多副本用 Redis 键抢占：同一时刻只允许一个实例执行，异常时由 TTL 自动释放。
 * 已发送且会话仍存在的附件不在此任务范围（仅随删除会话 / 删除整轮回收）。</p>
 *
 * @author AgentHub
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAttachmentReclaimTask {

    /**
     * 分布式执行锁：键存在表示本轮已被某个实例占用
     */
    private static final String LOCK_KEY = "agenthub:ai:chat:attachment-reclaim-lock";

    /**
     * 锁的最长保留时间：覆盖一轮扫描即可，异常退出后由 TTL 自动释放
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    /**
     * 执行间隔（每小时）
     */
    private static final Duration INTERVAL = Duration.ofHours(1);

    /**
     * 首次执行延迟（避开启动期流量）
     */
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);

    /**
     * 单次最多扫描批数（每批 {@link AiAttachmentReclaimer#BATCH_SIZE} 条）
     */
    private static final int MAX_BATCHES = 20;

    private final AiProperties aiProperties;

    private final AiAttachmentReclaimer reclaimer;

    /**
     * 周期调度器（单线程守护，避免与业务线程争抢）
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-attachment-reclaim");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 应用就绪后启动每小时自动回收。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int ttlHours = aiProperties.getChat().getPendingAttachmentTtlHours();
        if (ttlHours <= 0) {
            log.info("暂存附件自动回收已关闭（agenthub.ai.chat.pending-attachment-ttl-hours<=0）");
            return;
        }
        scheduler.scheduleAtFixedRate(this::runQuietly, INITIAL_DELAY.toMillis(), INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS);
        log.info("暂存附件自动回收已启动：每小时执行，保留 {} 小时", ttlHours);
    }

    /**
     * 立即执行一轮回收（幂等，可重复调用）。
     *
     * @return 回收统计；其它实例正在执行时返回 null
     */
    public AiAttachmentReclaimer.ReclaimResult runNow() {
        if (!Boolean.TRUE.equals(RedisUtils.setObjectIfAbsent(LOCK_KEY, "1", LOCK_TTL))) {
            log.debug("暂存附件回收跳过：其它实例正在执行");
            return null;
        }
        try {
            return reclaimer.reclaimPending(aiProperties.getChat().getPendingAttachmentTtlHours(), MAX_BATCHES);
        } finally {
            RedisUtils.deleteObject(LOCK_KEY);
        }
    }

    /**
     * 周期任务入口：任何异常只记日志，不能让调度线程退出。
     */
    private void runQuietly() {
        try {
            runNow();
        } catch (Exception e) {
            log.warn("暂存附件自动回收失败：{}", e.getMessage(), e);
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
