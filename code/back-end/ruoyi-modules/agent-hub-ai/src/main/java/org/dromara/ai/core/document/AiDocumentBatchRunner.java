package org.dromara.ai.core.document;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 知识库文档批量处理线程池。
 *
 * <p>解析与向量化都可能持续数十秒，批量执行绝不能占用 HTTP 请求线程：
 * 提交接口只做校验与状态占位后立即返回，实际处理在此线程池内异步完成，
 * 进度由 {@code ai_document.parse_status / vector_status} 承载，页面轮询读取。</p>
 *
 * <p>并发刻意压到 2：解析要下载并读文件，向量化要外呼 embedding 并写 Milvus，
 * 并发过高会与主业务争抢数据库连接池，反而拖慢整站。</p>
 *
 * @author AgentHub
 */
@Component
public class AiDocumentBatchRunner {

    /**
     * 队列上限（设计稿 §12.3 WARN 档「向量化队列积压打满 200」里的 200 即此值）：
     * 满时 {@link #submit} 抛 {@code TaskRejectedException}，由调用方把该批落为失败而不是静默丢弃。
     */
    public static final int QUEUE_CAPACITY = 200;

    /**
     * 执行线程池（有界，队列满时拒绝而不是无限堆积）
     */
    private final ThreadPoolTaskExecutor executor;

    public AiDocumentBatchRunner() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(QUEUE_CAPACITY);
        pool.setThreadNamePrefix("ai-kb-batch-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.executor = pool;
    }

    /**
     * 提交一个批量处理任务。
     *
     * @param task 执行体
     * @throws org.springframework.core.task.TaskRejectedException 队列已满时抛出
     */
    public void submit(Runnable task) {
        executor.execute(task);
    }

    /**
     * 当前排队等待的任务数（健康自检 §12.7 I-3 的「向量化队列积压」读此值）。
     *
     * <p>只反映本进程——多副本部署时每个副本各有一条队列，自检报告的是「当前这台」的积压，
     * 与 {@code ai_document.vector_status} 的待处理行数互补（后者是全局的）。</p>
     *
     * @return 队列中的任务数
     */
    public int queuedTaskCount() {
        return executor.getThreadPoolExecutor().getQueue().size();
    }

    /**
     * 应用关闭时优雅等待在跑任务结束。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

}
