package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.AiMilvusProbe;
import org.dromara.ai.core.AiVectorStore;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.channel.AiChannelStreamStatus;
import org.dromara.ai.core.document.AiDocumentBatchRunner;
import org.dromara.ai.core.health.AiHealthCapacityProbe;
import org.dromara.ai.core.health.AiHealthItemInvoker;
import org.dromara.ai.core.route.JevSystemOneSupport;
import org.dromara.ai.core.route.RouteJudge;
import org.dromara.ai.core.route.RouteJudgeMode;
import org.dromara.ai.core.route.RouteJudgeRequest;
import org.dromara.ai.core.route.RouteJudgeVerdict;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiDocument;
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
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChannelService;
import org.dromara.ai.service.IAiHealthCheckService;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiMcpClientService;
import org.dromara.ai.service.IAiMcpServerService;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.system.api.OssService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 平台健康自检 Service 实现。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>只读、不花钱</b>：{@link #check()} 只读 DB / Redis / 内存配置，**绝不发起外网调用**；
 *       需要真实连接的项返回 {@code UNKNOWN} 并标记可探测，由 {@link #probe(AiHealthProbeBo)} 按需验证。</li>
 *   <li><b>永不 500</b>：每个体检项各自 try/catch，单点异常降级为该行的 FAIL（并写明原因），
 *       不影响其余项，也不让页面整体报错。</li>
 *   <li><b>单点卡死也拖不垮整屏</b>：检项由 {@link AiHealthItemInvoker} 在单项 3 秒、全量 30 秒的
 *       预算内执行（§12.3 / §12.7 I-14）。超时项记 {@code UNKNOWN} 并写明「判定超时」，
 *       预算用尽后的剩余项直接出结论、不再执行。</li>
 *   <li><b>结果缓存 60 秒</b>：一次全量自检要遍历 15 项、多次查库，进页面与切回来不必每次重算。
 *       缓存读写同样按「永不 500」处理：Redis 坏了就当没缓存。页面「重新自检」带
 *       {@code refresh=true} 穿透，改完配置立刻能看到新结论。</li>
 * </ul>
 *
 * @author AgentHub
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiHealthCheckServiceImpl implements IAiHealthCheckService {

    /**
     * Redis 连通性探测键（只读一个不存在的键即可验证命令通道，不产生任何状态）
     */
    private static final String REDIS_PROBE_KEY = "agenthub:health:probe";

    /**
     * 出厂示例加密根密钥（与 {@code ProductionConfigGuard.SAMPLE_SECRETS} 同值）
     */
    private static final String SAMPLE_CRYPTO_KEY = "REPLACE_WITH_32_BYTE_ROOT_KEY";

    /**
     * MCP 出口出厂示例口令
     */
    private static final String SAMPLE_MCP_TOKEN = "AgentHub";

    /**
     * AI 业务表前缀（设计稿 §5 的 40 张表全部以此开头）
     */
    private static final String AI_TABLE_PREFIX = "ai_";

    /**
     * 体检报告结果缓存键（设计稿 §12.3：同一份报告 60 秒内复用）
     */
    static final String REPORT_CACHE_KEY = "agenthub:ai:health:report";

    /**
     * 体检报告缓存时长。取 60 秒是因为一次全量自检要遍历全部检项、多次查库，
     * 而页面切回来不需要每一秒都重算；手动刷新不受它限制。
     */
    private static final Duration REPORT_CACHE_TTL = Duration.ofSeconds(60);

    /**
     * 单个检项的判定超时（设计稿 §12.3 / §12.7 I-14）。取 3 秒是因为最慢的合法检项是一次
     * Milvus 建连列集合；超过它就不再等——一个依赖卡住不该把整屏拖成白屏。
     */
    private static final long ITEM_TIMEOUT_MILLIS = Duration.ofSeconds(3).toMillis();

    /**
     * 整屏体检的总预算（设计稿 §12.3 / §12.7 I-14）。「检项数 × 单项上限」是「每项都刚好卡满」的最坏值，
     * 页面等不起；预算耗尽后剩余检项直接记 UNKNOWN 且不再执行，因此实际耗时上界是 30 秒。
     */
    private static final long TOTAL_BUDGET_MILLIS = Duration.ofSeconds(30).toMillis();

    private static final String CATEGORY_INFRA = "基础设施";

    private static final String CATEGORY_MODEL = "模型与向量";

    private static final String CATEGORY_INTEGRATION = "集成";

    private static final String CATEGORY_RUNTIME = "安全与运行时";

    private static final String CATEGORY_AGENT = "智能体";

    private static final String CATEGORY_STORAGE = "存储";

    /**
     * 知识库检索模式：混合检索（BM25 + 向量），与 {@code AiKnowledgeBaseServiceImpl.MODE_HYBRID} 同值
     */
    private static final String MODE_HYBRID = "hybrid";

    /**
     * BM25 索引状态：未建 / 建设中 / 已就绪 / 建败（{@code ai_knowledge_base.bm25_index_status} 字典）
     */
    private static final String BM25_NONE = "none";
    private static final String BM25_BUILDING = "building";
    private static final String BM25_READY = "ready";
    private static final String BM25_FAILED = "failed";

    /**
     * 文档向量化状态：待向量化（{@code ai_document.vector_status} 字典 0）
     */
    private static final String DOC_VECTOR_PENDING = "0";

    private final DataSource dataSource;

    private final IAiModelConfigService modelConfigService;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    private final IAiAgentService agentService;

    private final IAiMcpServerService mcpServerService;

    private final IAiMcpClientService mcpClientService;

    private final AiMcpClientMapper mcpClientMapper;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final AiProperties aiProperties;

    private final AiVectorStore vectorStore;

    private final AiMilvusProbe milvusProbe;

    private final OssService ossService;

    private final RouteJudge routeJudge;

    private final IAiChannelService channelService;

    private final AiChannelStreamStatus channelStreamStatus;

    private final AiDocumentMapper documentMapper;

    private final AiDocumentBatchRunner documentBatchRunner;

    private final AiHealthCapacityProbe capacityProbe;

    /**
     * 检项执行接缝：生产注入带硬超时的实现，单测注入同线程直调（理由见 {@link AiHealthItemInvoker}）
     */
    private final AiHealthItemInvoker itemInvoker;

    /**
     * 平台版本（构建时由 Maven 过滤写入 {@code application.yml}，缺失时回退 unknown）
     */
    @Value("${agenthub.version:unknown}")
    private String platformVersion;

    @Override
    public AiHealthReportVo check() {
        return check(false);
    }

    @Override
    public AiHealthReportVo check(boolean refresh) {
        if (!refresh) {
            AiHealthReportVo cached = readCachedReport();
            if (cached != null) {
                return cached;
            }
        }
        AiHealthReportVo report = runChecks();
        cacheReport(report);
        return report;
    }

    /**
     * 读 60 秒内的报告缓存。
     *
     * <p>缓存只是省掉重复体检，不是判据来源，所以这里任何异常（Redis 不可用、值被别处写坏、
     * 反序列化失败）都只意味着「当作没缓存」，绝不把页面打成 500。</p>
     */
    private AiHealthReportVo readCachedReport() {
        try {
            String cached = RedisUtils.getCacheObject(REPORT_CACHE_KEY);
            if (StringUtils.isBlank(cached)) {
                return null;
            }
            AiHealthReportVo report = JsonUtils.parseObject(cached, AiHealthReportVo.class);
            return report == null || report.getTotal() <= 0 ? null : report;
        } catch (Exception e) {
            log.warn("健康自检报告缓存不可用，改为实时体检：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 写报告缓存并重置 TTL；写失败只降级为「不缓存」。
     */
    private void cacheReport(AiHealthReportVo report) {
        try {
            RedisUtils.setCacheObject(REPORT_CACHE_KEY, JsonUtils.toJsonString(report), REPORT_CACHE_TTL);
        } catch (Exception e) {
            log.warn("健康自检报告写缓存失败，本次结果仍正常返回：{}", e.getMessage());
        }
    }

    /**
     * 逐项体检并汇总（不含缓存判定）。
     */
    private AiHealthReportVo runChecks() {
        return runChecks(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MILLIS));
    }

    /**
     * @param deadlineNanos 整屏预算的截止时刻（{@link System#nanoTime()} 基准）。
     *                      开成包内可见是测试接缝：真实等满 30 秒的用例不该留在 CI 里。
     */
    AiHealthReportVo runChecks(long deadlineNanos) {
        List<AiHealthCheckVo> items = new ArrayList<>();
        items.add(safe("db", "数据库连通", CATEGORY_INFRA, this::checkDb, deadlineNanos));
        items.add(safe("db.schema", "AI 业务表齐全", CATEGORY_INFRA, this::checkSchema, deadlineNanos));
        items.add(safe("redis", "Redis 连通", CATEGORY_INFRA, this::checkRedis, deadlineNanos));
        items.add(safe("runtime.capacity", "磁盘与 Redis 余量", CATEGORY_INFRA, this::checkCapacity, deadlineNanos));
        items.add(safe("model.chat.key", "对话模型密钥", CATEGORY_MODEL, this::checkChatKeys, deadlineNanos));
        items.add(safe("model.embedding", "向量模型密钥与维度", CATEGORY_MODEL, this::checkEmbedding, deadlineNanos));
        items.add(safe("vector.milvus", "向量库（Milvus）", CATEGORY_MODEL, this::checkMilvus, deadlineNanos));
        items.add(safe("rag.bm25", "BM25 索引就绪", CATEGORY_RUNTIME, this::checkBm25Index, deadlineNanos));
        items.add(safe("rag.queue", "向量化队列积压", CATEGORY_RUNTIME, this::checkVectorQueue, deadlineNanos));
        items.add(safe("agent.model", "智能体绑定模型", CATEGORY_AGENT, this::checkAgentModels, deadlineNanos));
        items.add(safe("mcp.server", "MCP 出口口令与工具", CATEGORY_INTEGRATION, this::checkMcpServer, deadlineNanos));
        items.add(safe("mcp.client", "外部 MCP 连接", CATEGORY_INTEGRATION, this::checkMcpClients, deadlineNanos));
        items.add(safe("channel.stream", "渠道长连接在线", CATEGORY_INTEGRATION, this::checkChannelStream, deadlineNanos));
        items.add(safe("safety.engine", "内容安全引擎", CATEGORY_RUNTIME, this::checkSafetyEngine, deadlineNanos));
        items.add(safe("rag.rerank", "检索重排（Rerank）", CATEGORY_RUNTIME, this::checkRerank, deadlineNanos));
        items.add(safe("runtime.timezone", "时区一致性", CATEGORY_RUNTIME, this::checkTimezone, deadlineNanos));
        items.add(safe("runtime.reaper", "回收任务心跳", CATEGORY_RUNTIME, this::checkReaperHeartbeat, deadlineNanos));
        items.add(safe("runtime.error-log", "近 24 小时错误日志", CATEGORY_RUNTIME, this::checkErrorLog, deadlineNanos));
        items.add(safe("structured-decision", "结构化决策连通性", CATEGORY_RUNTIME, this::checkStructuredDecision, deadlineNanos));
        items.add(safe("storage.domain", "对象存储对外域名", CATEGORY_STORAGE, this::checkStorageDomain, deadlineNanos));
        items.add(safe("storage.io", "对象存储读写删", CATEGORY_STORAGE, this::checkStorageIo, deadlineNanos));
        items.add(safe("crypto.key", "加密根密钥收口", CATEGORY_RUNTIME, this::checkCryptoKey, deadlineNanos));

        AiHealthReportVo report = new AiHealthReportVo();
        report.setItems(items);
        report.setTotal(items.size());
        report.setPassCount((int) count(items, AiHealthCheckVo.STATUS_PASS));
        report.setWarnCount((int) count(items, AiHealthCheckVo.STATUS_WARN));
        report.setFailCount((int) count(items, AiHealthCheckVo.STATUS_FAIL));
        report.setUnknownCount((int) count(items, AiHealthCheckVo.STATUS_UNKNOWN));
        report.setOverall(overall(items));
        report.setCheckedAt(LocalDateTime.now());
        report.setVersion(platformVersion);
        return report;
    }

    @Override
    public AiHealthCheckVo probe(AiHealthProbeBo bo) {
        String target = bo == null ? null : bo.getTarget();
        if (AiHealthCheckVo.TARGET_MILVUS.equals(target)) {
            return probeMilvus();
        }
        if (AiHealthCheckVo.TARGET_MODEL.equals(target)) {
            return probeModel(bo.getId());
        }
        if (AiHealthCheckVo.TARGET_MCP_CLIENT.equals(target)) {
            return probeMcpClient(bo.getId());
        }
        if (AiHealthCheckVo.TARGET_JEV.equals(target)) {
            return probeJev();
        }
        throw new ServiceException("不支持的探测目标：" + target);
    }

    // ---------------------------------------------------------------- 各体检项

    /**
     * 数据库连通与版本。
     */
    private AiHealthCheckVo checkDb() {
        String key = "db";
        String name = "数据库连通";
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                return fail(key, name, CATEGORY_INFRA, "连接无效（isValid=false）", "检查数据库容器与连接池配置");
            }
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName();
            String version = meta.getDatabaseProductVersion();
            String detail = "已连接 " + product + " " + version;
            if (product == null || !(product.contains("PostgreSQL") || product.contains("MySQL"))) {
                return warn(key, name, CATEGORY_INFRA, detail + "（非平台默认支持的 PG / MySQL）",
                    "确认数据源方言与建库脚本方言一致");
            }
            return pass(key, name, CATEGORY_INFRA, detail);
        } catch (Exception e) {
            return fail(key, name, CATEGORY_INFRA, "连接失败：" + e.getMessage(),
                "检查数据库容器是否存活、连接串与口令是否正确");
        }
    }

    /**
     * AI 业务表齐全（缺表说明建库脚本未完整执行）。
     *
     * <p>待检清单不写死在代码里：加表时改一边不改另一边，自检就只按旧清单自证。实体上的
     * {@code @TableName} 是唯一来源，「DDL 表集合 = 实体表集合 = Mapper 覆盖集合」由
     * L00 F 段守住（设计稿 §12.7 I-1）。</p>
     */
    private AiHealthCheckVo checkSchema() {
        String key = "db.schema";
        String name = "AI 业务表齐全";
        Set<String> expected = expectedAiTables();
        if (expected.isEmpty()) {
            return fail(key, name, CATEGORY_INFRA, "AI 实体表清单为空：未从 @TableName 解析到任何 ai_ 开头的表",
                "确认 ruoyi-admin 依赖 agent-hub-ai，且 Mapper 扫描覆盖 org.dromara.ai.mapper");
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            Set<String> existing = new HashSet<>();
            try (ResultSet rs = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    existing.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            List<String> missing = expected.stream().filter(table -> !existing.contains(table)).toList();
            if (!missing.isEmpty()) {
                return fail(key, name, CATEGORY_INFRA,
                    "缺少表：" + String.join("、", missing) + "（实体登记 " + expected.size() + " 张，库内命中 "
                        + (expected.size() - missing.size()) + " 张）",
                    "执行建库脚本 script/sql/{postgres|mysql}/agenthub-ai-ddl.sql（编排序号 20，口径见 docs/configuration.md §3）");
            }
            return pass(key, name, CATEGORY_INFRA, "实体登记的 " + expected.size() + " 张 ai_ 表在库内全部存在");
        } catch (Exception e) {
            return fail(key, name, CATEGORY_INFRA, "元数据查询失败：" + e.getMessage(), "检查数据库账号权限");
        }
    }

    /**
     * 实体侧的 AI 表清单：MyBatis-Plus 在解析 Mapper 时按实体注册 TableInfo。
     *
     * <p>只认显式 {@code @TableName} 的实体——{@code AiAgent} 这类跨表共用字段载体没有注解，
     * MyBatis-Plus 会按类名推导出一个并不存在的 {@code ai_agent}，照着它查表就是永久失败。</p>
     */
    static Set<String> expectedAiTables() {
        return TableInfoHelper.getTableInfos().stream()
            .map(info -> info.getEntityType().getAnnotation(TableName.class))
            .filter(Objects::nonNull)
            .map(TableName::value)
            .map(table -> table.toLowerCase(Locale.ROOT))
            .filter(table -> table.startsWith(AI_TABLE_PREFIX))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Redis 连通（读取一个不存在的键，只验证命令通道）。
     */
    private AiHealthCheckVo checkRedis() {
        String key = "redis";
        String name = "Redis 连通";
        try {
            RedisUtils.getCacheObject(REDIS_PROBE_KEY);
            return pass(key, name, CATEGORY_INFRA, "命令通道可用（运行期配置可正常读写）");
        } catch (Exception e) {
            return fail(key, name, CATEGORY_INFRA, "读写失败：" + e.getMessage(),
                "检查 Redis 容器、密码与网络；Redis 不可用会导致运行期配置与装配缓存失效");
        }
    }

    /**
     * 对话模型密钥是否已配置（开箱第一公里的头号阻塞点）。
     */
    private AiHealthCheckVo checkChatKeys() {
        String key = "model.chat.key";
        String name = "对话模型密钥";
        List<AiModelConfigVo> models = modelConfigService.queryOptions("chat");
        if (models == null || models.isEmpty()) {
            return fail(key, name, CATEGORY_MODEL, "没有启用中的对话模型",
                "到「模型配置」新增一个对话模型并启用");
        }
        List<String> missing = models.stream()
            .filter(model -> StringUtils.isBlank(model.getApiKeyMask()))
            .map(AiModelConfigVo::getConfigName)
            .toList();
        AiHealthCheckVo vo;
        if (missing.size() == models.size()) {
            vo = fail(key, name, CATEGORY_MODEL,
                "全部 " + models.size() + " 个对话模型均未配置 API 密钥",
                "到「模型配置」补全密钥；未配置密钥时对话、知识库与专家生成全部不可用");
        } else if (!missing.isEmpty()) {
            vo = warn(key, name, CATEGORY_MODEL,
                "以下对话模型未配置密钥：" + String.join("、", missing),
                "到「模型配置」补全，避免切到这些模型时报错");
        } else {
            vo = pass(key, name, CATEGORY_MODEL, models.size() + " 个启用中的对话模型均已配置密钥");
        }
        return withProbe(vo, AiHealthCheckVo.TARGET_MODEL, models.get(0).getId());
    }

    /**
     * 向量模型密钥与维度一致性（维度不一致会导致建库/检索直接失败）。
     */
    private AiHealthCheckVo checkEmbedding() {
        String key = "model.embedding";
        String name = "向量模型密钥与维度";
        List<AiKnowledgeBaseVo> bases = knowledgeBaseService.queryOptions();
        if (bases == null || bases.isEmpty()) {
            return pass(key, name, CATEGORY_MODEL, "无启用中的知识库，跳过");
        }
        Map<Long, AiModelConfigVo> models = modelConfigService.queryOptions("embedding").stream()
            .filter(model -> model.getId() != null)
            .collect(Collectors.toMap(AiModelConfigVo::getId, model -> model, (left, right) -> left,
                LinkedHashMap::new));
        List<String> problems = new ArrayList<>();
        Long probeId = null;
        for (AiKnowledgeBaseVo base : bases) {
            if (base.getEmbeddingModelId() == null) {
                problems.add(base.getKbName() + "：未绑定向量模型");
                continue;
            }
            AiModelConfigVo model = models.get(base.getEmbeddingModelId());
            if (model == null) {
                problems.add(base.getKbName() + "：向量模型已停用或不存在");
                continue;
            }
            if (probeId == null) {
                probeId = model.getId();
            }
            if (StringUtils.isBlank(model.getApiKeyMask())) {
                problems.add(base.getKbName() + "：向量模型未配置密钥");
                continue;
            }
            if (model.getDimension() != null && base.getDimension() != null
                && !model.getDimension().equals(base.getDimension())) {
                problems.add(base.getKbName() + "：库维度 " + base.getDimension()
                    + " 与模型维度 " + model.getDimension() + " 不一致");
            }
        }
        AiHealthCheckVo vo;
        if (!problems.isEmpty()) {
            vo = fail(key, name, CATEGORY_MODEL, String.join("；", problems),
                "统一向量维度（或清空该库文档后按模型维度重建），并补全向量模型密钥");
        } else {
            vo = pass(key, name, CATEGORY_MODEL, bases.size() + " 个启用中的知识库维度与密钥均一致");
        }
        return probeId == null ? vo : withProbe(vo, AiHealthCheckVo.TARGET_MODEL, probeId);
    }

    /**
     * 向量库（Milvus）配置与连接状态。
     */
    private AiHealthCheckVo checkMilvus() {
        String key = "vector.milvus";
        String name = "向量库（Milvus）";
        AiProperties.Milvus milvus = aiProperties.getMilvus();
        if (StringUtils.isBlank(milvus.getUri())) {
            return fail(key, name, CATEGORY_MODEL, "未配置向量库地址",
                "配置 agenthub.ai.milvus.uri（容器内为 http://milvus:19530）");
        }
        List<AiKnowledgeBaseVo> bases = knowledgeBaseService.queryOptions();
        boolean ready = bases != null && bases.stream()
            .map(AiKnowledgeBaseVo::getCollectionName)
            .filter(StringUtils::isNotBlank)
            .anyMatch(vectorStore::isReady);
        AiHealthCheckVo vo;
        if (ready) {
            vo = pass(key, name, CATEGORY_MODEL, "本进程已与 " + milvus.getUri() + " 建立集合连接");
        } else {
            vo = unknown(key, name, CATEGORY_MODEL,
                "配置就绪（uri=" + milvus.getUri() + "），但本次启动尚未建立连接，无法本地判定",
                "点「测试」做一次真实连接；若暂不使用知识库可忽略");
        }
        vo.setProbeSupported(true);
        vo.setProbeTarget(AiHealthCheckVo.TARGET_MILVUS);
        return vo;
    }

    /**
     * 智能体绑定的模型是否可用（入口失效＝平台不可对话，专家失效＝该专家不可用）。
     *
     * <p>取平台全量视图而不是 {@code queryOptions()}：后者按登录用户的可见范围过滤专家，
     * 会让同一项在不同人眼里结论不同，而报告还要按 §12.3 缓存 60 秒跨请求复用。</p>
     */
    private AiHealthCheckVo checkAgentModels() {
        String key = "agent.model";
        String name = "智能体绑定模型";
        List<AiAgentVo> agents = agentService.queryPlatformOptions();
        if (agents == null || agents.isEmpty()) {
            return fail(key, name, CATEGORY_AGENT, "未查询到启用中的智能体",
                "到「小Z配置」完成入口智能体配置");
        }
        Map<Long, AiModelConfigVo> available = modelConfigService.queryOptions(AiModelConfig.TYPE_CHAT).stream()
            .filter(model -> model.getId() != null)
            .collect(Collectors.toMap(AiModelConfigVo::getId, model -> model, (left, right) -> left,
                LinkedHashMap::new));
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (AiAgentVo agent : agents) {
            boolean entry = AiAgent.ROLE_ENTRY.equals(agent.getRole());
            String label = StringUtils.isBlank(agent.getAgentName()) ? agent.getAgentCode() : agent.getAgentName();
            if (agent.getModelId() == null) {
                (entry ? failures : warnings).add(label + "：未绑定对话模型");
            } else if (!available.containsKey(agent.getModelId())) {
                (entry ? failures : warnings).add(label + "：对话模型已停用或不存在");
            }
            appendSlotIssue(agent.getMemoryModelId(), available, label + "：记忆抽取模型", warnings);
            appendSlotIssue(agent.getSummaryModelId(), available, label + "：压缩摘要模型", warnings);
            appendSlotIssue(agent.getSubagentModelId(), available, label + "：子智能体默认模型", warnings);
        }
        if (!failures.isEmpty()) {
            List<String> all = new ArrayList<>(failures);
            all.addAll(warnings);
            return fail(key, name, CATEGORY_AGENT, String.join("；", all),
                "到「小Z配置」重新绑定一个启用中的对话模型，否则平台无法对话");
        }
        if (!warnings.isEmpty()) {
            return warn(key, name, CATEGORY_AGENT, String.join("；", warnings),
                "到「专家配置」修正模型绑定（不修则回退该专家的主模型或报错）");
        }
        return pass(key, name, CATEGORY_AGENT, agents.size() + " 个启用中的智能体模型绑定均可用");
    }

    /**
     * MCP 出口口令与已挂载工具。
     */
    private AiHealthCheckVo checkMcpServer() {
        String key = "mcp.server";
        String name = "MCP 出口口令与工具";
        AiMcpServerVo server = mcpServerService.queryServer();
        if (server == null) {
            return warn(key, name, CATEGORY_INTEGRATION, "未取到出口状态", "查看应用日志确认 MCP 出口是否装配成功");
        }
        if (!server.isAuthEnabled()) {
            return pass(key, name, CATEGORY_INTEGRATION, "未启用口令校验（出口 " + server.getEndpoint() + "）");
        }
        String token = server.getToken();
        if (StringUtils.isBlank(token)) {
            return fail(key, name, CATEGORY_INTEGRATION, "已启用口令校验但口令为空，出口所有请求都会被拒绝",
                "到「MCP 服务端」页配置访问口令");
        }
        List<String> liveTools = server.getLiveTools();
        if (liveTools == null || liveTools.isEmpty()) {
            return warn(key, name, CATEGORY_INTEGRATION, "口令已配置，但出口当前未挂载任何工具",
                "到「MCP 服务端」勾选需要对外暴露的只读工具");
        }
        if (SAMPLE_MCP_TOKEN.equals(token) || token.length() < 16) {
            return warn(key, name, CATEGORY_INTEGRATION,
                "口令较弱（出厂示例值或长度不足 16），当前已挂载 " + liveTools.size() + " 个工具",
                "改为更强的口令，并同步更新调用方（含自连接客户端）");
        }
        return pass(key, name, CATEGORY_INTEGRATION, "口令已配置，出口已挂载 " + liveTools.size() + " 个工具");
    }

    /**
     * 外部 MCP 连接是否具备可用形态（真连由「测试」触发）。
     */
    private AiHealthCheckVo checkMcpClients() {
        String key = "mcp.client";
        String name = "外部 MCP 连接";
        List<AiMcpClient> clients = mcpClientMapper.selectList(Wrappers.<AiMcpClient>lambdaQuery()
            .eq(AiMcpClient::getStatus, AiMcpClient.STATUS_ENABLED)
            .orderByAsc(AiMcpClient::getId));
        if (clients == null || clients.isEmpty()) {
            return pass(key, name, CATEGORY_INTEGRATION, "无启用中的外部 MCP 连接");
        }
        List<String> blankUrl = clients.stream()
            .filter(client -> StringUtils.isBlank(client.getUrl()))
            .map(AiMcpClient::getServerName)
            .toList();
        if (!blankUrl.isEmpty()) {
            return fail(key, name, CATEGORY_INTEGRATION, "启用中的连接缺少地址：" + String.join("、", blankUrl),
                "补全连接地址或停用该连接");
        }
        String names = clients.stream().map(AiMcpClient::getServerName).collect(Collectors.joining("、"));
        AiHealthCheckVo vo = unknown(key, name, CATEGORY_INTEGRATION,
            "启用中的连接 " + clients.size() + " 个（" + names + "），需真实连接才能判定可用性",
            "点「测试」验证能否连通并发现工具");
        vo.setProbeSupported(true);
        vo.setProbeTarget(AiHealthCheckVo.TARGET_MCP_CLIENT);
        vo.setProbeId(clients.get(0).getId());
        return vo;
    }

    /**
     * 内容安全引擎（本地词库 / 外部 API；外部引擎缺地址或密钥时降级为本地词库）。
     */
    private AiHealthCheckVo checkSafetyEngine() {
        String key = "safety.engine";
        String name = "内容安全引擎";
        AiSecurityConfigBo security = runtimeConfigStore.security();
        String pii = security.isMaskPii() ? "开启" : "关闭";
        if (!security.isEnabled()) {
            return warn(key, name, CATEGORY_RUNTIME,
                "内容审核未启用（PII 脱敏：" + pii + "，两者独立开关）",
                "按需到「安全合规配置」开启；开启后默认只留痕（action=log）");
        }
        boolean external = "external".equalsIgnoreCase(security.getEngine());
        if (external && (StringUtils.isBlank(security.getProviderEndpoint())
            || StringUtils.isBlank(security.getProviderApiKey()))) {
            return warn(key, name, CATEGORY_RUNTIME,
                "审核引擎配置为 external，但接口地址 / 密钥不全，实际降级为本地词库（PII 脱敏：" + pii + "）",
                "到「安全合规配置」补全外部引擎的接口地址与密钥，或改回 local 使用本地词库");
        }
        return pass(key, name, CATEGORY_RUNTIME,
            "内容审核已启用（引擎 " + (external ? "external" : "local")
                + "，处置动作 " + security.getAction() + "，PII 脱敏：" + pii + "）");
    }

    /**
     * 检索重排（按**知识库级配置**判定：启用重排的库是否都绑定了可用的重排模型）。
     *
     * <p>口径（2026-10-14 调整）：重排模型登记在「模型配置」（{@code model_type=rerank}），
     * 「是否启用 + 用哪个模型」由知识库配置（{@code ai_knowledge_base.rerank_enabled / rerank_model_id}）。
     * 若某启用重排的知识库未绑模型 / 模型不存在 / 类型不符 / 已停用 / 缺密钥，检索只会**静默降级为原序**，
     * 故这里逐一给出 warn；全部可用才 pass。</p>
     */
    private AiHealthCheckVo checkRerank() {
        String key = "rag.rerank";
        String name = "检索重排（Rerank）";
        List<AiKnowledgeBaseVo> rerankKbs = knowledgeBaseService.queryOptions().stream()
            .filter(kb -> "1".equals(StringUtils.trim(kb.getRerankEnabled())))
            .toList();
        if (rerankKbs.isEmpty()) {
            return pass(key, name, CATEGORY_RUNTIME, "检索重排未启用（保持向量原序）");
        }
        StringBuilder problems = new StringBuilder();
        for (AiKnowledgeBaseVo kb : rerankKbs) {
            String reason = rerankModelProblem(kb.getRerankModelId());
            if (reason != null) {
                problems.append(kb.getKbName()).append("：").append(reason).append("；");
            }
        }
        if (problems.length() > 0) {
            return warn(key, name, CATEGORY_RUNTIME,
                "有启用重排的知识库其重排模型不可用，检索将静默降级为原序：" + problems,
                "到「模型配置」新增并启用 rerank 模型、补全密钥，或到「知识库管理」关闭该库的重排");
        }
        return pass(key, name, CATEGORY_RUNTIME,
            "检索重排已启用（" + rerankKbs.size() + " 个知识库，融合模式 " + aiProperties.getRag().getRerankMode() + "）");
    }

    /**
     * 校验某重排模型是否可用。
     *
     * @param modelId 模型主键（可为空）
     * @return 不可用原因；可用时返回 {@code null}
     */
    private String rerankModelProblem(Long modelId) {
        if (modelId == null) {
            return "未绑定重排模型";
        }
        AiModelConfigVo model = modelConfigService.queryById(modelId);
        if (model == null) {
            return "绑定的重排模型不存在";
        }
        if (!AiModelConfig.TYPE_RERANK.equalsIgnoreCase(StringUtils.trim(model.getModelType()))) {
            return "绑定的模型不是重排类型（" + model.getConfigName() + "）";
        }
        if (!"0".equals(StringUtils.trim(model.getStatus()))) {
            return "绑定的重排模型已停用（" + model.getConfigName() + "）";
        }
        if (StringUtils.isBlank(model.getApiKeyMask())) {
            return "绑定的重排模型未配置密钥（" + model.getConfigName() + "）";
        }
        return null;
    }

    /**
     * 时区一致性（注入时区与 JVM 默认时区不一致时，模型给出的时间会与日志/系统对不上）。
     */
    private AiHealthCheckVo checkTimezone() {
        String key = "runtime.timezone";
        String name = "时区一致性";
        String configured = aiProperties.getChat().getCurrentTimeZone();
        String jvm = TimeZone.getDefault().getID();
        if (!aiProperties.getChat().isCurrentTimeEnabled()) {
            return pass(key, name, CATEGORY_RUNTIME, "未启用「当前时间」注入，无需比对（JVM 时区 " + jvm + "）");
        }
        if (StringUtils.isBlank(configured)) {
            return warn(key, name, CATEGORY_RUNTIME,
                "未配置注入时区，将回退 JVM 默认时区（" + jvm + "）",
                "配置 agenthub.ai.chat.current-time-zone（推荐 Asia/Shanghai）");
        }
        if (!configured.equalsIgnoreCase(jvm)) {
            return warn(key, name, CATEGORY_RUNTIME,
                "注入时区 " + configured + " 与 JVM 默认时区 " + jvm + " 不一致",
                "统一容器 TZ 与 agenthub.ai.chat.current-time-zone，避免模型给出的时间与系统日志对不上");
        }
        return pass(key, name, CATEGORY_RUNTIME, "注入时区与 JVM 默认时区一致（" + jvm + "）");
    }

    /**
     * 结构化决策（专家路由判断）连通性：本地只判定配置形态，真实连通由「测试」探测。
     *
     * <p>本项<b>绝不外呼</b>（与 {@link #check()} 的契约一致）：关闭态直接判通过；开启后按所选决策模型的
     * 形态给 PASS / WARN / FAIL（见 {@link #resolveDecisionModelShape(AiJevConfigBo)}）；
     * 形态齐全时返回 UNKNOWN 并标记可探测。</p>
     */
    private AiHealthCheckVo checkStructuredDecision() {
        String key = "structured-decision";
        String name = "结构化决策连通性";
        AiJevConfigBo jev = runtimeConfigStore.jev();
        DecisionModelShape shape = resolveDecisionModelShape(jev);
        if (shape.status() != null) {
            return item(key, name, CATEGORY_RUNTIME, shape.status(), shape.detail(), shape.suggestion());
        }
        AiHealthCheckVo vo = unknown(key, name, CATEGORY_RUNTIME,
            "已启用（决策模型「" + shape.model().getConfigName() + "」，模式="
                + RouteJudgeMode.parse(jev.getMode()).label()
                + "，阈值=" + jev.getConfidenceThreshold() + "），需探测确认连通性",
            "点「测试」发起一次真实判定；判定不可用时会自动回落工具决策，不影响对话");
        vo.setProbeSupported(true);
        vo.setProbeTarget(AiHealthCheckVo.TARGET_JEV);
        return vo;
    }

    /**
     * 解析所选决策模型的形态（「模型配置」中类型为 {@code decision} 的记录），并给出本地可定的结论。
     *
     * <p>决策模型的连接信息统一由「模型配置」管理，本配置只持有 {@code modelConfigId}，故形态校验覆盖：
     * 未启用 → 通过；未选模型 / 模型不存在 / 已停用 / 类型不符 → 失败；密钥为空 → 告警；
     * 全部满足 → 需探测（{@code status} 返回 {@code null}）。</p>
     *
     * @param jev 结构化决策运行期配置
     * @return 形态判定结果
     */
    private DecisionModelShape resolveDecisionModelShape(AiJevConfigBo jev) {
        if (!jev.isEnabled()) {
            return new DecisionModelShape(null, AiHealthCheckVo.STATUS_PASS,
                "未启用，路由由小Z 自行判定（关闭态零外呼）", "");
        }
        Long modelConfigId = jev.getModelConfigId();
        if (modelConfigId == null) {
            return new DecisionModelShape(null, AiHealthCheckVo.STATUS_FAIL,
                "已开启但未选择决策模型，判定不可用",
                "到「安全合规配置 → 结构化决策路由」选择一条决策模型（需先在「模型配置」登记，类型选「决策模型」）");
        }
        AiModelConfigVo model = modelConfigService.queryById(modelConfigId);
        if (model == null) {
            return new DecisionModelShape(null, AiHealthCheckVo.STATUS_FAIL,
                "已开启但所选决策模型已不存在（id=" + modelConfigId + "），判定不可用",
                "到「安全合规配置 → 结构化决策路由」重新选择决策模型");
        }
        if (!"0".equals(model.getStatus())) {
            return new DecisionModelShape(null, AiHealthCheckVo.STATUS_FAIL,
                "所选决策模型「" + model.getConfigName() + "」已停用，判定不可用",
                "到「模型配置」启用该模型，或改选其他决策模型");
        }
        if (!AiModelConfig.TYPE_DECISION.equalsIgnoreCase(model.getModelType())) {
            return new DecisionModelShape(null, AiHealthCheckVo.STATUS_FAIL,
                "所选模型「" + model.getConfigName() + "」类型不是决策模型（实际 " + model.getModelType() + "）",
                "到「安全合规配置 → 结构化决策路由」改选类型为「决策模型」的记录");
        }
        if (StringUtils.isBlank(modelConfigService.decryptApiKey(modelConfigId))) {
            return new DecisionModelShape(model, AiHealthCheckVo.STATUS_WARN,
                "所选决策模型「" + model.getConfigName() + "」未配置 API 密钥，判定将不可用（自动回落工具决策）",
                "到「模型配置」补全该模型的 API 密钥");
        }
        return new DecisionModelShape(model, null, "", "");
    }

    /**
     * 对象存储对外域名（未配置时前端附件预览/下载会失败，后端不受影响）。
     */
    private AiHealthCheckVo checkStorageDomain() {
        String key = "storage.domain";
        String name = "对象存储对外域名";
        String domain = ossService.selectDomainUrl();
        if (StringUtils.isBlank(domain)) {
            return warn(key, name, CATEGORY_STORAGE, "对象存储对外域名（domain_url）未配置",
                "到「对象存储配置」补全 domain_url（须为浏览器可达地址），否则附件预览/下载会失败");
        }
        return pass(key, name, CATEGORY_STORAGE, "对外域名：" + domain);
    }

    /**
     * 磁盘与 Redis 内存余量（§12.3 WARN 档「余量 &lt;15%」，读数细节见 {@link AiHealthCapacityProbe#capacity()}）。
     */
    private AiHealthCheckVo checkCapacity() {
        return verdict("runtime.capacity", "磁盘与 Redis 余量", CATEGORY_INFRA, capacityProbe.capacity());
    }

    /**
     * 混合检索知识库的 BM25 索引是否就绪（§12.3 WARN 档）。
     *
     * <p>只看启用中的知识库：停用库的索引坏了不影响线上检索，不值得报警。{@code queryOptions()} 只按
     * 启用状态过滤、不按登录用户可见范围过滤，符合 §12.7 I-15 对缓存报告的口径要求。</p>
     *
     * <p>没有任何混合检索库时判通过——此时「BM25 没建」是正确状态而不是缺口，不是「接不进」。</p>
     */
    private AiHealthCheckVo checkBm25Index() {
        String key = "rag.bm25";
        String name = "BM25 索引就绪";
        List<AiKnowledgeBaseVo> hybrid = knowledgeBaseService.queryOptions().stream()
            .filter(kb -> MODE_HYBRID.equals(kb.getRetrievalMode()))
            .toList();
        if (hybrid.isEmpty()) {
            return pass(key, name, CATEGORY_RUNTIME, "没有启用中的混合检索知识库，无需 BM25 索引");
        }
        List<String> failed = new ArrayList<>();
        List<String> notReady = new ArrayList<>();
        for (AiKnowledgeBaseVo kb : hybrid) {
            String label = StringUtils.isBlank(kb.getKbName()) ? kb.getKbCode() : kb.getKbName();
            String status = kb.getBm25IndexStatus();
            if (BM25_FAILED.equals(status)) {
                failed.add(label);
            } else if (!BM25_READY.equals(status)) {
                notReady.add(label + "（" + (StringUtils.isBlank(status) ? BM25_NONE : status) + "）");
            }
        }
        if (!failed.isEmpty()) {
            return warn(key, name, CATEGORY_RUNTIME,
                String.join("；", failed) + "：BM25 索引构建失败，混合检索会退化为纯向量检索",
                "到「知识库」重新触发索引构建；暂时修不好可把该库检索模式改回向量");
        }
        if (!notReady.isEmpty()) {
            return warn(key, name, CATEGORY_RUNTIME,
                String.join("；", notReady) + "：BM25 索引未就绪（未建或建设中）",
                "到「知识库」触发索引构建，建设完成后自动转为就绪");
        }
        return pass(key, name, CATEGORY_RUNTIME, hybrid.size() + " 个混合检索知识库的 BM25 索引均已就绪");
    }

    /**
     * 向量化批量队列积压（§12.3 WARN 档「打满 200」）。
     *
     * <p>排队数来自本进程的有界线程池，只反映当前副本；库内「待向量化」行数走 mapper 直查、
     * 不带可见范围过滤（§12.7 I-15），两个数一起给才看得出纳管有没有在动。
     * 判据按契约只用「打满」：排队未满而仍有待处理行属正常节奏，写进说明而不报警。</p>
     */
    private AiHealthCheckVo checkVectorQueue() {
        String key = "rag.queue";
        String name = "向量化队列积压";
        int queued = documentBatchRunner.queuedTaskCount();
        long pending = documentMapper.selectCount(Wrappers.lambdaQuery(AiDocument.class)
            .eq(AiDocument::getVectorStatus, DOC_VECTOR_PENDING));
        String detail = "本进程批量队列 " + queued + "/" + AiDocumentBatchRunner.QUEUE_CAPACITY
            + "，库内待向量化文档 " + pending + " 篇";
        if (queued >= AiDocumentBatchRunner.QUEUE_CAPACITY) {
            return warn(key, name, CATEGORY_RUNTIME, detail + "（队列已满，新提交的批量任务会被拒绝）",
                "等队列排空再提交；批量排队时间过长时同步调大 agenthub.ai.reaper.stale-minutes，避免排队被误判为中断");
        }
        return pass(key, name, CATEGORY_RUNTIME, detail);
    }

    /**
     * 长连接渠道（钉钉 / 飞书 {@code mode=ws}）是否已拉起（§12.3 WARN 档）。
     *
     * <p>状态是<b>单实例内存快照</b>，只代表这台副本拉起过没有，不代表此刻与平台网关连通
     * （断线由 SDK 自动重连）；说明里写清口径，免得运维把「运行中」读成「此刻在线」。</p>
     */
    private AiHealthCheckVo checkChannelStream() {
        String key = "channel.stream";
        String name = "渠道长连接在线";
        // 三条出口共用一个分类，且必须与 runChecks 注册时的分类一致：超时行的分类由注册处给，
        // 正常行的分类由这里给，两边不一致同一个检项会在页面两个分组里各出现一次。
        String category = CATEGORY_INTEGRATION;
        List<String> codes = channelService.listEnabledLongConnectionChannelCodes();
        if (codes == null || codes.isEmpty()) {
            return pass(key, name, category, "未启用长连接渠道（钉钉恒为长连接、飞书按 mode=ws），无需在线判定");
        }
        List<String> failed = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        for (String code : codes) {
            AiChannelStreamStatus.Snapshot snapshot = channelStreamStatus.get(code);
            if (snapshot == null) {
                offline.add(code + "（本实例未登记）");
            } else if (AiChannelStreamStatus.FAILED.equals(snapshot.status())) {
                failed.add(code + "：" + StringUtils.defaultIfBlank(snapshot.detail(), "未记录原因"));
            } else if (!AiChannelStreamStatus.RUNNING.equals(snapshot.status())) {
                offline.add(code + "（" + snapshot.status() + "）");
            }
        }
        if (!failed.isEmpty() || !offline.isEmpty()) {
            List<String> issues = new ArrayList<>(failed);
            issues.addAll(offline);
            return warn(key, name, category,
                String.join("；", issues) + "：长连接渠道启动失败或未在本实例拉起，该平台暂不会收到消息",
                "到「渠道接入」核对凭据与出网策略，保存后重启服务；多副本部署时确认每个副本都拉起了连接");
        }
        return pass(key, name, category,
            codes.size() + " 个长连接渠道均已在本实例拉起（断线由 SDK 自动重连，非实时连通性）");
    }

    /**
     * 回收任务心跳（§12.3 WARN 档「心跳超 2 个扫描周期」）。
     */
    private AiHealthCheckVo checkReaperHeartbeat() {
        return verdict("runtime.reaper", "回收任务心跳", CATEGORY_RUNTIME, capacityProbe.reaperHeartbeat());
    }

    /**
     * 近 24 小时的 ERROR 日志（§12.3 WARN 档）。
     */
    private AiHealthCheckVo checkErrorLog() {
        return verdict("runtime.error-log", "近 24 小时错误日志", CATEGORY_RUNTIME, capacityProbe.errorLog());
    }

    /**
     * 对象存储读 / 写 / 删（§12.3 FAIL 档要求的三项全链路）。
     *
     * <p><b>为什么现在是 UNKNOWN</b>：底座对业务模块只开放只读接口（{@code OssService} 的 URL 查询与批量取链接），
     * 没有上传与删除入口。要真跑一遍读写删，要么往桶里写探针对象（留下需要回收的垃圾文件），
     * 要么绕过底座自建客户端（与底座的配置与缓存脱节）。§12.7 I-3 的口径是「接不进的一律记 UNKNOWN 而非 PASS」，
     * 所以这里给出未接入的结论与原因，不伪造一次成功。</p>
     */
    private AiHealthCheckVo checkStorageIo() {
        return unknown("storage.io", "对象存储读写删", CATEGORY_STORAGE,
            "未接入：底座对象存储接口只暴露读（URL 查询），自检在不污染存储桶的前提下无法验证写与删",
            "确认方案后再接入：由底座开放带 TTL 的探针对象读写删接口，或允许自检写入固定前缀并即时删除");
    }

    /**
     * 加密根密钥是否已收口（沿用出厂值时 prod 启动会被生产配置收口检查拒绝）。
     */
    private AiHealthCheckVo checkCryptoKey() {
        String key = "crypto.key";
        String name = "加密根密钥收口";
        String cryptoKey = aiProperties.getCryptoKey();
        if (StringUtils.isBlank(cryptoKey)) {
            return fail(key, name, CATEGORY_RUNTIME, "未配置加密根密钥",
                "配置 agenthub.ai.crypto-key（16 / 24 / 32 位）");
        }
        if (SAMPLE_CRYPTO_KEY.equals(cryptoKey)) {
            return warn(key, name, CATEGORY_RUNTIME, "仍在使用出厂示例值",
                "设置环境变量 AGENTHUB_AI_CRYPTO_KEY 后重启；更换后需到「模型配置」重新录入密钥（旧密文无法解密）");
        }
        return pass(key, name, CATEGORY_RUNTIME, "已替换出厂值");
    }

    // ---------------------------------------------------------------- 探测

    /**
     * 探测 Milvus 连接。
     */
    private AiHealthCheckVo probeMilvus() {
        String key = "probe:" + AiHealthCheckVo.TARGET_MILVUS;
        String name = "Milvus 向量库";
        long start = System.currentTimeMillis();
        try {
            String detail = milvusProbe.probe();
            return probed(key, name, CATEGORY_MODEL, AiHealthCheckVo.STATUS_PASS, detail, "",
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            return probed(key, name, CATEGORY_MODEL, AiHealthCheckVo.STATUS_FAIL,
                "探测失败：" + e.getMessage(), "检查 Milvus 容器、地址与凭据（agenthub.ai.milvus.*）",
                System.currentTimeMillis() - start);
        }
    }

    /**
     * 探测模型连通性（发起一次真实调用）。
     */
    private AiHealthCheckVo probeModel(Long modelId) {
        if (modelId == null) {
            throw new ServiceException("模型探测需要指定模型主键");
        }
        AiModelConfigVo model = modelConfigService.queryById(modelId);
        if (model == null) {
            throw new ServiceException("模型不存在或已被删除");
        }
        String name = "模型 " + model.getConfigName();
        long start = System.currentTimeMillis();
        try {
            AiModelConfigBo bo = new AiModelConfigBo();
            bo.setId(modelId);
            AiModelTestVo result = modelConfigService.testConnection(bo);
            long elapsed = result != null && result.getElapsedMs() != null
                ? result.getElapsedMs() : System.currentTimeMillis() - start;
            boolean success = result != null && Boolean.TRUE.equals(result.getSuccess());
            String message = result == null ? "未返回测试结果" : result.getMessage();
            return probed("probe:" + AiHealthCheckVo.TARGET_MODEL + ":" + modelId, name, CATEGORY_MODEL,
                success ? AiHealthCheckVo.STATUS_PASS : AiHealthCheckVo.STATUS_FAIL,
                success ? "连通：" + message : "失败：" + message,
                success ? "" : "核对密钥、服务地址与模型标识；额度不足请在服务商充值或更换密钥", elapsed);
        } catch (Exception e) {
            return probed("probe:" + AiHealthCheckVo.TARGET_MODEL + ":" + modelId, name, CATEGORY_MODEL,
                AiHealthCheckVo.STATUS_FAIL, "探测异常：" + e.getMessage(),
                "核对密钥、服务地址与模型标识", System.currentTimeMillis() - start);
        }
    }

    /**
     * 探测外部 MCP 连接（真实建连并发现工具）。
     */
    private AiHealthCheckVo probeMcpClient(Long clientId) {
        if (clientId == null) {
            throw new ServiceException("MCP 连接探测需要指定连接主键");
        }
        AiMcpClient client = mcpClientMapper.selectById(clientId);
        if (client == null) {
            throw new ServiceException("MCP 连接不存在或已被删除");
        }
        String name = "MCP 连接 " + client.getServerName();
        long start = System.currentTimeMillis();
        try {
            AiMcpClientBo bo = new AiMcpClientBo();
            bo.setId(clientId);
            AiMcpClientTestVo result = mcpClientService.testConnection(bo);
            long elapsed = System.currentTimeMillis() - start;
            boolean success = result != null && result.isSuccess();
            String message = result == null ? "未返回测试结果" : result.getMessage();
            int toolCount = result == null || result.getTools() == null ? 0 : result.getTools().size();
            return probed("probe:" + AiHealthCheckVo.TARGET_MCP_CLIENT + ":" + clientId, name,
                CATEGORY_INTEGRATION,
                success ? AiHealthCheckVo.STATUS_PASS : AiHealthCheckVo.STATUS_FAIL,
                success ? "连通，发现 " + toolCount + " 个工具（" + message + "）" : "失败：" + message,
                success ? "" : "核对地址、传输方式与请求头凭据；跨容器部署时容器内需能访问该地址", elapsed);
        } catch (Exception e) {
            return probed("probe:" + AiHealthCheckVo.TARGET_MCP_CLIENT + ":" + clientId, name,
                CATEGORY_INTEGRATION, AiHealthCheckVo.STATUS_FAIL, "探测异常：" + e.getMessage(),
                "核对地址、传输方式与请求头凭据", System.currentTimeMillis() - start);
        }
    }

    /**
     * 探测结构化决策连通性（真实调一次判定；这是本项唯一允许外呼的入口）。
     *
     * <p>未启用时无需探测直接判通过；所选决策模型形态不满足（未选 / 不存在 / 停用 / 类型不符 / 无密钥）时
     * 直接返回形态结论（不做无谓外呼）；调用结果按「可用 / 不可用」给 PASS / WARN——判定不可用时按口径 P3
     * 会自动回落工具决策，不影响对话，故不作为 FAIL。</p>
     */
    private AiHealthCheckVo probeJev() {
        String key = "probe:" + AiHealthCheckVo.TARGET_JEV;
        String name = "结构化决策连通性";
        AiJevConfigBo jev = runtimeConfigStore.jev();
        DecisionModelShape shape = resolveDecisionModelShape(jev);
        if (!jev.isEnabled()) {
            return probed(key, name, CATEGORY_RUNTIME, AiHealthCheckVo.STATUS_PASS, "未启用，无需探测", "", 0L);
        }
        if (shape.status() != null && !AiHealthCheckVo.STATUS_PASS.equals(shape.status())) {
            // 形态已不满足（未选模型 / 不存在 / 停用 / 类型不符 / 无密钥）：直接返回形态结论，不做无谓外呼
            return probed(key, name, CATEGORY_RUNTIME, shape.status(), shape.detail(), shape.suggestion(), 0L);
        }
        long start = System.currentTimeMillis();
        try {
            // 最小请求：固定假候选（self / other 由实现补充），仅验证连通性，不参与真实路由
            RouteJudgeRequest request = new RouteJudgeRequest("这是一次连通性探测",
                JevSystemOneSupport.probeCandidates());
            Optional<RouteJudgeVerdict> verdictOpt = routeJudge.judge(request);
            long elapsed = System.currentTimeMillis() - start;
            if (verdictOpt.isEmpty()) {
                return probed(key, name, CATEGORY_RUNTIME, AiHealthCheckVo.STATUS_WARN,
                    "判定不可用：未返回判定结果",
                    "判定不可用时会自动回落工具决策，不影响对话；到「模型配置」核对所选决策模型的服务地址、密钥与模型标识", elapsed);
            }
            RouteJudgeVerdict verdict = verdictOpt.get();
            if (StringUtils.isNotBlank(verdict.error())) {
                return probed(key, name, CATEGORY_RUNTIME, AiHealthCheckVo.STATUS_WARN,
                    "判定不可用：" + verdict.error(),
                    "判定不可用时会自动回落工具决策，不影响对话；到「模型配置」核对所选决策模型的服务地址、密钥与模型标识", elapsed);
            }
            return probed(key, name, CATEGORY_RUNTIME, AiHealthCheckVo.STATUS_PASS,
                "判定可用（耗时 " + verdict.latencyMs() + " ms，输入 Token " + verdict.inputTokens()
                    + "，选择 " + verdict.choice() + "）", "", elapsed);
        } catch (Exception e) {
            return probed(key, name, CATEGORY_RUNTIME, AiHealthCheckVo.STATUS_WARN,
                "探测异常：" + e.getMessage(),
                "判定不可用时会自动回落工具决策，不影响对话", System.currentTimeMillis() - start);
        }
    }

    /**
     * 所选决策模型的形态判定结果。
     *
     * @param model      模型配置（形态可用、需探测时非空）
     * @param status     本地已可定论的状态（PASS / WARN / FAIL）；{@code null} 表示形态可用、需探测
     * @param detail     说明
     * @param suggestion 修复建议
     */
    private record DecisionModelShape(AiModelConfigVo model, String status, String detail, String suggestion) {
    }

    // ---------------------------------------------------------------- 工具方法

    /**
     * 单项兜底：异常降级为该行 FAIL、超时降级为 UNKNOWN，绝不让整页 500。
     *
     * <p>本项预算取「单项上限」与「整屏剩余」的较小值：卡住的是第 14 项时，
     * 不该再给满 3 秒把整屏拖到预算之外。</p>
     */
    private AiHealthCheckVo safe(String key, String name, String category,
                                 Callable<AiHealthCheckVo> body, long deadlineNanos) {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMillis <= 0) {
            return unknown(key, name, category,
                "判定超时：整屏 " + TimeUnit.MILLISECONDS.toSeconds(TOTAL_BUDGET_MILLIS) + " 秒预算已用尽，本项未执行",
                "点「重新自检」或逐项「测试」定位卡住的依赖");
        }
        long budgetMillis = Math.min(ITEM_TIMEOUT_MILLIS, remainingMillis);
        long startedAt = System.nanoTime();
        try {
            AiHealthCheckVo vo = itemInvoker.invoke(body, budgetMillis);
            if (vo == null) {
                return fail(key, name, category, "体检项未返回结果", "查看应用日志定位");
            }
            vo.setElapsedMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            return vo;
        } catch (TimeoutException e) {
            log.warn("健康自检项判定超时：key={}, budgetMillis={}", key, budgetMillis);
            return unknown(key, name, category,
                "判定超时：单项超过 " + budgetMillis + " 毫秒未返回",
                "点「重新自检」或逐项「测试」，并检查该依赖是否可达");
        } catch (Exception e) {
            log.warn("健康自检项执行失败：key={}, cause={}", key, e.getMessage());
            return fail(key, name, category, "体检异常：" + e.getMessage(), "查看应用日志定位具体原因");
        }
    }

    /**
     * 槽位模型失效时追加告警（未配置不算问题）。
     */
    private void appendSlotIssue(Long slotModelId, Map<Long, AiModelConfigVo> available,
                                 String label, List<String> warnings) {
        if (slotModelId != null && !available.containsKey(slotModelId)) {
            warnings.add(label + "已停用或不存在");
        }
    }

    private AiHealthCheckVo withProbe(AiHealthCheckVo vo, String target, Long id) {
        vo.setProbeSupported(true);
        vo.setProbeTarget(target);
        vo.setProbeId(id);
        return vo;
    }

    private long count(List<AiHealthCheckVo> items, String status) {
        return items.stream().filter(item -> status.equals(item.getStatus())).count();
    }

    private String overall(List<AiHealthCheckVo> items) {
        if (items.stream().anyMatch(item -> AiHealthCheckVo.STATUS_FAIL.equals(item.getStatus()))) {
            return AiHealthCheckVo.STATUS_FAIL;
        }
        if (items.stream().anyMatch(item -> AiHealthCheckVo.STATUS_WARN.equals(item.getStatus()))) {
            return AiHealthCheckVo.STATUS_WARN;
        }
        return AiHealthCheckVo.STATUS_PASS;
    }

    private AiHealthCheckVo pass(String key, String name, String category, String detail) {
        return item(key, name, category, AiHealthCheckVo.STATUS_PASS, detail, "");
    }

    private AiHealthCheckVo warn(String key, String name, String category, String detail, String suggestion) {
        return item(key, name, category, AiHealthCheckVo.STATUS_WARN, detail, suggestion);
    }

    private AiHealthCheckVo fail(String key, String name, String category, String detail, String suggestion) {
        return item(key, name, category, AiHealthCheckVo.STATUS_FAIL, detail, suggestion);
    }

    private AiHealthCheckVo unknown(String key, String name, String category, String detail, String suggestion) {
        return item(key, name, category, AiHealthCheckVo.STATUS_UNKNOWN, detail, suggestion);
    }

    /**
     * 把探针结论落成检项行：状态与文案由探针判定，键 / 名称 / 分类归本服务。
     */
    private AiHealthCheckVo verdict(String key, String name, String category, AiHealthCapacityProbe.Verdict verdict) {
        return item(key, name, category, verdict.status(), verdict.detail(), verdict.suggestion());
    }

    private AiHealthCheckVo probed(String key, String name, String category, String status,
                                   String detail, String suggestion, long elapsedMs) {
        AiHealthCheckVo vo = item(key, name, category, status, detail, suggestion);
        vo.setElapsedMs(elapsedMs);
        return vo;
    }

    private AiHealthCheckVo item(String key, String name, String category, String status,
                                 String detail, String suggestion) {
        AiHealthCheckVo vo = new AiHealthCheckVo();
        vo.setKey(key);
        vo.setName(name);
        vo.setCategory(category);
        vo.setStatus(status);
        vo.setDetail(detail);
        vo.setSuggestion(suggestion);
        return vo;
    }

}
