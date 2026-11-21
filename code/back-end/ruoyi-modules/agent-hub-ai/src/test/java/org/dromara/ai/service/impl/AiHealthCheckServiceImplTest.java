package org.dromara.ai.service.impl;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.AiMilvusProbe;
import org.dromara.ai.core.AiVectorStore;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.health.AiHealthItemInvoker;
import org.dromara.ai.core.route.RouteJudge;
import org.dromara.ai.core.route.RouteJudgeRequest;
import org.dromara.ai.core.route.RouteJudgeVerdict;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiHealthProbeBo;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.bo.AiMcpClientBo;
import org.dromara.ai.domain.bo.AiModelConfigBo;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.domain.vo.AiHealthReportVo;
import org.dromara.ai.domain.vo.AiKnowledgeBaseVo;
import org.dromara.ai.domain.vo.AiMcpClientTestVo;
import org.dromara.ai.domain.vo.AiMcpServerVo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.domain.vo.AiModelTestVo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiMcpClientService;
import org.dromara.ai.service.IAiMcpServerService;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiHealthCheckServiceImpl} 单元测试：逐项判据、汇总归并、探测转发，以及
 * 「自检不发起外网调用」这一关键约束。
 */
@DisplayName("平台健康自检服务")
class AiHealthCheckServiceImplTest {

    private final DataSource dataSource = mock(DataSource.class);

    private final IAiModelConfigService modelConfigService = mock(IAiModelConfigService.class);

    private final IAiKnowledgeBaseService knowledgeBaseService = mock(IAiKnowledgeBaseService.class);

    private final IAiAgentService agentService = mock(IAiAgentService.class);

    private final IAiMcpServerService mcpServerService = mock(IAiMcpServerService.class);

    private final IAiMcpClientService mcpClientService = mock(IAiMcpClientService.class);

    private final AiMcpClientMapper mcpClientMapper = mock(AiMcpClientMapper.class);

    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    private final AiProperties aiProperties = new AiProperties();

    private final AiVectorStore vectorStore = mock(AiVectorStore.class);

    private final AiMilvusProbe milvusProbe = mock(AiMilvusProbe.class);

    private final OssService ossService = mock(OssService.class);

    private final RouteJudge routeJudge = mock(RouteJudge.class);

    private AiHealthCheckServiceImpl service;

    /** 库内已有表名集合的测试替身：按实体清单造齐，个别用例再摘除几张模拟建库脚本没跑全。 */
    private final Set<String> existingTables = new HashSet<>();

    private MockedStatic<RedisUtils> redisUtils;

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() throws Exception {
        redisUtils = mockStatic(RedisUtils.class);
        redisUtils.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
        aiProperties.setCryptoKey("unit-test-crypto-key");
        aiProperties.getChat().setCurrentTimeZone(TimeZone.getDefault().getID());
        service = new AiHealthCheckServiceImpl(dataSource, modelConfigService, knowledgeBaseService,
            agentService, mcpServerService, mcpClientService, mcpClientMapper, runtimeConfigStore,
            aiProperties, vectorStore, milvusProbe, ossService, routeJudge, AiHealthItemInvoker.direct());
        stubHealthy();
    }

    @AfterEach
    void tearDown() {
        redisUtils.close();
    }

    // ---------------------------------------------------------------- 健康基线

    @Test
    @DisplayName("全部就绪时整体通过，且项数与计数自洽")
    void shouldPassWhenEverythingReady() {
        AiHealthReportVo report = service.check();
        assertEquals(15, report.getTotal());
        assertEquals(report.getTotal(),
            report.getPassCount() + report.getWarnCount() + report.getFailCount() + report.getUnknownCount());
        assertEquals(AiHealthCheckVo.STATUS_PASS, report.getOverall(), "失败原因：" + report.getItems());
        assertNotNull(report.getCheckedAt());
    }

    @Test
    @DisplayName("自检不得发起任何外网调用（共 3 处可探测点全部为 UNKNOWN 或纯本地判定）")
    void shouldNotCallRemoteOnCheck() {
        AiHealthReportVo report = service.check();
        // 模型 / MCP 客户端 / Milvus 的真连方法一次都不能被调用
        verify(modelConfigService, never()).testConnection(any(AiModelConfigBo.class));
        verify(mcpClientService, never()).testConnection(any(AiMcpClientBo.class));
        verify(milvusProbe, never()).probe();
        // 未建连时向量库项应是「待探测」而非绿灯
        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, itemOf(report, "vector.milvus").getStatus());
    }

    // ------------------------------------------------- 结果缓存（设计稿 §12.3：60 秒内复用，手动刷新穿透）

    @Test
    @DisplayName("60 秒内已有缓存报告时直接复用，不再逐项查库")
    void shouldReuseCachedReportWithinTtl() throws Exception {
        redisUtils.when(() -> RedisUtils.getCacheObject(AiHealthCheckServiceImpl.REPORT_CACHE_KEY))
            .thenReturn(cachedReportJson(3));

        AiHealthReportVo report = service.check();

        assertEquals(3, report.getTotal(), "命中的是缓存里的报告，不是重新算出来的 15 项");
        verify(dataSource, never()).getConnection();
    }

    @Test
    @DisplayName("实时体检后按 60 秒 TTL 把报告写回 Redis")
    void shouldCacheReportWithSixtySecondTtl() {
        service.check();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        redisUtils.verify(() -> RedisUtils.setCacheObject(
            eq(AiHealthCheckServiceImpl.REPORT_CACHE_KEY), payload.capture(), ttl.capture()));
        assertEquals(Duration.ofSeconds(60), ttl.getValue());
        assertTrue(payload.getValue().contains("\"total\":15"), "缓存里存的应是本次完整报告的 JSON");
    }

    @Test
    @DisplayName("手动刷新（refresh=true）穿透缓存重新体检，并覆盖旧缓存")
    void shouldBypassCacheWhenRefreshRequested() throws Exception {
        redisUtils.when(() -> RedisUtils.getCacheObject(AiHealthCheckServiceImpl.REPORT_CACHE_KEY))
            .thenReturn(cachedReportJson(3));

        AiHealthReportVo report = service.check(true);

        assertEquals(15, report.getTotal());
        verify(dataSource, atLeastOnce()).getConnection();
        redisUtils.verify(() -> RedisUtils.setCacheObject(
            eq(AiHealthCheckServiceImpl.REPORT_CACHE_KEY), anyString(), eq(Duration.ofSeconds(60))));
    }

    @Test
    @DisplayName("缓存内容损坏时回退实时体检，页面不报错")
    void shouldFallBackToLiveCheckWhenCacheCorrupted() {
        redisUtils.when(() -> RedisUtils.getCacheObject(AiHealthCheckServiceImpl.REPORT_CACHE_KEY))
            .thenReturn("{ 不是合法 JSON");

        AiHealthReportVo report = service.check();

        assertEquals(15, report.getTotal());
        assertEquals(AiHealthCheckVo.STATUS_PASS, report.getOverall(), "失败原因：" + report.getItems());
    }

    @Test
    @DisplayName("写缓存失败只降级为不缓存，不影响报告返回")
    void shouldReturnReportWhenCacheWriteFails() {
        redisUtils.when(() -> RedisUtils.setCacheObject(anyString(), any(), any(Duration.class)))
            .thenThrow(new RuntimeException("redis down"));

        AiHealthReportVo report = service.check();

        assertEquals(15, report.getTotal());
        assertEquals(AiHealthCheckVo.STATUS_PASS, report.getOverall(), "失败原因：" + report.getItems());
    }

    /** 造一份「上一次体检」的缓存值：项数改成与实时结果不同的数字，便于区分复用还是重算。 */
    private String cachedReportJson(int total) {
        AiHealthReportVo cached = new AiHealthReportVo();
        cached.setTotal(total);
        cached.setPassCount(total);
        cached.setOverall(AiHealthCheckVo.STATUS_PASS);
        cached.setCheckedAt(LocalDateTime.now().minusSeconds(30));
        return JsonUtils.toJsonString(cached);
    }

    // -------------------------------- 超时与预算（设计稿 §12.3 / §12.7 I-14：单项 3 秒、整屏 30 秒）

    @Test
    @DisplayName("单项判定超时：该项记 UNKNOWN 并写明判定超时，其余项照常执行")
    void shouldMarkUnknownWhenItemExceedsTimeout() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        // 第 3 项（redis）超时：证明「一项卡住」不会让后面的检项一起消失，也不会让页面报错。
        AiHealthCheckServiceImpl timingOut = withInvoker((body, timeoutMillis) -> {
            if (executed.incrementAndGet() == 3) {
                throw new TimeoutException("stubbed");
            }
            return body.call();
        });

        AiHealthReportVo report = timingOut.check();

        assertEquals(15, executed.get(), "超时之后剩余检项仍要执行");
        assertEquals(15, report.getTotal());
        AiHealthCheckVo timedOut = itemOf(report, "redis");
        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, timedOut.getStatus(),
            "单项超时要记 UNKNOWN（未判定），不能按检项自身失败算 FAIL");
        assertTrue(timedOut.getDetail().contains("判定超时"), "超时行要写明判定超时，实际：" + timedOut.getDetail());
        assertEquals(AiHealthCheckVo.STATUS_PASS, itemOf(report, "db").getStatus(), "超时项之前的检项不受影响");
        assertEquals(AiHealthCheckVo.STATUS_PASS, itemOf(report, "crypto.key").getStatus(), "超时项之后的检项不受影响");
        assertEquals(2, report.getUnknownCount(), "基线的向量库「待探测」之外，只多出超时这一项");
    }

    @Test
    @DisplayName("单项预算取「3 秒上限」与「整屏剩余」的较小值")
    void shouldCapItemBudgetByRemainingTotal() {
        List<Long> budgets = new ArrayList<>();
        AiHealthItemInvoker capturing = (body, timeoutMillis) -> {
            budgets.add(timeoutMillis);
            return body.call();
        };

        withInvoker(capturing).runChecks(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(60_000));
        assertEquals(15, budgets.size());
        assertTrue(budgets.stream().allMatch(budget -> budget == 3_000L),
            "整屏预算充裕时每项应按单项上限 3000 毫秒下发，实际：" + budgets);

        budgets.clear();
        withInvoker(capturing).runChecks(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000));
        assertEquals(15, budgets.size());
        assertTrue(budgets.stream().allMatch(budget -> budget > 0 && budget <= 1_000L),
            "整屏只剩 1 秒时不该再按 3 秒给单项预算，实际：" + budgets);
    }

    @Test
    @DisplayName("整屏 30 秒预算耗尽：剩余检项不再执行，全部记 UNKNOWN 并写明预算用尽")
    void shouldSkipRemainingItemsWhenTotalBudgetExhausted() {
        AtomicInteger executed = new AtomicInteger();
        AiHealthCheckServiceImpl skipped = withInvoker((body, timeoutMillis) -> {
            executed.incrementAndGet();
            return body.call();
        });

        AiHealthReportVo report = skipped.runChecks(System.nanoTime() - 1L);

        assertEquals(0, executed.get(), "预算已用尽时一个检项都不该再执行");
        assertEquals(15, report.getTotal());
        assertEquals(15, report.getUnknownCount());
        assertTrue(report.getItems().stream().allMatch(item ->
                AiHealthCheckVo.STATUS_UNKNOWN.equals(item.getStatus())
                    && item.getDetail().contains("判定超时")
                    && item.getDetail().contains("30 秒")),
            "每行都要写明是整屏 30 秒预算用尽，实际：" + report.getItems());
    }

    @Test
    @DisplayName("每项耗时由实现按真实执行时间写入，压过检项自带的占位值")
    void shouldStampElapsedMillisOnItem() {
        AiHealthCheckServiceImpl slow = withInvoker((body, timeoutMillis) -> {
            AiHealthCheckVo vo = body.call();
            vo.setElapsedMs(1L);
            Thread.sleep(20);
            return vo;
        });

        AiHealthReportVo report = slow.check();

        assertEquals(15, report.getTotal());
        assertNotNull(itemOf(report, "db").getElapsedMs());
        assertTrue(itemOf(report, "db").getElapsedMs() >= 15L,
            "每行耗时要按真实执行时间回填，接口里少了它就分不清是哪一项在拖，实际：" + itemOf(report, "db").getElapsedMs());
    }

    // ---------------------------------------------------------------- 逐项判据

    @Test
    @DisplayName("全部对话模型缺密钥时判失败，并给出补密钥建议")
    void shouldFailWhenAllChatKeysMissing() {
        when(modelConfigService.queryOptions("chat"))
            .thenReturn(List.of(chatModel(1L, "qwen-flash", "")));
        AiHealthCheckVo item = itemOf(service.check(), "model.chat.key");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
        assertTrue(item.getSuggestion().contains("模型配置"));
        assertTrue(item.isProbeSupported());
        assertEquals(AiHealthCheckVo.TARGET_MODEL, item.getProbeTarget());
    }

    @Test
    @DisplayName("部分对话模型缺密钥时只告警")
    void shouldWarnWhenSomeChatKeysMissing() {
        when(modelConfigService.queryOptions("chat"))
            .thenReturn(List.of(chatModel(1L, "qwen-flash", "******"), chatModel(2L, "qwen-max", "")));
        AiHealthCheckVo item = itemOf(service.check(), "model.chat.key");
        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
        assertTrue(item.getDetail().contains("qwen-max"));
    }

    @Test
    @DisplayName("知识库维度与向量模型维度不一致时判失败")
    void shouldFailWhenEmbeddingDimensionMismatch() {
        when(knowledgeBaseService.queryOptions())
            .thenReturn(List.of(kb(1L, "演示库", 10L, 768)));
        when(modelConfigService.queryOptions("embedding"))
            .thenReturn(List.of(embeddingModel(10L, "******", 1024)));
        AiHealthCheckVo item = itemOf(service.check(), "model.embedding");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
        assertTrue(item.getDetail().contains("768"));
        assertTrue(item.getDetail().contains("1024"));
    }

    @Test
    @DisplayName("Milvus 未配置地址时判失败")
    void shouldFailWhenMilvusUriBlank() {
        aiProperties.getMilvus().setUri("");
        AiHealthCheckVo item = itemOf(service.check(), "vector.milvus");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
    }

    @Test
    @DisplayName("已建连的向量库项判通过")
    void shouldPassMilvusWhenCollectionReady() {
        when(knowledgeBaseService.queryOptions())
            .thenReturn(List.of(kb(1L, "演示库", 10L, 1024)));
        when(vectorStore.isReady("kb_demo")).thenReturn(true);
        AiHealthCheckVo item = itemOf(service.check(), "vector.milvus");
        assertEquals(AiHealthCheckVo.STATUS_PASS, item.getStatus());
    }

    @Test
    @DisplayName("审核引擎配置为 external 但地址/密钥不全时告警（实际降级为本地词库）")
    void shouldWarnWhenSafetyEngineExternal() {
        AiSecurityConfigBo security = new AiSecurityConfigBo();
        security.setEnabled(true);
        security.setEngine("external");
        when(runtimeConfigStore.security()).thenReturn(security);
        AiHealthCheckVo item = itemOf(service.check(), "safety.engine");
        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
        assertTrue(item.getDetail().contains("降级"));
    }

    @Test
    @DisplayName("审核引擎配置为 external 且地址/密钥齐备时判通过")
    void shouldPassWhenSafetyEngineExternalConfigured() {
        AiSecurityConfigBo security = new AiSecurityConfigBo();
        security.setEnabled(true);
        security.setEngine("external");
        security.setProviderEndpoint("https://ext.example.com/moderate");
        security.setProviderApiKey("secret");
        when(runtimeConfigStore.security()).thenReturn(security);
        AiHealthCheckVo item = itemOf(service.check(), "safety.engine");
        assertEquals(AiHealthCheckVo.STATUS_PASS, item.getStatus());
        assertTrue(item.getDetail().contains("external"));
    }

    @Test
    @DisplayName("启用重排的知识库所绑重排模型无密钥时告警（会静默降级）")
    void shouldWarnWhenRerankEnabledWithoutKey() {
        AiKnowledgeBaseVo kb = kb(1L, "演示库", 10L, 1024);
        kb.setRerankEnabled("1");
        kb.setRerankModelId(20L);
        when(knowledgeBaseService.queryOptions()).thenReturn(List.of(kb));
        AiModelConfigVo rerank = new AiModelConfigVo();
        rerank.setId(20L);
        rerank.setConfigName("重排-20");
        rerank.setModelType(AiModelConfig.TYPE_RERANK);
        rerank.setStatus("0");
        rerank.setApiKeyMask("");
        when(modelConfigService.queryById(20L)).thenReturn(rerank);
        AiHealthCheckVo item = itemOf(service.check(), "rag.rerank");
        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
        assertTrue(item.getDetail().contains("未配置密钥"));
    }

    @Test
    @DisplayName("入口智能体模型失效判失败，专家模型失效只告警")
    void shouldFailEntryButWarnExpertWhenModelMissing() {
        when(agentService.queryPlatformOptions()).thenReturn(List.of(
            agent(AiAgent.ROLE_ENTRY, "小Z", 999L, null, null, null),
            agent(AiAgent.ROLE_EXPERT, "公告专家", 998L, 997L, null, null)));
        AiHealthCheckVo item = itemOf(service.check(), "agent.model");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
        assertTrue(item.getDetail().contains("小Z"));
        assertTrue(item.getDetail().contains("公告专家"));
    }

    @Test
    @DisplayName("专家槽位模型失效时告警")
    void shouldWarnWhenExpertSlotModelMissing() {
        when(agentService.queryPlatformOptions()).thenReturn(List.of(
            agent(AiAgent.ROLE_ENTRY, "小Z", 1L, null, null, null),
            agent(AiAgent.ROLE_EXPERT, "公告专家", 1L, 997L, null, null)));
        AiHealthCheckVo item = itemOf(service.check(), "agent.model");
        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
        assertTrue(item.getDetail().contains("记忆抽取模型"));
    }

    @Test
    @DisplayName("智能体项按平台全量判定，不取登录用户可见范围")
    void shouldCheckAgentModelsPlatformWide() {
        when(agentService.queryPlatformOptions()).thenReturn(List.of(
            agent(AiAgent.ROLE_ENTRY, "小Z", 1L, null, null, null),
            agent(AiAgent.ROLE_EXPERT, "他部门专家", 999L, null, null, null)));

        AiHealthCheckVo item = itemOf(service.check(), "agent.model");

        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
        assertTrue(item.getDetail().contains("他部门专家"));
        verify(agentService, never()).queryOptions();
    }

    @Test
    @DisplayName("加密根密钥仍为出厂示例值时告警")
    void shouldWarnWhenCryptoKeyIsSample() {
        aiProperties.setCryptoKey("agenthub-ai-2026");
        AiHealthCheckVo item = itemOf(service.check(), "crypto.key");
        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
        assertTrue(item.getSuggestion().contains("AGENTHUB_AI_CRYPTO_KEY"), "实际建议：" + item.getSuggestion());
    }

    @Test
    @DisplayName("对象存储对外域名未配置时告警")
    void shouldWarnWhenStorageDomainBlank() {
        when(ossService.selectDomainUrl()).thenReturn("");
        AiHealthCheckVo item = itemOf(service.check(), "storage.domain");
        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
    }

    @Test
    @DisplayName("Redis 不可用时该项判失败，其余项不受影响")
    void shouldFailRedisOnlyWhenUnavailable() {
        redisUtils.when(() -> RedisUtils.getCacheObject(anyString()))
            .thenThrow(new IllegalStateException("connection refused"));
        AiHealthReportVo report = service.check();
        assertEquals(AiHealthCheckVo.STATUS_FAIL, itemOf(report, "redis").getStatus());
        assertEquals(AiHealthCheckVo.STATUS_PASS, itemOf(report, "db").getStatus());
        assertEquals(AiHealthCheckVo.STATUS_FAIL, report.getOverall());
    }

    @Test
    @DisplayName("整体结论：有失败即 FAIL，只有告警则 WARN")
    void shouldAggregateOverall() {
        aiProperties.setCryptoKey("agenthub-ai-2026");
        when(ossService.selectDomainUrl()).thenReturn("");
        AiHealthReportVo warnOnly = service.check();
        assertEquals(AiHealthCheckVo.STATUS_WARN, warnOnly.getOverall());

        aiProperties.getMilvus().setUri("");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, service.check().getOverall());
    }

    @Test
    @DisplayName("单体检项异常降级为该项 FAIL，不影响其余项")
    void shouldDegradeSingleItemOnException() {
        when(knowledgeBaseService.queryOptions()).thenThrow(new IllegalStateException("boom"));
        AiHealthReportVo report = service.check();
        assertEquals(AiHealthCheckVo.STATUS_FAIL, itemOf(report, "model.embedding").getStatus());
        assertEquals(AiHealthCheckVo.STATUS_PASS, itemOf(report, "db").getStatus());
    }

    @Test
    @DisplayName("缺任意一张 ai_ 表即判失败，并在结论里点名缺表")
    void shouldFailAndNameMissingTables() {
        existingTables.remove("ai_trace");
        existingTables.remove("ai_model_config");
        AiHealthCheckVo item = itemOf(service.check(), "db.schema");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
        assertTrue(item.getDetail().contains("ai_trace"), item.getDetail());
        assertTrue(item.getDetail().contains("ai_model_config"), item.getDetail());
    }

    @Test
    @DisplayName("缺表建议指向本项目建库脚本，而不是底座遗留脚本名")
    void shouldPointMissingTablesAtAgenthubDdl() {
        existingTables.remove("ai_trace");
        AiHealthCheckVo item = itemOf(service.check(), "db.schema");
        assertTrue(item.getSuggestion().contains("agenthub-ai-ddl.sql"), item.getSuggestion());
        assertFalse(item.getSuggestion().contains("ry_ai"), "不得指向底座遗留脚本：" + item.getSuggestion());
    }

    // ---------------------------------------------------------------- 探测

    @Test
    @DisplayName("探测 Milvus 成功判通过并带耗时")
    void shouldProbeMilvus() {
        when(milvusProbe.probe()).thenReturn("连接成功（uri=http://milvus:19530）");
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_MILVUS, null));
        assertEquals(AiHealthCheckVo.STATUS_PASS, result.getStatus());
        assertNotNull(result.getElapsedMs());
    }

    @Test
    @DisplayName("探测 Milvus 失败判失败并带排查建议")
    void shouldFailProbeMilvusOnException() {
        when(milvusProbe.probe()).thenThrow(new ServiceException("连接失败：timeout"));
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_MILVUS, null));
        assertEquals(AiHealthCheckVo.STATUS_FAIL, result.getStatus());
        assertTrue(result.getSuggestion().contains("milvus"));
    }

    @Test
    @DisplayName("探测模型连通性转发到既有测试能力")
    void shouldProbeModel() {
        when(modelConfigService.queryById(1L)).thenReturn(chatModel(1L, "qwen-flash", "******"));
        AiModelTestVo vo = new AiModelTestVo();
        vo.setSuccess(true);
        vo.setMessage("pong");
        vo.setElapsedMs(12L);
        when(modelConfigService.testConnection(any(AiModelConfigBo.class))).thenReturn(vo);
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_MODEL, 1L));
        assertEquals(AiHealthCheckVo.STATUS_PASS, result.getStatus());
        assertEquals(12L, result.getElapsedMs());
    }

    @Test
    @DisplayName("探测外部 MCP 连接转发到既有测试能力")
    void shouldProbeMcpClient() {
        AiMcpClient client = new AiMcpClient();
        client.setId(5L);
        client.setServerName("platform_system_info");
        when(mcpClientMapper.selectById(5L)).thenReturn(client);
        AiMcpClientTestVo vo = new AiMcpClientTestVo();
        vo.setSuccess(true);
        vo.setMessage("ok");
        vo.setTools(List.of("demo_get_data"));
        when(mcpClientService.testConnection(any(AiMcpClientBo.class))).thenReturn(vo);
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_MCP_CLIENT, 5L));
        assertEquals(AiHealthCheckVo.STATUS_PASS, result.getStatus());
        assertTrue(result.getDetail().contains("1 个工具"));
    }

    @Test
    @DisplayName("模型探测缺主键或不支持的目标时抛业务异常")
    void shouldRejectInvalidProbe() {
        assertThrows(ServiceException.class, () -> service.probe(probeBo(AiHealthCheckVo.TARGET_MODEL, null)));
        assertThrows(ServiceException.class, () -> service.probe(probeBo("unknown", null)));
    }

    // ---------------------------------------------------------------- 结构化决策

    @Test
    @DisplayName("结构化决策未启用时判通过（关闭态零外呼）")
    void shouldPassStructuredDecisionWhenDisabled() {
        when(runtimeConfigStore.jev()).thenReturn(jev(false, null));
        AiHealthCheckVo item = itemOf(service.check(), "structured-decision");
        assertEquals(AiHealthCheckVo.STATUS_PASS, item.getStatus());
    }

    @Test
    @DisplayName("结构化决策已开启但未选择决策模型时判失败")
    void shouldFailStructuredDecisionWithoutModel() {
        when(runtimeConfigStore.jev()).thenReturn(jev(true, null));
        AiHealthCheckVo item = itemOf(service.check(), "structured-decision");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
        assertTrue(item.getDetail().contains("未选择决策模型"));
    }

    @Test
    @DisplayName("所选决策模型已停用时判失败")
    void shouldFailStructuredDecisionWhenModelDisabled() {
        when(runtimeConfigStore.jev()).thenReturn(jev(true, 9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("1"));
        AiHealthCheckVo item = itemOf(service.check(), "structured-decision");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
        assertTrue(item.getDetail().contains("已停用"));
    }

    @Test
    @DisplayName("所选模型类型不是 decision 时判失败")
    void shouldFailStructuredDecisionWhenModelTypeMismatch() {
        AiModelConfigVo wrongType = decisionModel("0");
        wrongType.setModelType(AiModelConfig.TYPE_CHAT);
        when(runtimeConfigStore.jev()).thenReturn(jev(true, 9L));
        when(modelConfigService.queryById(9L)).thenReturn(wrongType);
        AiHealthCheckVo item = itemOf(service.check(), "structured-decision");
        assertEquals(AiHealthCheckVo.STATUS_FAIL, item.getStatus());
        assertTrue(item.getDetail().contains("类型不是决策模型"));
    }

    @Test
    @DisplayName("所选决策模型未配置密钥时告警（判定不可用但会自动回落）")
    void shouldWarnStructuredDecisionWithoutKey() {
        when(runtimeConfigStore.jev()).thenReturn(jev(true, 9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0"));
        when(modelConfigService.decryptApiKey(9L)).thenReturn(null);
        AiHealthCheckVo item = itemOf(service.check(), "structured-decision");
        assertEquals(AiHealthCheckVo.STATUS_WARN, item.getStatus());
        assertTrue(item.getDetail().contains("未配置 API 密钥"));
    }

    @Test
    @DisplayName("决策模型配置齐全时待探测并标记可探测")
    void shouldUnknownStructuredDecisionWhenReady() {
        when(runtimeConfigStore.jev()).thenReturn(jev(true, 9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0"));
        when(modelConfigService.decryptApiKey(9L)).thenReturn("sk-test");
        AiHealthCheckVo item = itemOf(service.check(), "structured-decision");
        assertEquals(AiHealthCheckVo.STATUS_UNKNOWN, item.getStatus());
        assertTrue(item.isProbeSupported());
        assertEquals(AiHealthCheckVo.TARGET_JEV, item.getProbeTarget());
        assertTrue(item.getDetail().contains("影子"));
        assertTrue(item.getDetail().contains("百炼决策模型"));
    }

    @Test
    @DisplayName("探测结构化决策：未启用直接判通过且不发起判定")
    void shouldProbeJevPassWhenDisabled() {
        when(runtimeConfigStore.jev()).thenReturn(jev(false, null));
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_JEV, null));
        assertEquals(AiHealthCheckVo.STATUS_PASS, result.getStatus());
        verify(routeJudge, never()).judge(any(RouteJudgeRequest.class));
    }

    @Test
    @DisplayName("探测结构化决策：所选模型形态不满足时直接返回形态结论且不外呼")
    void shouldProbeJevRejectWhenShapeInvalid() {
        when(runtimeConfigStore.jev()).thenReturn(jev(true, null));
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_JEV, null));
        assertEquals(AiHealthCheckVo.STATUS_FAIL, result.getStatus());
        verify(routeJudge, never()).judge(any(RouteJudgeRequest.class));
    }

    @Test
    @DisplayName("探测结构化决策：判定可用判通过并带耗时与 Token")
    void shouldProbeJevPassWhenAvailable() {
        when(runtimeConfigStore.jev()).thenReturn(jev(true, 9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0"));
        when(modelConfigService.decryptApiKey(9L)).thenReturn("sk-test");
        when(routeJudge.judge(any(RouteJudgeRequest.class)))
            .thenReturn(Optional.of(new RouteJudgeVerdict("probe_expert_a", 0.9, Map.of(), 62L, 159L, null)));
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_JEV, null));
        assertEquals(AiHealthCheckVo.STATUS_PASS, result.getStatus());
        assertTrue(result.getDetail().contains("62"));
        assertTrue(result.getDetail().contains("159"));
    }

    @Test
    @DisplayName("探测结构化决策：判定不可用判告警并说明回落不影响对话")
    void shouldWarnProbeJevWhenUnavailable() {
        when(runtimeConfigStore.jev()).thenReturn(jev(true, 9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0"));
        when(modelConfigService.decryptApiKey(9L)).thenReturn("sk-test");
        when(routeJudge.judge(any(RouteJudgeRequest.class)))
            .thenReturn(Optional.of(new RouteJudgeVerdict(null, null, null, 12L, 0L, "调用超时")));
        AiHealthCheckVo result = service.probe(probeBo(AiHealthCheckVo.TARGET_JEV, null));
        assertEquals(AiHealthCheckVo.STATUS_WARN, result.getStatus());
        assertTrue(result.getSuggestion().contains("回落"));
    }

    // ---------------------------------------------------------------- 造数据

    /**
     * 构造结构化决策配置（仅用于判定分支测试）。
     *
     * @param enabled       总开关
     * @param modelConfigId 所选决策模型主键（「模型配置」）
     */
    private AiJevConfigBo jev(boolean enabled, Long modelConfigId) {
        AiJevConfigBo bo = new AiJevConfigBo();
        bo.setEnabled(enabled);
        bo.setModelConfigId(modelConfigId);
        return bo;
    }

    /**
     * 构造决策模型配置（「模型配置」中类型 decision、地址与标识齐备）。
     *
     * @param status 状态（0 正常 / 1 停用）
     */
    private AiModelConfigVo decisionModel(String status) {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setId(9L);
        vo.setConfigName("百炼决策模型");
        vo.setModelType(AiModelConfig.TYPE_DECISION);
        vo.setModelCode("decision-model-preview");
        vo.setBaseUrl("https://ws.cn-beijing.maas.aliyuncs.com/compatible-mode");
        vo.setStatus(status);
        return vo;
    }

    private void stubHealthy() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(meta.getDatabaseProductVersion()).thenReturn("14.0");
        // 真实驱动一次性返回库内全部表（pattern 为 "%"），也支持按表名点查；两种调用都要答得上。
        existingTables.clear();
        existingTables.addAll(AiHealthCheckServiceImpl.expectedAiTables());
        assertEquals(40, existingTables.size(), "AI 实体表清单与设计稿 §5 的 40 张不一致，健康自检基准失准");
        existingTables.addAll(List.of("sys_user", "sys_menu", "gen_table"));
        when(meta.getTables(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            String pattern = invocation.getArgument(2);
            List<String> rows = "%".equals(pattern)
                ? new ArrayList<>(existingTables)
                : existingTables.stream().filter(t -> t.equalsIgnoreCase(pattern)).toList();
            AtomicInteger cursor = new AtomicInteger(-1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenAnswer(x -> cursor.incrementAndGet() < rows.size());
            when(rs.getString("TABLE_NAME")).thenAnswer(x -> rows.get(cursor.get()));
            return rs;
        });

        AiModelConfigVo chat = chatModel(1L, "qwen-flash", "******");
        AiModelConfigVo embedding = embeddingModel(10L, "******", 1024);
        when(modelConfigService.queryOptions("chat")).thenReturn(List.of(chat));
        when(modelConfigService.queryOptions("embedding")).thenReturn(List.of(embedding));
        when(knowledgeBaseService.queryOptions()).thenReturn(List.of());
        when(agentService.queryPlatformOptions())
            .thenReturn(List.of(agent(AiAgent.ROLE_ENTRY, "小Z", 1L, null, null, null)));

        AiMcpServerVo server = new AiMcpServerVo();
        server.setEndpoint("/mcp");
        server.setAuthEnabled(true);
        server.setToken("strong-token-0123456789");
        server.setLiveTools(List.of("get_notice"));
        when(mcpServerService.queryServer()).thenReturn(server);
        when(mcpClientMapper.selectList(any())).thenReturn(List.of());

        AiSecurityConfigBo security = new AiSecurityConfigBo();
        security.setEnabled(true);
        security.setEngine("local");
        security.setAction("log");
        security.setMaskPii(true);
        when(runtimeConfigStore.security()).thenReturn(security);

        // 结构化决策默认关闭（关闭态判通过、零外呼）
        when(runtimeConfigStore.jev()).thenReturn(jev(false, null));

        when(ossService.selectDomainUrl()).thenReturn("http://localhost:9000");
    }

    private AiModelConfigVo chatModel(Long id, String name, String mask) {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setId(id);
        vo.setConfigName(name);
        vo.setModelType("chat");
        vo.setApiKeyMask(mask);
        return vo;
    }

    private AiModelConfigVo embeddingModel(Long id, String mask, Integer dimension) {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setId(id);
        vo.setConfigName("embedding-" + id);
        vo.setModelType("embedding");
        vo.setApiKeyMask(mask);
        vo.setDimension(dimension);
        return vo;
    }

    private AiKnowledgeBaseVo kb(Long id, String name, Long embeddingModelId, Integer dimension) {
        AiKnowledgeBaseVo vo = new AiKnowledgeBaseVo();
        vo.setId(id);
        vo.setKbName(name);
        vo.setEmbeddingModelId(embeddingModelId);
        vo.setDimension(dimension);
        vo.setCollectionName("kb_demo");
        return vo;
    }

    private AiAgentVo agent(String role, String name, Long modelId, Long memoryModelId,
                            Long summaryModelId, Long subagentModelId) {
        AiAgentVo vo = new AiAgentVo();
        vo.setId(100L);
        vo.setRole(role);
        vo.setAgentName(name);
        vo.setAgentCode(role + "_code");
        vo.setModelId(modelId);
        vo.setMemoryModelId(memoryModelId);
        vo.setSummaryModelId(summaryModelId);
        vo.setSubagentModelId(subagentModelId);
        return vo;
    }

    private AiHealthProbeBo probeBo(String target, Long id) {
        AiHealthProbeBo bo = new AiHealthProbeBo();
        bo.setTarget(target);
        bo.setId(id);
        return bo;
    }

    /** 换一个检项执行接缝重建服务：超时与预算的用例要靠假接缝注入「第 N 项卡住」「预算已耗尽」。 */
    private AiHealthCheckServiceImpl withInvoker(AiHealthItemInvoker invoker) {
        return new AiHealthCheckServiceImpl(dataSource, modelConfigService, knowledgeBaseService,
            agentService, mcpServerService, mcpClientService, mcpClientMapper, runtimeConfigStore,
            aiProperties, vectorStore, milvusProbe, ossService, routeJudge, invoker);
    }

    private AiHealthCheckVo itemOf(AiHealthReportVo report, String key) {
        return report.getItems().stream()
            .filter(item -> key.equals(item.getKey()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("未找到体检项：" + key));
    }

}
