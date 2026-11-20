package org.dromara.ai.contract;

// 工具实现标识（ai_tool.impl_name）的唯一常量源，取值由 script/sql 双方言脚本冻结；改动须按设计稿 §6 走契约变更流程并同步三条任务线。

public final class AiToolImpl {
    /** 当前时间：ai_tool.tool_code=get_current_time，类型 READ */
    public static final String GET_CURRENT_TIME = "GET_CURRENT_TIME";
    /** 当前用户：ai_tool.tool_code=get_current_user，类型 READ */
    public static final String GET_CURRENT_USER = "GET_CURRENT_USER";
    /** 当前部门：ai_tool.tool_code=get_current_dept，类型 READ */
    public static final String GET_CURRENT_DEPT = "GET_CURRENT_DEPT";
    /** 当前权限：ai_tool.tool_code=get_current_permission，类型 READ */
    public static final String GET_CURRENT_PERMISSION = "GET_CURRENT_PERMISSION";
    /** 路由决策：ai_tool.tool_code=route_decision，类型 READ */
    public static final String ROUTE_DECISION = "ROUTE_DECISION";
    /** 记忆检索：ai_tool.tool_code=memory_search，类型 READ */
    public static final String MEMORY_SEARCH = "MEMORY_SEARCH";
    /** 按路径加载技能：ai_tool.tool_code=load_skill_through_path，类型 READ */
    public static final String LOAD_SKILL_THROUGH_PATH = "LOAD_SKILL_THROUGH_PATH";
    /** 读取技能：ai_tool.tool_code=read_skill，类型 READ */
    public static final String READ_SKILL = "READ_SKILL";
    /** 任务清单写入：ai_tool.tool_code=todo_write，类型 WRITE */
    public static final String TODO_WRITE = "TODO_WRITE";
    /** 记忆写入：ai_tool.tool_code=memory_save，类型 WRITE */
    public static final String MEMORY_SAVE = "MEMORY_SAVE";
    /** 会话检索：ai_tool.tool_code=session_search，类型 READ */
    public static final String SESSION_SEARCH = "SESSION_SEARCH";

    private AiToolImpl() {}
}
