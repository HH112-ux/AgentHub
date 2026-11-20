import type { HealthCheckItem, HealthProbePayload, HealthReport } from '@/api/ai/health/types';
import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

/**
 * 平台健康自检（只读，不发起任何外网调用）
 * @param refresh 是否穿透服务端 60 秒结果缓存重新体检，页面「重新自检」传 true
 */
export const getHealthReport = (refresh = false): AxiosPromise<HealthReport> => {
  return request({
    url: '/ai/health/check',
    method: 'get',
    params: { refresh }
  });
};

/**
 * 对单个体检项做真实连接探测（模型连通性 / Milvus / 外部 MCP 连接）
 * @param data 探测目标与对象主键
 */
export const probeHealthItem = (data: HealthProbePayload): AxiosPromise<HealthCheckItem> => {
  return request({
    url: '/ai/health/probe',
    method: 'post',
    data
  });
};
