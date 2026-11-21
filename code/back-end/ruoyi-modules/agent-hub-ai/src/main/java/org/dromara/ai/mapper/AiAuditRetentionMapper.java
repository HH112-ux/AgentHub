package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计与运行数据保留清理用 Mapper（计数 + 分批取 id + 按 id 物理删除）。
 *
 * <p>这些表是明细 / 追加表，按时间清理必须物理删除：{@code ai_route_run} 实体带
 * {@code @TableLogic}，走 MyBatis-Plus 的 {@code delete} 会退化为逻辑删除、无法真实回收空间，
 * 故此处用显式 SQL 直删。<b>清理范围不含会话正文 {@code ai_message}</b>（会话内容属业务数据，
 * 仍由用户在会话页自行删除）。</p>
 *
 * <p><b>为什么是「先取 id 再按 id 删」而不是一条 {@code delete … limit n}</b>：分批删除要同时跑在
 * PostgreSQL 与 MySQL 上（同一份 SQL，两套栈共用本模块），而 {@code delete} 的 {@code limit} 只有
 * MySQL 支持；换成 {@code where id in (select … limit n)} 又会踩 MySQL 的「不能在子查询里指定删除目标表」
 * （同表派生表还会被 derived_merge 优化器合并而重新触发该错误）。取 id 与删 id 两条语句在两种方言里
 * 语义完全一致，单事务行数因此不会超过一批（§12.4「单事务 ≤5000 行」），也不需要跨批长事务：级联删
 * 按「同批先删子、再删父」提交，父行还在时子行不会先走，反过来也不会留下孤立引用。
 * 同理，级联删除的子表条件由 Java 侧传入父 id 列表，不在 {@code delete} 里写子表子查询。</p>
 *
 * <p>{@code select …Ids} 一律不带 {@code order by}：走 {@code create_time} 索引做范围扫描、取满一批就停，
 * 排序对删除没有意义，加了反而要扫完整段再排。</p>
 *
 * @author AgentHub
 */
public interface AiAuditRetentionMapper {

    // ---------------------------------------------------------------- ai_trace（父链路自引用，见 §12.7 I-7）

    /**
     * 统计超期的链路追踪行数：自身超期，或父链路已超期（父链路先走、子链路留下就是孤儿明细）
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("""
        select count(*) from ai_trace
         where create_time < #{cutoff}
            or parent_trace_id in (select id from ai_trace where create_time < #{cutoff})""")
    long countTrace(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一批超期的链路追踪主键
     *
     * @param cutoff 保留截止时间
     * @param batch  单批行数上限
     * @return 主键列表
     */
    @Select("select id from ai_trace where create_time < #{cutoff} limit #{batch}")
    List<Long> selectTraceIds(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /**
     * 按主键物理删除链路追踪
     *
     * @param ids 主键列表（非空）
     * @return 删除行数
     */
    @Delete("<script>delete from ai_trace where id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deleteTraceByIds(@Param("ids") List<Long> ids);

    /**
     * 删除父链路已超期的子链路（与 {@link #deleteTraceByIds} 同批父 id，先子后父）
     *
     * @param parentIds 本批被删父链路主键
     * @return 删除行数
     */
    @Delete("<script>delete from ai_trace where parent_trace_id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deleteTraceChildrenByIds(@Param("ids") List<Long> parentIds);

    // ---------------------------------------------------------------- ai_tool_call_log

    /**
     * 统计超期的工具调用明细行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_tool_call_log where create_time < #{cutoff}")
    long countToolCallLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一批超期的工具调用明细主键
     *
     * @param cutoff 保留截止时间
     * @param batch  单批行数上限
     * @return 主键列表
     */
    @Select("select id from ai_tool_call_log where create_time < #{cutoff} limit #{batch}")
    List<Long> selectToolCallLogIds(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /**
     * 按主键物理删除工具调用明细
     *
     * @param ids 主键列表（非空）
     * @return 删除行数
     */
    @Delete("<script>delete from ai_tool_call_log where id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deleteToolCallLogByIds(@Param("ids") List<Long> ids);

    // ---------------------------------------------------------------- ai_permission_decision_log

    /**
     * 统计超期的权限决策日志行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_permission_decision_log where create_time < #{cutoff}")
    long countPermissionDecisionLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一批超期的权限决策日志主键
     *
     * @param cutoff 保留截止时间
     * @param batch  单批行数上限
     * @return 主键列表
     */
    @Select("select id from ai_permission_decision_log where create_time < #{cutoff} limit #{batch}")
    List<Long> selectPermissionDecisionLogIds(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /**
     * 按主键物理删除权限决策日志
     *
     * @param ids 主键列表（非空）
     * @return 删除行数
     */
    @Delete("<script>delete from ai_permission_decision_log where id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deletePermissionDecisionLogByIds(@Param("ids") List<Long> ids);

    // ---------------------------------------------------------------- ai_agent_task_run

    /**
     * 统计超期的定时任务执行记录行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_agent_task_run where create_time < #{cutoff}")
    long countAgentTaskRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一批超期的定时任务执行记录主键
     *
     * @param cutoff 保留截止时间
     * @param batch  单批行数上限
     * @return 主键列表
     */
    @Select("select id from ai_agent_task_run where create_time < #{cutoff} limit #{batch}")
    List<Long> selectAgentTaskRunIds(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /**
     * 按主键物理删除定时任务执行记录
     *
     * @param ids 主键列表（非空）
     * @return 删除行数
     */
    @Delete("<script>delete from ai_agent_task_run where id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deleteAgentTaskRunByIds(@Param("ids") List<Long> ids);

    // ---------------------------------------------------------------- ai_route_run 与子表 ai_route_expert_call

    /**
     * 统计超期的编排运行行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_route_run where create_time < #{cutoff}")
    long countRouteRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一批超期的编排运行主键
     *
     * @param cutoff 保留截止时间
     * @param batch  单批行数上限
     * @return 主键列表
     */
    @Select("select id from ai_route_run where create_time < #{cutoff} limit #{batch}")
    List<Long> selectRouteRunIds(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /**
     * 按主键物理删除编排运行（跳过 {@code @TableLogic}，直达物理删除）
     *
     * @param ids 主键列表（非空）
     * @return 删除行数
     */
    @Delete("<script>delete from ai_route_run where id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deleteRouteRunByIds(@Param("ids") List<Long> ids);

    /**
     * 统计超期的专家调用明细行数：自身超期，或所属编排运行已超期（§12.7 I-7「子记录按父记录联动删除」）
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("""
        select count(*) from ai_route_expert_call
         where create_time < #{cutoff}
            or route_run_id in (select route_run_id from ai_route_run where create_time < #{cutoff})""")
    long countRouteExpertCall(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一批超期的专家调用明细主键（含所属编排运行已超期但自身未超期的行）
     *
     * @param cutoff 保留截止时间
     * @param batch  单批行数上限
     * @return 主键列表
     */
    @Select("""
        select id from ai_route_expert_call
         where create_time < #{cutoff}
            or route_run_id in (select route_run_id from ai_route_run where create_time < #{cutoff})
         limit #{batch}""")
    List<Long> selectRouteExpertCallIds(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /**
     * 按主键物理删除专家调用明细
     *
     * @param ids 主键列表（非空）
     * @return 删除行数
     */
    @Delete("<script>delete from ai_route_expert_call where id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deleteRouteExpertCallByIds(@Param("ids") List<Long> ids);

    /**
     * 删除指定编排运行的全部专家调用明细（与父表同批执行，父行删完不留孤儿）
     *
     * @param runIds 本批被删的编排运行主键
     * @return 删除行数
     */
    @Delete("<script>delete from ai_route_expert_call where route_run_id in <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></script>")
    int deleteRouteExpertCallByRunIds(@Param("ids") List<Long> runIds);

}
