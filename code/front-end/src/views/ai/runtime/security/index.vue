<template>
  <div class="p-2 app-container">
    <el-tabs v-model="activeTab" class="security-tabs">
      <!-- 页签一：内容审核（敏感词 / 正则的检测与处置） -->
      <el-tab-pane label="内容审核" name="content">
        <el-card shadow="hover" class="table-panel" v-loading="loading">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>内容审核</h3>
                <p>
                  对用户输入、模型输出与渠道出站等内容做敏感词 / 正则检测，命中后按动作「只记录 / 脱敏 / 阻断」处理。
                  保存后写入运行期配置，下一次对话 / 编排即按新策略生效。
                </p>
              </div>
              <div class="toolbar-actions">
                <el-button icon="Refresh" @click="loadConfig">刷新</el-button>
                <el-button
                  v-hasPermi="['ai:runtime:security:edit']"
                  type="primary"
                  icon="Check"
                  :loading="saving"
                  @click="handleSave('content')"
                >
                  保存
                </el-button>
              </div>
            </div>
          </template>

          <el-alert
            class="security-alert"
            type="info"
            :closable="false"
            show-icon
            title="内容审核默认关闭；建议先以「只记录」灰度观察，评估误伤后再切「脱敏放行 / 阻断」。与「PII 脱敏」页签相互独立，保存仅提交本页签字段。"
          />

          <el-form ref="formRef" :model="form" :rules="rules" label-width="220px" class="config-form">
            <el-form-item label="启用内容审核" prop="enabled">
              <el-switch v-model="form.enabled" />
              <span class="form-tip">仅控制本页签的敏感词 / 正则检测；不影响「PII 脱敏」与「提示词注入防护」</span>
            </el-form-item>

            <el-form-item label="检测引擎" prop="engine">
              <el-select v-model="form.engine" style="width: 260px">
                <el-option label="本地词库（内置敏感词 / 正则）" value="local" />
                <el-option label="外部 API（调用第三方内容安全接口）" value="external" />
              </el-select>
            </el-form-item>

            <el-form-item label="命中动作" prop="action">
              <el-select v-model="form.action" style="width: 260px">
                <el-option label="只记录（命中不拦截）" value="log" />
                <el-option label="脱敏放行（替换命中片段后放行）" value="mask" />
                <el-option label="阻断（直接拒绝并给出提示）" value="block" />
              </el-select>
            </el-form-item>

            <el-form-item label="用户输入检测" prop="inputCheck">
              <el-switch v-model="form.inputCheck" />
            </el-form-item>
            <el-form-item label="模型输出检测" prop="outputCheck">
              <el-switch v-model="form.outputCheck" />
            </el-form-item>
            <el-form-item label="渠道出站检测" prop="channelOutCheck">
              <el-switch v-model="form.channelOutCheck" />
              <span class="form-tip">回推到飞书 / 企业微信 / GitHub / 钉钉等渠道前先做一次检测</span>
            </el-form-item>

            <el-form-item label="工具结果审核" prop="toolResultMask">
              <template #label>
                <span class="label-tip">
                  工具结果审核
                  <el-tooltip placement="top">
                    <template #content>
                      <div class="tip-content">落库留痕与前端展示，框架内部回填模型不可见。</div>
                    </template>
                    <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <el-switch v-model="form.toolResultMask" />
            </el-form-item>
            <el-form-item label="知识库入库审核" prop="kbIngestCheck">
              <template #label>
                <span class="label-tip">
                  知识库入库审核
                  <el-tooltip placement="top">
                    <template #content>
                      <div class="tip-content">切片送向量库前审核，命中阻断则拒绝入库。</div>
                    </template>
                    <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <el-switch v-model="form.kbIngestCheck" />
            </el-form-item>
            <el-form-item label="MCP 出口审核" prop="mcpOutCheck">
              <template #label>
                <span class="label-tip">
                  MCP 出口审核
                  <el-tooltip placement="top">
                    <template #content>
                      <div class="tip-content">对外暴露给第三方 AI 的只读工具入参 / 出参。</div>
                    </template>
                    <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <el-switch v-model="form.mcpOutCheck" />
            </el-form-item>

            <el-form-item label="提示词注入防护" prop="promptHardening">
              <template #label>
                <span class="label-tip">
                  提示词注入防护
                  <el-tooltip placement="top">
                    <template #content>
                      <div class="tip-content">system prompt 加固 + 外部内容 &lt;untrusted&gt; 边界标记。</div>
                    </template>
                    <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <el-switch v-model="form.promptHardening" />
              <span class="form-tip">同样不受「启用内容审核」控制</span>
            </el-form-item>

            <el-form-item label="流式回退字符数" prop="holdBackChars">
              <template #label>
                <span class="label-tip">
                  流式回退字符数
                  <el-tooltip placement="top">
                    <template #content>
                      <div class="tip-content">流式输出先缓冲 N 个字符再判定，避免命中片段被提前放出；0 表示不缓冲。</div>
                    </template>
                    <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
              </template>
              <el-input-number
                v-model="form.holdBackChars"
                :min="0"
                :step="10"
                controls-position="right"
                style="width: 220px"
              />
              <span class="form-tip">0 表示不缓冲</span>
            </el-form-item>
            <el-form-item label="命中留证片段字符数" prop="excerptChars">
              <el-input-number
                v-model="form.excerptChars"
                :min="10"
                :step="10"
                controls-position="right"
                style="width: 220px"
              />
              <span class="form-tip">安全事件中保留的命中原文长度</span>
            </el-form-item>

            <template v-if="form.engine === 'external'">
              <el-form-item label="外部引擎接口地址" prop="providerEndpoint">
                <el-input
                  v-model="form.providerEndpoint"
                  placeholder="https://example.com/api/moderate"
                  style="width: 420px"
                />
              </el-form-item>
              <el-form-item label="外部引擎接口密钥" prop="providerApiKey">
                <el-input
                  v-model="form.providerApiKey"
                  type="password"
                  show-password
                  :placeholder="keyMask ? '留空表示不修改已保存的密钥' : '请输入接口密钥'"
                  style="width: 420px"
                />
                <span class="form-tip">
                  {{ keyMask ? `已配置：${keyMask}（留空提交＝不修改）` : '当前未配置密钥，该引擎将降级为本地词库' }}
                </span>
              </el-form-item>
            </template>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- 页签二：PII 脱敏（独立开关，不受「启用内容审核」控制） -->
      <el-tab-pane label="PII 脱敏" name="pii">
        <el-card shadow="hover" class="table-panel" v-loading="loading">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>PII 脱敏</h3>
                <p>
                  对会外流或落库的文本做掩码脱敏（手机号 / 身份证 / 邮箱 / 银行卡 / IP）。
                  <b>独立开关</b>，不受「内容审核」页签影响。
                </p>
              </div>
              <div class="toolbar-actions">
                <el-button
                  v-hasPermi="['ai:runtime:security:edit']"
                  type="primary"
                  icon="Check"
                  :loading="saving"
                  @click="handleSave('pii')"
                >
                  保存
                </el-button>
              </div>
            </div>
          </template>

          <el-form :model="form" label-width="220px" class="config-form">
            <el-form-item label="PII 脱敏" prop="maskPii">
              <el-switch v-model="form.maskPii" />
              <span class="form-tip">手机号 / 身份证 / 邮箱 / 银行卡 / IP</span>
            </el-form-item>

            <el-form-item label="生效范围">
              <span class="form-tip-block">
                模型输出、渠道出站、工具结果、知识库入库、MCP 出口 —— 这些会外流或落库的文本会被掩码；<br />
                <b>不作用于用户输入原话</b>（对话与渠道入站的原始输入保持原样）。
              </span>
            </el-form-item>

            <el-form-item label="结构化决策出境">
              <span class="form-tip-block">
                外呼决策模型的 <code>state</code> <b>恒定脱敏</b>（口径强制、无开关），也不受本项控制。
              </span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>
      <!-- 页签三：无人值守白名单（定时任务 / 渠道接入等无人确认场景的写工具放行） -->
      <el-tab-pane label="无人值守白名单" name="unattended">
        <el-card shadow="hover" class="table-panel" v-loading="loading">
          <template #header>
            <div class="toolbar-shell">
              <div class="table-heading">
                <h3>无人值守白名单</h3>
                <p>定时任务 / 渠道接入等无人确认场景下，仅此处显式列出的写工具会被自动执行；默认全部拒绝。</p>
              </div>
              <div class="toolbar-actions">
                <el-button icon="Refresh" @click="refreshAllowlist">刷新</el-button>
                <el-button
                  v-hasPermi="['ai:runtime:unattended:edit']"
                  type="primary"
                  icon="Check"
                  :loading="allowlistSaving"
                  @click="handleSaveAllowlist"
                >
                  保存
                </el-button>
              </div>
            </div>
          </template>

          <el-alert
            class="security-alert"
            type="warning"
            :closable="false"
            show-icon
            title="这里的写工具会在定时任务 / 渠道接入等无人确认场景下自动执行；默认全部拒绝，仅显式列出的放行；显式配置为 DENY 的工具仍以 DENY 为准。"
          />

          <el-form label-width="220px" class="config-form">
            <el-form-item label="允许自动执行的写工具">
              <el-select
                v-model="allowlistToolCodes"
                multiple
                filterable
                :loading="toolOptionsLoading"
                :placeholder="writeToolOptions.length ? '请选择写工具' : '暂无写工具'"
                style="width: 100%"
              >
                <el-option
                  v-for="option in writeToolOptions"
                  :key="option.toolCode"
                  :label="`${option.toolName || option.toolCode}（${option.toolCode}）`"
                  :value="option.toolCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="当前生效值">
              <span class="form-tip-block">
                {{ currentAllowlist.length ? currentAllowlist.join('、') : '空（全部写工具拒绝）' }}
              </span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup name="AiRuntimeSecurity" lang="ts">
import { getSecurityConfig, getUnattendedWriteToolOptions, updateSecurityConfig, updateUnattendedAllowlist } from '@/api/ai/runtime';
import type { SecurityConfig, SecurityConfigPatch } from '@/api/ai/runtime/types';
import type { ToolVO } from '@/api/ai/tool/types';
import modal from '@/plugins/modal';

const loading = ref(true);
const saving = ref(false);
const formRef = ref<ElFormInstance>();

/** 当前页签：内容审核 / PII 脱敏 */
const activeTab = ref('content');

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<SecurityConfig>({
  enabled: false,
  engine: 'local',
  action: 'log',
  inputCheck: true,
  outputCheck: true,
  channelOutCheck: false,
  toolResultMask: true,
  kbIngestCheck: true,
  mcpOutCheck: true,
  promptHardening: true,
  maskPii: true,
  holdBackChars: 20,
  excerptChars: 120,
  providerEndpoint: '',
  providerApiKey: ''
});

const rules = {
  engine: [{ required: true, message: '请选择检测引擎', trigger: 'change' }],
  action: [{ required: true, message: '请选择命中动作', trigger: 'change' }]
};

/** 已配置密钥的掩码（服务端只回掩码：`******` 表示已配置，空串表示未配置） */
const keyMask = ref('');

/** 无人值守页签：可选写工具、当前生效白名单与待提交选择（独立于 content 页签的表单对象） */
const writeToolOptions = ref<ToolVO[]>([]);
const toolOptionsLoading = ref(false);
const allowlistSaving = ref(false);
const currentAllowlist = ref<string[]>([]);
const allowlistToolCodes = ref<string[]>([]);

/** 加载无人值守可选写工具（失败时只清空选项，不影响其他页签） */
const loadWriteToolOptions = async () => {
  toolOptionsLoading.value = true;
  try {
    const res = await getUnattendedWriteToolOptions();
    writeToolOptions.value = res.data ?? [];
  } catch (error) {
    // 工具选项只是本页签的输入源：接口异常不应让整个安全页白屏
    console.warn('无人值守写工具选项加载失败', error);
    writeToolOptions.value = [];
  } finally {
    toolOptionsLoading.value = false;
  }
};

/** 加载当前生效配置 */
const loadConfig = async () => {
  loading.value = true;
  try {
    const res = await getSecurityConfig();
    if (res.data) {
      // 密钥不回显（服务端只回掩码 providerApiKeyMask）：显式置空后以空值提交，
      // 配合后端「留空＝不修改」的合并，避免把已配置的外部审核引擎密钥覆盖成空
      keyMask.value = res.data.providerApiKeyMask ?? '';
      form.value = { ...res.data, providerApiKey: undefined };
      // 白名单单独存放：它与 content / pii 的表单字段无交集，混入 form 会被页签保存互相覆盖
      currentAllowlist.value = [...(res.data.unattendedWriteAllowlist ?? [])];
      allowlistToolCodes.value = [...currentAllowlist.value];
    }
  } finally {
    loading.value = false;
  }
};

/** 保存后只回填当前页签字段，保留另一页签未保存的编辑 */
const refreshAfterSave = async (scope: 'content' | 'pii') => {
  const res = await getSecurityConfig();
  if (!res.data) {
    return;
  }
  keyMask.value = res.data.providerApiKeyMask ?? '';
  const server: SecurityConfig = { ...res.data, providerApiKey: undefined };
  if (scope === 'content') {
    form.value = {
      ...form.value,
      enabled: server.enabled,
      engine: server.engine,
      action: server.action,
      inputCheck: server.inputCheck,
      outputCheck: server.outputCheck,
      channelOutCheck: server.channelOutCheck,
      toolResultMask: server.toolResultMask,
      kbIngestCheck: server.kbIngestCheck,
      mcpOutCheck: server.mcpOutCheck,
      promptHardening: server.promptHardening,
      holdBackChars: server.holdBackChars,
      excerptChars: server.excerptChars,
      providerEndpoint: server.providerEndpoint,
      providerApiKey: undefined
    };
  } else {
    form.value = { ...form.value, maskPii: server.maskPii };
  }
};

/** 保存当前页签（只提交本页签字段，另一页签未保存的修改不会被提交） */
const handleSave = async (scope: 'content' | 'pii') => {
  if (scope === 'content') {
    await formRef.value?.validate();
  }
  const patch: SecurityConfigPatch =
    scope === 'content'
      ? {
          enabled: form.value.enabled,
          engine: form.value.engine,
          action: form.value.action,
          inputCheck: form.value.inputCheck,
          outputCheck: form.value.outputCheck,
          channelOutCheck: form.value.channelOutCheck,
          toolResultMask: form.value.toolResultMask,
          kbIngestCheck: form.value.kbIngestCheck,
          mcpOutCheck: form.value.mcpOutCheck,
          promptHardening: form.value.promptHardening,
          holdBackChars: form.value.holdBackChars,
          excerptChars: form.value.excerptChars,
          providerEndpoint: form.value.providerEndpoint,
          providerApiKey: form.value.providerApiKey
        }
      : { maskPii: form.value.maskPii };
  saving.value = true;
  try {
    await updateSecurityConfig(patch);
    modal.msgSuccess(scope === 'content' ? '内容审核配置已保存，下一次对话 / 编排即生效' : 'PII 脱敏配置已保存，下一次对话 / 编排即生效');
    await refreshAfterSave(scope);
  } finally {
    saving.value = false;
  }
};

/** 刷新无人值守页签：重读生效值与可选写工具 */
const refreshAllowlist = async () => {
  await Promise.all([loadConfig(), loadWriteToolOptions()]);
};

/** 保存无人值守白名单：二次确认 + 必填理由，任一环节取消即中止 */
const handleSaveAllowlist = async () => {
  const submitted = [...allowlistToolCodes.value].sort();
  const current = [...currentAllowlist.value].sort();
  const unchanged = submitted.length === current.length && submitted.every((code, index) => code === current[index]);
  if (unchanged) {
    // 项目 modal 插件没有 msgInfo，msg 即 ElMessage.info
    modal.msg('白名单未变更');
    return;
  }
  const codesText = allowlistToolCodes.value.length ? allowlistToolCodes.value.join('、') : '空（全部拒绝）';
  let reason = '';
  try {
    await ElMessageBox.confirm(
      `本次将允许 ${allowlistToolCodes.value.length} 个写工具在无人确认场景自动执行：${codesText}。确认继续？`,
      '确认修改无人值守白名单',
      {
        type: 'warning',
        confirmButtonText: '确认放行',
        cancelButtonText: '取消',
        // 放行写工具属于高风险操作，确认按钮用危险色强化提示
        confirmButtonClass: 'el-button--danger'
      }
    );
    const { value } = await ElMessageBox.prompt('请填写变更理由（将记入审计日志）', '变更理由', {
      inputType: 'textarea',
      inputValidator: (input: string) => (!!input && input.length <= 200) || '变更理由必填且不超过 200 字'
    });
    reason = value;
  } catch {
    return;
  }
  allowlistSaving.value = true;
  try {
    await updateUnattendedAllowlist({ toolCodes: allowlistToolCodes.value, reason });
    modal.msgSuccess('无人值守白名单已保存，下一次任务 / 渠道调用即生效');
    await loadConfig();
  } finally {
    allowlistSaving.value = false;
  }
};

onMounted(() => {
  loadConfig();
  loadWriteToolOptions();
});
</script>

<style scoped lang="scss">
.security-tabs {
  :deep(.el-tabs__content) {
    padding-top: 4px;
  }
}

.security-alert {
  margin-bottom: 16px;
}

.config-form {
  max-width: 860px;
  padding-top: 4px;
}

.form-tip {
  margin-left: 10px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.form-tip-block {
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.7;
}
</style>
