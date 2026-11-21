package org.dromara.ai.core.health;

import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiHealthItemTimeoutInvoker} 单元测试：硬超时的四条契约——
 * 正常返回、到点抛 {@code TimeoutException} 且调用方不陪跑、超时后中断工作线程、检项异常原样抛出。
 *
 * <p>时间阈值都留了数倍余量（预算 50 毫秒对检项 1 秒），慢机器上只会更早变红而不会误绿。</p>
 */
@DisplayName("健康自检硬超时执行器")
class AiHealthItemTimeoutInvokerTest {

    private final AiHealthItemTimeoutInvoker invoker = new AiHealthItemTimeoutInvoker();

    private AiHealthCheckVo item(String status) {
        AiHealthCheckVo vo = new AiHealthCheckVo();
        vo.setKey("db");
        vo.setStatus(status);
        return vo;
    }

    @Test
    @DisplayName("预算内返回时结果原样交出，且检项跑在工作线程上")
    void shouldReturnResultFromWorkerThread() throws Exception {
        AtomicReference<Thread> runner = new AtomicReference<>();

        AiHealthCheckVo result = invoker.invoke(() -> {
            runner.set(Thread.currentThread());
            return item(AiHealthCheckVo.STATUS_PASS);
        }, 3_000L);

        assertEquals(AiHealthCheckVo.STATUS_PASS, result.getStatus());
        assertNotSame(Thread.currentThread(), runner.get(), "检项必须在工作线程执行，否则超时判定无从谈起");
        assertTrue(runner.get().isVirtual(), "按项建线程，不用线程池：卡死的检项不该占住可复用的平台线程");
    }

    @Test
    @DisplayName("超过预算抛 TimeoutException，调用方不陪检项等到自己睡完")
    void shouldThrowTimeoutWithoutBlockingCaller() {
        long startedAt = System.nanoTime();

        assertThrows(TimeoutException.class, () -> invoker.invoke(() -> {
            TimeUnit.MILLISECONDS.sleep(1_000L);
            return item(AiHealthCheckVo.STATUS_PASS);
        }, 50L));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(elapsedMillis < 300L, "50 毫秒预算不该拖住调用方，实际等了 " + elapsedMillis + " 毫秒");
    }

    @Test
    @DisplayName("超时后中断工作线程，卡住的检项不会永远挂着")
    void shouldInterruptTimedOutWorker() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);

        assertThrows(TimeoutException.class, () -> invoker.invoke(() -> {
            try {
                TimeUnit.SECONDS.sleep(30L);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return item(AiHealthCheckVo.STATUS_PASS);
        }, 50L));

        assertTrue(interrupted.await(2L, TimeUnit.SECONDS), "cancel(true) 应把中断送到工作线程");
    }

    @Test
    @DisplayName("检项自身异常按原异常抛出，不包成 ExecutionException")
    void shouldRethrowOriginalException() {
        IllegalStateException boom = new IllegalStateException("库连不上");

        Exception thrown = assertThrows(Exception.class, () -> invoker.invoke(() -> {
            throw boom;
        }, 3_000L));

        assertSame(boom, thrown);
    }
}
