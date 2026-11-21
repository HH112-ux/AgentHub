package org.dromara.ai.core.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.run.AiStaleRunReaper;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisMaster;
import org.redisson.api.redisnode.RedisNode;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.api.redisnode.RedisSingle;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 运行余量与日志探针（设计稿 §12.3 WARN 档的「磁盘或 Redis 内存余量 &lt;15%」「Reaper 心跳超 2 个扫描周期」
 * 「近 24 小时出现 ERROR 日志」三项）。
 *
 * <p>这三项都不查业务表，读的是进程与机器本身：文件系统、Redis {@code INFO} 段、本地日志文件。
 * 单独成类而不是写在体检服务里，是为了让「文件截断」「INFO 段缺失」这类边界能用临时目录和假客户端
 * 直接测，不必把整个体检服务搭起来。</p>
 *
 * <p><b>读不到就说读不到</b>：余量算不出、心跳键读失败、日志文件不存在，一律回 {@code UNKNOWN} 并写明原因，
 * 绝不因为「没发现问题」而回 {@code PASS}（§12.7 I-3 验收口径）。能读到多少就判多少，缺口在 {@code detail} 里说清。</p>
 *
 * @author AgentHub
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiHealthCapacityProbe {

    /**
     * 心跳容忍的扫描周期倍数，超过判 WARN（设计稿 §12.3「超 2 个扫描周期」）
     */
    private static final int HEARTBEAT_MISS_CYCLES = 2;

    /**
     * 回收器首轮扫描的间隔下限（秒），与 {@code AiStaleRunReaper.MIN_SCAN_INTERVAL_SECONDS} 同值
     */
    private static final long MIN_SCAN_INTERVAL_SECONDS = 30L;

    /**
     * ERROR 日志的回看窗口（小时），对应 §12.3「近 24 小时」
     */
    private static final long ERROR_LOG_WINDOW_HOURS = 24L;

    /**
     * ERROR 日志单次读取上限（字节）：错误日志可能滚到几百 MB，自检不能为它把堆吃光，
     * 只读文件尾并在结论里声明覆盖范围。按每行约 200 字节估算是近万行，足够覆盖 24 小时。
     */
    private static final long ERROR_LOG_MAX_BYTES = 2L * 1024 * 1024;

    /**
     * logback {@code log.pattern} 的行首时间戳格式
     */
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 行首时间戳的字符下标：日期 10 位 + 空格 + 时间 8 位 = 19
     */
    private static final int LOG_TIMESTAMP_LENGTH = 19;

    /**
     * 字节单位换算（只用于展示文案，判定一律用原始字节）
     */
    private static final double GB = 1024d * 1024d * 1024d;

    private static final double MB = 1024d * 1024d;

    private final AiProperties aiProperties;

    /**
     * 探针结论：状态 + 说明 + 修复建议，由体检服务落成检项行。
     *
     * @param status     {@link AiHealthCheckVo#STATUS_PASS} 等四态之一
     * @param detail     页面展示的判断依据（含读到的数字）
     * @param suggestion 修复建议，PASS 时为空串
     */
    public record Verdict(String status, String detail, String suggestion) {
    }

    /**
     * 磁盘与 Redis 内存余量。
     *
     * <p>磁盘按智能体工作区（{@code agenthub.ai.chat.workspace-root}）所在文件系统统计——工作区目录
     * 通常尚未创建，因此向上找第一个真实存在的父目录；Redis 只看 {@code maxmemory} 上限下的剩余比例，
     * 未设上限时报告用量而不臆造余量。两边都读不到才判 {@code UNKNOWN}，只读到一边就按能读到的判并在
     * 说明里标出缺口。</p>
     *
     * @return 结论
     */
    public Verdict capacity() {
        Reading disk = diskReading();
        Reading redis = redisReading();
        if (disk == null && redis == null) {
            return new Verdict(AiHealthCheckVo.STATUS_UNKNOWN, "磁盘与 Redis 内存余量均无法读取",
                "确认应用有工作目录的读取权限，以及 Redis 部署形态为本项目的单实例模式");
        }
        StringBuilder detail = new StringBuilder();
        detail.append(disk == null ? "磁盘余量不可读；" : disk.detail() + "；");
        detail.append(redis == null ? "Redis 内存信息不可读。" : redis.detail() + "。");
        boolean low = (disk != null && disk.low()) || (redis != null && redis.low());
        if (low) {
            return new Verdict(AiHealthCheckVo.STATUS_WARN, detail.toString(),
                "余量低于 " + watermarkPercent() + "%：清理磁盘或调大 Redis maxmemory 后重新自检");
        }
        return new Verdict(AiHealthCheckVo.STATUS_PASS, detail.toString(), "");
    }

    /**
     * Reaper 心跳年龄判定。
     *
     * <p>心跳由各任务 {@code finally} 写入，因此「键很旧」说明调度线程没在跑，而不是「扫描报错」。
     * 进程刚起、还没到首轮扫描时读不到键，此时按 {@code UNKNOWN}（未知）而不是报警或放行——
     * 首轮间隔由 {@code agenthub.ai.reaper.scan-interval-seconds} 决定，下限 {@value #MIN_SCAN_INTERVAL_SECONDS} 秒。</p>
     *
     * @return 结论
     */
    public Verdict reaperHeartbeat() {
        AiProperties.Reaper reaper = aiProperties.getReaper();
        if (!reaper.isEnabled()) {
            return new Verdict(AiHealthCheckVo.STATUS_UNKNOWN,
                "回收器已关闭（agenthub.ai.reaper.enabled=false），无心跳可比",
                "开启回收器，或确认中断记录改由人工处理");
        }
        long intervalSeconds = Math.max(MIN_SCAN_INTERVAL_SECONDS, reaper.getScanIntervalSeconds());
        Long ageSeconds;
        try {
            ageSeconds = AiTaskHeartbeat.beatAgeSeconds(AiStaleRunReaper.HEARTBEAT_TASK);
        } catch (Exception e) {
            // 读不动 Redis 时不能推断「线程死了」，只能报未知
            return new Verdict(AiHealthCheckVo.STATUS_UNKNOWN, "心跳读取失败：" + e.getMessage(), "检查 Redis 连通性");
        }
        if (ageSeconds == null) {
            long uptimeSeconds = processUptimeSeconds();
            if (uptimeSeconds < intervalSeconds) {
                return new Verdict(AiHealthCheckVo.STATUS_UNKNOWN,
                    "尚无心跳记录（进程启动 " + uptimeSeconds + " 秒，未到首轮扫描的 " + intervalSeconds + " 秒）",
                    "稍后点「重新自检」");
            }
            return new Verdict(AiHealthCheckVo.STATUS_WARN, "无心跳记录：回收调度线程可能已退出",
                "查看应用日志中 ai-stale-run-reaper 线程的异常，必要时重启服务");
        }
        long limitSeconds = intervalSeconds * HEARTBEAT_MISS_CYCLES;
        String name = "回收器（" + AiStaleRunReaper.HEARTBEAT_TASK + "）";
        if (ageSeconds > limitSeconds) {
            return new Verdict(AiHealthCheckVo.STATUS_WARN,
                name + "上次心跳距今 " + ageSeconds + " 秒，超过 " + HEARTBEAT_MISS_CYCLES
                    + " 个扫描周期（" + limitSeconds + " 秒）",
                "确认 agenthub.ai.reaper.enabled 为 true 且调度线程未因异常退出；重启服务可恢复");
        }
        return new Verdict(AiHealthCheckVo.STATUS_PASS,
            name + "上次心跳距今 " + ageSeconds + " 秒（扫描间隔 " + intervalSeconds + " 秒）", "");
    }

    /**
     * 近 24 小时的 ERROR 日志条数。
     *
     * <p>只统计当前文件（滚动归档 {@code .log.gz} 不解析），时间戳取 logback
     * {@code %d{yyyy-MM-dd HH:mm:ss}} 行首；栈信息续行没有行首时间戳，天然不会重复计数。</p>
     *
     * <p>结论只报条数与最近时间，<b>不回显日志正文</b>：这个接口对整个管理端可见，
     * 把 ERROR 原文搬进响应等于把可能含业务数据的堆栈扩散到页面与浏览器历史里。</p>
     *
     * @return 结论
     */
    public Verdict errorLog() {
        File file = new File(aiProperties.getHealth().getErrorLogPath());
        if (!file.isFile()) {
            return new Verdict(AiHealthCheckVo.STATUS_UNKNOWN, "未找到错误日志文件：" + file.getAbsolutePath(),
                "确认 logback 的 log.path 与 agenthub.ai.health.error-log-path 是否一致");
        }
        LocalDateTime since = LocalDateTime.now().minusHours(ERROR_LOG_WINDOW_HOURS);
        int errors = 0;
        int windowedLines = 0;
        String latestTimestamp = "";
        boolean truncated = file.length() > ERROR_LOG_MAX_BYTES;
        try {
            String tail = new String(readTail(file, ERROR_LOG_MAX_BYTES), StandardCharsets.UTF_8);
            for (String line : tail.split("\n")) {
                LocalDateTime at = lineTimestamp(line);
                if (at == null) {
                    // 无行首时间戳的行：栈信息续行或非本格式文本，不参与计数
                    continue;
                }
                if (at.isBefore(since)) {
                    continue;
                }
                windowedLines++;
                if (line.contains("ERROR")) {
                    errors++;
                    latestTimestamp = line.substring(0, LOG_TIMESTAMP_LENGTH);
                }
            }
        } catch (IOException e) {
            return new Verdict(AiHealthCheckVo.STATUS_UNKNOWN, "错误日志读取失败：" + e.getMessage(),
                "检查日志文件读取权限");
        }
        String coverage = "回看 " + ERROR_LOG_WINDOW_HOURS + " 小时，窗口内日志行 " + windowedLines + " 行"
            + (truncated ? "（文件超出 " + ERROR_LOG_MAX_BYTES / 1024 / 1024 + "MB 读取窗口，仅覆盖尾部）" : "");
        if (errors > 0) {
            return new Verdict(AiHealthCheckVo.STATUS_WARN,
                "近 " + ERROR_LOG_WINDOW_HOURS + " 小时出现 ERROR 日志 " + errors + " 条，最近一条 " + latestTimestamp
                    + "；" + coverage,
                "到服务器查看 " + file.getAbsolutePath() + " 定位原因（本页不展示日志正文）");
        }
        return new Verdict(AiHealthCheckVo.STATUS_PASS,
            "近 " + ERROR_LOG_WINDOW_HOURS + " 小时无 ERROR 日志；" + coverage, "");
    }

    // ---------------------------------------------------------------- 内部读取

    /**
     * 一次读数：展示文案 + 是否低于水位。读不到时整个返回 {@code null}，调用方据此只按可得的数据判定。
     */
    record Reading(String detail, boolean low) {
    }

    /**
     * 告警水位（百分比）：默认 15 即设计稿 §12.3 的冻结值，开成配置是为了让边界用例不依赖跑测试那台机器的磁盘。
     */
    private int watermarkPercent() {
        return aiProperties.getHealth().getLowWatermarkPercent();
    }

    /**
     * 磁盘读数。开成包内可见是测试接缝：余量百分比取决于跑测试那台机器，Redis 侧的边界用例
     * （正好 15% 与 14%）必须能把磁盘这一边固定下来才谈得上确定性。
     */
    Reading diskReading() {
        File target = existingAncestor(new File(aiProperties.getChat().getWorkspaceRoot()));
        if (target == null) {
            return null;
        }
        long total = target.getTotalSpace();
        long free = target.getUsableSpace();
        if (total <= 0 || free <= 0) {
            return null;
        }
        long freePercent = free * 100 / total;
        return new Reading("磁盘剩余 " + freePercent + "%（" + target.getAbsolutePath() + " 共 "
            + round(total / GB) + "GB，余 " + round(free / GB) + "GB）", freePercent < watermarkPercent());
    }

    private Reading redisReading() {
        try {
            RedissonClient client = RedisUtils.getClient();
            RedisSingle nodes = client == null ? null : client.getRedisNodes(RedisNodes.SINGLE);
            RedisMaster master = nodes == null ? null : nodes.getInstance();
            Map<String, String> info = master == null ? null : master.info(RedisNode.InfoSection.MEMORY);
            if (info == null || info.isEmpty()) {
                return null;
            }
            long used = parseLong(info.get("used_memory"));
            long max = parseLong(info.get("maxmemory"));
            if (used <= 0) {
                return null;
            }
            if (max <= 0) {
                // 未设上限时没有「剩余比例」可谈，只报用量，让人自己判断宿主机余量
                return new Reading("Redis 已用 " + round(used / MB) + "MB（未设 maxmemory 上限，余量按宿主机计）", false);
            }
            long freePercent = (max - used) * 100 / max;
            return new Reading("Redis 已用 " + round(used / MB) + "MB / 上限 " + round(max / MB)
                + "MB（剩余 " + freePercent + "%）", freePercent < watermarkPercent());
        } catch (Exception e) {
            log.debug("Redis 内存信息读取失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析行首时间戳，格式不符返回 {@code null}。
     */
    private LocalDateTime lineTimestamp(String line) {
        if (line.length() < LOG_TIMESTAMP_LENGTH
            || line.charAt(4) != '-' || line.charAt(7) != '-' || line.charAt(10) != ' '
            || line.charAt(13) != ':' || line.charAt(16) != ':') {
            return null;
        }
        try {
            return LocalDateTime.parse(line.substring(0, LOG_TIMESTAMP_LENGTH), LOG_TIMESTAMP);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 向上找第一个存在的目录：工作区通常要等第一次装配文件才创建，未创建时磁盘仍可统计。
     */
    private File existingAncestor(File path) {
        File current = path;
        while (current != null && !current.exists()) {
            current = current.getParentFile();
        }
        return current;
    }

    /**
     * 进程已运行秒数。开成包内可见是测试接缝：「首轮心跳还没到」与「线程死了」的边界要用一个不可能的运行时长来验证，
     * 真实等满一个扫描周期的用例不该留在 CI 里。
     */
    long processUptimeSeconds() {
        long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        return Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
    }

    private byte[] readTail(File file, long maxBytes) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
            long length = access.length();
            long from = Math.max(0L, length - maxBytes);
            access.seek(from);
            byte[] buffer = new byte[(int) (length - from)];
            access.readFully(buffer);
            return buffer;
        }
    }

    private long parseLong(String raw) {
        try {
            return raw == null ? 0L : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String round(double value) {
        return String.format("%.1f", value);
    }

}
