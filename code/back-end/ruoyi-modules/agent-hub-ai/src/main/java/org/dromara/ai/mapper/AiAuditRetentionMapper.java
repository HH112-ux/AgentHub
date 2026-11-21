package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计与运行数据保留清理用 Mapper（计数 + 分批物理删除）。
 *
 * <p>这些表是明细 / 追加表，按时间清理必须**物理删除**：{@code ai_route_run} 实体带
 * {@code @TableLogic}，走 MyBatis-Plus 的 {@code delete} 会退化为逻辑删除、无法真实回收空间，
 * 故此处用显式 SQL 直删。**清理范围不含会话正文 {@code ai_message}**（会话内容属业务数据，
 * 仍由用户在会话页自行删除）。</p>
 *
 * <p><b>分批（§12.4 / §12.7 I-6）</b>：一条 {@code delete … where create_time < cutoff} 会把整段
 * 超期数据压进一个事务，百万行级别就是长锁与 WAL 洪峰。改为「先按 {@code create_time} 索引取一片
 * 主键、再按主键删这一片」，单条 DELETE 影响行数由调用方给定的批大小封顶。</p>
 *
 * <p><b>引用一致（§12.7 I-7）</b>：子表 {@code ai_route_expert_call} 与 {@code ai_trace} 的父子链路
 * 都按**父记录的 {@code create_time}** 判定，并额外认领「父记录已不存在」的孤儿子记录，因此清理结果
 * 与逐表执行顺序无关（父先删、子后删时，子会以孤儿身份被同一条谓词捞走）。</p>
 *
 * <p><b>越权约束（§12.4）</b>：本 Mapper 的取数谓词只允许出现时间边界与父子引用，禁止按用户、
 * 会话或关键词选择性删除，由 {@code scripts/checks/audit-retention-guards.mjs} 静态守住。</p>
 *
 * <p>删除按批各自独立执行、不加跨表事务：单批 DELETE 自身原子，避免跨表长事务长时间持锁。</p>
 *
 * <p>分片排序取主键而非 {@code create_time}：主键是雪花 ID，本身随时间单调，排序等价于「先清最旧的」，
 * 又不会像时间列那样因同秒并列而让某一取数片跨不出边界。</p>
 *
 * @author AgentHub
 */
public interface AiAuditRetentionMapper {

    /**
     * 统计超期的链路追踪行数（含父链路已超期或已不存在的子链路）
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("""
        select count(*) from ai_trace t
        left join ai_trace p on p.id = t.parent_trace_id
        where t.create_time < #{cutoff}
           or (t.parent_trace_id is not null and (p.id is null or p.create_time < #{cutoff}))
        """)
    long countTrace(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一片待清理的链路追踪主键（超期链路与父链路超期 / 丢失的子链路，按主键升序）
     *
     * @param cutoff 保留截止时间
     * @param limit  单片最大行数
     * @return 主键列表
     */
    @Select("""
        select t.id from ai_trace t
        left join ai_trace p on p.id = t.parent_trace_id
        where t.create_time < #{cutoff}
           or (t.parent_trace_id is not null and (p.id is null or p.create_time < #{cutoff}))
        order by t.id asc limit #{limit}
        """)
    List<Long> selectTraceIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 按主键批删链路追踪（跳过 {@code @TableLogic}，直达物理删除）
     *
     * @param ids 本片主键（非空）
     * @return 删除行数
     */
    @Delete("""
        <script>
        delete from ai_trace where id in
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int deleteTraceByIds(@Param("ids") List<Long> ids);

    /**
     * 统计超期的工具调用明细行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_tool_call_log where create_time < #{cutoff}")
    long countToolCallLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一片超期的工具调用明细主键
     *
     * @param cutoff 保留截止时间
     * @param limit  单片最大行数
     * @return 主键列表
     */
    @Select("select id from ai_tool_call_log where create_time < #{cutoff} order by id asc limit #{limit}")
    List<Long> selectToolCallLogIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 按主键批删工具调用明细
     *
     * @param ids 本片主键（非空）
     * @return 删除行数
     */
    @Delete("""
        <script>
        delete from ai_tool_call_log where id in
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int deleteToolCallLogByIds(@Param("ids") List<Long> ids);

    /**
     * 统计超期的权限决策日志行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_permission_decision_log where create_time < #{cutoff}")
    long countPermissionDecisionLog(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一片超期的权限决策日志主键
     *
     * @param cutoff 保留截止时间
     * @param limit  单片最大行数
     * @return 主键列表
     */
    @Select("select id from ai_permission_decision_log where create_time < #{cutoff} order by id asc limit #{limit}")
    List<Long> selectPermissionDecisionLogIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 按主键批删权限决策日志
     *
     * @param ids 本片主键（非空）
     * @return 删除行数
     */
    @Delete("""
        <script>
        delete from ai_permission_decision_log where id in
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int deletePermissionDecisionLogByIds(@Param("ids") List<Long> ids);

    /**
     * 统计超期的定时任务执行记录行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_agent_task_run where create_time < #{cutoff}")
    long countAgentTaskRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一片超期的定时任务执行记录主键
     *
     * @param cutoff 保留截止时间
     * @param limit  单片最大行数
     * @return 主键列表
     */
    @Select("select id from ai_agent_task_run where create_time < #{cutoff} order by id asc limit #{limit}")
    List<Long> selectAgentTaskRunIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 按主键批删定时任务执行记录
     *
     * @param ids 本片主键（非空）
     * @return 删除行数
     */
    @Delete("""
        <script>
        delete from ai_agent_task_run where id in
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int deleteAgentTaskRunByIds(@Param("ids") List<Long> ids);

    /**
     * 统计超期的编排运行行数
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("select count(*) from ai_route_run where create_time < #{cutoff}")
    long countRouteRun(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一片超期的编排运行主键
     *
     * @param cutoff 保留截止时间
     * @param limit  单片最大行数
     * @return 主键列表
     */
    @Select("select id from ai_route_run where create_time < #{cutoff} order by id asc limit #{limit}")
    List<Long> selectRouteRunIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 按主键批删编排运行（跳过 {@code @TableLogic}，直达物理删除）
     *
     * @param ids 本片主键（非空）
     * @return 删除行数
     */
    @Delete("""
        <script>
        delete from ai_route_run where id in
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int deleteRouteRunByIds(@Param("ids") List<Long> ids);

    /**
     * 统计待清理的专家调用明细行数
     *
     * <p>口径与 {@link #selectRouteExpertCallIds} 逐字对齐：自身超期、所属编排运行超期，
     * 或编排运行已不存在（父先删完留下的孤儿）三种都算，页面「超期行数」才不会少报。</p>
     *
     * @param cutoff 保留截止时间
     * @return 行数
     */
    @Select("""
        select count(*) from ai_route_expert_call c
        left join ai_route_run r on r.id = c.route_run_id
        where c.create_time < #{cutoff} or r.id is null or r.create_time < #{cutoff}
        """)
    long countRouteExpertCall(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 取一片待清理的专家调用明细主键（按父编排运行的截止时间联动，§12.7 I-7）
     *
     * @param cutoff 保留截止时间
     * @param limit  单片最大行数
     * @return 主键列表
     */
    @Select("""
        select c.id from ai_route_expert_call c
        left join ai_route_run r on r.id = c.route_run_id
        where c.create_time < #{cutoff} or r.id is null or r.create_time < #{cutoff}
        order by c.id asc limit #{limit}
        """)
    List<Long> selectRouteExpertCallIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 按主键批删专家调用明细
     *
     * @param ids 本片主键（非空）
     * @return 删除行数
     */
    @Delete("""
        <script>
        delete from ai_route_expert_call where id in
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int deleteRouteExpertCallByIds(@Param("ids") List<Long> ids);

}
