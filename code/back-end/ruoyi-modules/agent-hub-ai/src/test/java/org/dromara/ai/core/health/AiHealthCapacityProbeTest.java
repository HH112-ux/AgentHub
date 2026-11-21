package org.dromara.ai.core.health;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisMaster;
import org.redisson.api.redisnode.RedisNode;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.api.redisnode.RedisSingle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link AiHealthCapacityProbe} 单元测试（设计稿 §12.3 WARN 档 / §12.7 I-3）。
 *
 * <p>三个探针各自只依赖一类外部读数，因此每类都把它接进可控范围：磁盘用 {@link TempDir} 的真实文件系统
 * 但把水位阈值调到不干预，Redis 用假客户端喂 {@code INFO memory} 原文，错误日志用临时文件造时间窗。
 * 边界用例（15% 与 14%）刻意只让 Redis 一边参与判定，避免结论取决于跑测试那台机器的磁盘余量。</p>
 */
@DisplayName("健康自检运行余量探针")
class AiHealthCapacityProbeTest {

    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 心跳键（与 AiTaskHeartbeat 的前缀 + 回收器任务名同源）
     */
    private static final String HEARTBEAT_KEY = "agenthub:ai:heartbeat:run-reaper";

    private final AiProperties aiProperties = new AiProperties();

    @TempDir
    Path workspace;

    private MockedStatic<RedisUtils> redisUtils;

    private AiHealthCapacityProbe probe;

    /**
     * 一份与跑测试的机器无关的磁盘读数
     */
    private static final AiHealthCapacityProbe.Reading HEALTHY_DISK =
        new AiHealthCapacityProbe.Reading("磁盘剩余 42%（/data 共 100.0GB，余 42.0GB）", false);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        redisUtils = mockStatic(RedisUtils.class);
        aiProperties.getChat().setWorkspaceRoot(workspace.toString());
        aiProperties.getHealth().setErrorLogPath(workspace.resolve("sys-error.log").toString());
        probe = new AiHealthCapacityProbe(aiProperties);
    }

    @AfterEach
    void tearDown() {
        redisUtils.close();
    }

    // ---------------------------------------------------------------- 磁盘与 Redis 余量

    @Test
    @DisplayName("磁盘与 Redis 都读不到时判 UNKNOWN，而不是「没发现问题」的 PASS")
    void shouldReportUnknownWhenNeitherReadingIsAvailable() {
        probe = withDisk(null);

        AiHealthCapacityProbe.Verdict verdict = probe.capacity();

        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("均无法读取"), verdict.detail());
    }

    @Test
    @DisplayName("磁盘余量按工作区所在文件系统统计，并写出百分比与容量")
    void shouldReportDiskUsageOnWorkspaceFileSystem() {
        aiProperties.getHealth().setLowWatermarkPercent(0);

        AiHealthCapacityProbe.Verdict verdict = probe.capacity();

        long freePercent = workspace.toFile().getUsableSpace() * 100 / workspace.toFile().getTotalSpace();
        assertEquals(AiHealthCheckVo.STATUS_PASS, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("磁盘剩余 " + freePercent + "%"), verdict.detail());
        assertTrue(verdict.detail().contains("Redis 内存信息不可读"), verdict.detail());
    }

    @Test
    @DisplayName("未创建的深路径工作区向上找已存在的父目录，仍按同一文件系统统计")
    void shouldFallBackToExistingAncestorWhenWorkspaceNotCreated() {
        aiProperties.getHealth().setLowWatermarkPercent(0);
        aiProperties.getChat().setWorkspaceRoot(workspace.resolve("data/agent-workspace").toString());

        assertTrue(probe.capacity().detail().contains("磁盘剩余"), probe.capacity().detail());
    }

    @Test
    @DisplayName("Redis 余量正好 15% 判通过（§12.3 的 &lt;15% 是严格小于）")
    void shouldPassExactlyAtWatermarkPercent() {
        probe = withDisk(HEALTHY_DISK);
        stubRedisMemory(850L * 1024 * 1024, 1000L * 1024 * 1024);

        AiHealthCapacityProbe.Verdict verdict = probe.capacity();

        assertEquals(AiHealthCheckVo.STATUS_PASS, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("剩余 15%"), verdict.detail());
    }

    @Test
    @DisplayName("Redis 余量 14% 判告警并给出 maxmemory 处置建议")
    void shouldWarnBelowWatermarkPercent() {
        probe = withDisk(HEALTHY_DISK);
        stubRedisMemory(860L * 1024 * 1024, 1000L * 1024 * 1024);

        AiHealthCapacityProbe.Verdict verdict = probe.capacity();

        assertEquals(AiHealthCheckVo.STATUS_WARN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("剩余 14%"), verdict.detail());
        assertTrue(verdict.suggestion().contains("maxmemory"), verdict.suggestion());
    }

    @Test
    @DisplayName("未设 maxmemory 上限时只报用量，不臆造一个余量百分比")
    void shouldReportUsageWithoutPercentWhenUnlimited() {
        probe = withDisk(HEALTHY_DISK);
        stubRedisMemory(20L * 1024 * 1024, 0L);

        AiHealthCapacityProbe.Verdict verdict = probe.capacity();

        assertEquals(AiHealthCheckVo.STATUS_PASS, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("未设 maxmemory 上限"), verdict.detail());
        assertFalse(verdict.detail().contains("剩余 %"), verdict.detail());
    }

    // ---------------------------------------------------------------- 回收器心跳

    @Test
    @DisplayName("心跳距今超过 2 个扫描周期判告警")
    void shouldWarnWhenHeartbeatStale() {
        redisUtils.when(() -> RedisUtils.getCacheObject(HEARTBEAT_KEY))
            .thenReturn(String.valueOf(System.currentTimeMillis() - 900_000L));

        AiHealthCapacityProbe.Verdict verdict = probe.reaperHeartbeat();

        assertEquals(AiHealthCheckVo.STATUS_WARN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("超过 2 个扫描周期"), verdict.detail());
    }

    @Test
    @DisplayName("心跳在一个周期内判通过")
    void shouldPassWhenHeartbeatFresh() {
        redisUtils.when(() -> RedisUtils.getCacheObject(HEARTBEAT_KEY))
            .thenReturn(String.valueOf(System.currentTimeMillis() - 12_000L));

        AiHealthCapacityProbe.Verdict verdict = probe.reaperHeartbeat();

        assertEquals(AiHealthCheckVo.STATUS_PASS, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("run-reaper"), verdict.detail());
    }

    @Test
    @DisplayName("首轮扫描之前读不到心跳算未知，既不报警也不算通过")
    void shouldStayUnknownBeforeFirstCycle() {
        aiProperties.getReaper().setScanIntervalSeconds(86_400);
        redisUtils.when(() -> RedisUtils.getCacheObject(HEARTBEAT_KEY)).thenReturn(null);

        AiHealthCapacityProbe.Verdict verdict = probe.reaperHeartbeat();

        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("尚无心跳记录"), verdict.detail());
    }

    @Test
    @DisplayName("早已越过首轮却仍无心跳判告警（调度线程已退出的口径）")
    void shouldWarnWhenHeartbeatNeverAppearedAfterFirstCycle() {
        aiProperties.getReaper().setScanIntervalSeconds(300);
        redisUtils.when(() -> RedisUtils.getCacheObject(HEARTBEAT_KEY)).thenReturn(null);
        AiHealthCapacityProbe longUptimeProbe = new AiHealthCapacityProbe(aiProperties) {
            @Override
            long processUptimeSeconds() {
                return 86_400L;
            }
        };

        AiHealthCapacityProbe.Verdict verdict = longUptimeProbe.reaperHeartbeat();

        assertEquals(AiHealthCheckVo.STATUS_WARN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("无心跳记录"), verdict.detail());
    }

    @Test
    @DisplayName("心跳读不动只判 UNKNOWN：不能把 Redis 故障说成线程死了")
    void shouldReportUnknownWhenHeartbeatReadFails() {
        redisUtils.when(() -> RedisUtils.getCacheObject(HEARTBEAT_KEY))
            .thenThrow(new IllegalStateException("Unable to connect to Redis"));

        AiHealthCapacityProbe.Verdict verdict = probe.reaperHeartbeat();

        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("心跳读取失败"), verdict.detail());
    }

    @Test
    @DisplayName("回收器关闭时判 UNKNOWN 并点名配置项")
    void shouldReportUnknownWhenReaperDisabled() {
        aiProperties.getReaper().setEnabled(false);

        AiHealthCapacityProbe.Verdict verdict = probe.reaperHeartbeat();

        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("agenthub.ai.reaper.enabled=false"), verdict.detail());
    }

    // ---------------------------------------------------------------- 近 24 小时 ERROR 日志

    @Test
    @DisplayName("时间窗内出现 ERROR 判告警，只报条数与最近时间、不回显日志正文")
    void shouldWarnOnRecentErrorLog() throws IOException {
        writeLog(
            hourLine(-30, "ERROR", "旧的一条不该算进来 token=abc"),
            hourLine(-5, "ERROR", "java.lang.IllegalStateException 泄漏样例 secret=12345"),
            "        at org.example.OrderService.pay(OrderService.java:88)",
            hourLine(-1, "ERROR", "渠道回包解析失败"));

        AiHealthCapacityProbe.Verdict verdict = probe.errorLog();

        assertEquals(AiHealthCheckVo.STATUS_WARN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("2 条"), verdict.detail());
        assertFalse(verdict.detail().contains("secret"), "响应里不得带出日志正文：" + verdict.detail());
        assertFalse(verdict.detail().contains("OrderService"), "栈信息不搬进接口：" + verdict.detail());
        assertTrue(verdict.suggestion().contains("sys-error.log"), verdict.suggestion());
    }

    @Test
    @DisplayName("只有超出 24 小时的旧 ERROR 时判通过")
    void shouldPassWhenErrorsAreOlderThanWindow() throws IOException {
        writeLog(hourLine(-30, "ERROR", "很久以前"), hourLine(-26, "INFO", "普通信息"));

        AiHealthCapacityProbe.Verdict verdict = probe.errorLog();

        assertEquals(AiHealthCheckVo.STATUS_PASS, verdict.status(), verdict.detail());
    }

    @Test
    @DisplayName("栈信息续行不重复计数：一条 ERROR 只算一次")
    void shouldCountOneEntryPerErrorEvenWithStackTrace() throws IOException {
        writeLog(hourLine(-2, "ERROR", "编排失败"),
            hourLine(-2, "ERROR", "编排失败"),
            "java.util.concurrent.TimeoutException: 3000ms",
            "\tat org.dromara.ai.core.route.RouteJudge.judge(RouteJudge.java:44)");

        AiHealthCapacityProbe.Verdict verdict = probe.errorLog();

        assertTrue(verdict.detail().contains("2 条"), verdict.detail());
    }

    @Test
    @DisplayName("日志文件不存在判 UNKNOWN 并提示两个路径口径")
    void shouldReportUnknownWhenErrorLogFileMissing() {
        aiProperties.getHealth().setErrorLogPath(workspace.resolve("nope.log").toString());

        AiHealthCapacityProbe.Verdict verdict = probe.errorLog();

        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("未找到错误日志文件"), verdict.detail());
        assertTrue(verdict.suggestion().contains("error-log-path"), verdict.suggestion());
    }

    @Test
    @DisplayName("超大日志只扫尾部，并在结论里声明覆盖范围")
    void shouldDeclareTailWindowForOversizedLog() throws IOException {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            builder.append(hourLine(-25, "ERROR", "窗口外的历史错误 " + i)).append('\n');
        }
        builder.append(hourLine(-1, "ERROR", "尾部最近一条"));
        Path file = workspace.resolve("sys-error.log");
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
        assertTrue(Files.size(file) > 2L * 1024 * 1024, "构造的日志应超出读取窗口：" + Files.size(file));

        AiHealthCapacityProbe.Verdict verdict = probe.errorLog();

        assertEquals(AiHealthCheckVo.STATUS_WARN, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("仅覆盖尾部"), verdict.detail());
        assertTrue(verdict.detail().contains("1 条"), verdict.detail());
    }

    // ---------------------------------------------------------------- 造数据

    /**
     * 把磁盘一侧的读数固定下来。Redis 余量的边界用例（正好 15%、14%、无上限）必须知道另一侧不会翻盘，
     * 而真实磁盘余量取决于跑测试那台机器；传 {@code null} 即「磁盘读不到」。
     */
    private AiHealthCapacityProbe withDisk(AiHealthCapacityProbe.Reading disk) {
        return new AiHealthCapacityProbe(aiProperties) {
            @Override
            AiHealthCapacityProbe.Reading diskReading() {
                return disk;
            }
        };
    }

    /**
     * 喂一份假的 Redis {@code INFO memory}；不 stub {@code getClient()} 即等价于「读不到 Redis」。
     *
     * @param usedMemory used_memory（字节），{@code null} 表示该字段缺失
     * @param maxMemory  maxmemory（字节），{@code null} 表示缺失、0 表示未设上限
     */
    private void stubRedisMemory(long usedMemory, long maxMemory) {
        RedissonClient client = mock(RedissonClient.class);
        RedisSingle nodes = mock(RedisSingle.class);
        RedisMaster master = mock(RedisMaster.class);
        Map<String, String> info = new HashMap<>();
        info.put("used_memory", String.valueOf(usedMemory));
        info.put("maxmemory", String.valueOf(maxMemory));
        when(client.getRedisNodes(RedisNodes.SINGLE)).thenReturn(nodes);
        when(nodes.getInstance()).thenReturn(master);
        when(master.info(RedisNode.InfoSection.MEMORY)).thenReturn(info);
        redisUtils.when(RedisUtils::getClient).thenReturn(client);
    }

    /**
     * 造一行 logback 格式日志：相对当前小时偏移的小时数 + 级别 + 正文。
     */
    private String hourLine(int hoursAgo, String level, String message) {
        LocalDateTime at = LocalDateTime.now().plusHours(hoursAgo);
        return at.format(LOG_STAMP) + " [main] " + level + " org.dromara.ai.Demo - " + message;
    }

    private void writeLog(String... lines) throws IOException {
        Files.writeString(workspace.resolve("sys-error.log"), String.join("\n", lines) + "\n",
            StandardCharsets.UTF_8);
    }
}
