package org.dromara.ai.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.state.AgentStateStore;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话模型侧记忆的闲置过期回收任务。
 *
 * <p>补上「只终止不删除的会话让 Redis 状态永久驻留」的缺口：AgentScope 状态键没有 TTL，只有删除会话
 * （{@code AiSessionServiceImpl#clearSessionState}）时才清理。本任务按 {@code last_active_time}
 * 找出超过闲置天数的会话，逐条清掉其状态；历史消息仍在 {@code ai_message}，页面照常可查，
 * 只是模型不再记得该会话的既往上下文。</p>
 *
 * <p>阈值由 {@code agenthub.ai.chat.session-state-idle-days} 控制（默认 30 天），&lt;=0 关闭。
 * 多副本用 Redis 键抢占，同一时刻只允许一个实例执行；扫描用主键游标分批推进，
 * 游标随 Redis 持久化，避免每轮都从最旧会话重新扫。</p>
 *
 * @author AgentHub
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSessionStateReaper {

    /**
     * 分布式执行锁：键存在表示本轮已被某个实例占用
     */
    private static final String LOCK_KEY = "agenthub:ai:session:state-reap-lock";

    /**
     * 扫描游标（上次处理到的会话主键）；一轮扫完（无更多候选）时删除，下一轮从头开始
     */
    private static final String CURSOR_KEY = "agenthub:ai:session:state-reap-cursor";

    /**
     * 锁的最长保留时间：覆盖一轮扫描即可，异常退出后由 TTL 自动释放
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    /**
     * 执行间隔（每天一次）
     */
    private static final Duration INTERVAL = Duration.ofHours(24);

    /**
     * 首次执行延迟（避开启动期流量）
     */
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(15);

    /**
     * 单批扫描条数
     */
    private static final int BATCH_SIZE = 200;

    /**
     * 单次最多扫描批数，避免单轮长时间占用数据库
     */
    private static final int MAX_BATCHES = 20;

    private final AiSessionMapper sessionMapper;

    private final AgentStateStore aiAgentStateStore;

    private final AiProperties aiProperties;

    /**
     * 周期调度器（单线程守护，避免与业务线程争抢）
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-session-state-reap");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 回收统计。
     *
     * @param scanned 扫描到的闲置会话数
     * @param cleared 已清理状态数的会话数
     * @param failed  清理失败数
     */
    public record ReapResult(int scanned, int cleared, int failed) {
    }

    /**
     * 应用就绪后启动每日自动清理。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int idleDays = aiProperties.getChat().getSessionStateIdleDays();
        if (idleDays <= 0) {
            log.info("会话状态闲置回收已关闭（agenthub.ai.chat.session-state-idle-days<=0）");
            return;
        }
        scheduler.scheduleAtFixedRate(this::runQuietly, INITIAL_DELAY.toMillis(), INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS);
        log.info("会话状态闲置回收已启动：每天执行，闲置超过 {} 天的会话清理模型侧记忆", idleDays);
    }

    /**
     * 立即执行一轮回收（幂等，可重复调用）。
     *
     * @return 回收统计；开关关闭或其它实例正在执行时返回 null
     */
    public ReapResult runNow() {
        int idleDays = aiProperties.getChat().getSessionStateIdleDays();
        if (idleDays <= 0) {
            return null;
        }
        if (!Boolean.TRUE.equals(RedisUtils.setObjectIfAbsent(LOCK_KEY, "1", LOCK_TTL))) {
            log.debug("会话状态回收跳过：其它实例正在执行");
            return null;
        }
        try {
            return reap(idleDays);
        } finally {
            RedisUtils.deleteObject(LOCK_KEY);
        }
    }

    /**
     * 按主键游标分批清理闲置会话的状态。
     *
     * @param idleDays 闲置天数阈值
     * @return 回收统计
     */
    ReapResult reap(int idleDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(idleDays);
        long cursor = readCursor();
        int scanned = 0;
        int cleared = 0;
        int failed = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<AiSession> sessions = selectIdleSessions(cutoff, cursor);
            if (sessions.isEmpty()) {
                resetCursor();
                break;
            }
            for (AiSession session : sessions) {
                scanned++;
                if (clearState(session)) {
                    cleared++;
                } else {
                    failed++;
                }
            }
            cursor = sessions.get(sessions.size() - 1).getId();
            if (sessions.size() < BATCH_SIZE) {
                // 候选已扫完，下一轮从最旧会话重新开始
                resetCursor();
                break;
            }
            RedisUtils.setCacheObject(CURSOR_KEY, String.valueOf(cursor));
        }
        return new ReapResult(scanned, cleared, failed);
    }

    /**
     * 查询一批闲置会话（主键大于游标、按主键升序）。
     *
     * @param cutoff 活跃时间阈值
     * @param cursor 主键游标
     * @return 会话列表（仅取主键与归属用户）
     */
    private List<AiSession> selectIdleSessions(LocalDateTime cutoff, long cursor) {
        LambdaQueryWrapper<AiSession> lqw = Wrappers.lambdaQuery(AiSession.class);
        lqw.select(AiSession::getId, AiSession::getUserId)
            .lt(AiSession::getLastActiveTime, cutoff)
            .gt(AiSession::getId, cursor)
            .orderByAsc(AiSession::getId)
            .last("limit " + BATCH_SIZE);
        return sessionMapper.selectList(lqw);
    }

    /**
     * 清理单个会话的模型侧记忆；失败只记日志，不中断整轮回收。
     *
     * @param session 会话
     * @return 是否清理成功
     */
    private boolean clearState(AiSession session) {
        if (session.getUserId() == null) {
            // 状态键按 (userId, sessionId) 定位，缺用户无法定位，跳过
            return false;
        }
        try {
            aiAgentStateStore.delete(String.valueOf(session.getUserId()), String.valueOf(session.getId()));
            return true;
        } catch (Exception e) {
            log.warn("清理会话状态失败，sessionId={}：{}", session.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 读取扫描游标（缺失或非法时从 0 开始）。
     *
     * @return 会话主键游标
     */
    private long readCursor() {
        String value = RedisUtils.getCacheObject(CURSOR_KEY);
        try {
            return value == null || value.isBlank() ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 删除扫描游标，让下一轮从最旧会话重新开始。
     */
    private void resetCursor() {
        RedisUtils.deleteObject(CURSOR_KEY);
    }

    /**
     * 周期任务入口：任何异常只记日志，不能让调度线程退出。
     */
    private void runQuietly() {
        try {
            runNow();
        } catch (Exception e) {
            log.warn("会话状态闲置回收失败：{}", e.getMessage(), e);
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
