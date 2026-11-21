package org.dromara.ai.core.audit;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.log.enums.BusinessStatus;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.log.enums.OperatorType;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
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

    /**
     * 清理记录的操作模块名：与手动清理那条（切面落的「审计数据清理」）同前缀，操作日志页可按标题检索
     */
    private static final String TITLE = "审计数据清理（自动）";

    /**
     * 清理记录的请求方式：调度线程发起，不走 HTTP（{@code sys_oper_log.request_method} 限 10 字符）
     */
    private static final String REQUEST_METHOD = "SCHEDULE";

    /**
     * 清理记录的操作人：自动任务无登录用户，按系统身份记
     */
    private static final String SYSTEM_OPERATOR = "system";

    /**
     * 异常信息入库长度上限，与底座 {@code LogAspect} 的正文截断同档
     */
    private static final int MAX_ERROR_LENGTH = 2000;

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
     *
     * <p>一轮无论删了多少批，都只投递<b>一条</b>清理记录：逐表累计行数、批数与截止时间整体写进
     * 返回参数，与手动清理那条（{@code @Log} 切面落的）在「操作日志」页同表可查（§12.4 / §12.7 I-6）。</p>
     */
    public void runNow() {
        Boolean acquired = RedisUtils.setObjectIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("审计数据自动清理跳过：其它实例正在执行");
            return;
        }
        long startedAt = System.nanoTime();
        try {
            AiAuditRetentionVo vo = retentionService.purgeIfConfigured();
            if (vo != null) {
                long total = vo.getPurged() == null ? 0L
                    : vo.getPurged().values().stream().mapToLong(Long::longValue).sum();
                log.info("审计数据自动清理完成：cutoff={}, total={}", vo.getCutoff(), total);
                recordPurge(vo, null, costMillis(startedAt));
            }
        } catch (Exception e) {
            recordPurge(null, e, costMillis(startedAt));
            throw e;
        } finally {
            RedisUtils.deleteObject(LOCK_KEY);
        }
    }

    /**
     * 投递自动清理记录到底座操作日志。
     *
     * <p>自动任务没有请求上下文，{@code LogAspect} 那条路走不到（它要先取
     * {@code HttpServletRequest}），因此这里直接发一个 {@link OperLogEvent}，由底座的异步监听落库。
     * 投递失败不能带崩清理本身，故整体吞异常只记日志。</p>
     *
     * @param vo        清理结果（异常时为 null）
     * @param error     清理异常（正常完成时为 null）
     * @param costMillis 本轮耗时
     */
    private void recordPurge(AiAuditRetentionVo vo, Exception error, long costMillis) {
        try {
            OperLogEvent operLog = new OperLogEvent();
            operLog.setTitle(TITLE);
            operLog.setBusinessType(BusinessType.CLEAN.ordinal());
            operLog.setMethod(AiAuditRetentionTask.class.getName() + ".runNow()");
            operLog.setRequestMethod(REQUEST_METHOD);
            // 操作人类别取「其它」：本轮由调度线程发起，没有登录用户可归属。
            operLog.setOperatorType(OperatorType.OTHER.ordinal());
            operLog.setOperName(SYSTEM_OPERATOR);
            operLog.setOperUrl("internal://ai-audit-retention-task");
            operLog.setOperParam(vo == null ? null : JsonUtils.toJsonString(Map.of("retentionDays", vo.getRetentionDays())));
            operLog.setJsonResult(vo == null ? null : JsonUtils.toJsonString(vo));
            operLog.setOperTime(LocalDateTime.now());
            operLog.setCostTime(costMillis);
            operLog.setStatus(error == null ? BusinessStatus.SUCCESS.ordinal() : BusinessStatus.FAIL.ordinal());
            if (error != null) {
                operLog.setErrorMsg(StringUtils.substring(error.getMessage(), 0, MAX_ERROR_LENGTH));
            }
            SpringUtils.context().publishEvent(operLog);
        } catch (Exception e) {
            log.warn("审计数据自动清理记录投递失败：{}", e.getMessage());
        }
    }

    /**
     * @param startedAt {@link System#nanoTime()} 起点
     * @return 距今毫秒数
     */
    private static long costMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
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
