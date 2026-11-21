package org.dromara.ai.core.health;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;

import java.time.Duration;

/**
 * 后台任务的存活心跳（设计稿 §12.3 WARN 档「Reaper 心跳超 2 个扫描周期」）。
 *
 * <p>本项目的定时任务全部是进程内 {@code ScheduledExecutorService}（无 XXL-Job 一类的调度中心），
 * 因此「任务还在不在跑」在进程外唯一可读的凭证就是一个带 TTL 的 Redis 键：跑一次写一次，
 * 健康自检按键的年龄判定。键不存在有两种含义——进程刚起还没到第一轮、或调度线程已死，
 * 前者由 {@link #beatAge(String)} 返回空值区分，调用方据此给「启动未满一个周期」而非报警。</p>
 *
 * <p>心跳只写这一个键、不参与任何业务判定，Redis 不可用时静默跳过（不能让心跳把任务本身带崩）。</p>
 *
 * @author AgentHub
 */
@Slf4j
public final class AiTaskHeartbeat {

    /**
     * 心跳键前缀，后接任务名
     */
    private static final String KEY_PREFIX = "agenthub:ai:heartbeat:";

    /**
     * 键的存活倍数：TTL 取扫描周期的 4 倍，保证自检读到的键不会因为一次抖动就消失
     */
    private static final int TTL_FACTOR = 4;

    private AiTaskHeartbeat() {
    }

    /**
     * 记录一次「本任务刚跑完」。
     *
     * @param task           任务名（如 {@code run-reaper}）
     * @param intervalSeconds 该任务的扫描周期（秒）
     */
    public static void beat(String task, long intervalSeconds) {
        long ttlSeconds = Math.max(60L, intervalSeconds * TTL_FACTOR);
        try {
            RedisUtils.setCacheObject(KEY_PREFIX + task,
                String.valueOf(System.currentTimeMillis()), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("任务心跳写入失败：task={}, cause={}", task, e.getMessage());
        }
    }

    /**
     * 距离上一次心跳过去了多少秒。
     *
     * <p>读取异常**不外抛**到这里、而是留给调用方：Redis 读不动与「键确实不存在」是两个结论
     * （前者只能报未知，后者说明调度线程出问题了），在本方法里吞掉异常就把它们混成同一个了。</p>
     *
     * @param task 任务名
     * @return 秒数；从未心跳（键不存在）时返回 {@code null}
     * @throws RuntimeException Redis 读取失败，或键值不是可解析的时间戳
     */
    public static Long beatAgeSeconds(String task) {
        Object raw = RedisUtils.getCacheObject(KEY_PREFIX + task);
        if (raw == null) {
            return null;
        }
        long at = Long.parseLong(String.valueOf(raw));
        return Math.max(0L, (System.currentTimeMillis() - at) / 1000L);
    }
}
