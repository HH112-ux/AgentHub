package org.dromara.ai.core.health;

import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生产用的硬超时执行器：一个检项一条虚拟线程，到点即弃。
 *
 * <p>不用固定线程池：一次全量自检有 15 项，卡死的检项（例如连不上 Milvus 时的 TCP 等待）
 * 会长期占住池内线程，几次刷新之后池子被占满，报告就退化成「整屏都超时」——那已经不是现场真相。
 * 虚拟线程按项创建、随任务结束回收，滞留的只会是虚拟线程本身，不占平台线程。</p>
 *
 * <p>超时后 {@code cancel(true)} 会中断工作线程；驱动不响应中断时，那条线程要等自身的
 * socket 超时才退出，但**调用方已经拿到超时结论**，不再等它。</p>
 *
 * @author AgentHub
 */
@Component
public class AiHealthItemTimeoutInvoker implements AiHealthItemInvoker {

    @Override
    public AiHealthCheckVo invoke(Callable<AiHealthCheckVo> body, long timeoutMillis) throws Exception {
        FutureTask<AiHealthCheckVo> task = new FutureTask<>(body);
        Thread.startVirtualThread(task);
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }
}
