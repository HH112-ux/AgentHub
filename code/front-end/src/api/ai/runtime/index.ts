import type { CompactionConfig, JevConfig, PlanConfig, RouteBudgetConfig, SecurityConfig, SecurityConfigPatch, UnattendedAllowlistForm } from '@/api/ai/runtime/types';
import type { ToolVO } from '@/api/ai/tool/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 查询当前生效的上下文压缩策略
 */
export const getCompactionConfig = (): AxiosPromise<CompactionConfig> => {
  return request({
    url: '/ai/runtime/compaction',
    method: 'get'
  });
};

/**
 * 保存上下文压缩策略（运行期生效）
 * @param data 压缩策略
 */
export const updateCompactionConfig = (data: CompactionConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/compaction',
    method: 'put',
    data
  });
};

/**
 * 查询当前生效的计划模式与任务清单配置
 */
export const getPlanConfig = (): AxiosPromise<PlanConfig> => {
  return request({
    url: '/ai/runtime/plan',
    method: 'get'
  });
};

/**
 * 保存计划模式与任务清单配置（运行期生效）
 * @param data 计划模式配置
 */
export const updatePlanConfig = (data: PlanConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/plan',
    method: 'put',
    data
  });
};

/**
 * 查询当前生效的编排预算配置
 */
export const getRouteBudgetConfig = (): AxiosPromise<RouteBudgetConfig> => {
  return request({
    url: '/ai/runtime/route-budget',
    method: 'get'
  });
};

/**
 * 保存编排预算配置（运行期生效）
 * @param data 编排预算配置
 */
export const updateRouteBudgetConfig = (data: RouteBudgetConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/route-budget',
    method: 'put',
    data
  });
};

/**
 * 查询当前生效的安全合规配置
 */
export const getSecurityConfig = (): AxiosPromise<SecurityConfig> => {
  return request({
    url: '/ai/runtime/security',
    method: 'get'
  });
};

/**
 * 保存安全合规配置（运行期生效；按页签部分更新，未提交字段沿用当前生效值）
 * @param data 安全合规配置的部分字段
 */
export const updateSecurityConfig = (data: SecurityConfigPatch): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/security',
    method: 'put',
    data
  });
};

/**
 * 查询无人值守白名单可选的写工具（启用中且非只读）
 */
export const getUnattendedWriteToolOptions = (): AxiosPromise<ToolVO[]> => {
  return request({
    url: '/ai/runtime/unattended/tool-options',
    method: 'get'
  });
};

/**
 * 保存无人值守写工具白名单（运行期生效；需独立权限且必须填写变更理由）
 * @param data 完整名单与变更理由
 */
export const updateUnattendedAllowlist = (data: UnattendedAllowlistForm): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/unattended',
    method: 'put',
    data
  });
};

/**
 * 查询当前生效的结构化决策路由配置（不回显密钥，只返回是否已配置）
 */
export const getJevConfig = (): AxiosPromise<JevConfig> => {
  return request({
    url: '/ai/runtime/jev',
    method: 'get'
  });
};

/**
 * 保存结构化决策路由配置（运行期生效；密钥留空表示不修改已保存密钥）
 * @param data 结构化决策配置
 */
export const updateJevConfig = (data: JevConfig): AxiosPromise<void> => {
  return request({
    url: '/ai/runtime/jev',
    method: 'put',
    data
  });
};