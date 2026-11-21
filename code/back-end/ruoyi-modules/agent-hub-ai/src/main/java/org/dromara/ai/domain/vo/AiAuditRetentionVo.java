package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计与运行数据保留策略视图。
 *
 * <p>{@code pending} 为各表**当前超期行数**（按保留天数算出的截止时间统计），供页面在清理前展示体量；
 * {@code purged} 仅在执行清理后回带（每表实际删除行数，逐批累计而来），查询时为 {@code null}；
 * {@code purgedBatches} 与 {@code partialTables} 记录每表删了几批、有没有表因触到单轮上限而留到下一轮
 * （§12.4 / §12.7 I-6 的分批口径），同样只在清理后回带。</p>
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
     * 本次清理各表的分批删除批数（未执行清理时为 null）
     */
    private Map<String, Integer> purgedBatches;

    /**
     * 本轮触到单轮上限、留待下一轮继续清理的表（未执行清理时为 null，全清完时为空列表）
     */
    private List<String> partialTables;

}
