package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.agentscope.core.state.AgentStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiExpertDirectory;
import org.dromara.ai.core.agent.AiRunRegistry;
import org.dromara.ai.core.chat.AiAttachmentReclaimer;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.bo.AiSessionBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiChatAttachmentVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.domain.vo.AiRegenerateCheckVo;
import org.dromara.ai.domain.vo.AiSessionExportVo;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.ai.domain.vo.AiSourceVo;
import org.dromara.ai.domain.vo.AiToolCallLogVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.dromara.ai.mapper.AiScenarioPackMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.mapper.AiToolCallLogMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 会话 Service 业务层处理
 *
 * @author AgentHub
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiSessionServiceImpl implements IAiSessionService {

    /**
     * 平台内置只读工具白名单。
     *
     * <p>这些工具由框架 / 平台代码注册，不落 {@code ai_tool} 表，但本身不产生业务副作用：
     * 技能读取、编排协议、子智能体编排（子智能体内部的写工具会单独落明细并照常拦截）。
     * 未在此白名单且未登记的工具仍按 fail-closed 视为写操作。</p>
     */
    private static final Set<String> BUILTIN_READ_ONLY_TOOLS = Set.of(
        "route_decision", "submit_expert_result",
        "load_skill_through_path", "read_skill",
        "agent_spawn", "agent_send", "agent_list", "todo_write");

    /**
     * 默认会话标题模板
     */
    private static final String DEFAULT_TITLE_TEMPLATE = "与 %s 的对话";

    /**
     * 默认标题改写时截取的用户问句长度
     */
    private static final int TITLE_MAX_LENGTH = 30;

    /**
     * 会话标题落库最大长度（与 ai_session.session_title 一致）
     */
    private static final int TITLE_STORE_MAX_LENGTH = 200;

    /**
     * 回调失败原因落库的最大长度
     */
    private static final int ERROR_MSG_MAX_LENGTH = 1000;

    /**
     * 会话状态：进行中（取值单一来源见 {@link AiSession#STATUS_ACTIVE}）
     */
    private static final String SESSION_ACTIVE = AiSession.STATUS_ACTIVE;

    /**
     * 会话状态：已结束
     */
    private static final String SESSION_CLOSED = AiSession.STATUS_CLOSED;

    /**
     * 消息状态：正常
     */
    private static final String MESSAGE_NORMAL = AiMessage.STATUS_NORMAL;

    /**
     * 消息状态：失败
     */
    private static final String MESSAGE_FAILED = AiMessage.STATUS_FAILED;

    /**
     * 流式状态：已完成
     */
    private static final String STREAMING_DONE = "0";

    /**
     * 冲突业务码，与 HTTP 409 同值。底座业务错误一律走「HTTP 200 + 响应体 {@code code}」
     * （{@code GlobalExceptionHandler.handleServiceException}），前端按 {@code code} 判分支、按 {@code msg} 提示，
     * 因此这里给的是响应体码而不是状态码（§12.7 I-8）
     */
    private static final int CONFLICT_CODE = 409;

    /**
     * 会话状态：进行中
     */
    private static final String SESSION_ACTIVE_TEXT = "进行中";

    /**
     * 会话状态：已结束
     */
    private static final String SESSION_CLOSED_TEXT = "已结束";

    /**
     * 导出时间格式
     */
    private static final DateTimeFormatter EXPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiSessionMapper baseMapper;

    private final AiMessageMapper messageMapper;

    private final AiToolCallLogMapper toolCallLogMapper;

    /**
     * 工具注册表：重新生成预检按 {@code tool_code} 取 {@code tool_type} 判定写操作
     */
    private final AiToolMapper toolMapper;

    private final AiScenarioPackMapper scenarioPackMapper;

    private final IAiAgentService agentService;

    /**
     * 场景包 Service：创建会话时校验并固化所选场景包（启用 + 生效范围可见）
     */
    private final IAiScenarioPackService scenarioPackService;

    private final UserService userService;

    private final DeptService deptService;

    /**
     * AgentScope 状态存储（删除会话时清理该会话的模型记忆）
     */
    private final AgentStateStore aiAgentStateStore;

    /**
     * 专家名录：校验「指定专家」是否在当前用户可见范围内（与编排候选同源）
     */
    private final AiExpertDirectory expertDirectory;

    /**
     * 对话附件 Service：绑定随消息上传的图片 / 文档，并在消息列表回显
     */
    private final IAiChatAttachmentService aiChatAttachmentService;

    /**
     * 附件回收器：删会话 / 删整轮时级联清理对象存储文件与附件行
     */
    private final AiAttachmentReclaimer attachmentReclaimer;

    /**
     * 取消句柄注册表：判定「本实例上这一轮还在不在跑」的唯一现场依据（§12.7 I-8）
     */
    private final AiRunRegistry runRegistry;

    /**
     * 编排运行表：句柄是单实例内存态，跨副本 / 重启后仍在跑的编排由库里的 {@code running} 行兜住
     */
    private final AiRouteRunMapper routeRunMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiSessionVo createSession(Long agentId, Long scenarioPackId, String title, Long pinnedExpertId) {
        AiAgent agent = agentService.getEnabledAgent(agentId);
        // 场景包（可空）：必须存在、启用中且在当前用户生效范围内，才允许固化到会话
        scenarioPackService.validateSelectable(scenarioPackId);
        AiScenarioPack pack = scenarioPackService.getEnabledPackOrNull(scenarioPackId);
        // 指定专家（可空）：必须启用、专家角色且在当前用户可见范围内
        validatePinnedExpert(pinnedExpertId);
        AiSession add = new AiSession();
        add.setSessionTitle(StringUtils.isBlank(title) ? buildDefaultTitle(agent.getAgentName()) : StringUtils.trim(title));
        add.setAgentId(agent.getId());
        add.setAgentCode(agent.getAgentCode());
        add.setScenarioPackId(pack == null ? null : pack.getId());
        add.setScenarioPackCode(pack == null ? null : pack.getPackCode());
        add.setPinnedExpertId(pinnedExpertId);
        add.setUserId(LoginHelper.getUserId());
        add.setDeptId(LoginHelper.getDeptId());
        add.setMessageCount(0);
        add.setStatus(SESSION_ACTIVE);
        add.setTopFlag(AiSession.TOP_NORMAL);
        add.setLastActiveTime(LocalDateTime.now());
        add.setSource(AiSession.SOURCE_CHAT);
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("创建会话失败");
        }
        return withDisplayNames(baseMapper.selectVoById(add.getId()));
    }

    /**
     * 指定 / 取消指定专家（仅本人会话）。
     *
     * <p>与 {@code scenario_pack_id} 的差别：本项**会话内可变**（指定 / 取消都不会中断上下文），
     * 取消时用 {@code LambdaUpdateWrapper} 显式写入 {@code null}——MyBatis-Plus 的
     * {@code updateById} 默认忽略 null 字段，写不出「清空」。</p>
     *
     * @param sessionId     会话主键
     * @param expertAgentId 专家主键；{@code null} 表示取消指定
     * @return 是否更新成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean setPinnedExpert(Long sessionId, Long expertAgentId) {
        getOwnedSession(sessionId);
        validatePinnedExpert(expertAgentId);
        LambdaUpdateWrapper<AiSession> luw = Wrappers.lambdaUpdate();
        luw.eq(AiSession::getId, sessionId);
        luw.set(AiSession::getPinnedExpertId, expertAgentId);
        return baseMapper.update(null, luw) > 0;
    }

    @Override
    public AiSessionVo getOwnedSessionVo(Long sessionId) {
        getOwnedSession(sessionId);
        return withDisplayNames(baseMapper.selectVoById(sessionId));
    }

    /**
     * 校验「指定专家」可选性：专家主键可空（＝取消指定）；非空时必须启用且可见。
     *
     * <p>：拆表后「是不是专家」由**查询落在专家表**天然保证，无需再判角色；
     * 且**不过滤 {@code auto_route}**——{@code auto_route='0'} 的专家只能被用户手动指定，
     * 若沿用自动候选清单校验会永远无法指定（修复 B1）。</p>
     *
     * @param expertAgentId 专家主键（可空）
     */
    private void validatePinnedExpert(Long expertAgentId) {
        if (expertAgentId == null) {
            return;
        }
        AiExpertAgent expert = expertDirectory.visibleExpert(LoginHelper.getUserId(), LoginHelper.getDeptId(), expertAgentId);
        if (expert == null) {
            throw new ServiceException("该专家不存在或不在你的可见范围内");
        }
    }

    /**
     * 为单个会话视图对象补充展示名（智能体 / 场景包 / 用户 / 部门 / 指定专家名称）。
     *
     * @param vo 会话视图对象（可空）
     * @return 入参对象
     */
    private AiSessionVo withDisplayNames(AiSessionVo vo) {
        if (vo != null) {
            fillDisplayNames(List.of(vo));
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiSession createUnattendedSession(Long agentId, String title, Long userId, Long deptId, String source) {
        AiAgent agent = agentService.getEnabledAgent(agentId);
        AiSession add = new AiSession();
        add.setSessionTitle(StringUtils.isBlank(title) ? buildDefaultTitle(agent.getAgentName()) : StringUtils.trim(title));
        add.setAgentId(agent.getId());
        add.setAgentCode(agent.getAgentCode());
        add.setUserId(userId);
        add.setDeptId(deptId);
        add.setMessageCount(0);
        add.setStatus(SESSION_ACTIVE);
        add.setTopFlag(AiSession.TOP_NORMAL);
        add.setLastActiveTime(LocalDateTime.now());
        add.setSource(StringUtils.isBlank(source) ? AiSession.SOURCE_TASK : source);
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("创建会话失败");
        }
        return add;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage appendUserMessageUnattended(Long sessionId, String content) {
        AiSession session = getSessionOrThrow(sessionId);
        return appendUserMessageInternal(session, content, null);
    }

    @Override
    public PageResult<AiSessionVo> queryMySessions(AiSessionBo bo, PageQuery pageQuery) {
        // 对话页强制只看本人会话
        LambdaQueryWrapper<AiSession> lqw = buildQueryWrapper(bo, LoginHelper.getUserId());
        Page<AiSessionVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        // 会话列表需回显所选场景包名称（对话页底栏把「已固化的场景包」以只读标签展示）
        fillDisplayNames(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    /**
     * 删除一问一答（整轮）：该用户消息本身，以及紧随其后的助手消息（到下一条用户消息为止）。
     *
     * <p><b>只删平台侧记录（逻辑删除），不动模型记忆</b>：AgentScope 的会话状态按
     * {@code (userId, sessionId)} 整体存放（见 {@link #clearSessionState}），无法只摘掉某一轮，
     * 因此被删内容仍会留在模型上下文中、可能被后续回答引用；若要真正「遗忘」，
     * 只能清空整个会话记忆（会一并丢失其余轮次），本方法刻意不做这件事。</p>
     *
     * @param sessionId 会话主键
     * @param messageId 用户消息主键
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteChatRound(Long sessionId, Long messageId) {
        AiSession session = getOwnedSession(sessionId);
        AiMessage target = requireRoundTarget(sessionId, messageId);
        ChatRound round = resolveRound(session, target);
        // 写操作轮次禁止删除：删掉后审计链路（工具明细、编排轨迹）就难以从会话页回溯
        List<AiRegenerateCheckVo.WriteTool> writeTools = detectWriteTools(round.answers());
        if (!writeTools.isEmpty()) {
            throw new ServiceException(writeToolReason(writeTools));
        }
        if (round.pendingConfirm()) {
            throw new ServiceException("该轮存在待确认的工具调用，请先处置后再删除");
        }
        List<Long> doomed = new ArrayList<>();
        doomed.add(messageId);
        for (AiMessage answer : round.answers()) {
            if (STREAMING_DONE.equals(answer.getStreaming())) {
                doomed.add(answer.getId());
                continue;
            }
            throw new ServiceException("该轮回答仍在生成中，请先停止后再删除");
        }
        // 附件不随消息逻辑删除级联：转 ORPHANED 进入留档宽限期，对象由回收任务到期后删（§12.6）
        attachmentReclaimer.orphanByMessageIds(List.of(messageId));
        boolean flag = messageMapper.deleteByIds(doomed) > 0;
        if (flag) {
            decrementMessageCount(sessionId, doomed.size());
        }
        return flag;
    }

    @Override
    public AiRegenerateCheckVo checkRegenerable(Long sessionId, Long userMessageId) {
        AiSession session = getOwnedSession(sessionId);
        if (!SESSION_ACTIVE.equals(session.getStatus())) {
            return rejectRegenerate(null, AiRegenerateCheckVo.CODE_SESSION_CLOSED, "会话已结束，不能重新生成");
        }
        AiMessage target = userMessageId == null ? null : messageMapper.selectById(userMessageId);
        if (target == null || !sessionId.equals(target.getSessionId())
            || !AiMessage.ROLE_USER.equals(target.getRole())) {
            return rejectRegenerate(null, AiRegenerateCheckVo.CODE_ROUND_NOT_FOUND, "消息不存在或不属于该会话");
        }
        ChatRound round = resolveRound(session, target);
        List<Long> answerIds = round.answers().stream().map(AiMessage::getId).toList();
        if (!round.lastRound()) {
            return rejectRegenerate(answerIds, AiRegenerateCheckVo.CODE_ROUND_NOT_LAST,
                "只支持重新生成最后一轮回答");
        }
        if (round.streaming()) {
            return rejectRegenerate(answerIds, AiRegenerateCheckVo.CODE_ROUND_STREAMING,
                "该轮回答仍在生成中，请先停止后再重新生成");
        }
        if (round.pendingConfirm()) {
            return rejectRegenerate(answerIds, AiRegenerateCheckVo.CODE_ROUND_PENDING_CONFIRM,
                "该轮存在待确认的工具调用，请先处置后再重新生成");
        }
        List<AiRegenerateCheckVo.WriteTool> writeTools = detectWriteTools(round.answers());
        if (!writeTools.isEmpty()) {
            boolean onlyUnknown = writeTools.stream().allMatch(AiRegenerateCheckVo.WriteTool::isUnknown);
            AiRegenerateCheckVo vo = rejectRegenerate(answerIds,
                onlyUnknown ? AiRegenerateCheckVo.CODE_UNKNOWN_TOOL_USED : AiRegenerateCheckVo.CODE_WRITE_TOOL_USED,
                writeToolReason(writeTools));
            vo.setWriteTools(writeTools);
            return vo;
        }
        AiRegenerateCheckVo vo = new AiRegenerateCheckVo();
        vo.setAllowed(true);
        vo.setReasonCode(AiRegenerateCheckVo.CODE_OK);
        vo.setReason("可以重新生成");
        vo.setAssistantMessageIds(answerIds);
        vo.setWriteTools(List.of());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAnswerMessages(Long sessionId, Long userId, Collection<Long> answerIds) {
        if (answerIds == null || answerIds.isEmpty()) {
            return;
        }
        List<Long> ids = answerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        // 收尾在 Reactor 线程执行、没有 Sa-Token 上下文，故按调用方传入的归属用户校验，
        // 避免凭消息主键删到别人的会话
        AiSession session = sessionId == null ? null : baseMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) {
            throw new ServiceException("会话不存在或无权访问");
        }
        int deleted = messageMapper.deleteByIds(ids);
        if (deleted > 0) {
            decrementMessageCount(sessionId, deleted);
        }
    }

    /**
     * 构造「不允许重新生成」的预检结果。
     *
     * @param answerIds  本轮助手消息主键（可空）
     * @param reasonCode 原因码
     * @param reason     面向用户的原因
     * @return 预检结果
     */
    private AiRegenerateCheckVo rejectRegenerate(List<Long> answerIds, String reasonCode, String reason) {
        AiRegenerateCheckVo vo = new AiRegenerateCheckVo();
        vo.setAllowed(false);
        vo.setReasonCode(reasonCode);
        vo.setReason(reason);
        vo.setAssistantMessageIds(answerIds == null ? List.of() : answerIds);
        vo.setWriteTools(List.of());
        return vo;
    }

    /**
     * 校验「本轮用户消息」的存在性与归属。
     *
     * @param sessionId 会话主键
     * @param messageId 消息主键
     * @return 用户消息实体
     */
    private AiMessage requireRoundTarget(Long sessionId, Long messageId) {
        AiMessage target = messageId == null ? null : messageMapper.selectById(messageId);
        if (target == null || !sessionId.equals(target.getSessionId())) {
            throw new ServiceException("消息不存在或不属于该会话");
        }
        if (!AiMessage.ROLE_USER.equals(target.getRole())) {
            throw new ServiceException("只能操作本轮用户提问");
        }
        return target;
    }

    /**
     * 解析一问一答：该用户消息之后、下一条用户消息之前的全部助手消息。
     *
     * @param session 会话实体
     * @param target  本轮用户消息
     * @return 轮次视图
     */
    private ChatRound resolveRound(AiSession session, AiMessage target) {
        LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMessage::getSessionId, session.getId());
        lqw.orderByAsc(AiMessage::getId);
        List<AiMessage> all = messageMapper.selectList(lqw);
        int start = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(target.getId())) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new ServiceException("消息不存在或已被删除");
        }
        List<AiMessage> answers = new ArrayList<>();
        boolean lastRound = true;
        for (int i = start + 1; i < all.size(); i++) {
            AiMessage next = all.get(i);
            if (AiMessage.ROLE_USER.equals(next.getRole())) {
                lastRound = false;
                break;
            }
            answers.add(next);
        }
        boolean streaming = answers.stream().anyMatch(item -> !STREAMING_DONE.equals(item.getStreaming()));
        boolean pendingConfirm = answers.stream()
            .anyMatch(item -> AiMessage.STATUS_PENDING_CONFIRM.equals(item.getStatus()));
        return new ChatRound(answers, lastRound, streaming, pendingConfirm);
    }

    /**
     * 判定某轮是否发起过写操作。
     *
     * <p>口径（fail-closed）：</p>
     * <ul>
     *   <li>已登记且 {@code tool_type='1'}：{@code SUCCESS / ERROR / INTERRUPTED} 均视为写操作
     *       （错误与中断也可能已在外部系统生效）；{@code DENIED} 视为用户已拒绝、无副作用，放行。</li>
     *   <li>未在 {@code ai_tool} 登记：无法证明只读，一律视为写操作。</li>
     * </ul>
     *
     * @param answers 本轮助手消息
     * @return 命中的写操作工具（按工具编码 + 状态去重）
     */
    private List<AiRegenerateCheckVo.WriteTool> detectWriteTools(List<AiMessage> answers) {
        List<Long> messageIds = answers.stream().map(AiMessage::getId).filter(Objects::nonNull).toList();
        if (messageIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<AiToolCallLog> logQuery = Wrappers.lambdaQuery();
        logQuery.in(AiToolCallLog::getMessageId, messageIds);
        logQuery.orderByAsc(AiToolCallLog::getId);
        List<AiToolCallLog> logs = toolCallLogMapper.selectList(logQuery);
        if (logs.isEmpty()) {
            return List.of();
        }
        Set<String> toolCodes = logs.stream()
            .map(AiToolCallLog::getToolName)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        Map<String, AiTool> tools = new HashMap<>();
        if (!toolCodes.isEmpty()) {
            LambdaQueryWrapper<AiTool> toolQuery = Wrappers.lambdaQuery();
            toolQuery.in(AiTool::getToolCode, toolCodes);
            for (AiTool tool : toolMapper.selectList(toolQuery)) {
                tools.put(tool.getToolCode(), tool);
            }
        }
        Map<String, AiRegenerateCheckVo.WriteTool> hits = new LinkedHashMap<>();
        for (AiToolCallLog log : logs) {
            // 用户已拒绝的调用没有真正执行，不构成重复副作用风险
            if ("DENIED".equalsIgnoreCase(log.getState())) {
                continue;
            }
            AiTool tool = tools.get(log.getToolName());
            boolean unknown = tool == null;
            // 平台内置只读工具不在 ai_tool 登记，但可证明无副作用
            if (unknown && BUILTIN_READ_ONLY_TOOLS.contains(log.getToolName())) {
                continue;
            }
            boolean write = unknown || !AiTool.TYPE_READ_ONLY.equals(tool.getToolType());
            if (!write) {
                continue;
            }
            AiRegenerateCheckVo.WriteTool item = new AiRegenerateCheckVo.WriteTool();
            item.setToolCode(log.getToolName());
            item.setToolName(unknown ? log.getToolName() : tool.getToolName());
            item.setState(log.getState());
            item.setUnknown(unknown);
            hits.putIfAbsent(log.getToolName() + "|" + log.getState(), item);
        }
        return new ArrayList<>(hits.values());
    }

    /**
     * 组装写操作拦截的用户提示。
     *
     * @param writeTools 命中的写操作工具
     * @return 面向用户的原因
     */
    private String writeToolReason(List<AiRegenerateCheckVo.WriteTool> writeTools) {
        String names = writeTools.stream()
            .map(AiRegenerateCheckVo.WriteTool::getToolName)
            .distinct()
            .collect(Collectors.joining("、"));
        boolean onlyUnknown = writeTools.stream().allMatch(AiRegenerateCheckVo.WriteTool::isUnknown);
        return onlyUnknown
            ? "该轮调用了未登记的工具（" + names + "），无法确认是否只读，不能重新生成"
            : "该轮执行过写操作（" + names + "），不能重新生成";
    }

    /**
     * 回退会话的消息计数（消息条数为存储计数器，删除后需同步递减）。
     *
     * @param sessionId 会话主键
     * @param removed   删除的消息条数
     */
    private void decrementMessageCount(Long sessionId, int removed) {
        AiSession session = baseMapper.selectById(sessionId);
        int remaining = Math.max(0, (session == null || session.getMessageCount() == null
            ? 0 : session.getMessageCount()) - removed);
        AiSession update = new AiSession();
        update.setId(sessionId);
        update.setMessageCount(remaining);
        baseMapper.updateById(update);
    }

    /**
     * 一问一答的解析结果。
     *
     * @param answers       本轮助手消息
     * @param lastRound     是否为会话最后一轮
     * @param streaming     是否有助手消息仍在生成
     * @param pendingConfirm 是否有待人工确认的助手消息
     */
    private record ChatRound(List<AiMessage> answers, boolean lastRound, boolean streaming,
                             boolean pendingConfirm) {
    }

    @Override
    public List<AiMessageVo> queryMessages(Long sessionId) {
        getOwnedSession(sessionId);
        LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMessage::getSessionId, sessionId);
        lqw.orderByAsc(AiMessage::getId);
        List<AiMessageVo> messages = messageMapper.selectVoList(lqw);
        fillAttachments(messages);
        return messages;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage appendUserMessage(Long sessionId, String content) {
        AiSession session = getOwnedSession(sessionId);
        return appendUserMessageInternal(session, content, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage appendUserMessage(Long sessionId, String content, List<Long> attachmentIds) {
        if (StringUtils.isBlank(content) && (attachmentIds == null || attachmentIds.isEmpty())) {
            throw new ServiceException("请输入内容或上传附件");
        }
        AiSession session = getOwnedSession(sessionId);
        return appendUserMessageInternal(session, content, attachmentIds);
    }

    /**
     * 落库用户消息（共用实现，会话归属由调用方先行校验）。
     *
     * @param session       会话实体
     * @param content       用户输入内容（可为空，此时须有附件）
     * @param attachmentIds 随消息发送的附件主键集合（可空）
     * @return 用户消息实体
     */
    private AiMessage appendUserMessageInternal(AiSession session, String content, List<Long> attachmentIds) {
        Long sessionId = session.getId();
        if (!SESSION_ACTIVE.equals(session.getStatus())) {
            throw new ServiceException("会话已结束，无法继续提问");
        }
        String text = content == null ? "" : content;
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRole(AiMessage.ROLE_USER);
        message.setContent(text);
        message.setStreaming(STREAMING_DONE);
        message.setStatus(MESSAGE_NORMAL);
        message.setToolCallCount(0);
        messageMapper.insert(message);
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            aiChatAttachmentService.bindToMessage(sessionId, message.getId(), attachmentIds);
        }
        touchSession(sessionId);
        if (isDefaultTitle(session)) {
            // 只发附件时以占位文案作为标题来源
            String titleSource = StringUtils.isNotBlank(text) ? text : "（上传了附件）";
            AiSession rename = new AiSession();
            rename.setId(sessionId);
            rename.setSessionTitle(abbreviate(titleSource));
            baseMapper.updateById(rename);
        }
        return message;
    }

    /**
     * 为消息列表批量回填附件视图。
     *
     * @param messages 消息视图列表
     */
    private void fillAttachments(List<AiMessageVo> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Long> ids = messages.stream().map(AiMessageVo::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, List<AiChatAttachmentVo>> attachmentMap = aiChatAttachmentService.listVoMapByMessageIds(ids);
        if (attachmentMap == null || attachmentMap.isEmpty()) {
            return;
        }
        messages.forEach(item -> item.setAttachments(attachmentMap.get(item.getId())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage persistAssistantTurn(Long sessionId, String content, String replyId, Long durationMs,
                                          List<AiToolCallLog> toolLogs, ChatTokenUsage tokens, Long userId,
                                          List<AiSourceVo> sources) {
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setContent(content);
        message.setReplyId(replyId);
        message.setStreaming(STREAMING_DONE);
        message.setStatus(MESSAGE_NORMAL);
        message.setDurationMs(durationMs);
        message.setToolCallCount(toolLogs == null ? 0 : toolLogs.size());
        applyTokens(message, tokens);
        applySources(message, sources);
        messageMapper.insert(message);
        insertToolLogs(sessionId, message.getId(), toolLogs, userId);
        touchSession(sessionId);
        return message;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage persistAssistantFailure(Long sessionId, String errorMsg, Long durationMs) {
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setStreaming(STREAMING_DONE);
        message.setStatus(MESSAGE_FAILED);
        message.setDurationMs(durationMs);
        message.setToolCallCount(0);
        message.setErrorMsg(StringUtils.substring(errorMsg, 0, ERROR_MSG_MAX_LENGTH));
        messageMapper.insert(message);
        touchSession(sessionId);
        return message;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage persistAssistantAborted(Long sessionId, String content, Long durationMs,
                                             List<AiSourceVo> sources) {
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setContent(content);
        message.setStreaming(STREAMING_DONE);
        message.setStatus(AiMessage.STATUS_ABORTED);
        message.setDurationMs(durationMs);
        message.setToolCallCount(0);
        applySources(message, sources);
        messageMapper.insert(message);
        touchSession(sessionId);
        return message;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage persistPendingTurn(Long sessionId, String content, String replyId, Long durationMs,
                                        List<AiToolCallLog> toolLogs, ChatTokenUsage tokens, Long userId,
                                        List<AiSourceVo> sources) {
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setContent(content);
        message.setReplyId(replyId);
        message.setStreaming(STREAMING_DONE);
        message.setStatus(AiMessage.STATUS_PENDING_CONFIRM);
        message.setDurationMs(durationMs);
        message.setToolCallCount(toolLogs == null ? 0 : toolLogs.size());
        applyTokens(message, tokens);
        applySources(message, sources);
        messageMapper.insert(message);
        insertToolLogs(sessionId, message.getId(), toolLogs, userId);
        touchSession(sessionId);
        return message;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage appendAssistantTurn(Long messageId, String appendContent, String status,
                                         Long additionalDurationMs, List<AiToolCallLog> toolLogs,
                                         ChatTokenUsage tokens, List<AiSourceVo> sources) {
        AiMessage exists = messageMapper.selectById(messageId);
        if (exists == null) {
            throw new ServiceException("助手消息不存在或已被删除");
        }
        AiMessage update = new AiMessage();
        update.setId(messageId);
        update.setContent(StringUtils.isEmpty(appendContent)
            ? exists.getContent() : (exists.getContent() == null ? "" : exists.getContent()) + appendContent);
        update.setStatus(status);
        update.setDurationMs((exists.getDurationMs() == null ? 0L : exists.getDurationMs())
            + (additionalDurationMs == null ? 0L : additionalDurationMs));
        update.setToolCallCount((exists.getToolCallCount() == null ? 0 : exists.getToolCallCount())
            + (toolLogs == null ? 0 : toolLogs.size()));
        update.setPromptTokens(sumNullable(exists.getPromptTokens(), tokens == null ? null : tokens.promptTokens()));
        update.setCompletionTokens(sumNullable(exists.getCompletionTokens(), tokens == null ? null : tokens.completionTokens()));
        update.setTotalTokens(sumNullable(exists.getTotalTokens(), tokens == null ? null : tokens.totalTokens()));
        // 来源为空时不动原值（MyBatis-Plus updateById 默认忽略 null），避免恢复段覆盖首段来源
        if (sources != null && !sources.isEmpty()) {
            update.setSources(JsonUtils.toJsonString(sources));
        }
        messageMapper.updateById(update);
        AiSession session = baseMapper.selectById(exists.getSessionId());
        insertToolLogs(exists.getSessionId(), messageId, toolLogs, session == null ? null : session.getUserId());
        return messageMapper.selectById(messageId);
    }

    @Override
    public List<AiMessageVo> queryPendingMessages(Long sessionId) {
        getOwnedSession(sessionId);
        LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMessage::getSessionId, sessionId);
        lqw.eq(AiMessage::getStatus, AiMessage.STATUS_PENDING_CONFIRM);
        lqw.orderByAsc(AiMessage::getId);
        return messageMapper.selectVoList(lqw);
    }

    /**
     * 写入 Token 用量。
     *
     * @param message 消息实体
     * @param tokens  Token 用量
     */
    private void applyTokens(AiMessage message, ChatTokenUsage tokens) {
        if (tokens == null) {
            return;
        }
        message.setPromptTokens(tokens.promptTokens());
        message.setCompletionTokens(tokens.completionTokens());
        message.setTotalTokens(tokens.totalTokens());
    }

    /**
     * 写入回答参考来源（序列化为 JSON 字符串存库）。
     *
     * @param message 消息实体
     * @param sources 参考来源（可为空）
     */
    private void applySources(AiMessage message, List<AiSourceVo> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        message.setSources(JsonUtils.toJsonString(sources));
    }

    /**
     * 批量落库工具调用明细。
     *
     * @param sessionId 会话主键
     * @param messageId 助手消息主键
     * @param toolLogs  工具调用明细（可为空）
     * @param userId    触发用户（实时对话为登录用户，无人值守为运行身份用户）
     */
    private void insertToolLogs(Long sessionId, Long messageId, List<AiToolCallLog> toolLogs, Long userId) {
        if (toolLogs == null || toolLogs.isEmpty()) {
            return;
        }
        for (AiToolCallLog log : toolLogs) {
            log.setSessionId(sessionId);
            log.setMessageId(messageId);
            if (log.getUserId() == null) {
                log.setUserId(userId);
            }
            toolCallLogMapper.insert(log);
        }
    }

    /**
     * 可空求和。
     *
     * @param left  原值
     * @param right 增量
     * @return 求和结果；两者皆空时返回 null
     */
    private Integer sumNullable(Integer left, Integer right) {
        if (left == null && right == null) {
            return null;
        }
        return (left == null ? 0 : left) + (right == null ? 0 : right);
    }

    @Override
    public void touchSession(Long sessionId) {
        AiSession session = baseMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        AiSession update = new AiSession();
        update.setId(sessionId);
        update.setLastActiveTime(LocalDateTime.now());
        update.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        baseMapper.updateById(update);
    }

    @Override
    public Boolean closeSession(Long sessionId) {
        AiSession session = getOwnedSession(sessionId);
        // 「先终止再删除」要真能让删除放行：只改状态不取消执行，删的时候照样被判进行中，等于把 409 变成死循环
        runRegistry.cancel(sessionId);
        if (SESSION_CLOSED.equals(session.getStatus())) {
            return true;
        }
        return markClosed(List.of(sessionId));
    }

    /**
     * 把会话置为已结束，并按「目标状态是否已达成」而非「本条语句改了几行」判定成功。
     *
     * <p>带 {@code status = 进行中} 的条件是状态 CAS：并发的一方改成功后，另一方不该把已结束的行再改一遍。
     * 重复终止、或另一个请求（另一个副本）抢先把状态改掉时本条影响 0 行，但会话已是我们要的终态——
     * 此时回失败会让页面在双击或重试时弹「操作失败」，而状态早已生效（§12.7 I-8 的幂等口径）。</p>
     *
     * @param ids 会话主键集合（非空）
     * @return 目标会话是否全部处于已结束状态
     */
    private boolean markClosed(Collection<Long> ids) {
        AiSession update = new AiSession();
        update.setStatus(SESSION_CLOSED);
        LambdaUpdateWrapper<AiSession> luw = Wrappers.lambdaUpdate();
        luw.in(AiSession::getId, ids)
            .eq(AiSession::getStatus, SESSION_ACTIVE);
        baseMapper.update(update, luw);
        return baseMapper.selectByIds(ids).stream().allMatch(item -> SESSION_CLOSED.equals(item.getStatus()));
    }

    @Override
    public Boolean renameSession(Long sessionId, String title) {
        getOwnedSession(sessionId);
        String newTitle = StringUtils.trim(title);
        if (StringUtils.isBlank(newTitle)) {
            throw new ServiceException("会话标题不能为空");
        }
        if (newTitle.length() > TITLE_STORE_MAX_LENGTH) {
            throw new ServiceException("会话标题不能超过 " + TITLE_STORE_MAX_LENGTH + " 个字符");
        }
        AiSession update = new AiSession();
        update.setId(sessionId);
        update.setSessionTitle(newTitle);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public Boolean toggleTop(Long sessionId, Boolean top) {
        getOwnedSession(sessionId);
        AiSession update = new AiSession();
        update.setId(sessionId);
        update.setTopFlag(Boolean.TRUE.equals(top) ? AiSession.TOP_PINNED : AiSession.TOP_NORMAL);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public AiSession getOwnedSession(Long sessionId) {
        AiSession session = sessionId == null ? null : baseMapper.selectById(sessionId);
        if (session == null || !LoginHelper.getUserId().equals(session.getUserId())) {
            throw new ServiceException("会话不存在或无权访问");
        }
        return session;
    }

    @Override
    public PageResult<AiSessionVo> queryPageList(AiSessionBo bo, PageQuery pageQuery) {
        Page<AiSessionVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo, null));
        fillDisplayNames(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public List<AiMessageVo> querySessionMessages(Long sessionId) {
        getSessionOrThrow(sessionId);
        LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMessage::getSessionId, sessionId);
        lqw.orderByAsc(AiMessage::getId);
        List<AiMessageVo> messages = messageMapper.selectVoList(lqw);
        fillAttachments(messages);
        return messages;
    }

    @Override
    public List<AiToolCallLogVo> queryToolCalls(Long sessionId) {
        getSessionOrThrow(sessionId);
        LambdaQueryWrapper<AiToolCallLog> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiToolCallLog::getSessionId, sessionId);
        lqw.orderByAsc(AiToolCallLog::getId);
        return toolCallLogMapper.selectVoList(lqw);
    }

    @Override
    public List<AiToolCallLogVo> queryOwnedToolCalls(Long sessionId) {
        getOwnedSession(sessionId);
        return queryToolCalls(sessionId);
    }

    @Override
    public Boolean closeWithValidByIds(Collection<Long> ids, String reason) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要终止的会话");
        }
        List<AiSession> list = baseMapper.selectByIds(ids);
        if (list.size() != ids.size()) {
            throw new ServiceException("会话不存在或已被删除");
        }
        requireReasonForOthers("终止", reason, list.stream().map(AiSession::getUserId).toList());
        list.forEach(session -> runRegistry.cancel(session.getId()));
        return markClosed(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, String reason) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的会话");
        }
        List<AiSession> list = baseMapper.selectByIds(ids);
        if (list.size() != ids.size()) {
            throw new ServiceException("您没有删除权限!");
        }
        requireReasonForOthers("删除", reason, list.stream().map(AiSession::getUserId).toList());
        rejectWhileGenerating(list);
        // 附件不随会话级联：转 ORPHANED 保留留档窗口，对象文件由回收任务到期后删（§12.5 删除语义 / §12.6）
        attachmentReclaimer.orphanBySessionIds(ids);
        boolean flag = baseMapper.deleteByIds(ids) > 0;
        if (flag) {
            LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
            lqw.in(AiMessage::getSessionId, ids);
            messageMapper.delete(lqw);
            list.forEach(this::clearSessionState);
        }
        return flag;
    }

    /**
     * 跨本人范围的治理操作必须留下理由（§12.5「操作他人会话必填理由并写底座操作日志」、§12.7 I-9）。
     *
     * <p><b>按目标归属判定，不按调用方权限判定</b>：同一个管理员既有自己的会话也有他人会话，
     * 只挑自己的那批操作不必填，混进一条他人的就整批必填。挂在权限上会出现「刚用自己的会话试了一遍
     * 不用填理由，换成别人的也没提示要填」，而留痕要防的恰恰是后一次。</p>
     *
     * <p><b>为什么在服务层而不是只靠接口注解</b>：{@code @Size} 只能管住长度，管不住「什么时候必须有」；
     * 且接口注解只覆盖 HTTP 入口，日后新增的批量入口（定时回收、渠道转发）绕过 Controller 即无声免检。
     * 三个治理动作（终止 / 删除 / 导出）都从这里过，门禁 {@code session-governance-reason.mjs} 盯的就是
     * 「有没有人另开一条不过这里的路」。</p>
     *
     * <p>理由本身由 Controller 上的 {@code @Log} 切面随请求参数落 {@code sys_oper_log}，
     * 被拒绝的操作同样落一条失败记录（含「未填理由」的原因）。</p>
     *
     * @param action   动作名，用于报错文案（终止 / 删除 / 导出）
     * @param reason   调用方填写的理由（可为空）
     * @param ownerIds 本次涉及的每条会话的归属用户
     */
    private void requireReasonForOthers(String action, String reason, Collection<Long> ownerIds) {
        Long currentUserId = LoginHelper.getUserId();
        long foreignCount = ownerIds.stream().filter(userId -> !Objects.equals(userId, currentUserId)).count();
        if (foreignCount == 0) {
            return;
        }
        String scope = "本次" + action + "含 " + foreignCount + " 个他人会话";
        if (StringUtils.isBlank(reason)) {
            throw new ServiceException(scope + "，请填写操作理由（将记入操作日志）");
        }
        if (reason.trim().length() > REASON_MAX_LENGTH) {
            throw new ServiceException(scope + "，操作理由不能超过 " + REASON_MAX_LENGTH + " 字");
        }
    }

    /**
     * 进行中的会话拒绝删除，报 {@code code = 409}（§12.5 并发与保护、§12.7 I-8）。
     *
     * <p>一轮正在跑的对话被删掉，留下的是一头不存在会话的消息与无从回溯的工具明细，
     * 而这一切发生在用户点「删除」和模型吐完最后一个 token 之间，重试也复现不出来——
     * 所以宁可拒绝，让调用方先终止（{@link #closeSession(Long)} 会取消执行）再删。</p>
     *
     * @param sessions 已读出的会话实体（调用方保证非空且与请求 id 等量）
     */
    private void rejectWhileGenerating(List<AiSession> sessions) {
        List<AiSession> busy = sessions.stream().filter(session -> isGenerating(session.getId())).toList();
        if (busy.isEmpty()) {
            return;
        }
        String labels = busy.stream().limit(3).map(this::describeSession).collect(Collectors.joining("、"));
        String reason = busy.size() > 3
            ? busy.size() + " 个会话仍在生成中（" + labels + " 等），请先终止后再删除"
            : "会话（" + labels + "）仍在生成中，请先终止后再删除";
        throw new ServiceException(reason, CONFLICT_CODE);
    }

    /**
     * 进行中的判定取两个信号的并集：{@link AiRunRegistry} 的取消句柄是**单实例内存态**
     * （含 HITL 挂起未收尾的那段），跨副本或本实例重启后只能靠库里 {@code status = running}
     * 的编排运行行；后者由 {@code AiStaleRunReaper} 回收，所以中断的编排不会长期把删除挡在 409 上。
     *
     * @param sessionId 会话主键
     * @return 该会话是否仍有未收尾的执行
     */
    private boolean isGenerating(Long sessionId) {
        if (runRegistry.isActive(sessionId)) {
            return true;
        }
        LambdaQueryWrapper<AiRouteRun> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiRouteRun::getSessionId, sessionId).eq(AiRouteRun::getStatus, AiRouteRun.STATUS_RUNNING);
        return routeRunMapper.selectCount(lqw) > 0;
    }

    /**
     * 报错里怎么称呼这个会话：本人的会话报标题（页面看得见），他人的只报主键，
     * 免得删除失败顺带把别人的会话标题吐进响应。
     *
     * @param session 会话实体
     * @return 用于报错文本的标识
     */
    private String describeSession(AiSession session) {
        Long currentUserId = LoginHelper.getUserId();
        if (currentUserId != null && currentUserId.equals(session.getUserId())
            && StringUtils.isNotBlank(session.getSessionTitle())) {
            return session.getSessionTitle();
        }
        return "id=" + session.getId();
    }

    @Override
    public List<AiSessionExportVo> buildExportData(AiSessionBo bo, String reason) {
        List<AiSessionVo> list = baseMapper.selectVoList(buildQueryWrapper(bo, null));
        requireReasonForOthers("导出", reason, list.stream().map(AiSessionVo::getUserId).toList());
        fillDisplayNames(list);
        List<AiSessionExportVo> result = new ArrayList<>(list.size());
        for (AiSessionVo vo : list) {
            AiSessionExportVo row = new AiSessionExportVo();
            row.setSessionTitle(vo.getSessionTitle());
            row.setAgentName(vo.getAgentName());
            row.setUserName(vo.getUserName());
            row.setDeptName(vo.getDeptName());
            row.setMessageCount(vo.getMessageCount());
            row.setStatusText(SESSION_CLOSED.equals(vo.getStatus()) ? SESSION_CLOSED_TEXT : SESSION_ACTIVE_TEXT);
            row.setCreateTime(formatTime(vo.getCreateTime()));
            row.setLastActiveTime(formatTime(vo.getLastActiveTime()));
            result.add(row);
        }
        return result;
    }

    @Override
    public List<AiAgentVo> queryAgentOptions() {
        QueryWrapper<AiSession> qw = Wrappers.query();
        qw.select("distinct agent_id");
        List<Long> agentIds = baseMapper.selectObjs(qw).stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(Long::valueOf)
            .toList();
        if (agentIds.isEmpty()) {
            return List.of();
        }
        // 含停用智能体：历史会话仍归属已停用的智能体，筛选时需可见（两表解析）
        return agentService.queryEntityVos(agentIds);
    }

    /**
     * 查询会话实体（不校验归属，供无人值守链路续接同一会话使用）。
     *
     * @param sessionId 会话主键
     * @return 会话实体
     */
    @Override
    public AiSession getSession(Long sessionId) {
        return getSessionOrThrow(sessionId);
    }

    /**
     * 查询会话实体（不校验归属，供会话管理页使用）。
     *
     * @param sessionId 会话主键
     * @return 会话实体
     */
    private AiSession getSessionOrThrow(Long sessionId) {
        AiSession session = sessionId == null ? null : baseMapper.selectById(sessionId);
        if (session == null) {
            throw new ServiceException("会话不存在或已被删除");
        }
        return session;
    }

    /**
     * 填充智能体名称、归属用户昵称与部门名称。
     *
     * @param list 会话列表
     */
    private void fillDisplayNames(List<AiSessionVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> agentIds = new HashSet<>();
        Set<Long> packIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> deptIds = new HashSet<>();
        for (AiSessionVo vo : list) {
            if (vo.getAgentId() != null) {
                agentIds.add(vo.getAgentId());
            }
            // 指定专家也是 ai_agent 主键，复用同一份名称映射
            if (vo.getPinnedExpertId() != null) {
                agentIds.add(vo.getPinnedExpertId());
            }
            if (vo.getScenarioPackId() != null) {
                packIds.add(vo.getScenarioPackId());
            }
            if (vo.getUserId() != null) {
                userIds.add(vo.getUserId());
            }
            if (vo.getDeptId() != null) {
                deptIds.add(vo.getDeptId());
            }
        }
        Map<Long, String> agentNames = new HashMap<>();
        if (!agentIds.isEmpty()) {
            agentService.queryEntities(agentIds).forEach(agent -> agentNames.put(agent.getId(), agent.getAgentName()));
        }
        Map<Long, String> packNames = new HashMap<>();
        if (!packIds.isEmpty()) {
            scenarioPackMapper.selectByIds(packIds)
                .forEach(pack -> packNames.put(pack.getId(), pack.getPackName()));
        }
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of() : deptService.selectDeptNamesByIds(deptIds);
        for (AiSessionVo vo : list) {
            vo.setAgentName(agentNames.get(vo.getAgentId()));
            vo.setPinnedExpertName(vo.getPinnedExpertId() == null ? null : agentNames.get(vo.getPinnedExpertId()));
            // 场景包已删除时回落到编码快照，避免页面出现空列
            vo.setScenarioPackName(vo.getScenarioPackId() == null ? null
                : packNames.getOrDefault(vo.getScenarioPackId(), vo.getScenarioPackCode()));
            vo.setUserName(userNames.get(vo.getUserId()));
            vo.setDeptName(deptNames.get(vo.getDeptId()));
        }
    }

    /**
     * 清理会话在模型侧的记忆（Redis 中的 AgentScope 状态）。
     *
     * @param session 会话实体
     */
    private void clearSessionState(AiSession session) {
        try {
            aiAgentStateStore.delete(String.valueOf(session.getUserId()), String.valueOf(session.getId()));
        } catch (Exception e) {
            // 记忆清理失败不影响会话删除结果
            log.warn("清理会话记忆失败，sessionId={}：{}", session.getId(), e.getMessage());
        }
    }

    /**
     * 格式化导出用时间。
     *
     * @param time 时间
     * @return 格式化结果
     */
    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(EXPORT_TIME_FORMAT);
    }

    /**
     * 构建会话动态查询条件。
     *
     * @param bo          查询条件
     * @param forceUserId 强制限定的归属用户（非空时优先）
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiSession> buildQueryWrapper(AiSessionBo bo, Long forceUserId) {
        Long userId = forceUserId != null ? forceUserId
            : (Boolean.TRUE.equals(bo.getMine()) ? LoginHelper.getUserId() : bo.getUserId());
        LambdaQueryWrapper<AiSession> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getSessionTitle()), AiSession::getSessionTitle, bo.getSessionTitle());
        lqw.eq(bo.getSessionId() != null, AiSession::getId, bo.getSessionId());
        lqw.eq(StringUtils.isNotBlank(bo.getSource()), AiSession::getSource, bo.getSource());
        lqw.eq(bo.getAgentId() != null, AiSession::getAgentId, bo.getAgentId());
        lqw.eq(userId != null, AiSession::getUserId, userId);
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiSession::getStatus, bo.getStatus());
        lqw.ge(bo.getBeginTime() != null, AiSession::getLastActiveTime, bo.getBeginTime());
        lqw.le(bo.getEndTime() != null, AiSession::getLastActiveTime, bo.getEndTime());
        // 置顶会话优先，其余按最后活跃时间倒序
        lqw.orderByDesc(AiSession::getTopFlag);
        lqw.orderByDesc(AiSession::getLastActiveTime);
        return lqw;
    }

    /**
     * 构建默认会话标题。
     *
     * @param agentName 智能体名称
     * @return 默认标题
     */
    private String buildDefaultTitle(String agentName) {
        return String.format(DEFAULT_TITLE_TEMPLATE, StringUtils.isBlank(agentName) ? "智能体" : agentName);
    }

    /**
     * 判断会话标题是否仍为创建时的默认值。
     *
     * @param session 会话实体
     * @return 是否为默认标题
     */
    private boolean isDefaultTitle(AiSession session) {
        if (StringUtils.isBlank(session.getSessionTitle())) {
            return true;
        }
        AiAgent agent = agentService.queryEntity(session.getAgentId());
        return agent != null && session.getSessionTitle().equals(buildDefaultTitle(agent.getAgentName()));
    }

    /**
     * 截取用户问句作为会话标题。
     *
     * @param content 用户输入内容
     * @return 会话标题
     */
    private String abbreviate(String content) {
        String text = StringUtils.trim(content).replaceAll("\\s+", " ");
        return StringUtils.substring(text, 0, TITLE_MAX_LENGTH);
    }

}
