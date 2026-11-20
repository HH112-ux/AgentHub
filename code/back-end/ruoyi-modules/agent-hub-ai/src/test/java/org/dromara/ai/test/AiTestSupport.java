package org.dromara.ai.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.linpeilie.Converter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.utils.SpringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;

/**
 * AI 模块单元测试的公共支撑：静态依赖初始化。
 *
 * <p>纯单元测试不起 Spring 容器，但以下两类静态能力会在类初始化时要求容器 / MyBatis 上下文，
 * 否则首次触达即抛异常：</p>
 * <ul>
 *   <li>{@code MapstructUtils} / {@code JsonUtils} / {@code RedisUtils} / {@code IdGeneratorUtil}：
 *       类初始化时从 Spring 取 {@code Converter} / {@code JsonMapper} / {@code RedissonClient} / {@code IdentifierGenerator} Bean；</li>
 *   <li>{@code LambdaQueryWrapper}：需要目标实体的 MyBatis-Plus {@code TableInfo}（lambda 缓存），
 *       否则抛 "can not find lambda cache for this entity"。</li>
 * </ul>
 *
 * <p>类初始化每个 JVM 只做一次，取的是当时挂在 {@code SpringUtils} 上的容器；测试类各自 new 一个
 * 只含部分 Bean 的容器会把它换掉，于是「谁先跑」决定后面全部用例是否可用——surefire 默认按文件系统顺序
 * 执行，本地与 CI 顺序不同即表现为本地全绿、CI 偶发红。因此这里由本类统一装载容器，并在装载后
 * 立刻把这几个工具类初始化掉（{@link #warmUpUtilityClasses()}），测试类只调 {@link #initialize()}，
 * 不再自建容器。</p>
 *
 * <p>用法：在测试类 {@code @BeforeAll} 中调用一次 {@link #initialize()}（幂等）。</p>
 */
public final class AiTestSupport {

    /**
     * 需要注册 TableInfo 的 AI 实体（org.dromara.ai.domain 下的表实体）。
     */
    private static final String[] ENTITY_NAMES = {
        "AiAgent", "AiAgentKb", "AiAgentSkill", "AiAgentSubagent", "AiAgentTask", "AiAgentTaskRun",
        "AiAgentTool", "AiAgentToolTrust", "AiChannel", "AiChannelIdentity", "AiChannelThread",
        "AiChunk", "AiDailyDigest", "AiDocument", "AiEntryAgent", "AiExpertAgent", "AiKnowledgeBase", "AiMcpClient",
        "AiMessage", "AiMessageAttachment", "AiModelConfig", "AiPermissionDecisionLog", "AiRouteExpertCall", "AiRouteRun",
        "AiSafetyEvent", "AiScenarioPack", "AiScenarioPackMcpTool", "AiScenarioPackSkill", "AiScenarioPackTool",
        "AiSensitiveWord", "AiSession", "AiSessionResource", "AiSkill", "AiSkillResource", "AiSubagent", "AiTool",
        "AiToolCallLog", "AiToolTag", "AiToolTagRel", "AiTrace", "AiUsageStat"
    };

    /**
     * 类初始化即依赖容器 Bean 的工具类；在本类装载好容器后一次性初始化，后续用例不再受执行顺序影响。
     */
    private static final String[] UTILITY_CLASS_NAMES = {
        "org.dromara.common.core.utils.MapstructUtils",
        "org.dromara.common.json.utils.JsonUtils",
        "org.dromara.common.redis.utils.RedisUtils",
        "org.dromara.common.mybatis.utils.IdGeneratorUtil"
    };

    private static final GenericApplicationContext CONTEXT = createContext();

    static {
        registerTableInfo();
        warmUpUtilityClasses();
    }

    private AiTestSupport() {
    }

    /**
     * 触发最小容器与 TableInfo 初始化（幂等）。
     */
    public static void initialize() {
        CONTEXT.isActive();
    }

    /**
     * 提前完成 {@link #UTILITY_CLASS_NAMES} 的类初始化，把「首次触达即取 Bean」固定在容器正确的时刻。
     */
    public static void warmUpUtilityClasses() {
        for (String name : UTILITY_CLASS_NAMES) {
            try {
                Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("工具类不存在：" + name, e);
            }
        }
    }

    /**
     * 为全部 AI 实体注册 MyBatis-Plus TableInfo（幂等）。
     */
    public static void registerTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("org.dromara.ai.test");
        for (String name : ENTITY_NAMES) {
            try {
                TableInfoHelper.initTableInfo(assistant, Class.forName("org.dromara.ai.domain." + name));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("AI 实体类不存在：" + name, e);
            }
        }
    }

    /**
     * 按属性名浅拷贝构造目标对象，模拟 Mapstruct Plus 的转换语义。
     *
     * @param source     源对象
     * @param targetType 目标类型
     * @param <T>        目标泛型
     * @return 目标实例
     */
    public static <T> T copy(Object source, Class<T> targetType) {
        try {
            T target = targetType.getDeclaredConstructor().newInstance();
            BeanUtils.copyProperties(source, target);
            return target;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造目标对象：" + targetType, e);
        }
    }

    private static GenericApplicationContext createContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.getBeanFactory().registerSingleton("jsonMapper", JsonMapper.builder().build());
        // RedisUtils / IdGeneratorUtil 的静态字段在类初始化时即从容器取 Bean，缺它会让类初始化永久失败，
        // 之后每个 mockStatic(RedisUtils.class) 都抛错并污染 Mockito 的 matcher 栈（后续用例报 InvalidUseOfMatchers）。
        context.getBeanFactory().registerSingleton("redissonClient", mock(RedissonClient.class));
        context.getBeanFactory().registerSingleton("identifierGenerator", mock(IdentifierGenerator.class));
        context.refresh();
        new SpringUtils().setApplicationContext(context);
        return context;
    }

}