package org.dromara.ai.contract;

// 工具实现标识（ai_tool.impl_name）的唯一常量源：取值与 AiToolFactory 的 IMPL_* 常量、
// 双方言 agenthub-ai-seed.sql 的 ai_tool 预置行三方一致，由 e2e/http/L00-contract-consistency.mjs 校验。
// impl_name 是装配期查找键，改名即断路由，改动须按设计稿 §6 走契约变更流程。

public final class AiToolImpl {
    /** IMPL_AI_USAGE_BY_AGENT */
    public static final String IMPL_AI_USAGE_BY_AGENT = "ai_usage_by_agent";
    /** IMPL_CREATE_DEPT */
    public static final String IMPL_CREATE_DEPT = "create_dept";
    /** IMPL_CREATE_NOTICE */
    public static final String IMPL_CREATE_NOTICE = "create_notice";
    /** IMPL_CREATE_USER */
    public static final String IMPL_CREATE_USER = "create_user";
    /** IMPL_DELETE_DEPT */
    public static final String IMPL_DELETE_DEPT = "delete_dept";
    /** IMPL_DELETE_NOTICE */
    public static final String IMPL_DELETE_NOTICE = "delete_notice";
    /** IMPL_DELETE_USER */
    public static final String IMPL_DELETE_USER = "delete_user";
    /** IMPL_GET_CURRENT_DEPT */
    public static final String IMPL_GET_CURRENT_DEPT = "get_current_dept";
    /** IMPL_GET_CURRENT_PERMISSIONS */
    public static final String IMPL_GET_CURRENT_PERMISSIONS = "get_current_permissions";
    /** IMPL_GET_CURRENT_TIME */
    public static final String IMPL_GET_CURRENT_TIME = "get_current_time";
    /** IMPL_GET_CURRENT_USER */
    public static final String IMPL_GET_CURRENT_USER = "get_current_user";
    /** IMPL_GET_DEPT */
    public static final String IMPL_GET_DEPT = "get_dept";
    /** IMPL_GET_MENU */
    public static final String IMPL_GET_MENU = "get_menu";
    /** IMPL_GET_NOTICE */
    public static final String IMPL_GET_NOTICE = "get_notice";
    /** IMPL_GET_ROLE */
    public static final String IMPL_GET_ROLE = "get_role";
    /** IMPL_GET_ROLE_PERMISSIONS */
    public static final String IMPL_GET_ROLE_PERMISSIONS = "get_role_permissions";
    /** IMPL_GET_SYSTEM_INFO */
    public static final String IMPL_GET_SYSTEM_INFO = "get_system_info";
    /** IMPL_GET_USER */
    public static final String IMPL_GET_USER = "get_user";
    /** IMPL_KNOWLEDGE_SEARCH */
    public static final String IMPL_KNOWLEDGE_SEARCH = "knowledge_search";
    /** IMPL_PLATFORM_DAILY_DIGEST */
    public static final String IMPL_PLATFORM_DAILY_DIGEST = "platform_daily_digest";
    /** IMPL_PLATFORM_DAILY_DRILL */
    public static final String IMPL_PLATFORM_DAILY_DRILL = "platform_daily_drill";
    /** IMPL_SEARCH_DEPT */
    public static final String IMPL_SEARCH_DEPT = "search_dept";
    /** IMPL_SEARCH_MENUS */
    public static final String IMPL_SEARCH_MENUS = "search_menus";
    /** IMPL_SEARCH_NOTICE */
    public static final String IMPL_SEARCH_NOTICE = "search_notice";
    /** IMPL_SEARCH_ROLES */
    public static final String IMPL_SEARCH_ROLES = "search_roles";
    /** IMPL_SEARCH_USER */
    public static final String IMPL_SEARCH_USER = "search_user";
    /** IMPL_UPDATE_DEPT */
    public static final String IMPL_UPDATE_DEPT = "update_dept";
    /** IMPL_UPDATE_NOTICE */
    public static final String IMPL_UPDATE_NOTICE = "update_notice";
    /** IMPL_UPDATE_USER */
    public static final String IMPL_UPDATE_USER = "update_user";
    /** IMPL_WRITE_DAILY_DIGEST */
    public static final String IMPL_WRITE_DAILY_DIGEST = "write_daily_digest";

    private AiToolImpl() {}
}
