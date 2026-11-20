package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.ai.domain.AiPermissionDecisionLog;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 权限上下文改写支撑。
 *
 * <p>把某 {@code (userId, sessionId)} 槽位上**显式 ask 规则一并改写为 deny**，并对指定写工具追加
 * deny 规则（无人值守的代码级兜底）；白名单写工具保持放行：
 * 框架的 {@link PermissionMode#DONT_ASK} 只降级「未命中即 ASK」的默认档，对显式 ask 规则无效——
 * 实测仍会触发 {@code RequireUserConfirmEvent} 并让该轮停在等待确认上（任务看似结束但无任何回答）。</p>
 *
 * <p>改写只作用于该槽位，不影响共享单例上的其他会话。两条链路共用本方法：
 * 无人值守链路（定时任务 / 渠道接入）与编排链路的专家执行（专家未开写工具开关时是纯分析型，
 * {@code ask} 即拒绝、不悬挂）。<b>代码级兜底</b>：无人值守链路的写工具拒绝不再依赖
 * 「智能体 / 场景包决策」——即使误配为允许，也按 {@code ai_tool.tool_type} 追加 DENY；
 * 仅 {@code agenthub.ai.security.unattended-write-allowlist} 中显式列出的编码放行。</p>
 *
 * <p>另含用户级「记住该工具」信任的槽位合并（{@link #applyUserToolTrust}），入口智能体与专家执行
 * 两条链路共用同一实现、只按链路传入各自的装配基线与槽位键。</p>
 *
 * @author AgentHub
 */
final class AiPermissionSupport {

    /**
     * 无人值守兜底拒绝规则的来源标识
     */
    private static final String SOURCE_UNATTENDED = "unattended";

    /**
     * 无人值守写工具白名单规则的来源标识
     */
    private static final String SOURCE_UNATTENDED_ALLOW = "unattended-allowlist";

    private AiPermissionSupport() {
    }

    /**
     * 把槽位上的 ask 规则改写为 deny，并追加指定写工具的 deny 规则。
     *
     * <p>处理顺序：① 保留工作目录与 allow / deny 基线；② ask 规则一律改写为 deny
     * （白名单写工具除外，避免无人确认悬挂）；③ 白名单写工具补 ALLOW——若管理端显式配置了 DENY，
     * 框架按 deny &gt; allow 判定，仍以 DENY 为准；④ 其余写工具追加 DENY。</p>
     *
     * @param agent            已装配的智能体
     * @param userId           用户标识（字符串形式，与 AgentScope 槽位键一致）
     * @param sessionId        会话标识（字符串形式）
     * @param writeToolCodes   全部写工具编码（{@code ai_tool.tool_type != 只读}）
     * @param allowedWriteCodes 允许无人值守自动执行的写工具编码（白名单）
     */
    static void denyWriteTools(HarnessAgent agent, String userId, String sessionId,
                               Set<String> writeToolCodes, Set<String> allowedWriteCodes) {
        AgentState agentState = agent.getDelegate().getAgentState(userId, sessionId);
        PermissionContextState base = agentState.getPermissionContext();
        Set<String> allowed = allowedWriteCodes == null ? Set.of() : allowedWriteCodes;
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(PermissionMode.DONT_ASK);
        base.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        base.getAllowRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addAllowRule(tool, rule)));
        base.getDenyRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addDenyRule(tool, rule)));
        base.getAskRules().forEach((tool, rules) -> {
            if (allowed.contains(tool)) {
                return;
            }
            rules.forEach(rule -> builder.addDenyRule(tool, rule));
        });
        for (String tool : allowed) {
            builder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW,
                SOURCE_UNATTENDED_ALLOW));
        }
        if (writeToolCodes != null) {
            for (String tool : writeToolCodes) {
                if (!allowed.contains(tool)) {
                    builder.addDenyRule(tool, new PermissionRule(tool, null, PermissionBehavior.DENY,
                        SOURCE_UNATTENDED));
                }
            }
        }
        agent.getDelegate().replacePermissionContext(userId, sessionId, builder.build());
    }

    /**
     * 把「用户已记住的工具」并入指定槽位的权限上下文。
     *
     * <p><b>为什么必须以管理端档位基线重建、而不是在现有上下文上叠加</b>：① 装配实例是
     * 智能体级、跨用户共享的，用户级信任只能落在 {@code (userId, sessionId)} 槽位；② 框架在每轮
     * call 开始时都会从状态存储重载 {@code AgentState} 并用其中的权限上下文重建权限引擎，
     * 因此恢复流里 {@code ConfirmResult} 附带的规则只对当轮有效；③ 「撤销记住」（删信任行）后必须
     * 让 ask 规则自动恢复——按基线重建即可自然做到（同名 ask 规则在已记住时不再生成，
     * 框架判定顺序为 deny &gt; ask &gt; 工具自检 &gt; allow，留着 ask 会让「记住」失效）。</p>
     *
     * <p>幂等：当前槽位已应用的 remember 规则集合与传入集合一致时直接返回，避免每轮重建并持久化会话状态。</p>
     *
     * <p><b>基线与槽位必须按链路给出</b>：入口智能体用「智能体 × 场景包」常规装配基线 +
     * 用户会话槽位；专家执行链路用<b>专家执行装配</b>基线（{@code permissionContextOfExpert}）+
     * {@code routeRunId#expertAgentId} 槽位——用错基线会让规则集合与实际装配的专家实例不一致。</p>
     *
     * @param agent     已装配的智能体
     * @param base      该链路对应的管理端档位权限上下文
     * @param trusted   该用户在该智能体上已记住的工具编码集合
     * @param userId    用户标识（字符串形式，与 AgentScope 槽位键一致）
     * @param sessionId 会话槽位标识（字符串形式）
     */
    static void applyUserToolTrust(HarnessAgent agent, PermissionContextState base, Set<String> trusted,
                                   String userId, String sessionId) {
        PermissionContextState current = agent.getDelegate().getAgentState(userId, sessionId).getPermissionContext();
        if (rememberAllowToolCodes(current).equals(trusted) && coversAllowRules(current, base)) {
            // 已一致：无需重建（绝大多数轮次走这里）
            return;
        }
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(base.getMode());
        // 工作目录属运行期状态（可能被计划模式等追加），从当前槽位继承
        current.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        base.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        base.getAllowRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addAllowRule(tool, rule)));
        base.getDenyRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addDenyRule(tool, rule)));
        base.getAskRules().forEach((tool, rules) -> {
            if (!trusted.contains(tool)) {
                rules.forEach(rule -> builder.addAskRule(tool, rule));
            }
        });
        for (String tool : trusted) {
            builder.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW,
                AiPermissionDecisionLog.SOURCE_REMEMBER));
        }
        agent.getDelegate().replacePermissionContext(userId, sessionId, builder.build());
    }

    /**
     * 判断当前槽位是否已覆盖基线的全部 allow 规则。
     *
     * <p>会话槽位会持久化权限上下文；装配基线新增放行（例如平台模式新增
     * {@code memory_search} / {@code remember_user_preference}）后，老会话仍保留旧上下文。
     * 只比较「记住信任集合」会把这种情况误判为无需重建，导致工具一直 DENIED。</p>
     *
     * @param current 当前槽位上下文
     * @param base    当前装配基线
     * @return 基线允许的每个工具在槽位里都有 allow 规则时返回 true
     */
    private static boolean coversAllowRules(PermissionContextState current, PermissionContextState base) {
        if (base == null) {
            return true;
        }
        if (current == null) {
            return false;
        }
        return base.getAllowRules().keySet().stream()
            .allMatch(tool -> current.getAllowRules().containsKey(tool));
    }

    /**
     * 取权限上下文里「记住来源（remember）」的 allow 工具集合。
     *
     * @param context 权限上下文
     * @return 已由「记住该工具」放行的工具编码集合
     */
    static Set<String> rememberAllowToolCodes(PermissionContextState context) {
        Set<String> codes = new LinkedHashSet<>();
        if (context == null) {
            return codes;
        }
        context.getAllowRules().forEach((tool, rules) -> {
            if (rules != null && rules.stream()
                .anyMatch(rule -> AiPermissionDecisionLog.SOURCE_REMEMBER.equals(rule.source()))) {
                codes.add(tool);
            }
        });
        return codes;
    }
}
