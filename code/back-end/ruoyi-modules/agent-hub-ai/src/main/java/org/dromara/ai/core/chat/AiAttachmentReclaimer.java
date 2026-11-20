package org.dromara.ai.core.chat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.mapper.AiMessageAttachmentMapper;
import org.dromara.system.api.OssService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天附件回收器：统一「先删对象存储、后删库行」的回收口径。
 *
 * <p>对象存储是外部资源、删除不可回滚，故顺序固定为「先删对象 → 成功后再删行」：
 * 对象删除失败时保留行、等下一轮重试（对象存储对不存在的键按幂等处理，重试安全）。
 * 本类**不开启事务**——外部调用不应被数据库事务包裹。</p>
 *
 * <p>回收入口有三处：暂存件超期（定时任务）、删除会话、删除整轮。
 * 已发送且会话仍存在的附件不自动清理，避免误删用户回看的材料。</p>
 *
 * @author AgentHub
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAttachmentReclaimer {

    /**
     * 单批扫描条数：避免一次把全部暂存件读进内存
     */
    public static final int BATCH_SIZE = 200;

    private final AiMessageAttachmentMapper attachmentMapper;

    private final OssService ossService;

    /**
     * 回收结果统计。
     *
     * @param scanned 扫描条数
     * @param deleted 删除行数
     * @param failed  因对象删除失败而保留的条数
     */
    public record ReclaimResult(int scanned, int deleted, int failed) {
    }

    /**
     * 回收超期未发送的暂存附件。
     *
     * <p>按主键游标向后推进：对象删除失败的行本轮不再重试，留待下一轮，
     * 避免同一批失败行把扫描卡在原地。</p>
     *
     * @param ttlHours   保留时长（小时），&lt;=0 表示不回收
     * @param maxBatches 单次最多扫描批数
     * @return 回收统计
     */
    public ReclaimResult reclaimPending(int ttlHours, int maxBatches) {
        if (ttlHours <= 0 || maxBatches <= 0) {
            return new ReclaimResult(0, 0, 0);
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ttlHours);
        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        Long lastId = null;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<AiMessageAttachment> list = attachmentMapper.selectList(
                Wrappers.<AiMessageAttachment>lambdaQuery()
                    .isNull(AiMessageAttachment::getMessageId)
                    .lt(AiMessageAttachment::getCreateTime, cutoff)
                    .gt(lastId != null, AiMessageAttachment::getId, lastId)
                    .orderByAsc(AiMessageAttachment::getId)
                    .last("limit " + BATCH_SIZE));
            if (list == null || list.isEmpty()) {
                break;
            }
            scanned += list.size();
            ReclaimResult result = reclaim(list);
            deleted += result.deleted();
            failed += result.failed();
            lastId = list.get(list.size() - 1).getId();
            if (list.size() < BATCH_SIZE) {
                break;
            }
        }
        if (scanned > 0) {
            log.info("暂存附件回收：扫描={}, 删除={}, 失败保留={}, cutoff={}", scanned, deleted, failed, cutoff);
        }
        return new ReclaimResult(scanned, deleted, failed);
    }

    /**
     * 按会话回收全部附件（删除会话时调用）。
     *
     * @param sessionIds 会话主键集合
     * @return 删除行数
     */
    public int reclaimBySessionIds(Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        List<AiMessageAttachment> list = attachmentMapper.selectList(
            Wrappers.<AiMessageAttachment>lambdaQuery()
                .in(AiMessageAttachment::getSessionId, sessionIds));
        return reclaim(list).deleted();
    }

    /**
     * 按用户消息回收附件（删除整轮时调用）。
     *
     * @param messageIds 消息主键集合
     * @return 删除行数
     */
    public int reclaimByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        List<AiMessageAttachment> list = attachmentMapper.selectList(
            Wrappers.<AiMessageAttachment>lambdaQuery()
                .in(AiMessageAttachment::getMessageId, messageIds));
        return reclaim(list).deleted();
    }

    /**
     * 回收一批附件：逐个删对象，成功的批量物理删行。
     *
     * @param attachments 附件集合
     * @return 回收统计
     */
    public ReclaimResult reclaim(Collection<AiMessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new ReclaimResult(0, 0, 0);
        }
        int failed = 0;
        List<Long> doomed = new ArrayList<>();
        Set<Long> handledOss = new HashSet<>();
        for (AiMessageAttachment attachment : attachments) {
            if (attachment == null || attachment.getId() == null) {
                continue;
            }
            if (!deleteObjectQuietly(attachment, handledOss)) {
                failed++;
                continue;
            }
            doomed.add(attachment.getId());
        }
        int deleted = doomed.isEmpty() ? 0 : attachmentMapper.deletePhysicallyByIds(doomed);
        return new ReclaimResult(attachments.size(), deleted, failed);
    }

    /**
     * 删除附件对应的对象存储文件；失败只告警并保留记录。
     *
     * @param attachment 附件
     * @param handledOss 本批已处理过的 ossId（防止同一对象被重复删除）
     * @return 是否可继续删行
     */
    private boolean deleteObjectQuietly(AiMessageAttachment attachment, Set<Long> handledOss) {
        Long ossId = attachment.getOssId();
        if (ossId == null || !handledOss.add(ossId)) {
            return true;
        }
        try {
            ossService.deleteByIds(String.valueOf(ossId));
            return true;
        } catch (Exception e) {
            log.warn("附件对象回收失败，保留记录等待重试：attachmentId={}, ossId={}, err={}",
                attachment.getId(), ossId, e.getMessage());
            return false;
        }
    }

}
