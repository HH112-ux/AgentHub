-- AgentHub AI 能力域结构脚本：40 张 ai_ 表（设计稿 §5 表清单、§6 冻结契约）。
-- 幂等：CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS，可重复执行；破坏性增列段已折叠进表体，不再单列。
-- 表名与列名属契约面，改动必须同步另一方言、契约常量与受影响模块。

create table if not exists ai_model_config ( id bigint not null, config_name varchar(100) not null, provider varchar(50) not null, model_type varchar(20) not null, model_code varchar(100) not null, supports_vision char(1) default '1', base_url varchar(255) not null, api_key text, dimension integer, timeout_seconds integer default 60, max_retries integer default 1, status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', input_price numeric(12, 6), output_price numeric(12, 6), constraint pk_ai_model_config primary key (id) );

comment on table ai_model_config is 'AI 模型配置表';

comment on column ai_model_config.config_name is '配置名称';

comment on column ai_model_config.provider is '提供商（openai/dashscope/deepseek/glm/ollama/custom）';

comment on column ai_model_config.model_type is '模型类型（chat 对话 / embedding 向量 / decision 决策 / rerank 重排）';

comment on column ai_model_config.model_code is '模型标识（如 qwen-plus、text-embedding-v3）';

comment on column ai_model_config.supports_vision is '是否支持视觉（图片输入；仅对话模型有意义）：1是 0否';

comment on column ai_model_config.base_url is '服务地址（OpenAI 兼容根地址）';

comment on column ai_model_config.api_key is 'API 密钥（AES 密文）';

comment on column ai_model_config.dimension is '向量维度（embedding 模型必填）';

comment on column ai_model_config.timeout_seconds is '超时时间（秒）';

comment on column ai_model_config.max_retries is '失败重试次数';

comment on column ai_model_config.status is '状态（0正常 1停用）';

comment on column ai_model_config.input_price is '输入单价（元 / 千 Token），为空按 0 计';

comment on column ai_model_config.output_price is '输出单价（元 / 千 Token），为空按 0 计';

create unique index if not exists uk_ai_model_config_name on ai_model_config (config_name) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_knowledge_base ( id bigint not null, kb_name varchar(100) not null, kb_code varchar(100) not null, description varchar(500), embedding_model_id bigint not null, collection_name varchar(100) not null, dimension integer not null, chunk_size integer default 500, chunk_overlap integer default 50, retrieval_top_k integer default 5, rerank_enabled varchar(1) not null default '0', rerank_model_id bigint, retrieval_mode varchar(16) not null default 'vector', bm25_index_status varchar(16) not null default 'none', similarity_threshold numeric(4, 3) default 0.500, status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', owner_dept_id bigint, visible_scope varchar(1) default '0', constraint pk_ai_knowledge_base primary key (id) );

comment on table ai_knowledge_base is 'AI 知识库表';

comment on column ai_knowledge_base.kb_code is '知识库编码（唯一，用于命名 Milvus 集合）';

comment on column ai_knowledge_base.embedding_model_id is '向量模型（ai_model_config.id，model_type=embedding）';

comment on column ai_knowledge_base.collection_name is 'Milvus 集合名称';

comment on column ai_knowledge_base.dimension is '向量维度（与向量模型一致）';

comment on column ai_knowledge_base.chunk_size is '切片长度（字符）';

comment on column ai_knowledge_base.chunk_overlap is '切片重叠长度（字符）';

comment on column ai_knowledge_base.retrieval_top_k is '默认召回条数';

comment on column ai_knowledge_base.rerank_enabled is '是否启用检索重排（0否 1是）';

comment on column ai_knowledge_base.rerank_model_id is '重排模型（ai_model_config.id，model_type=rerank）';

comment on column ai_knowledge_base.retrieval_mode is '检索方式（vector 向量 / hybrid 向量+BM25）';

comment on column ai_knowledge_base.bm25_index_status is 'BM25 索引状态（none/building/ready/failed）';

comment on column ai_knowledge_base.similarity_threshold is '默认相似度阈值';

comment on column ai_knowledge_base.status is '状态（0正常 1停用）';

comment on column ai_knowledge_base.owner_dept_id is '归属部门（visible_scope=1 时生效）';

comment on column ai_knowledge_base.visible_scope is '生效范围：0 公开 1 本部门 2 仅本人';

create unique index if not exists uk_ai_knowledge_base_code on ai_knowledge_base (kb_code) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_document ( id bigint not null, kb_id bigint not null, doc_name varchar(255) not null, file_suffix varchar(20), oss_id bigint, file_url varchar(500), file_size bigint, char_count integer default 0, chunk_count integer default 0, parse_status char(1) default '0', error_msg varchar(1000), remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', content text, vector_status char(1) default '0', vector_error_msg varchar(1000), constraint pk_ai_document primary key (id) );

comment on table ai_document is 'AI 知识库文档表';

comment on column ai_document.doc_name is '文档名称';

comment on column ai_document.file_suffix is '文件后缀（小写，不含点）';

comment on column ai_document.oss_id is 'OSS 文件 ID（sys_oss.oss_id）';

comment on column ai_document.file_url is '文件访问地址';

comment on column ai_document.file_size is '文件大小（字节）';

comment on column ai_document.char_count is '解析后字符数';

comment on column ai_document.chunk_count is '切片数量';

comment on column ai_document.parse_status is '解析状态（0待解析 1解析中 2已解析 3解析失败）';

comment on column ai_document.error_msg is '解析失败原因';

comment on column ai_document.content is '解析后的纯文本内容（用于在线预览）';

comment on column ai_document.vector_status is '向量化状态（0待向量化 1向量化中 2已向量化 3向量化失败）';

comment on column ai_document.vector_error_msg is '向量化失败原因';

create index if not exists idx_ai_document_kb on ai_document (kb_id);

create table if not exists ai_chunk ( id bigint not null, kb_id bigint not null, doc_id bigint not null, chunk_index integer not null, content text, char_count integer default 0, vector_id varchar(100), heading_path varchar(500), page_no integer, char_start integer, char_end integer, create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_chunk primary key (id) );

comment on table ai_chunk is 'AI 知识库文档切片表';

comment on column ai_chunk.chunk_index is '切片序号（从 0 开始）';

comment on column ai_chunk.content is '切片正文';

comment on column ai_chunk.vector_id is 'Milvus 中的向量主键';

comment on column ai_chunk.heading_path is '章节路径（各级标题用 > 连接，如 第2章 > 2.3 考勤）';

comment on column ai_chunk.page_no is '切片起始页码（PDF 等分页文档）';

comment on column ai_chunk.char_start is '切片在文档纯文本中的起始字符下标（含）';

comment on column ai_chunk.char_end is '切片在文档纯文本中的结束字符下标（不含）';

create index if not exists idx_ai_chunk_doc on ai_chunk (doc_id);

create index if not exists idx_ai_chunk_kb on ai_chunk (kb_id);

create table if not exists ai_entry_agent ( id bigint not null, agent_name varchar(100) not null, agent_code varchar(100) not null, description varchar(500), sys_prompt text, model_id bigint not null, memory_model_id bigint, summary_model_id bigint, subagent_model_id bigint, daily_token_quota bigint default 0, daily_request_quota integer default 0, monthly_token_quota bigint default 0, owner_dept_id bigint, visible_scope char(1) default '0', max_iters integer default 10, status char(1) default '0', remark varchar(500), singleton char(1) not null default '1', create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_entry_agent primary key (id), constraint ck_ai_entry_agent_singleton CHECK ((singleton = '1'::bpchar)) );

comment on table ai_entry_agent is 'AI 入口智能体表（全平台唯一，恒 1 行；REQ-AI-33 起由 ai_agent 拆出）';

comment on column ai_entry_agent.agent_code is '智能体编码（唯一，作为 AgentScope 的 agent name）';

comment on column ai_entry_agent.singleton is '单例行标记（恒 1，配合唯一索引强制「入口智能体全平台唯一」）';

create unique index if not exists uk_ai_entry_agent_code on ai_entry_agent (agent_code) WHERE (del_flag = '0'::bpchar);

create unique index if not exists uk_ai_entry_agent_singleton on ai_entry_agent (singleton);

create table if not exists ai_expert_agent ( id bigint not null, agent_name varchar(100) not null, agent_code varchar(100) not null, description varchar(500), sys_prompt text, model_id bigint not null, memory_model_id bigint, summary_model_id bigint, subagent_model_id bigint, daily_token_quota bigint default 0, daily_request_quota integer default 0, monthly_token_quota bigint default 0, owner_dept_id bigint, visible_scope char(1) default '0', max_iters integer default 10, status char(1) default '0', remark varchar(500), expert_domain varchar(64), capability_desc varchar(500), output_contract text, expert_write_enabled char(1) not null default '1', auto_route char(1) not null default '1', memory_enabled char(1) not null default '1', create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_expert_agent primary key (id), constraint ck_ai_expert_agent_auto_route CHECK ((auto_route = ANY (ARRAY['0'::bpchar, '1'::bpchar]))) );

comment on table ai_expert_agent is 'AI 专家智能体表（可被入口智能体路由；REQ-AI-33 起由 ai_agent 拆出）';

comment on column ai_expert_agent.agent_code is '智能体编码（唯一，作为 AgentScope 的 agent name 与路由专家 key）';

comment on column ai_expert_agent.visible_scope is '可见范围（0全员 1本部门 2仅创建人）';

comment on column ai_expert_agent.expert_domain is '专家领域标签（2026-10-02 起定义页已移除该输入项、不再参与换专家；仅存列）';

comment on column ai_expert_agent.capability_desc is '能力描述（注入入口智能体的路由提示词；为空回落 description）';

comment on column ai_expert_agent.output_contract is '默认输出契约（JSON Schema 文本，可空）；任务级契约优先';

comment on column ai_expert_agent.expert_write_enabled is '专家写工具开关（恒为 1：允许，按「允许」档生效、「需确认」档走 HITL 确认卡片）';

comment on column ai_expert_agent.auto_route is '是否自动路由（1 进入口智能体的自动候选清单；0 只能被用户手动指定）';

comment on column ai_expert_agent.memory_enabled is '是否启用长期记忆（1 开启 / 0 关闭：不注入也不沉淀长期记忆）';

create index if not exists idx_ai_expert_agent_auto_route on ai_expert_agent (auto_route, status) WHERE (del_flag = '0'::bpchar);

create unique index if not exists uk_ai_expert_agent_code on ai_expert_agent (agent_code) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_agent_tool ( id bigint not null, agent_id bigint not null, tool_code varchar(100) not null, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, decision char(1) default '0', constraint pk_ai_agent_tool primary key (id) );

comment on table ai_agent_tool is 'AI 智能体与业务工具关联表';

comment on column ai_agent_tool.tool_code is '工具编码（由 AiToolCatalog 定义，P2 改为工具注册表驱动）';

comment on column ai_agent_tool.decision is '工具授权决策（0允许 1需人工确认 2禁止）';

create unique index if not exists uk_ai_agent_tool on ai_agent_tool (agent_id, tool_code);

create table if not exists ai_agent_tool_trust ( id bigint not null, agent_id bigint not null, tool_code varchar(100) not null, user_id bigint not null, decision varchar(1) not null, source varchar(16) not null, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_agent_tool_trust primary key (id) );

comment on table ai_agent_tool_trust is 'AI 智能体 × 工具 × 用户的信任决策表（「记住该工具」沉淀，撤销即删除）';

comment on column ai_agent_tool_trust.user_id is '记住该工具的用户（信任只对该用户生效）';

create unique index if not exists uk_ai_agent_tool_trust on ai_agent_tool_trust (agent_id, tool_code, user_id);

create table if not exists ai_agent_kb ( id bigint not null, agent_id bigint not null, kb_id bigint not null, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_agent_kb primary key (id) );

comment on table ai_agent_kb is 'AI 智能体与知识库关联表';

comment on column ai_agent_kb.kb_id is '知识库主键（ai_knowledge_base.id）';

create unique index if not exists uk_ai_agent_kb on ai_agent_kb (agent_id, kb_id);

create table if not exists ai_session ( id bigint not null, session_title varchar(200), agent_id bigint not null, agent_code varchar(100) not null, user_id bigint not null, dept_id bigint, message_count integer default 0, status char(1) default '0', last_active_time timestamp, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', top_flag char(1) default '0', source varchar(20) default 'chat', scenario_pack_id bigint, scenario_pack_code varchar(64), pinned_expert_id bigint, constraint pk_ai_session primary key (id) );

comment on table ai_session is 'AI 会话表（id 即 AgentScope 的 sessionId）';

comment on column ai_session.session_title is '会话标题（首条用户消息截取生成）';

comment on column ai_session.agent_id is '所属智能体（ai_agent.id）';

comment on column ai_session.agent_code is '智能体编码快照，智能体删除后仍可追溯';

comment on column ai_session.user_id is '归属用户 ID';

comment on column ai_session.dept_id is '归属部门 ID';

comment on column ai_session.message_count is '消息条数';

comment on column ai_session.status is '状态（0进行中 1已结束）';

comment on column ai_session.last_active_time is '最后活跃时间';

comment on column ai_session.top_flag is '置顶标志（0普通 1置顶；置顶会话在列表中优先排序）';

comment on column ai_session.source is '会话来源：chat 手动对话 / task 定时任务 / channel 渠道接入';

comment on column ai_session.scenario_pack_id is '所选场景包（ai_scenario_pack.id，可空；为空时该会话不接入任何 MCP 工具，也不叠加包内工具与技能）';

comment on column ai_session.scenario_pack_code is '场景包编码快照（与 agent_code 同款，避免展示时回查）';

comment on column ai_session.pinned_expert_id is '指定专家（ai_agent.id，可空）：非空＝本会话本轮强制路由到该专家（用户指定，action=pinned），空＝走入口智能体正常决策；会话内可变更（取消指定即置空）';

create index if not exists idx_ai_session_user on ai_session (user_id, last_active_time DESC);

create table if not exists ai_session_resource ( id bigint not null, session_id bigint not null, resource_type varchar(20) not null, resource_id bigint not null, resource_key varchar(200) not null default '', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_session_resource primary key (id) );

comment on table ai_session_resource is 'AI 会话级资源集（REQ-AI-32）：会话选中的场景包 / 业务工具 / 技能 / MCP 工具，对小Z 与专家同时可见，会话内可增删、下一轮生效';

comment on column ai_session_resource.session_id is '会话主键（ai_session.id）';

comment on column ai_session_resource.resource_type is '资源类型（scenario 场景包 / tool 业务工具 / skill 技能 / mcp_tool MCP 工具）';

comment on column ai_session_resource.resource_id is '资源主键（场景包→ai_scenario_pack.id；工具→ai_tool.id；技能→ai_skill.id；MCP 工具→ai_mcp_client.id）';

comment on column ai_session_resource.resource_key is '稳定键（展示与去重）：场景包编码 / 工具编码 / 技能名 / MCP 远端工具名';

comment on column ai_session_resource.del_flag is '删除标志（0存在 1删除）：叉号移除即逻辑删除，重新选中即新插一行';

create index if not exists idx_ai_session_resource_session on ai_session_resource (session_id);

create unique index if not exists uk_ai_session_resource on ai_session_resource (session_id, resource_type, resource_id, resource_key) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_message ( id bigint not null, session_id bigint not null, role varchar(20) not null, content text, reply_id varchar(64), streaming char(1) default '0', status char(1) default '0', error_msg varchar(1000), tool_call_count integer default 0, prompt_tokens integer, completion_tokens integer, total_tokens integer, duration_ms bigint, sources text, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_message primary key (id) );

comment on table ai_message is 'AI 会话消息表';

comment on column ai_message.role is '角色（user 用户 / assistant 助手 / system 系统）';

comment on column ai_message.content is '消息正文（助手为流式拼接后的完整答案）';

comment on column ai_message.reply_id is 'AgentScope replyId，用于聚合流式块';

comment on column ai_message.streaming is '流式状态（0已完成 1流式中）';

comment on column ai_message.status is '状态（0正常 1失败 2待确认 3已中断）';

comment on column ai_message.error_msg is '失败原因';

comment on column ai_message.tool_call_count is '本轮工具调用次数';

comment on column ai_message.prompt_tokens is '输入 Token 数';

comment on column ai_message.completion_tokens is '输出 Token 数';

comment on column ai_message.total_tokens is '总 Token 数';

comment on column ai_message.duration_ms is '本轮耗时（毫秒）';

comment on column ai_message.sources is '回答参考来源（JSON 字符串，知识库命中；可空）';

create index if not exists idx_ai_message_session on ai_message (session_id, id);

create index if not exists idx_ai_message_create_time on ai_message (create_time);

create table if not exists ai_message_attachment ( id bigint not null, session_id bigint not null, message_id bigint, file_type varchar(16) not null, file_name varchar(255) not null, file_suffix varchar(32), file_size bigint, mime_type varchar(64), oss_id bigint, parsed_content text, create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_message_attachment primary key (id) );

comment on table ai_message_attachment is 'AI 会话消息附件表（聊天上传的图片/文档）';

comment on column ai_message_attachment.session_id is '所属会话（ai_session.id）';

comment on column ai_message_attachment.message_id is '所属用户消息（ai_message.id）；为空表示尚未发送的暂存件';

comment on column ai_message_attachment.file_type is '附件类型（image 图片 / document 文档）';

comment on column ai_message_attachment.file_name is '原始文件名';

comment on column ai_message_attachment.file_suffix is '文件后缀（小写，不含点）';

comment on column ai_message_attachment.file_size is '文件大小（字节）';

comment on column ai_message_attachment.mime_type is 'MIME 类型（图片为 image/*）';

comment on column ai_message_attachment.oss_id is '对象存储主键（sys_oss.oss_id）';

comment on column ai_message_attachment.parsed_content is '文档解析后的纯文本（图片为空）';

create index if not exists idx_ai_message_attachment_session on ai_message_attachment (session_id);

create index if not exists idx_ai_message_attachment_message on ai_message_attachment (message_id);

create index if not exists idx_ai_message_attachment_pending on ai_message_attachment (create_time) where message_id is null;

create table if not exists ai_tool_call_log ( id bigint not null, session_id bigint not null, message_id bigint, user_id bigint, tool_call_id varchar(64), tool_name varchar(100) not null, tool_input text, tool_output text, state varchar(20), duration_ms bigint, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, agent_id bigint, constraint pk_ai_tool_call_log primary key (id) );

comment on table ai_tool_call_log is 'AI 工具调用明细表（物理追加）';

comment on column ai_tool_call_log.session_id is '所属会话（ai_session.id）';

comment on column ai_tool_call_log.message_id is '所属助手消息（ai_message.id）';

comment on column ai_tool_call_log.user_id is '触发用户 ID';

comment on column ai_tool_call_log.tool_call_id is 'AgentScope toolCallId';

comment on column ai_tool_call_log.tool_name is '工具名称';

comment on column ai_tool_call_log.tool_input is '入参 JSON（由 tool_call delta 增量拼接）';

comment on column ai_tool_call_log.tool_output is '结果摘要（截断后）';

comment on column ai_tool_call_log.state is '结果状态（SUCCESS / ERROR / INTERRUPTED / DENIED）';

comment on column ai_tool_call_log.duration_ms is '工具执行耗时（毫秒）';

comment on column ai_tool_call_log.agent_id is '发起该工具调用的智能体主键（入口轮＝会话智能体，专家调用＝专家，无人值守＝对应智能体；历史行为空）';

create index if not exists idx_ai_tool_call_agent on ai_tool_call_log (message_id, agent_id);

create index if not exists idx_ai_tool_call_log_session on ai_tool_call_log (session_id, id);

create index if not exists idx_ai_tool_call_log_create_time on ai_tool_call_log (create_time);

create table if not exists ai_route_run ( id bigint not null, session_id bigint not null, message_id bigint, entry_agent_id bigint not null, user_id bigint, action varchar(8), decision_json text, task_context text, expert_call_count integer default 0, status varchar(16) default 'running', degrade_reason varchar(500), duration_ms bigint, create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', warning_text varchar(1000), decision_source char(1), judge_choice varchar(64), judge_confidence numeric(5,4), judge_probabilities text, judge_latency_ms integer, judge_tokens integer, judge_error varchar(500), judge_mode char(1), constraint pk_ai_route_run primary key (id) );

comment on table ai_route_run is 'AI 编排运行表（入口智能体的每一轮编排一行；自答时也落一行用于统计路由命中率）';

comment on column ai_route_run.session_id is '用户会话主键（ai_session.id）';

comment on column ai_route_run.message_id is '本轮的助手消息主键（ai_message.id）';

comment on column ai_route_run.entry_agent_id is '入口智能体主键（本期恒为小Z）';

comment on column ai_route_run.user_id is '提问用户';

comment on column ai_route_run.action is '路由决策（self 自答 / route 路由）';

comment on column ai_route_run.decision_json is '路由决策原文（含 reason / confidence，用于复盘决策质量）';

comment on column ai_route_run.task_context is '下发给专家的任务片段（原样留档，用于事后核对「到底传了什么」）';

comment on column ai_route_run.expert_call_count is '本次编排的专家调用次数（含重试与换专家）';

comment on column ai_route_run.status is '编排状态：pending（待人工确认收尾）/ running / success / degraded（降级自答）/ aborted';

comment on column ai_route_run.degrade_reason is '降级或中止原因';

comment on column ai_route_run.duration_ms is '编排总耗时（毫秒）';

comment on column ai_route_run.warning_text is '编排告警汇总（只告警不阻断）：L3 脱敏命中项（按类型计数）+ 事实篡改抽查不一致项（专家关键数字未在最终回答中出现）；无告警时为空';

comment on column ai_route_run.decision_source is '结构化决策判定来源：L 工具决策 / J 决策模型 / K 粘性复用上轮专家（跳过判定）/ F 回落（工具决策兜底）';

comment on column ai_route_run.judge_choice is '结构化决策：决策模型选择的选项（agent_code / self / other）';

comment on column ai_route_run.judge_confidence is '结构化决策：判定置信度';

comment on column ai_route_run.judge_probabilities is '结构化决策：概率分布（JSON 文本，影子校准用）';

comment on column ai_route_run.judge_latency_ms is '结构化决策：判定耗时（毫秒）';

comment on column ai_route_run.judge_tokens is '结构化决策：判定输入 Token（费用口径）';

comment on column ai_route_run.judge_error is '结构化决策：判定不可用原因（超时 / 报错 / 越界 / margin_rejected 边际不足 / circuit_breaker_open 熔断中）';

comment on column ai_route_run.judge_mode is '结构化决策：本轮执行模式 S 影子 / G 闸门';

create index if not exists idx_ai_route_run_message on ai_route_run (message_id);

create index if not exists idx_ai_route_run_session on ai_route_run (session_id, id);

create index if not exists idx_ai_route_run_create_time on ai_route_run (create_time);

create table if not exists ai_route_expert_call ( id bigint not null, route_run_id bigint not null, expert_agent_id bigint not null, expert_session_id varchar(128), attempt_no integer default 1, call_kind varchar(8), contract_json text, raw_output text, parsed_output text, validation_error varchar(1000), status varchar(16), prompt_tokens bigint, completion_tokens bigint, duration_ms bigint, create_dept bigint, create_by bigint, create_time timestamp, constraint pk_ai_route_expert_call primary key (id) );

comment on table ai_route_expert_call is 'AI 专家调用明细表（一次专家调用一行；追加型，物理删除）';

comment on column ai_route_expert_call.route_run_id is '所属编排（ai_route_run.id）';

comment on column ai_route_expert_call.expert_agent_id is '被调用的专家智能体主键（ai_agent.id）';

comment on column ai_route_expert_call.expert_session_id is '专家独立会话标识（routeRunId#expertAgentId），与用户会话完全隔离';

comment on column ai_route_expert_call.attempt_no is '第几次尝试（从 1 起）';

comment on column ai_route_expert_call.call_kind is '调用类型：first 首次 / retry 同专家重试 / switch 换专家';

comment on column ai_route_expert_call.contract_json is '本次下发的输出契约（第 3 次尝试记录放宽后的 partial 契约，便于核对当时按哪个松紧度判定）';

comment on column ai_route_expert_call.raw_output is '专家原始输出原文（不改写、不裁剪，供前端来源卡片与事后追溯）';

comment on column ai_route_expert_call.parsed_output is '通过契约校验后的结构化结果（JSON 文本）';

comment on column ai_route_expert_call.validation_error is '结构校验失败原因（重试时回传给专家）';

comment on column ai_route_expert_call.status is '调用状态：success / invalid / failed / timeout / pending_confirm / cancelled';

comment on column ai_route_expert_call.prompt_tokens is '该次调用的输入 Token（计入编排总预算）';

comment on column ai_route_expert_call.completion_tokens is '该次调用的输出 Token';

comment on column ai_route_expert_call.duration_ms is '该次调用耗时（毫秒）';

create index if not exists idx_ai_route_call_expert on ai_route_expert_call (expert_agent_id, create_time DESC);

create index if not exists idx_ai_route_call_run on ai_route_expert_call (route_run_id, attempt_no);

create index if not exists idx_ai_route_expert_call_create_time on ai_route_expert_call (create_time);

create table if not exists ai_tool ( id bigint not null, tool_code varchar(100) not null, tool_name varchar(100) not null, description varchar(500), tool_type char(1) default '0', params_schema text, impl_name varchar(100), status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', mcp_exposed char(1) default '0', constraint pk_ai_tool primary key (id) );

comment on table ai_tool is '业务工具注册表（可被智能体调用的语义化工具）';

comment on column ai_tool.tool_type is '工具类型（0只读 1写入；写入类需 P2 的 HITL 确认闭环）';

comment on column ai_tool.params_schema is '参数定义（JSON Schema）';

comment on column ai_tool.impl_name is '代码侧实现标识（AiToolFactory 中的 key）';

comment on column ai_tool.status is '状态（0启用 1停用；停用后不再装配进智能体）';

comment on column ai_tool.mcp_exposed is '是否对外暴露给 MCP 出口（0否 1是；仅只读工具可勾选）';

create unique index if not exists uk_ai_tool_code on ai_tool (tool_code) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_mcp_client ( id bigint not null, server_name varchar(100) not null, transport varchar(20) not null, url varchar(500) not null, headers_json text, enable_tools varchar(2000), timeout_ms integer, init_timeout_ms integer, description varchar(500), status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_mcp_client primary key (id) );

comment on table ai_mcp_client is '外部 MCP Server 连接配置表（平台级，全部智能体可用）';

comment on column ai_mcp_client.server_name is 'Server 名称（唯一，作为 ToolsConfig.mcpServers 的键）';

comment on column ai_mcp_client.transport is '传输方式（sse / http；不放开 stdio）';

comment on column ai_mcp_client.url is 'Server 地址（sse 或 streamable-http 端点）';

comment on column ai_mcp_client.headers_json is '请求头 JSON（AES 密文，可承载 Authorization 等凭据，接口不回显明文）';

comment on column ai_mcp_client.enable_tools is '工具白名单（逗号分隔；为空表示放行该 Server 全部工具）';

comment on column ai_mcp_client.timeout_ms is '单次请求超时（毫秒），为空取框架默认';

comment on column ai_mcp_client.init_timeout_ms is '初始化超时（毫秒），为空取框架默认';

comment on column ai_mcp_client.status is '状态（0启用 1停用；停用后不再接入智能体装配）';

create unique index if not exists uk_ai_mcp_client_name on ai_mcp_client (server_name) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_tool_tag ( id bigint not null, tag_name varchar(50) not null, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_tool_tag primary key (id) );

comment on table ai_tool_tag is '业务工具标签定义表';

comment on column ai_tool_tag.tag_name is '标签名称（唯一，最长 50 字）';

comment on column ai_tool_tag.remark is '备注（用途说明，便于管理大量标签）';

create unique index if not exists uk_ai_tool_tag_name on ai_tool_tag (tag_name) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_tool_tag_rel ( id bigint not null, tool_id bigint not null, tag_id bigint not null, create_time timestamp, constraint pk_ai_tool_tag_rel primary key (id) );

comment on table ai_tool_tag_rel is '业务工具与标签关联表（物理删除）';

comment on column ai_tool_tag_rel.tool_id is '业务工具主键（ai_tool.id）';

comment on column ai_tool_tag_rel.tag_id is '标签主键（ai_tool_tag.id）';

create index if not exists idx_ai_tool_tag_rel_tag on ai_tool_tag_rel (tag_id);

create unique index if not exists uk_ai_tool_tag_rel on ai_tool_tag_rel (tool_id, tag_id);

create table if not exists ai_skill ( id bigint not null, name varchar(255) not null, description text not null, skill_content text not null, source varchar(255) not null default 'agenthub', metadata_json text, created_at timestamp, updated_at timestamp, owner_dept_id bigint, visible_scope varchar(1) default '0', status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, update_by bigint, constraint pk_ai_skill primary key (id) );

comment on table ai_skill is 'AI 技能表（正文即 SKILL.md；列结构兼容 AgentScope PostgreSQL 技能仓库）';

comment on column ai_skill.name is '技能标识（唯一，即 AgentScope 的 skillId）';

comment on column ai_skill.description is '技能用途说明（供模型判断是否加载）';

comment on column ai_skill.skill_content is 'SKILL.md 全文（YAML frontmatter + Markdown 正文）';

comment on column ai_skill.source is '技能来源（本项目维护的固定为 agenthub）';

comment on column ai_skill.metadata_json is '技能元数据 JSON（框架读取用，可为空）';

comment on column ai_skill.owner_dept_id is '归属部门（visible_scope=1 时生效）';

comment on column ai_skill.visible_scope is '生效范围：0 公开 1 本部门 2 仅本人';

comment on column ai_skill.status is '状态（0启用 1停用）';

create unique index if not exists uk_ai_skill_name on ai_skill (name);

create table if not exists ai_skill_resource ( id bigint not null, resource_path varchar(500) not null, resource_content text not null, created_at timestamp, updated_at timestamp, constraint pk_ai_skill_resource primary key (id, resource_path), constraint fk_ai_skill_resource_skill FOREIGN KEY (id) REFERENCES ai_skill(id) ON DELETE CASCADE );

comment on table ai_skill_resource is 'AI 技能参考资料表（id 即所属技能主键）';

comment on column ai_skill_resource.resource_path is '相对路径（如 references/faq.md）';

comment on column ai_skill_resource.resource_content is '文件内容';

create table if not exists ai_agent_skill ( id bigint not null, agent_id bigint not null, skill_id bigint not null, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_agent_skill primary key (id) );

comment on table ai_agent_skill is 'AI 智能体与技能关联表';

comment on column ai_agent_skill.skill_id is '技能主键（ai_skill.id）';

create unique index if not exists uk_ai_agent_skill on ai_agent_skill (agent_id, skill_id);

create table if not exists ai_subagent ( id bigint not null, name varchar(100) not null, description varchar(1000) not null, sys_prompt text, workspace_mode varchar(16) default 'ISOLATED', model_id bigint, steps integer default 10, tool_codes text, status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_subagent primary key (id) );

comment on table ai_subagent is 'AI 子智能体定义表（平台级可复用，与智能体多对多绑定）';

comment on column ai_subagent.name is '子智能体标识（唯一，即 AgentScope SubagentDeclaration 的 name / agent_id）';

comment on column ai_subagent.description is '用途说明（主智能体据此决定是否委派，务必写清何时使用）';

comment on column ai_subagent.sys_prompt is '子智能体系统提示词（即 inlineAgentsBody；为空时使用框架通用子智能体提示词）';

comment on column ai_subagent.workspace_mode is '工作区模式：ISOLATED 隔离工作区 / SHARED 与主智能体共享工作区';

comment on column ai_subagent.model_id is '指定模型（ai_model_config.id，可空；为空时用 ai_agent.subagent_model_id，再为空则继承主模型）';

comment on column ai_subagent.steps is '单次最大推理迭代次数（框架默认 10）';

comment on column ai_subagent.tool_codes is '工具白名单（JSON 数组，仅允许 ai_tool.tool_type=0 的只读工具）';

comment on column ai_subagent.status is '状态（0启用 1停用）';

create unique index if not exists uk_ai_subagent_name on ai_subagent (name);

create table if not exists ai_agent_subagent ( id bigint not null, agent_id bigint not null, subagent_id bigint not null, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_agent_subagent primary key (id) );

comment on table ai_agent_subagent is 'AI 智能体与子智能体关联表';

comment on column ai_agent_subagent.subagent_id is '子智能体主键（ai_subagent.id）';

create unique index if not exists uk_ai_agent_subagent on ai_agent_subagent (agent_id, subagent_id);

create table if not exists ai_channel ( id bigint not null, channel_name varchar(100) not null, channel_code varchar(64) not null, platform_type varchar(20) not null, mode varchar(16) not null default 'webhook', agent_id bigint not null, app_id varchar(200), app_secret varchar(500), platform_agent_id varchar(64), encrypt_key varchar(500), verification_token varchar(500), webhook_secret varchar(500), api_token varchar(500), api_base varchar(200), status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_channel primary key (id) );

comment on table ai_channel is 'AI 渠道接入配置（REQ-AI-24）';

comment on column ai_channel.channel_name is '渠道名称';

comment on column ai_channel.channel_code is '渠道编码（唯一，回调地址中的路径标识）';

comment on column ai_channel.platform_type is '平台类型：feishu 飞书 / wecom 企业微信 / github GitHub / dingtalk 钉钉（长连接）';

comment on column ai_channel.mode is '接入形态：webhook Webhook 回调（默认，需平台公网可达） / ws 长连接（应用主动外连、免公网 IP；仅飞书区分）';

comment on column ai_channel.agent_id is '默认智能体（ai_agent.id）';

comment on column ai_channel.app_id is '应用标识（飞书 appId / 企微 corpId / 钉钉 AppKey；GitHub 为空）';

comment on column ai_channel.app_secret is '应用密钥（AES 密文；飞书 appSecret / 企微 secret / 钉钉 AppSecret）';

comment on column ai_channel.platform_agent_id is '平台内应用 ID（企微 agentId / 钉钉 RobotCode；其它平台为空）';

comment on column ai_channel.encrypt_key is '回调加解密密钥（AES 密文；飞书 Encrypt Key / 企微 EncodingAESKey）';

comment on column ai_channel.verification_token is '回调校验口令（AES 密文；飞书 Verification Token / 企微 Token）';

comment on column ai_channel.webhook_secret is 'Webhook 签名密钥（AES 密文；GitHub Webhook Secret）';

comment on column ai_channel.api_token is '平台访问令牌（AES 密文；GitHub PAT）';

comment on column ai_channel.api_base is '平台 API 基地址（飞书 / 企微 / GitHub / 钉钉 OpenAPI；留空用各平台默认值）';

comment on column ai_channel.status is '状态（0启用 1停用）';

create unique index if not exists uk_ai_channel_code on ai_channel (channel_code) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_channel_identity ( id bigint not null, channel_id bigint not null, external_user_id varchar(200) not null, platform_user_id bigint not null, status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_channel_identity primary key (id) );

comment on table ai_channel_identity is 'AI 渠道外部身份映射（REQ-AI-24）';

comment on column ai_channel_identity.channel_id is '所属渠道（ai_channel.id）';

comment on column ai_channel_identity.external_user_id is '外部用户标识（飞书 open_id / 企微 UserID / GitHub login）';

comment on column ai_channel_identity.platform_user_id is '映射到的平台用户（sys_user.user_id）';

comment on column ai_channel_identity.status is '状态（0启用 1停用）';

create unique index if not exists uk_ai_channel_identity on ai_channel_identity (channel_id, external_user_id) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_channel_thread ( id bigint not null, channel_id bigint not null, thread_key varchar(300) not null, session_id bigint not null, create_time timestamp, last_active_time timestamp, constraint pk_ai_channel_thread primary key (id) );

comment on table ai_channel_thread is 'AI 渠道消息线程与会话映射（REQ-AI-24）';

comment on column ai_channel_thread.channel_id is '所属渠道（ai_channel.id）';

comment on column ai_channel_thread.thread_key is '线程标识（框架 Peer.key() 口径：<peerKind>:<peerId>，kind 为小写 direct / group / thread，如 direct:oc_xxx、thread:owner/repo#7）';

comment on column ai_channel_thread.session_id is '映射到的会话（ai_session.id）';

create unique index if not exists uk_ai_channel_thread on ai_channel_thread (channel_id, thread_key);

create table if not exists ai_agent_task ( id bigint not null, task_name varchar(100) not null, agent_id bigint not null, agent_code varchar(100) not null, task_prompt text not null, cron_expression varchar(100) not null, run_as_user_id bigint not null, run_as_dept_id bigint, last_run_time timestamp, status char(1) default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_agent_task primary key (id) );

comment on table ai_agent_task is 'AI 定时任务表';

comment on column ai_agent_task.task_name is '任务名称';

comment on column ai_agent_task.agent_id is '运行的智能体（ai_agent.id）';

comment on column ai_agent_task.agent_code is '智能体编码快照，智能体删除后仍可追溯';

comment on column ai_agent_task.task_prompt is '任务提示词（作为每轮对话的用户消息）';

comment on column ai_agent_task.cron_expression is 'Cron 表达式（Spring 6 位：秒 分 时 日 月 周）';

comment on column ai_agent_task.run_as_user_id is '运行身份用户（会话与数据权限按该用户）';

comment on column ai_agent_task.run_as_dept_id is '运行身份部门（随运行身份用户带出）';

comment on column ai_agent_task.last_run_time is '上次计划执行时间（到点判定与抢占去重基准）';

comment on column ai_agent_task.status is '状态（0启用 1停用）';

create unique index if not exists uk_ai_agent_task_name on ai_agent_task (task_name) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_agent_task_run ( id bigint not null, task_id bigint not null, task_name varchar(100), agent_id bigint, session_id bigint, message_id bigint, trigger_type char(1) default '0', status char(1) default '2', start_time timestamp, end_time timestamp, duration_ms bigint, prompt_tokens integer, completion_tokens integer, total_tokens integer, tool_call_count integer default 0, error_msg varchar(1000), remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_agent_task_run primary key (id) );

comment on table ai_agent_task_run is 'AI 定时任务执行记录表（物理追加）';

comment on column ai_agent_task_run.task_id is '所属任务（ai_agent_task.id）';

comment on column ai_agent_task_run.task_name is '任务名称快照';

comment on column ai_agent_task_run.agent_id is '智能体主键快照';

comment on column ai_agent_task_run.session_id is '本次执行产生的会话（ai_session.id）';

comment on column ai_agent_task_run.message_id is '本次执行的助手消息（ai_message.id）';

comment on column ai_agent_task_run.trigger_type is '触发方式（0定时 1手动）';

comment on column ai_agent_task_run.status is '状态（0成功 1失败 2执行中）';

comment on column ai_agent_task_run.start_time is '开始时间';

comment on column ai_agent_task_run.end_time is '结束时间';

comment on column ai_agent_task_run.duration_ms is '执行耗时（毫秒）';

comment on column ai_agent_task_run.total_tokens is '总 Token 数';

comment on column ai_agent_task_run.tool_call_count is '工具调用次数';

comment on column ai_agent_task_run.error_msg is '失败原因';

create index if not exists idx_ai_agent_task_run_session on ai_agent_task_run (session_id);

create index if not exists idx_ai_agent_task_run_task on ai_agent_task_run (task_id, id DESC);

create index if not exists idx_ai_agent_task_run_create_time on ai_agent_task_run (create_time);

create table if not exists ai_usage_stat ( id bigint not null, stat_date date not null, agent_id bigint not null, model_id bigint, user_id bigint not null, usage_type char(1) not null default '0', prompt_tokens bigint default 0, completion_tokens bigint default 0, total_tokens bigint default 0, request_count integer default 0, tool_call_count integer default 0, cost numeric(14, 6) default 0, create_time timestamp, update_time timestamp, constraint pk_ai_usage_stat primary key (id) );

comment on table ai_usage_stat is 'AI 用量统计表（按日期 / 智能体 / 模型 / 用户 / 用量类型聚合）';

comment on column ai_usage_stat.stat_date is '统计日期';

comment on column ai_usage_stat.agent_id is '智能体主键；知识库入库等无智能体归属的平台调用固定为 0';

comment on column ai_usage_stat.usage_type is '用量类型（0对话 1知识库入库）';

comment on column ai_usage_stat.request_count is '请求（对话轮次）数；知识库入库固定为 0';

comment on column ai_usage_stat.tool_call_count is '工具调用次数';

comment on column ai_usage_stat.cost is '费用估算（元；按模型单价计算，未配置单价按 0 计）';

create index if not exists idx_ai_usage_user on ai_usage_stat (user_id, stat_date DESC);

create unique index if not exists uk_ai_usage on ai_usage_stat (stat_date, agent_id, model_id, user_id, usage_type);

create table if not exists ai_daily_digest ( id bigint not null, digest_date date not null, level char(1) default '0', digest_text text, usage_total_tokens bigint default 0, usage_request_count integer default 0, usage_tool_call_count integer default 0, usage_compare_pct numeric(6, 2), quota_usage_pct numeric(6, 2), task_run_count integer default 0, task_fail_count integer default 0, backlog_count integer default 0, backlog_top_node varchar(100), backlog_max_wait_minutes integer, perm_allow_count integer default 0, perm_ask_count integer default 0, perm_deny_count integer default 0, login_success_count integer default 0, login_fail_count integer default 0, login_anomaly_count integer default 0, task_run_id bigint, session_id bigint, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_daily_digest primary key (id), constraint uk_ai_daily_digest_date unique (digest_date) );

comment on table ai_daily_digest is 'AI 平台运行日报表（每天一条；由任务产出投递器写入）';

comment on column ai_daily_digest.digest_date is '日报日期（唯一；重跑按日期 upsert）';

comment on column ai_daily_digest.level is '结论级别（0平稳 1需关注 2需处理）';

comment on column ai_daily_digest.digest_text is '日报正文（人话版；落库保真，便于回看与推送失败补投）';

comment on column ai_daily_digest.usage_total_tokens is '当日总 Token';

comment on column ai_daily_digest.usage_request_count is '当日请求（对话轮次）数';

comment on column ai_daily_digest.usage_tool_call_count is '当日工具调用次数';

comment on column ai_daily_digest.usage_compare_pct is 'Token 环比（%；无上日数据时为 NULL）';

comment on column ai_daily_digest.quota_usage_pct is '配额使用率（%；配额为 0 即不限时为 NULL）';

comment on column ai_daily_digest.task_run_count is '定时任务执行次数';

comment on column ai_daily_digest.task_fail_count is '定时任务失败次数';

comment on column ai_daily_digest.backlog_count is '工作流待办积压条数（按运行中任务计）';

comment on column ai_daily_digest.backlog_top_node is '积压最多的节点名';

comment on column ai_daily_digest.backlog_max_wait_minutes is '最久滞留分钟数';

comment on column ai_daily_digest.perm_allow_count is '权限决策-允许次数';

comment on column ai_daily_digest.perm_ask_count is '权限决策-需确认次数';

comment on column ai_daily_digest.perm_deny_count is '权限决策-拒绝次数';

comment on column ai_daily_digest.login_success_count is '登录成功次数';

comment on column ai_daily_digest.login_fail_count is '登录失败次数';

comment on column ai_daily_digest.login_anomaly_count is '登录异动次数（异地或新 IP）';

comment on column ai_daily_digest.task_run_id is '来源任务执行记录（ai_agent_task_run.id）';

comment on column ai_daily_digest.session_id is '来源会话（ai_session.id）';

create table if not exists ai_permission_decision_log ( id bigint not null, session_id bigint, message_id bigint, agent_id bigint, user_id bigint, tool_code varchar(100), tool_name varchar(100), decision char(1), decision_source varchar(32), matched_rule varchar(64), reason varchar(500), tool_input text, confirmed char(1), confirm_source varchar(16), duration_ms bigint, create_time timestamp, constraint pk_ai_permission_decision_log primary key (id) );

comment on table ai_permission_decision_log is 'AI 工具权限决策日志（ALLOW / ASK / DENY 全量留痕）';

comment on column ai_permission_decision_log.decision is '决策（0允许 1需确认 2禁止）';

comment on column ai_permission_decision_log.decision_source is '决策来源（agent_tool 显式策略 / remember 记住规则 / global_mode 全局模式 / fallback 兜底）';

comment on column ai_permission_decision_log.confirmed is '人工确认结果（0拒绝 1确认；非 ASK 决策为空）';

comment on column ai_permission_decision_log.confirm_source is '确认作用范围（once 仅本次 / remember 记住该工具）';

create index if not exists idx_ai_perm_log_session on ai_permission_decision_log (session_id, message_id);

create index if not exists idx_ai_perm_log_tool on ai_permission_decision_log (tool_code, decision);

create index if not exists idx_ai_perm_log_user on ai_permission_decision_log (user_id, create_time DESC);

create index if not exists idx_ai_perm_log_create_time on ai_permission_decision_log (create_time);

create table if not exists ai_trace ( id bigint not null, session_id bigint not null, message_id bigint, agent_id bigint not null, source varchar(8) default 'chat', user_id bigint, model_id bigint, model_code varchar(200), model_provider varchar(64), summary_model_code varchar(200), memory_model_code varchar(200), subagent_model_code varchar(200), context_json text, transitions_json text, status varchar(16) default 'running', duration_ms bigint, tool_call_count integer, prompt_tokens integer, completion_tokens integer, total_tokens integer, error_msg varchar(1000), create_time timestamp, update_time timestamp, parent_trace_id bigint, constraint pk_ai_trace primary key (id) );

comment on table ai_trace is 'AI 链路追踪表（一次任务一条：执行期模型版本 + 上下文清单 + 状态迁移）';

comment on column ai_trace.session_id is '会话主键（ai_session.id）';

comment on column ai_trace.message_id is '助手消息主键（暂停阶段为待确认消息，恢复段更新同一条）';

comment on column ai_trace.agent_id is '智能体主键（ai_agent.id）';

comment on column ai_trace.source is '任务来源：chat 实时对话 / task 定时任务 / channel 渠道接入';

comment on column ai_trace.user_id is '触发用户（定时任务为运行身份用户）';

comment on column ai_trace.model_id is '执行时使用的主对话模型主键（ai_model_config.id）';

comment on column ai_trace.model_code is '执行时主对话模型标识（快照，事后改配置不改写历史）';

comment on column ai_trace.model_provider is '执行时主对话模型提供商';

comment on column ai_trace.summary_model_code is '执行时压缩摘要模型标识（未配置为空）';

comment on column ai_trace.memory_model_code is '执行时记忆抽取与合并模型标识（未配置为空）';

comment on column ai_trace.subagent_model_code is '执行时子智能体默认模型标识（未配置为空）';

comment on column ai_trace.context_json is '上下文清单 JSON（工具白名单 / 知识库 / 技能 / 子智能体 / MCP / 各运行期开关）';

comment on column ai_trace.transitions_json is '状态迁移 JSON 数组：[{at, state, detail}]';

comment on column ai_trace.status is '链路状态：running / pending_confirm / success / failed / aborted';

comment on column ai_trace.duration_ms is '总耗时（毫秒，含恢复段则累加）';

comment on column ai_trace.tool_call_count is '工具调用次数';

comment on column ai_trace.parent_trace_id is '父链路（编排链路为父、每次专家调用为子；null 表示顶层）';

create index if not exists idx_ai_trace_agent on ai_trace (agent_id, create_time DESC);

create index if not exists idx_ai_trace_message on ai_trace (message_id);

create index if not exists idx_ai_trace_parent on ai_trace (parent_trace_id);

create index if not exists idx_ai_trace_session on ai_trace (session_id);

create index if not exists idx_ai_trace_create_time on ai_trace (create_time);

create table if not exists ai_scenario_pack ( id bigint not null, pack_code varchar(64) not null, pack_name varchar(100) not null, description varchar(500), visible_scope char(1) not null default '0', owner_dept_id bigint, status char(1) not null default '0', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_scenario_pack primary key (id) );

comment on table ai_scenario_pack is 'AI 场景包定义表（按角色 / 部门打包业务工具 + MCP 工具 + 技能，对话时选用以增强能力）';

comment on column ai_scenario_pack.pack_code is '场景包编码（唯一，创建后不可修改）';

comment on column ai_scenario_pack.pack_name is '场景包名称（如「财务场景包」「人力场景包」）';

comment on column ai_scenario_pack.description is '用途说明（创建任务时供用户判断该选哪个包）';

comment on column ai_scenario_pack.visible_scope is '生效范围（0 公开 / 1 本部门 / 2 仅本人）';

comment on column ai_scenario_pack.owner_dept_id is '归属部门（visible_scope=1 时比较该列）';

comment on column ai_scenario_pack.status is '状态（0 启用 1 停用）';

create unique index if not exists uk_ai_scenario_pack_code on ai_scenario_pack (pack_code) WHERE (del_flag = '0'::bpchar);

create table if not exists ai_scenario_pack_tool ( id bigint not null, pack_id bigint not null, tool_code varchar(64) not null, decision char(1) not null default '1', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_scenario_pack_tool primary key (id) );

comment on table ai_scenario_pack_tool is '场景包 × 业务工具（含授权决策，叠加到智能体既有绑定之上）';

comment on column ai_scenario_pack_tool.tool_code is '业务工具编码（ai_tool.tool_code）';

comment on column ai_scenario_pack_tool.decision is '授权决策（0 允许 / 1 需确认 / 2 禁止），同一工具以包内决策优先';

create unique index if not exists uk_ai_scenario_pack_tool on ai_scenario_pack_tool (pack_id, tool_code);

create table if not exists ai_scenario_pack_mcp_tool ( id bigint not null, pack_id bigint not null, mcp_client_id bigint not null, tool_name varchar(200) not null, decision char(1) not null default '1', remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_scenario_pack_mcp_tool primary key (id) );

comment on table ai_scenario_pack_mcp_tool is '场景包 × 外部 MCP 工具（仅装配包内勾选项；未选包的会话不接入 MCP）';

comment on column ai_scenario_pack_mcp_tool.mcp_client_id is 'MCP 连接主键（ai_mcp_client.id）';

comment on column ai_scenario_pack_mcp_tool.tool_name is '远端自声明的工具名（即 ai_mcp_client.enable_tools 的白名单口径；注册进工具箱时会被归一化重命名）';

comment on column ai_scenario_pack_mcp_tool.decision is '授权决策（0 允许 / 1 需确认 / 2 禁止）';

create unique index if not exists uk_ai_scenario_pack_mcp_tool on ai_scenario_pack_mcp_tool (pack_id, mcp_client_id, tool_name);

create table if not exists ai_scenario_pack_skill ( id bigint not null, pack_id bigint not null, skill_id bigint not null, remark varchar(500), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, constraint pk_ai_scenario_pack_skill primary key (id) );

comment on table ai_scenario_pack_skill is '场景包 × 技能（叠加到智能体既有技能绑定之上，仍受技能生效范围过滤）';

comment on column ai_scenario_pack_skill.skill_id is '技能主键（ai_skill.id）';

create unique index if not exists uk_ai_scenario_pack_skill on ai_scenario_pack_skill (pack_id, skill_id);

create table if not exists ai_sensitive_word ( id bigint not null, word varchar(200) not null, is_regex char(1) default '0', category varchar(50), enabled char(1) default '1', remark varchar(255), create_dept bigint, create_by bigint, create_time timestamp, update_by bigint, update_time timestamp, del_flag char(1) default '0', constraint pk_ai_sensitive_word primary key (id) );

comment on table ai_sensitive_word is 'AI 敏感词库（支持普通词与正则，供输入 / 输出 / 渠道出站安全校验）';

comment on column ai_sensitive_word.word is '敏感词或正则表达式';

comment on column ai_sensitive_word.is_regex is '是否正则：0否（子串匹配） 1是';

comment on column ai_sensitive_word.category is '分类（可空，供分组管理与统计）';

comment on column ai_sensitive_word.enabled is '是否启用：1启用 0禁用';

create index if not exists idx_ai_sensitive_word_enabled on ai_sensitive_word (del_flag, enabled);

create table if not exists ai_safety_event ( id bigint not null, scene varchar(20), action varchar(20), engine varchar(20), session_id bigint, message_id bigint, agent_id bigint, user_id bigint, hit_count integer, hits_json text, text_excerpt varchar(255), create_time timestamp, constraint pk_ai_safety_event primary key (id) );

comment on table ai_safety_event is 'AI 安全事件留痕（敏感词 / PII 命中与处置，追加型明细）';

comment on column ai_safety_event.scene is '场景（input 用户输入 / output 模型输出 / channel_out 渠道出站）';

comment on column ai_safety_event.action is '处置动作（log 只记录 / mask 脱敏放行 / block 阻断）';

comment on column ai_safety_event.engine is '命中引擎（word 敏感词 / pii 隐私信息）';

comment on column ai_safety_event.hit_count is '命中项数量';

comment on column ai_safety_event.hits_json is '命中项 JSON 数组（命中词 / PII 类型）';

comment on column ai_safety_event.text_excerpt is '触发文本摘要（截断至 200 字符）';

create index if not exists idx_ai_safety_event_create_time on ai_safety_event (create_time);

create index if not exists idx_ai_safety_event_scene on ai_safety_event (scene);
