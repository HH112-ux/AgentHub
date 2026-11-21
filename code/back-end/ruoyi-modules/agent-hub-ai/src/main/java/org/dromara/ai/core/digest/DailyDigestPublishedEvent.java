package org.dromara.ai.core.digest;

import java.time.LocalDate;

/**
 * 运行日报落库成功事件（§12.3 触发时机 / §12.7 I-4）。
 *
 * <p>日报由 08:00 的内置定时任务经写工具 {@code write_daily_digest} 落库，落库点是
 * {@link DailyDigestPublisher#publish}。本事件只表示「某日日报已写入」，供健康自检侧
 * 在其后串一次体检；发布方不关心谁在听，也不因监听侧失败而改变落库结果。</p>
 *
 * @param digestDate 日报日期
 * @param level      结论级别（解析失败时为 {@code null}）
 */
public record DailyDigestPublishedEvent(LocalDate digestDate, String level) {
}
