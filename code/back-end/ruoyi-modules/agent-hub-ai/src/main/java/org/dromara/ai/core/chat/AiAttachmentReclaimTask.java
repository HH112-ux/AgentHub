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
 * 附件到期回收任务（§12.6 / §12.7 I-10）。
 *
 * <p>补上「上传后未发送也未移除的附件永久残留、删除后的附件对象无人收」两个缺口：应用就绪后每小时跑一轮，
 * 每轮先收到期 {@code PENDING}（删对象 + 删行），再收到期 {@code ORPHANED}（删对象 + 转 {@code RECYCLED} 留元数据）。
 * 顺序不能反：{@code PENDING} 占着作者配额，配额堵住的是「这位用户还能不能上传」，所以它先出血。</p>
 *
 * <p>暂存阈值由 {@code agenthub.ai.chat.pending-attachment-ttl-hours} 控制，&lt;=0 时**只关闭暂存件那一遍**；
 * 孤立件的宽限由 {@code orphan-attachment-ttl-hours} 控制（&lt;=0 表示不留宽限、下一轮即删对象），
 * 不受前者影响——会话删完了就不再产生暂存件，把两遍捆在一个开关上会让已转孤立的对象永远留在桶里。</p>
 *
 * <p>多副本用 Redis 键抢占：同一时刻只允许一个实例执行，异常时由 TTL 自动释放。</p>
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
     * 每遍扫描的最多批数（每批 {@link AiAttachmentReclaimer#BATCH_SIZE} 条；暂存件与孤立件各算一遍）
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
        int graceHours = aiProperties.getChat().getOrphanAttachmentTtlHours();
        scheduler.scheduleAtFixedRate(this::runQuietly, INITIAL_DELAY.toMillis(), INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS);
        log.info("附件到期回收已启动：每小时一轮；暂存件保留 {} 小时（<=0 关闭暂存件那一遍），孤立件留档宽限 {} 小时",
            ttlHours, graceHours);
    }

    /**
     * 立即执行一轮回收（幂等，可重复调用）：先暂存件、后孤立件，两遍都最旧的先收。
     *
     * @return 回收统计；其它实例正在执行时返回 null
     */
    public AiAttachmentReclaimer.ReclaimResult runNow() {
        if (!Boolean.TRUE.equals(RedisUtils.setObjectIfAbsent(LOCK_KEY, "1", LOCK_TTL))) {
            log.debug("附件到期回收跳过：其它实例正在执行");
            return null;
        }
        try {
            int ttlHours = aiProperties.getChat().getPendingAttachmentTtlHours();
            return reclaimer.reclaimPending(ttlHours, MAX_BATCHES)
                .plus(reclaimer.reclaimOrphans(MAX_BATCHES));
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
