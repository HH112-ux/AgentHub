package org.dromara.ai.contract;

// 权限标识（sys_menu.perms）的唯一常量源，取值与 script/sql/{mysql,postgres}/agenthub-ai-menu.sql 逐字对齐；
// 由 e2e/http/L00-contract-consistency.mjs 校验「注解 ⊆ 菜单 = 本类」，改动须按设计稿 §6 走契约变更流程。

public final class AiPermissionKeys {

    // ---- ai:agent ----
    /** 菜单「智能体新增」 */
    public static final String AI_AGENT_ADD = "ai:agent:add";
    /** 菜单「智能体修改」 */
    public static final String AI_AGENT_EDIT = "ai:agent:edit";
    /** 菜单「小Z配置修改」 */
    public static final String AI_AGENT_ENTRY_EDIT = "ai:agent:entry:edit";
    /** 菜单「小Z配置」 */
    public static final String AI_AGENT_ENTRY_QUERY = "ai:agent:entry:query";
    /** 菜单「专家配置」 */
    public static final String AI_AGENT_LIST = "ai:agent:list";
    /** 菜单「智能体查询」 */
    public static final String AI_AGENT_QUERY = "ai:agent:query";
    /** 菜单「智能体删除」 */
    public static final String AI_AGENT_REMOVE = "ai:agent:remove";

    // ---- ai:audit ----
    /** 菜单「会话与消息审计」 */
    public static final String AI_AUDIT_MESSAGE = "ai:audit:message";
    /** 菜单「消息导出」 */
    public static final String AI_AUDIT_MESSAGE_EXPORT = "ai:audit:message:export";
    /** 菜单「审计数据清理」 */
    public static final String AI_AUDIT_RETENTION_CLEANUP = "ai:audit:retention:cleanup";
    /** 菜单「审计保留修改」 */
    public static final String AI_AUDIT_RETENTION_EDIT = "ai:audit:retention:edit";
    /** 菜单「审计保留不限」：把保留天数设为 0（永不清理）的额外权限（§12.4） */
    public static final String AI_AUDIT_RETENTION_UNLIMITED = "ai:audit:retention:unlimited";
    /** 菜单「编排运行」 */
    public static final String AI_AUDIT_ROUTE = "ai:audit:route";
    /** 菜单「编排运行详情」 */
    public static final String AI_AUDIT_ROUTE_QUERY = "ai:audit:route:query";
    /** 菜单「工具调用明细」 */
    public static final String AI_AUDIT_TOOLCALL = "ai:audit:toolcall";
    /** 菜单「链路追踪」 */
    public static final String AI_AUDIT_TRACE = "ai:audit:trace";
    /** 菜单「链路查询」 */
    public static final String AI_AUDIT_TRACE_QUERY = "ai:audit:trace:query";

    // ---- ai:channel ----
    /** 菜单「渠道新增」 */
    public static final String AI_CHANNEL_ADD = "ai:channel:add";
    /** 菜单「渠道修改」 */
    public static final String AI_CHANNEL_EDIT = "ai:channel:edit";
    /** 菜单「渠道接入」 */
    public static final String AI_CHANNEL_LIST = "ai:channel:list";
    /** 菜单「渠道查询」 */
    public static final String AI_CHANNEL_QUERY = "ai:channel:query";
    /** 菜单「渠道删除」 */
    public static final String AI_CHANNEL_REMOVE = "ai:channel:remove";

    // ---- ai:chat ----
    /** 菜单「暂存附件回收」 */
    public static final String AI_CHAT_ATTACHMENT_RECLAIM = "ai:chat:attachment:reclaim";
    /** 菜单「对话偏好修改」 */
    public static final String AI_CHAT_PREF_EDIT = "ai:chat:pref:edit";
    /** 菜单「对话偏好」 */
    public static final String AI_CHAT_PREF_QUERY = "ai:chat:pref:query";
    /** 菜单「任务工作台」 */
    public static final String AI_CHAT_SEND = "ai:chat:send";

    // ---- ai:digest ----
    /** 菜单「平台运行日报」 */
    public static final String AI_DIGEST_LIST = "ai:digest:list";
    /** 菜单「平台运行日报详情」 */
    public static final String AI_DIGEST_QUERY = "ai:digest:query";

    // ---- ai:health ----
    /** 菜单「健康自检」 */
    public static final String AI_HEALTH = "ai:health";
    /** 菜单「结构化决策探测」 */
    public static final String AI_HEALTH_PROBE = "ai:health:probe";
    /** 菜单「健康自检查询」 */
    public static final String AI_HEALTH_QUERY = "ai:health:query";

    // ---- ai:kb ----
    /** 菜单「知识库新增」 */
    public static final String AI_KB_ADD = "ai:kb:add";
    /** 菜单「文档与切片」 */
    public static final String AI_KB_DOC = "ai:kb:doc";
    /** 菜单「文档解析」 */
    public static final String AI_KB_DOC_PARSE = "ai:kb:doc:parse";
    /** 菜单「文档预览」 */
    public static final String AI_KB_DOC_PREVIEW = "ai:kb:doc:preview";
    /** 菜单「文档删除」 */
    public static final String AI_KB_DOC_REMOVE = "ai:kb:doc:remove";
    /** 菜单「文档上传」 */
    public static final String AI_KB_DOC_UPLOAD = "ai:kb:doc:upload";
    /** 菜单「文档向量化」 */
    public static final String AI_KB_DOC_VECTOR = "ai:kb:doc:vector";
    /** 菜单「知识库修改」 */
    public static final String AI_KB_EDIT = "ai:kb:edit";
    /** 菜单「知识库管理」 */
    public static final String AI_KB_LIST = "ai:kb:list";
    /** 菜单「知识库查询」 */
    public static final String AI_KB_QUERY = "ai:kb:query";
    /** 菜单「知识库删除」 */
    public static final String AI_KB_REMOVE = "ai:kb:remove";
    /** 菜单「检索测试」 */
    public static final String AI_KB_RETRIEVAL = "ai:kb:retrieval";

    // ---- ai:mcp ----
    /** 菜单「MCP 客户端」 */
    public static final String AI_MCP_CLIENT = "ai:mcp:client";
    /** 菜单「MCP 客户端新增」 */
    public static final String AI_MCP_CLIENT_ADD = "ai:mcp:client:add";
    /** 菜单「MCP 客户端修改」 */
    public static final String AI_MCP_CLIENT_EDIT = "ai:mcp:client:edit";
    /** 菜单「MCP 客户端查询」 */
    public static final String AI_MCP_CLIENT_QUERY = "ai:mcp:client:query";
    /** 菜单「MCP 客户端删除」 */
    public static final String AI_MCP_CLIENT_REMOVE = "ai:mcp:client:remove";
    /** 菜单「MCP 服务端」 */
    public static final String AI_MCP_SERVER = "ai:mcp:server";
    /** 菜单「MCP 服务端修改」 */
    public static final String AI_MCP_SERVER_EDIT = "ai:mcp:server:edit";
    /** 菜单「MCP 服务端查询」 */
    public static final String AI_MCP_SERVER_QUERY = "ai:mcp:server:query";

    // ---- ai:memory ----
    /** 菜单「记忆归档」 */
    public static final String AI_MEMORY_ARCHIVE = "ai:memory:archive";
    /** 菜单「记忆清理」 */
    public static final String AI_MEMORY_CLEANUP = "ai:memory:cleanup";
    /** 菜单「触发记忆合并」 */
    public static final String AI_MEMORY_CONSOLIDATE = "ai:memory:consolidate";
    /** 菜单「记忆修改」 */
    public static final String AI_MEMORY_EDIT = "ai:memory:edit";
    /** 菜单「长期记忆」 */
    public static final String AI_MEMORY_LIST = "ai:memory:list";
    /** 菜单「记忆查询」 */
    public static final String AI_MEMORY_QUERY = "ai:memory:query";
    /** 菜单「记忆删除」 */
    public static final String AI_MEMORY_REMOVE = "ai:memory:remove";

    // ---- ai:model ----
    /** 菜单「模型配置新增」 */
    public static final String AI_MODEL_ADD = "ai:model:add";
    /** 菜单「模型配置复制」 */
    public static final String AI_MODEL_COPY = "ai:model:copy";
    /** 菜单「模型配置修改」 */
    public static final String AI_MODEL_EDIT = "ai:model:edit";
    /** 菜单「模型配置」 */
    public static final String AI_MODEL_LIST = "ai:model:list";
    /** 菜单「模型配置查询」 */
    public static final String AI_MODEL_QUERY = "ai:model:query";
    /** 菜单「模型配置删除」 */
    public static final String AI_MODEL_REMOVE = "ai:model:remove";
    /** 菜单「模型连通性测试」 */
    public static final String AI_MODEL_TEST = "ai:model:test";

    // ---- ai:pack ----
    /** 菜单「场景包新增」 */
    public static final String AI_PACK_ADD = "ai:pack:add";
    /** 菜单「场景包修改」 */
    public static final String AI_PACK_EDIT = "ai:pack:edit";
    /** 菜单「场景包」 */
    public static final String AI_PACK_LIST = "ai:pack:list";
    /** 菜单「场景包查询」 */
    public static final String AI_PACK_QUERY = "ai:pack:query";
    /** 菜单「场景包删除」 */
    public static final String AI_PACK_REMOVE = "ai:pack:remove";

    // ---- ai:perm ----
    /** 菜单「权限决策日志」 */
    public static final String AI_PERM_LIST = "ai:perm:list";
    /** 菜单「权限模式切换」 */
    public static final String AI_PERM_MODE = "ai:perm:mode";
    /** 菜单「决策查询」 */
    public static final String AI_PERM_QUERY = "ai:perm:query";

    // ---- ai:runtime ----
    /** 菜单「上下文与压缩策略」 */
    public static final String AI_RUNTIME_COMPACTION = "ai:runtime:compaction";
    /** 菜单「压缩策略修改」 */
    public static final String AI_RUNTIME_COMPACTION_EDIT = "ai:runtime:compaction:edit";
    /** 菜单「压缩策略查询」 */
    public static final String AI_RUNTIME_COMPACTION_QUERY = "ai:runtime:compaction:query";
    /** 菜单「结构化决策路由」 */
    public static final String AI_RUNTIME_JEV = "ai:runtime:jev";
    /** 菜单「结构化决策修改」 */
    public static final String AI_RUNTIME_JEV_EDIT = "ai:runtime:jev:edit";
    /** 菜单「结构化决策查询」 */
    public static final String AI_RUNTIME_JEV_QUERY = "ai:runtime:jev:query";
    /** 菜单「计划模式与任务清单」 */
    public static final String AI_RUNTIME_PLAN = "ai:runtime:plan";
    /** 菜单「计划模式修改」 */
    public static final String AI_RUNTIME_PLAN_EDIT = "ai:runtime:plan:edit";
    /** 菜单「计划模式查询」 */
    public static final String AI_RUNTIME_PLAN_QUERY = "ai:runtime:plan:query";
    /** 菜单「编排预算」 */
    public static final String AI_RUNTIME_ROUTE_BUDGET = "ai:runtime:route-budget";
    /** 菜单「编排预算修改」 */
    public static final String AI_RUNTIME_ROUTE_BUDGET_EDIT = "ai:runtime:route-budget:edit";
    /** 菜单「编排预算查询」 */
    public static final String AI_RUNTIME_ROUTE_BUDGET_QUERY = "ai:runtime:route-budget:query";
    /** 菜单「安全合规配置」 */
    public static final String AI_RUNTIME_SECURITY = "ai:runtime:security";
    /** 菜单「安全合规修改」 */
    public static final String AI_RUNTIME_SECURITY_EDIT = "ai:runtime:security:edit";
    /** 菜单「安全合规查询」 */
    public static final String AI_RUNTIME_SECURITY_QUERY = "ai:runtime:security:query";
    /** 菜单「无人值守白名单修改」 */
    public static final String AI_RUNTIME_UNATTENDED_EDIT = "ai:runtime:unattended:edit";

    // ---- ai:safety ----
    /** 菜单「安全事件」 */
    public static final String AI_SAFETY_EVENT_LIST = "ai:safety:event:list";
    /** 菜单「安全事件详情」 */
    public static final String AI_SAFETY_EVENT_QUERY = "ai:safety:event:query";
    /** 菜单「敏感词新增」 */
    public static final String AI_SAFETY_WORD_ADD = "ai:safety:word:add";
    /** 菜单「敏感词修改」 */
    public static final String AI_SAFETY_WORD_EDIT = "ai:safety:word:edit";
    /** 菜单「敏感词库」 */
    public static final String AI_SAFETY_WORD_LIST = "ai:safety:word:list";
    /** 菜单「敏感词查询」 */
    public static final String AI_SAFETY_WORD_QUERY = "ai:safety:word:query";
    /** 菜单「敏感词删除」 */
    public static final String AI_SAFETY_WORD_REMOVE = "ai:safety:word:remove";

    // ---- ai:session ----
    /** 菜单「会话终止」 */
    public static final String AI_SESSION_EDIT = "ai:session:edit";
    /** 菜单「会话导出」 */
    public static final String AI_SESSION_EXPORT = "ai:session:export";
    /** 菜单「任务管理」 */
    public static final String AI_SESSION_LIST = "ai:session:list";
    /** 菜单「会话明细查看」 */
    public static final String AI_SESSION_QUERY = "ai:session:query";
    /** 菜单「会话删除」 */
    public static final String AI_SESSION_REMOVE = "ai:session:remove";
    /** 菜单「会话状态回收」 */
    public static final String AI_SESSION_STATE_REAP = "ai:session:state:reap";

    // ---- ai:skill ----
    /** 菜单「技能新增」 */
    public static final String AI_SKILL_ADD = "ai:skill:add";
    /** 菜单「技能修改」 */
    public static final String AI_SKILL_EDIT = "ai:skill:edit";
    /** 菜单「技能仓库」 */
    public static final String AI_SKILL_LIST = "ai:skill:list";
    /** 菜单「技能查询」 */
    public static final String AI_SKILL_QUERY = "ai:skill:query";
    /** 菜单「技能删除」 */
    public static final String AI_SKILL_REMOVE = "ai:skill:remove";

    // ---- ai:subagent ----
    /** 菜单「子智能体新增」 */
    public static final String AI_SUBAGENT_ADD = "ai:subagent:add";
    /** 菜单「子智能体修改」 */
    public static final String AI_SUBAGENT_EDIT = "ai:subagent:edit";
    /** 菜单「子智能体」 */
    public static final String AI_SUBAGENT_LIST = "ai:subagent:list";
    /** 菜单「子智能体查询」 */
    public static final String AI_SUBAGENT_QUERY = "ai:subagent:query";
    /** 菜单「子智能体删除」 */
    public static final String AI_SUBAGENT_REMOVE = "ai:subagent:remove";

    // ---- ai:task ----
    /** 菜单「任务新增」 */
    public static final String AI_TASK_ADD = "ai:task:add";
    /** 菜单「任务修改」 */
    public static final String AI_TASK_EDIT = "ai:task:edit";
    /** 菜单「定时任务」 */
    public static final String AI_TASK_LIST = "ai:task:list";
    /** 菜单「任务查询」 */
    public static final String AI_TASK_QUERY = "ai:task:query";
    /** 菜单「任务删除」 */
    public static final String AI_TASK_REMOVE = "ai:task:remove";
    /** 菜单「立即执行」 */
    public static final String AI_TASK_RUN = "ai:task:run";

    // ---- ai:tool ----
    /** 菜单「工具新增」 */
    public static final String AI_TOOL_ADD = "ai:tool:add";
    /** 菜单「工具修改」 */
    public static final String AI_TOOL_EDIT = "ai:tool:edit";
    /** 菜单「业务工具注册」 */
    public static final String AI_TOOL_LIST = "ai:tool:list";
    /** 菜单「工具删除」 */
    public static final String AI_TOOL_REMOVE = "ai:tool:remove";
    /** 菜单「标签新增」 */
    public static final String AI_TOOL_TAG_ADD = "ai:tool:tag:add";
    /** 菜单「标签修改」 */
    public static final String AI_TOOL_TAG_EDIT = "ai:tool:tag:edit";
    /** 菜单「标签管理」 */
    public static final String AI_TOOL_TAG_QUERY = "ai:tool:tag:query";
    /** 菜单「标签删除」 */
    public static final String AI_TOOL_TAG_REMOVE = "ai:tool:tag:remove";

    // ---- ai:usage ----
    /** 菜单「用量导出」 */
    public static final String AI_USAGE_EXPORT = "ai:usage:export";
    /** 菜单「用量与配额」 */
    public static final String AI_USAGE_LIST = "ai:usage:list";
    /** 菜单「用量查询」 */
    public static final String AI_USAGE_QUERY = "ai:usage:query";

    // ---- ai:workspace ----
    /** 菜单「工作区修改」 */
    public static final String AI_WORKSPACE_EDIT = "ai:workspace:edit";
    /** 菜单「工作区文件」 */
    public static final String AI_WORKSPACE_LIST = "ai:workspace:list";
    /** 菜单「工作区查询」 */
    public static final String AI_WORKSPACE_QUERY = "ai:workspace:query";

    private AiPermissionKeys() {}
}
