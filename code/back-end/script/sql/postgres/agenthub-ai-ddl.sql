-- AgentHub AI 模块建表脚本（PostgreSQL）
-- 40 张 ai_ 表，全部语句幂等，可重复执行。列名与 MySQL 版严格一一对应。
-- 审计口径：业务表带 del_flag 软删；关联表与审计/日志表为物理删（§12.4 清理不引入软删）。

create table if not exists ai_model_config (
    id              int8            not null,
    model_code      varchar(64)     not null,
    model_name      varchar(128)    not null,
    provider        varchar(64),
    model_type      varchar(32)     not null,
    base_url        varchar(512),
    api_key_cipher  varchar(1024),
    temperature     numeric(4,2),
    max_tokens      int4,
    context_length  int4,
    param_json      text,
    status          char(1)         not null default '0',
    remark          varchar(500),
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    del_flag        char(1)         not null default '0',
    constraint pk_ai_model_config primary key (id)
);
comment on table ai_model_config is '模型配置：对话/向量/重排/视觉四类接入';
create index if not exists idx_ai_model_config_type on ai_model_config (model_type, status);
create index if not exists idx_ai_model_config_code on ai_model_config (model_code);

create table if not exists ai_knowledge_base (
    id              int8            not null,
    kb_code         varchar(64)     not null,
    kb_name         varchar(128)    not null,
    description     varchar(500),
    model_id        int8,
    vector_dim      int4,
    chunk_strategy  varchar(32),
    chunk_size      int4,
    chunk_overlap   int4,
    separators      varchar(255),
    retrieval_mode  varchar(16)     not null default 'vector',
    rerank_enabled  char(1)         not null default '0',
    rerank_model_id int8,
    top_k           int4,
    score_threshold numeric(6,4),
    bm25_status     varchar(16)     not null default 'NOT_BUILT',
    bm25_error      varchar(500),
    bm25_time       timestamp,
    scope_type      varchar(16)     not null default 'public',
    scope_dept      varchar(500),
    status          char(1)         not null default '0',
    remark          varchar(500),
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    del_flag        char(1)         not null default '0',
    constraint pk_ai_knowledge_base primary key (id)
);
comment on table ai_knowledge_base is '知识库定义：向量模型、切分策略、检索方式与 BM25 索引状态';
create index if not exists idx_ai_knowledge_base_code on ai_knowledge_base (kb_code);
create index if not exists idx_ai_knowledge_base_scope on ai_knowledge_base (scope_type, status);

create table if not exists ai_document (
    id              int8            not null,
    kb_id           int8            not null,
    doc_name        varchar(255)    not null,
    oss_id          int8,
    file_suffix     varchar(32),
    file_size       int8,
    parse_status    varchar(16)     not null default 'PENDING',
    vector_status   varchar(16)     not null default 'PENDING',
    chunk_count     int4            not null default 0,
    error_msg       varchar(1000),
    meta_json       text,
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    del_flag        char(1)         not null default '0',
    constraint pk_ai_document primary key (id)
);
comment on table ai_document is '知识库文档：解析与向量化状态、切片计数';
create index if not exists idx_ai_document_kb on ai_document (kb_id, del_flag);
create index if not exists idx_ai_document_vector on ai_document (vector_status);

create table if not exists ai_chunk (
    id              int8            not null,
    kb_id           int8            not null,
    doc_id          int8            not null,
    chunk_index     int4            not null default 0,
    content         text,
    vector_id       varchar(64),
    token_count     int4,
    position_info   varchar(500),
    meta_json       text,
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    del_flag        char(1)         not null default '0',
    constraint pk_ai_chunk primary key (id)
);
comment on table ai_chunk is '文档切片：正文、向量引用与位置元数据';
create index if not exists idx_ai_chunk_doc on ai_chunk (doc_id, chunk_index);
create index if not exists idx_ai_chunk_kb on ai_chunk (kb_id);

create table if not exists ai_entry_agent (
    id                   int8        not null,
    only_row_flag        varchar(1)  not null default '1',
    agent_code           varchar(64) not null,
    agent_name           varchar(128) not null,
    description          varchar(500),
    system_prompt        text,
    model_id             int8,
    slot_compress_model_id   int8,
    slot_memory_model_id     int8,
    slot_subagent_model_id   int8,
    max_iterations       int4        not null default 8,
    quota_daily_tokens   int8,
    quota_daily_requests int4,
    dept_id              int8,
    scope_type           varchar(16) not null default 'public',
    scope_dept           varchar(500),
    status               char(1)     not null default '0',
    remark               varchar(500),
    create_dept          int8,
    create_by            int8,
    create_time          timestamp,
    update_by            int8,
    update_time          timestamp,
    del_flag             char(1)     not null default '0',
    constraint pk_ai_entry_agent primary key (id)
);
comment on table ai_entry_agent is '入口智能体小Z：全平台唯一单行配置';
create unique index if not exists uk_ai_entry_agent_only_row on ai_entry_agent (only_row_flag);

create table if not exists ai_expert_agent (
    id                   int8        not null,
    agent_code           varchar(64) not null,
    agent_name           varchar(128) not null,
    description          varchar(500),
    system_prompt        text,
    model_id             int8,
    slot_compress_model_id   int8,
    slot_memory_model_id     int8,
    slot_subagent_model_id   int8,
    prompt_template      varchar(64),
    auto_route           char(1)     not null default '1',
    max_iterations       int4        not null default 8,
    token_budget         int8,
    wall_clock_seconds   int4,
    dept_id              int8,
    scope_type           varchar(16) not null default 'public',
    scope_dept           varchar(500),
    sort_num             int4        not null default 0,
    status               char(1)     not null default '0',
    remark               varchar(500),
    create_dept          int8,
    create_by            int8,
    create_time          timestamp,
    update_by            int8,
    update_time          timestamp,
    del_flag             char(1)     not null default '0',
    constraint pk_ai_expert_agent primary key (id)
);
comment on table ai_expert_agent is '领域专家配置：提示词、模型槽位、预算与可见范围';
create index if not exists idx_ai_expert_agent_code on ai_expert_agent (agent_code);
create index if not exists idx_ai_expert_agent_route on ai_expert_agent (auto_route, status);

create table if not exists ai_agent_tool (
    id          int8    not null,
    agent_id    int8    not null,
    tool_id     int8    not null,
    decision    char(1) not null default '0',
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_agent_tool primary key (id)
);
comment on table ai_agent_tool is '智能体与工具绑定及三档授权：0 允许 1 需确认 2 禁止';
create unique index if not exists uk_ai_agent_tool on ai_agent_tool (agent_id, tool_id);

create table if not exists ai_agent_tool_trust (
    id          int8    not null,
    agent_id    int8    not null,
    tool_id     int8    not null,
    user_id     int8    not null,
    session_id  int8    not null,
    trusted     char(1) not null default '1',
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_agent_tool_trust primary key (id)
);
comment on table ai_agent_tool_trust is '用户级"记住该工具"信任槽位，按用户与会话隔离';
create unique index if not exists uk_ai_agent_tool_trust on ai_agent_tool_trust (agent_id, tool_id, user_id, session_id);
create index if not exists idx_ai_agent_tool_trust_user on ai_agent_tool_trust (user_id, session_id);

create table if not exists ai_agent_kb (
    id          int8    not null,
    agent_id    int8    not null,
    kb_id       int8    not null,
    sort_num    int4    not null default 0,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_agent_kb primary key (id)
);
comment on table ai_agent_kb is '智能体与知识库绑定';
create unique index if not exists uk_ai_agent_kb on ai_agent_kb (agent_id, kb_id);

create table if not exists ai_agent_skill (
    id          int8    not null,
    agent_id    int8    not null,
    skill_id    int8    not null,
    sort_num    int4    not null default 0,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_agent_skill primary key (id)
);
comment on table ai_agent_skill is '智能体与技能绑定';
create unique index if not exists uk_ai_agent_skill on ai_agent_skill (agent_id, skill_id);

create table if not exists ai_agent_subagent (
    id          int8    not null,
    agent_id    int8    not null,
    subagent_id int8    not null,
    sort_num    int4    not null default 0,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_agent_subagent primary key (id)
);
comment on table ai_agent_subagent is '智能体与子智能体多对多绑定';
create unique index if not exists uk_ai_agent_subagent on ai_agent_subagent (agent_id, subagent_id);

create table if not exists ai_session (
    id                  int8        not null,
    session_uuid        varchar(64) not null,
    title               varchar(255),
    user_id             int8        not null,
    dept_id             int8,
    agent_id            int8,
    source_type         varchar(16) not null default 'web',
    status              varchar(20) not null default 'IDLE',
    pinned              char(1)     not null default '0',
    resource_signature  varchar(128),
    message_count       int4        not null default 0,
    last_message_time   timestamp,
    current_turn_id     int8,
    remark              varchar(500),
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    del_flag            char(1)     not null default '0',
    constraint pk_ai_session primary key (id)
);
comment on table ai_session is '会话主表：状态 RUNNING/AWAITING_CONFIRM/IDLE/STOPPED';
create unique index if not exists uk_ai_session_uuid on ai_session (session_uuid);
create index if not exists idx_ai_session_user on ai_session (user_id, del_flag, pinned, last_message_time);
create index if not exists idx_ai_session_status on ai_session (status, update_time);

create table if not exists ai_session_resource (
    id            int8        not null,
    session_id    int8        not null,
    resource_type varchar(32) not null,
    resource_id   int8        not null,
    resource_name varchar(128),
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    constraint pk_ai_session_resource primary key (id)
);
comment on table ai_session_resource is '会话级资源集：场景包/技能/MCP 工具/专家选中项';
create unique index if not exists uk_ai_session_resource on ai_session_resource (session_id, resource_type, resource_id);

create table if not exists ai_message (
    id              int8        not null,
    session_id      int8        not null,
    turn_id         int8        not null,
    seq             int4        not null default 0,
    sender_role     varchar(16) not null,
    message_type    varchar(32) not null default 'text',
    content         text,
    event_snapshot  text,
    agent_id        int8,
    model_id        int8,
    status          varchar(16) not null default 'OK',
    reason_code     varchar(64),
    token_count     int4,
    duration_ms     int8,
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    del_flag        char(1)     not null default '0',
    constraint pk_ai_message primary key (id)
);
comment on table ai_message is '会话消息：正文与编排事件快照，快照支撑确认卡片刷新还原';
create index if not exists idx_ai_message_session on ai_message (session_id, turn_id, seq);
create index if not exists idx_ai_message_turn on ai_message (turn_id);

create table if not exists ai_message_attachment (
    id          int8        not null,
    session_id  int8,
    message_id  int8,
    uploader_id int8        not null,
    file_name   varchar(255) not null,
    file_suffix varchar(32),
    modality    varchar(16) not null,
    size_bytes  int8,
    oss_id      int8,
    status      varchar(16) not null default 'PENDING',
    expire_at   timestamp,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    del_flag    char(1)     not null default '0',
    constraint pk_ai_message_attachment primary key (id)
);
comment on table ai_message_attachment is '消息附件：PENDING/BOUND/ORPHANED/RECYCLED 生命周期与到期回收';
create index if not exists idx_ai_attachment_owner on ai_message_attachment (uploader_id, status);
create index if not exists idx_ai_attachment_expire on ai_message_attachment (status, expire_at);
create index if not exists idx_ai_attachment_message on ai_message_attachment (message_id);

create table if not exists ai_tool (
    id                  int8        not null,
    tool_code           varchar(64) not null,
    tool_name           varchar(128) not null,
    tool_type           varchar(16) not null default 'READ',
    impl_name           varchar(128) not null,
    param_schema        text,
    category            varchar(64),
    builtin             char(1)     not null default '0',
    mcp_exposed         char(1)     not null default '0',
    unattended_allowed  char(1)     not null default '0',
    status              char(1)     not null default '0',
    description         varchar(1000),
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    del_flag            char(1)     not null default '0',
    constraint pk_ai_tool primary key (id)
);
comment on table ai_tool is '业务工具元数据：impl_name 是元数据与代码工厂之间唯一引用面';
create index if not exists idx_ai_tool_code on ai_tool (tool_code);
create index if not exists idx_ai_tool_impl on ai_tool (impl_name);
create index if not exists idx_ai_tool_type on ai_tool (tool_type, status);

create table if not exists ai_tool_tag (
    id          int8        not null,
    tag_name    varchar(64) not null,
    sort_num    int4        not null default 0,
    status      char(1)     not null default '0',
    remark      varchar(500),
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    del_flag    char(1)     not null default '0',
    constraint pk_ai_tool_tag primary key (id)
);
comment on table ai_tool_tag is '工具标签，名称唯一由服务层按未删记录复核';
create index if not exists idx_ai_tool_tag_name on ai_tool_tag (tag_name);

create table if not exists ai_tool_tag_rel (
    id          int8    not null,
    tag_id      int8    not null,
    tool_id     int8    not null,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_tool_tag_rel primary key (id)
);
comment on table ai_tool_tag_rel is '工具与标签关联';
create unique index if not exists uk_ai_tool_tag_rel on ai_tool_tag_rel (tag_id, tool_id);
create index if not exists idx_ai_tool_tag_rel_tool on ai_tool_tag_rel (tool_id);

create table if not exists ai_mcp_client (
    id                int8        not null,
    client_name       varchar(128) not null,
    transport         varchar(32) not null,
    endpoint          varchar(512),
    headers_cipher    text,
    tool_allowlist    text,
    timeout_seconds   int4        not null default 30,
    tool_names        text,
    description       varchar(500),
    last_test_time    timestamp,
    last_test_result  varchar(16),
    status            char(1)     not null default '0',
    create_dept       int8,
    create_by         int8,
    create_time       timestamp,
    update_by         int8,
    update_time       timestamp,
    del_flag          char(1)     not null default '0',
    constraint pk_ai_mcp_client primary key (id)
);
comment on table ai_mcp_client is '外部 MCP 客户端：传输、白名单与密文请求头';
create index if not exists idx_ai_mcp_client_name on ai_mcp_client (client_name);

create table if not exists ai_skill (
    id          int8        not null,
    skill_code  varchar(64) not null,
    skill_name  varchar(128) not null,
    description varchar(500),
    skill_md    text,
    builtin     char(1)     not null default '0',
    status      char(1)     not null default '0',
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    del_flag    char(1)     not null default '0',
    constraint pk_ai_skill primary key (id)
);
comment on table ai_skill is '技能仓库：SKILL.md 正文按需加载';
create index if not exists idx_ai_skill_code on ai_skill (skill_code);

create table if not exists ai_skill_resource (
    id            int8        not null,
    skill_id      int8        not null,
    resource_path varchar(255) not null,
    resource_type varchar(32),
    content_text  text,
    oss_id        int8,
    size_bytes    int8,
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    del_flag      char(1)     not null default '0',
    constraint pk_ai_skill_resource primary key (id)
);
comment on table ai_skill_resource is '技能参考资料';
create unique index if not exists uk_ai_skill_resource on ai_skill_resource (skill_id, resource_path);

create table if not exists ai_subagent (
    id             int8        not null,
    subagent_code  varchar(64) not null,
    subagent_name  varchar(128) not null,
    description    varchar(500),
    workspace_mode varchar(16) not null default 'ISOLATED',
    model_id       int8,
    max_steps      int4        not null default 20,
    tool_allowlist text,
    status         char(1)     not null default '0',
    create_dept    int8,
    create_by      int8,
    create_time    timestamp,
    update_by      int8,
    update_time    timestamp,
    del_flag       char(1)     not null default '0',
    constraint pk_ai_subagent primary key (id)
);
comment on table ai_subagent is '子智能体：工作区隔离模式、步数上限与只读工具白名单';
create index if not exists idx_ai_subagent_code on ai_subagent (subagent_code);

create table if not exists ai_channel (
    id                int8        not null,
    channel_type      varchar(32) not null,
    channel_name      varchar(128) not null,
    app_id            varchar(128),
    credential_cipher text,
    callback_path     varchar(255),
    connect_mode      varchar(16) not null default 'WEBHOOK',
    connection_state  varchar(16) not null default 'UNKNOWN',
    last_online_time  timestamp,
    status            char(1)     not null default '0',
    remark            varchar(500),
    create_dept       int8,
    create_by         int8,
    create_time       timestamp,
    update_by         int8,
    update_time       timestamp,
    del_flag          char(1)     not null default '0',
    constraint pk_ai_channel primary key (id)
);
comment on table ai_channel is '渠道接入配置：飞书/企微/钉钉/GitHub，凭据密文落库';
create index if not exists idx_ai_channel_type on ai_channel (channel_type, status);

create table if not exists ai_channel_identity (
    id                int8        not null,
    channel_id        int8        not null,
    external_user_id  varchar(128) not null,
    external_union_id varchar(128),
    external_name     varchar(128),
    user_id           int8,
    bind_time         timestamp,
    create_dept       int8,
    create_by         int8,
    create_time       timestamp,
    update_by         int8,
    update_time       timestamp,
    constraint pk_ai_channel_identity primary key (id)
);
comment on table ai_channel_identity is '外部身份到平台账号映射';
create unique index if not exists uk_ai_channel_identity on ai_channel_identity (channel_id, external_user_id);
create index if not exists idx_ai_channel_identity_user on ai_channel_identity (user_id);

create table if not exists ai_channel_thread (
    id                 int8        not null,
    channel_id         int8        not null,
    external_thread_id varchar(128) not null,
    external_user_id   varchar(128),
    session_id         int8        not null,
    last_message_time  timestamp,
    create_dept        int8,
    create_by          int8,
    create_time        timestamp,
    update_by          int8,
    update_time        timestamp,
    constraint pk_ai_channel_thread primary key (id)
);
comment on table ai_channel_thread is '外部消息线程到平台会话映射';
create unique index if not exists uk_ai_channel_thread on ai_channel_thread (channel_id, external_thread_id);

create table if not exists ai_agent_task (
    id                  int8        not null,
    task_name           varchar(128) not null,
    agent_id            int8        not null,
    run_as_user_id      int8        not null,
    cron_expression     varchar(64) not null,
    timezone            varchar(64) not null default 'Asia/Shanghai',
    prompt              text,
    quota_daily_tokens  int8,
    last_run_time       timestamp,
    next_run_time       timestamp,
    status              char(1)     not null default '0',
    remark              varchar(500),
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    del_flag            char(1)     not null default '0',
    constraint pk_ai_agent_task primary key (id)
);
comment on table ai_agent_task is '定时任务定义，last_run_time 供 60 秒派发原子抢占去重';
create index if not exists idx_ai_agent_task_next on ai_agent_task (status, next_run_time);
create index if not exists idx_ai_agent_task_last on ai_agent_task (last_run_time);

create table if not exists ai_agent_task_run (
    id              int8        not null,
    task_id         int8        not null,
    session_id      int8,
    run_status      varchar(16) not null default 'RUNNING',
    trigger_type    varchar(16) not null default 'CRON',
    start_time      timestamp,
    end_time        timestamp,
    duration_ms     int8,
    token_count     int4,
    tool_call_count int4,
    error_msg       varchar(1000),
    operator_id     int8,
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    constraint pk_ai_agent_task_run primary key (id)
);
comment on table ai_agent_task_run is '定时任务执行记录';
create index if not exists idx_ai_agent_task_run_task on ai_agent_task_run (task_id, start_time);

create table if not exists ai_usage_stat (
    id                int8    not null,
    stat_date         date    not null,
    agent_id          int8    not null default 0,
    model_id          int8    not null default 0,
    prompt_tokens     int8    not null default 0,
    completion_tokens int8    not null default 0,
    total_tokens      int8    not null default 0,
    request_count     int8    not null default 0,
    cost_amount       numeric(18,6) not null default 0,
    create_dept       int8,
    create_by         int8,
    create_time       timestamp,
    update_by         int8,
    update_time       timestamp,
    constraint pk_ai_usage_stat primary key (id)
);
comment on table ai_usage_stat is '按日期、智能体、模型聚合的用量与费用';
create unique index if not exists uk_ai_usage_stat on ai_usage_stat (stat_date, agent_id, model_id);

create table if not exists ai_daily_digest (
    id            int8        not null,
    digest_date   date        not null,
    title         varchar(255),
    content       text,
    block_json    text,
    source_ids    text,
    model_id      int8,
    generate_time timestamp,
    status        varchar(16) not null default 'PENDING',
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    constraint pk_ai_daily_digest primary key (id)
);
comment on table ai_daily_digest is '平台运行日报：五块快照按日期 upsert';
create unique index if not exists uk_ai_daily_digest_date on ai_daily_digest (digest_date);

create table if not exists ai_tool_call_log (
    id              int8        not null,
    session_id      int8,
    turn_id         int8,
    message_id      int8,
    agent_id        int8,
    tool_id         int8,
    tool_code       varchar(64) not null,
    tool_type       varchar(16),
    user_id         int8,
    trigger_source  varchar(16) not null default 'chat',
    decision        varchar(16) not null default 'ALLOW',
    confirm_result  varchar(24),
    status          varchar(16) not null default 'PENDING',
    duration_ms     int8,
    input_json      text,
    output_summary  text,
    output_oss_id   int8,
    idempotency_key varchar(64),
    reason_code     varchar(64),
    error_msg       varchar(1000),
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    constraint pk_ai_tool_call_log primary key (id)
);
comment on table ai_tool_call_log is '工具调用明细，按创建时间索引支撑保留清理';
create index if not exists idx_ai_tool_call_log_tool on ai_tool_call_log (tool_code, create_time);
create index if not exists idx_ai_tool_call_log_session on ai_tool_call_log (session_id, turn_id);
create index if not exists idx_ai_tool_call_log_create on ai_tool_call_log (create_time);
create index if not exists idx_ai_tool_call_log_idem on ai_tool_call_log (idempotency_key);

create table if not exists ai_route_run (
    id                  int8        not null,
    session_id          int8        not null,
    turn_id             int8        not null,
    user_id             int8,
    entry_agent_id      int8,
    action              varchar(16) not null default 'SELF',
    target_agent_id     int8,
    task_brief          text,
    reason              text,
    decision_raw        text,
    status              varchar(16) not null default 'SUCCESS',
    degrade_reason_code varchar(64),
    expert_count        int4        not null default 0,
    duration_ms         int8,
    token_count         int8,
    terminated_flag     char(1)     not null default '0',
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    constraint pk_ai_route_run primary key (id)
);
comment on table ai_route_run is '入口智能体每轮编排运行记录：动作、目标专家、决策原文';
create index if not exists idx_ai_route_run_turn on ai_route_run (session_id, turn_id);
create index if not exists idx_ai_route_run_create on ai_route_run (create_time);

create table if not exists ai_route_expert_call (
    id              int8        not null,
    route_run_id    int8        not null,
    agent_id        int8        not null,
    seq             int4        not null default 0,
    status          varchar(24) not null default 'ANSWERED',
    retry_count     int4        not null default 0,
    fail_reason     varchar(1000),
    raw_output      text,
    tool_call_count int4        not null default 0,
    duration_ms     int8,
    token_count     int8,
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    constraint pk_ai_route_expert_call primary key (id)
);
comment on table ai_route_expert_call is '每次专家调用明细，输出原文留证';
create index if not exists idx_ai_route_expert_run on ai_route_expert_call (route_run_id, seq);
create index if not exists idx_ai_route_expert_call_create on ai_route_expert_call (create_time);

create table if not exists ai_permission_decision_log (
    id              int8        not null,
    session_id      int8,
    turn_id         int8,
    tool_id         int8,
    tool_code       varchar(64) not null,
    agent_id        int8,
    user_id         int8,
    decision        varchar(16) not null,
    confirm_result  varchar(24),
    trigger_source  varchar(16) not null default 'chat',
    reason          varchar(1000),
    decide_time     timestamp,
    create_dept     int8,
    create_by       int8,
    create_time     timestamp,
    update_by       int8,
    update_time     timestamp,
    constraint pk_ai_permission_decision_log primary key (id)
);
comment on table ai_permission_decision_log is 'ALLOW/ASK/DENY 权限决策与人工确认处置留痕';
create index if not exists idx_ai_permission_log_tool on ai_permission_decision_log (tool_code, create_time);
create index if not exists idx_ai_permission_log_session on ai_permission_decision_log (session_id, turn_id);
create index if not exists idx_ai_permission_log_create on ai_permission_decision_log (create_time);

create table if not exists ai_trace (
    id                  int8        not null,
    session_id          int8        not null,
    turn_id             int8        not null,
    user_id             int8,
    entry_agent_id      int8,
    agent_id            int8,
    model_snapshot      text,
    slot_fallback       varchar(500),
    context_manifest    text,
    assemble_fingerprint varchar(128),
    state_timeline      text,
    status              varchar(16) not null default 'RUNNING',
    start_time          timestamp,
    end_time            timestamp,
    duration_ms         int8,
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    constraint pk_ai_trace primary key (id)
);
comment on table ai_trace is '链路追踪：一轮任务一条，含模型版本、上下文清单与状态迁移时间线';
create unique index if not exists uk_ai_trace_turn on ai_trace (turn_id);
create index if not exists idx_ai_trace_session on ai_trace (session_id);
create index if not exists idx_ai_trace_create on ai_trace (create_time);

create table if not exists ai_scenario_pack (
    id          int8        not null,
    pack_code   varchar(64) not null,
    pack_name   varchar(128) not null,
    description varchar(500),
    dept_id     int8,
    scope_type  varchar(16) not null default 'self',
    scope_dept  varchar(500),
    sort_num    int4        not null default 0,
    status      char(1)     not null default '0',
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    del_flag    char(1)     not null default '0',
    constraint pk_ai_scenario_pack primary key (id)
);
comment on table ai_scenario_pack is '场景包本体：按角色或部门打包工具、外部 MCP 工具与技能';
create index if not exists idx_ai_scenario_pack_code on ai_scenario_pack (pack_code);

create table if not exists ai_scenario_pack_tool (
    id          int8    not null,
    pack_id     int8    not null,
    tool_id     int8    not null,
    decision    char(1) not null default '0',
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_scenario_pack_tool primary key (id)
);
comment on table ai_scenario_pack_tool is '场景包内业务工具绑定，包内决策优先于智能体绑定';
create unique index if not exists uk_ai_scenario_pack_tool on ai_scenario_pack_tool (pack_id, tool_id);

create table if not exists ai_scenario_pack_mcp_tool (
    id          int8        not null,
    pack_id     int8        not null,
    client_id   int8        not null,
    tool_name   varchar(128) not null,
    decision    char(1)     not null default '0',
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_scenario_pack_mcp_tool primary key (id)
);
comment on table ai_scenario_pack_mcp_tool is '场景包内外部 MCP 工具白名单绑定';
create unique index if not exists uk_ai_scenario_pack_mcp_tool on ai_scenario_pack_mcp_tool (pack_id, client_id, tool_name);

create table if not exists ai_scenario_pack_skill (
    id          int8    not null,
    pack_id     int8    not null,
    skill_id    int8    not null,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_scenario_pack_skill primary key (id)
);
comment on table ai_scenario_pack_skill is '场景包内技能绑定';
create unique index if not exists uk_ai_scenario_pack_skill on ai_scenario_pack_skill (pack_id, skill_id);

create table if not exists ai_sensitive_word (
    id           int8        not null,
    word_content varchar(255) not null,
    word_type    varchar(16) not null default 'PLAIN',
    category     varchar(64),
    status       char(1)     not null default '0',
    remark       varchar(500),
    create_dept  int8,
    create_by    int8,
    create_time  timestamp,
    update_by    int8,
    update_time  timestamp,
    del_flag     char(1)     not null default '0',
    constraint pk_ai_sensitive_word primary key (id)
);
comment on table ai_sensitive_word is '敏感词库：普通词与正则、分类与启停';
create index if not exists idx_ai_sensitive_word_type on ai_sensitive_word (word_type, status);
create index if not exists idx_ai_sensitive_word_cat on ai_sensitive_word (category);

create table if not exists ai_safety_event (
    id          int8        not null,
    scene       varchar(32) not null,
    hit_type    varchar(16) not null,
    word_id     int8,
    masked_text varchar(500),
    event_action varchar(16) not null,
    engine_type   varchar(16) not null default 'local',
    user_id     int8,
    session_id  int8,
    message_id  int8,
    detail_json text,
    event_time  timestamp,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint pk_ai_safety_event primary key (id)
);
comment on table ai_safety_event is '安全事件命中与处置明细，命中片段先脱敏再落库';
create index if not exists idx_ai_safety_event_scene on ai_safety_event (scene, event_time);
create index if not exists idx_ai_safety_event_create on ai_safety_event (create_time);
