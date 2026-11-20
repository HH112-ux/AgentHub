package org.dromara.ai.core.mcp;

import org.dromara.common.core.utils.StringUtils;

/**
 * MCP 出口工具名的命名空间。
 *
 * <p><b>为什么需要</b>：出口工具此前直接用 {@code ai_tool.tool_code} 作为 MCP 工具名，与两类名字共享同一命名空间：
 * ① 平台内置工具（同一编码既是业务工具、又被经 {@code /mcp} 暴露给外部，客户端再接入时工具箱里出现两个同名工具）；
 * ② 其他模块以 Spring Bean 形式向同一个 {@code McpStatelessSyncServer} 注册的工具（如历史上的演示工具）——
 * 同步清单时按名删除 / 覆盖，会把别人的工具顶掉。</p>
 *
 * <p>故出口一律以 {@value #PREFIX} 为前缀暴露：{@code agenthub_<tool_code>}。前缀只用字母与下划线，
 * 满足 MCP / LLM function name 的合法字符集，且不含 AgentScope 的保留前缀 {@code __}。</p>
 *
 * <p><b>调用侧影响</b>：外部调用方（含平台自演示客户端）的白名单 / 调用名须使用带前缀的导出名。</p>
 *
 * @author AgentHub
 */
public final class McpExportNames {

    /**
     * 出口工具名前缀（命名空间）
     */
    public static final String PREFIX = "agenthub_";

    private McpExportNames() {
    }

    /**
     * 业务工具编码转出口工具名。
     *
     * @param toolCode 业务工具编码
     * @return 带命名空间的出口工具名；编码为空时返回 null
     */
    public static String of(String toolCode) {
        String code = StringUtils.trim(toolCode);
        return StringUtils.isBlank(code) ? null : PREFIX + code;
    }

}
