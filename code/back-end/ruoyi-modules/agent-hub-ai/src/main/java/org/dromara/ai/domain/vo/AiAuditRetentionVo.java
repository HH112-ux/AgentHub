package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计与运行数据保留策略视图。
 *
 * <p>{@code pending} 为各表**当前超期行数**（按保留天数算出的截止时间统计），供页面在清理前展示体量；
 * {@code purged} 仅在执行清理后回带（每表实际删除行数），查询时为 {@code null}。</p>
 *
 * @author AgentHub
 */
@Data
public class AiAuditRetentionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 保留天数（0＝不限，不清理）
     */
    private int retentionDays;

    /**
     * 保留截止时间（早于该时间的行会被清理；不限时为 null）
     */
    private String cutoff;

    /**
     * 各表当前超期行数：键＝表名，值＝行数
     */
    private Map<String, Long> pending = new LinkedHashMap<>();

    /**
     * 本次清理各表实际删除行数（未执行清理时为 null）
     */
    private Map<String, Long> purged;

    /**
     * 上一次清理记录（§12.4 操作面「上次清理时间与各类型删除行数」）。
     *
     * <p>手动清理的历史条目由底座操作日志（{@code sys_oper_log}，业务类型 CLEAN）承担，本对象只留
     * 最近一次——定时任务线程没有请求上下文，落不进操作日志，而页面必须能看到夜里那轮删了多少。
     * 逐批累计后的总行数记在这里，批次数一并给出，便于判断是不是被批次上限截断过。</p>
     */
    private LastPurge lastPurge;

    /**
     * 一次清理的留痕。
     */
    @Data
    public static class LastPurge implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 清理完成时间（{@code yyyy-MM-dd HH:mm:ss}，本地时区）
         */
        private String at;

        /**
         * 本次使用的截止时间（早于该时间的行被删除）
         */
        private String cutoff;

        /**
         * 本次生效的保留天数
         */
        private int retentionDays;

        /**
         * 触发方式：{@code manual}（页面按钮）/ {@code auto}（每日 03:30 定时）
         */
        private String trigger;

        /**
         * 操作人（自动清理为 null）
         */
        private Long operatorId;

        /**
         * 各表删除行数（逐批累计）
         */
        private Map<String, Long> purged = new LinkedHashMap<>();

        /**
         * 合计删除行数
         */
        private long total;

        /**
         * 用到的批次数（跨全部表累计）
         */
        private int batches;

    }

}
