package org.dromara.ai.core.health;

import org.dromara.ai.domain.vo.AiHealthCheckVo;

import java.util.concurrent.Callable;

/**
 * 健康自检单项执行接缝（设计稿 §12.3 / §12.7 I-14：单项 3 秒、整屏 30 秒）。
 *
 * <p><b>为什么要做成接口</b>：硬超时只能把工作交给另一个线程、再由 {@code Future} 判定，
 * 而本模块的健康自检单测依赖 {@code mockStatic}，Mockito 的静态桩是**线程局部**的——
 * 实现一旦固定走工作线程，这批用例会在桩失效的那一行集体变红。
 * 因此生产注入 {@link AiHealthItemTimeoutInvoker}，单测注入 {@link #direct()} 的同线程直调。</p>
 *
 * <p><b>工作线程的前提</b>：检项本体不得读登录态。RuoYi 的数据权限处理器与
 * {@code InjectionMetaObjectHandler} 在没有请求上下文的线程上取不到登录用户
 * （见 {@code AiRouteRecorder} 类注释）。这条前提正是 §12.7 I-15 把检项取数改成平台全量视图的原因，
 * 由 {@code AiAgentServiceImplTest} 的「未碰登录上下文」断言守住；本模块的 ai_ Mapper 也不带
 * {@code @DataPermission}。</p>
 *
 * @author AgentHub
 */
public interface AiHealthItemInvoker {

    /**
     * 在给定预算内执行一个检项。
     *
     * @param body          检项本体（可为抛受检异常的 lambda，故取 {@link Callable} 而非 {@code Supplier}）
     * @param timeoutMillis 本项可用毫秒数（调用方已按单项上限与整屏剩余取小）
     * @return 检项结果
     * @throws java.util.concurrent.TimeoutException 超出预算仍未返回
     * @throws Exception                             检项自身抛出的异常，由调用方按既有口径记 FAIL
     */
    AiHealthCheckVo invoke(Callable<AiHealthCheckVo> body, long timeoutMillis) throws Exception;

    /**
     * 同线程直调：不判定超时，仅供单测与本地排障使用。
     *
     * @return 直调实现
     */
    static AiHealthItemInvoker direct() {
        return (body, timeoutMillis) -> body.call();
    }
}
