package org.dromara.ai.core.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
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
 * 聊天附件回收器：§12.6 生命周期里「把对象存储文件与库行收干净」的那一段。
 *
 * <p>对象存储是外部资源、删除不可回滚，故顺序固定为「先删对象 → 成功后再改行」：
 * 对象删除失败时保留原状态、等下一轮重试（对象存储对不存在的键按幂等处理，重试安全）。
 * 本类**不开启事务**——外部调用不应被数据库事务包裹。</p>

 * <p>四条收痕路径（§12.6 / §12.7 I-10）：</p>
 * <ul>
 *   <li>{@code PENDING} 到期（{@code expire_at}）：删对象并**连行一起删**——从未发送过的暂存件没有任何消息引用它，
 *       留一行墓碑只会永久占着配额账目；</li>
 *   <li>会话删除 / 整轮删除：把 {@code PENDING}、{@code BOUND} 转 {@code ORPHANED} 并写留档宽限期限，
 *       宽限期内对象文件仍在，导出留档与误删回看拿得到附件；</li>
 *   <li>{@code ORPHANED} 到期：删对象后转 {@code RECYCLED}，只留文件名 / 大小等元数据；</li>
 *   <li>页面「立即回收」暂存件：直接走 {@link #reclaim(Collection)}（连行删，等同手动到期）。</li>
 * </ul>
 *
 * <p>两类到期扫描都**最旧的先收**（{@code expire_at} 升序、同值按主键升序）：配额与对象存储容量都是先积压先出血，
 * 一轮扫不完时先处理的应当是等得最久的那些。</p>
 *
 * @author AgentHub
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAttachmentReclaimer {

    /**
     * 单批扫描条数：避免一次把全部待回收附件读进内存
     */
    public static final int BATCH_SIZE = 200;

    private final AiMessageAttachmentMapper attachmentMapper;

    private final OssService ossService;

    /**
     * 留档宽限时长取自 {@code agenthub.ai.chat.orphan-attachment-ttl-hours}：宽限期是回收策略的一部分，
     * 由本类统一持有，调用方（会话删除 / 整轮删除）不必各自把配置穿一遍。
     */
    private final AiProperties aiProperties;

    /**
     * 回收结果统计。
     *
     * @param scanned  扫描条数
     * @param deleted  物理删行数（到期暂存件与手动回收）
     * @param recycled 转 {@code RECYCLED} 的孤立件行数
     * @param failed   因对象删除失败而保留的条数
     */
    public record ReclaimResult(int scanned, int deleted, int recycled, int failed) {

        /**
         * 合并两轮扫描的统计（回收任务一轮里先收暂存件、再收孤立件）。
         *
         * @param other 另一轮统计
         * @return 合计统计
         */
        public ReclaimResult plus(ReclaimResult other) {
            return new ReclaimResult(scanned + other.scanned(), deleted + other.deleted(),
                recycled + other.recycled(), failed + other.failed());
        }
    }

    /**
     * 回收到期未发送的暂存附件（{@code PENDING}）。
     *
     * <p>按 {@code (expire_at, id)} 游标向后推进：对象删除失败的行本轮不再重试，留待下一轮，
     * 避免同一批失败行把扫描卡在原地。</p>
     *
     * @param ttlHours   暂存保留时长（小时），&lt;=0 表示回收已关闭（与登记侧写 {@code expire_at} 用的是同一个开关）
     * @param maxBatches 单次最多扫描批数
     * @return 回收统计
     */
    public ReclaimResult reclaimPending(int ttlHours, int maxBatches) {
        if (ttlHours <= 0 || maxBatches <= 0) {
            return new ReclaimResult(0, 0, 0, 0);
        }
        // 到期判定只看登记时写好的 expire_at：改配置只影响后续上传，已上传的按当时的期限收，不追溯
        LocalDateTime deadline = LocalDateTime.now();
        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        Long lastId = null;
        LocalDateTime lastExpireAt = null;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<AiMessageAttachment> list = attachmentMapper.selectList(
                batch(AiMessageAttachment.STATUS_PENDING, deadline, lastExpireAt, lastId));
            if (list == null || list.isEmpty()) {
                break;
            }
            scanned += list.size();
            ReclaimResult result = reclaim(list);
            deleted += result.deleted();
            failed += result.failed();
            lastExpireAt = list.get(list.size() - 1).getExpireAt();
            lastId = list.get(list.size() - 1).getId();
            if (list.size() < BATCH_SIZE) {
                break;
            }
        }
        if (scanned > 0) {
            log.info("暂存附件到期回收：扫描={}, 删除={}, 失败保留={}, deadline={}", scanned, deleted, failed, deadline);
        }
        return new ReclaimResult(scanned, deleted, 0, failed);
    }

    /**
     * 回收到期的孤立附件（{@code ORPHANED}）：删对象后转 {@code RECYCLED}，保留元数据行。
     *
     * @param maxBatches 单次最多扫描批数
     * @return 回收统计
     */
    public ReclaimResult reclaimOrphans(int maxBatches) {
        if (maxBatches <= 0) {
            return new ReclaimResult(0, 0, 0, 0);
        }
        LocalDateTime deadline = LocalDateTime.now();
        int scanned = 0;
        int recycled = 0;
        int failed = 0;
        Long lastId = null;
        LocalDateTime lastExpireAt = null;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<AiMessageAttachment> list = attachmentMapper.selectList(
                batch(AiMessageAttachment.STATUS_ORPHANED, deadline, lastExpireAt, lastId));
            if (list == null || list.isEmpty()) {
                break;
            }
            scanned += list.size();
            ReclaimResult result = recycle(list);
            recycled += result.recycled();
            failed += result.failed();
            lastExpireAt = list.get(list.size() - 1).getExpireAt();
            lastId = list.get(list.size() - 1).getId();
            if (list.size() < BATCH_SIZE) {
                break;
            }
        }
        if (scanned > 0) {
            log.info("孤立附件到期回收：扫描={}, 转 RECYCLED={}, 失败保留={}, deadline={}", scanned, recycled, failed, deadline);
        }
        return new ReclaimResult(scanned, 0, recycled, failed);
    }

    /**
     * 会话删除：该会话下未回收的附件转 {@code ORPHANED}（§12.5 删除语义「附件转 ORPHANED」）。
     *
     * @param sessionIds 会话主键集合
     * @return 转孤立的行数
     */
    public int orphanBySessionIds(Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        return markOrphaned(Wrappers.<AiMessageAttachment>lambdaQuery()
            .in(AiMessageAttachment::getSessionId, sessionIds));
    }

    /**
     * 整轮删除：绑定在该轮用户消息上的附件转 {@code ORPHANED}。
     *
     * @param messageIds 消息主键集合
     * @return 转孤立的行数
     */
    public int orphanByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return markOrphaned(Wrappers.<AiMessageAttachment>lambdaQuery()
            .in(AiMessageAttachment::getMessageId, messageIds));
    }

    /**
     * 把一批附件转 {@code ORPHANED} 并写留档期限。
     *
     * <p>只收 {@code PENDING} 与 {@code BOUND}：已 {@code ORPHANED} 的行不重置期限（否则反复删除同一会话
     * 会把宽限无限往后推），已 {@code RECYCLED} 的行更不能回退——对象文件已经不在了。</p>
     *
     * @param scope 范围条件（按会话或按消息）
     * @return 更新行数
     */
    private int markOrphaned(LambdaQueryWrapper<AiMessageAttachment> scope) {
        LocalDateTime expireAt = LocalDateTime.now()
            .plusHours(Math.max(aiProperties.getChat().getOrphanAttachmentTtlHours(), 0));
        AiMessageAttachment patch = new AiMessageAttachment();
        patch.setStatus(AiMessageAttachment.STATUS_ORPHANED);
        patch.setExpireAt(expireAt);
        scope.in(AiMessageAttachment::getStatus,
            List.of(AiMessageAttachment.STATUS_PENDING, AiMessageAttachment.STATUS_BOUND));
        return attachmentMapper.update(patch, scope);
    }

    /**
     * 到期暂存件与手动回收的落点：逐个删对象，成功的批量物理删行。
     *
     * @param attachments 附件集合
     * @return 回收统计
     */
    public ReclaimResult reclaim(Collection<AiMessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new ReclaimResult(0, 0, 0, 0);
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
        return new ReclaimResult(attachments.size(), deleted, 0, failed);
    }

    /**
     * 到期孤立件的落点：逐个删对象，成功的批量转 {@code RECYCLED}（保留元数据行）。
     *
     * @param attachments 附件集合
     * @return 回收统计
     */
    public ReclaimResult recycle(Collection<AiMessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new ReclaimResult(0, 0, 0, 0);
        }
        int failed = 0;
        List<Long> recycled = new ArrayList<>();
        Set<Long> handledOss = new HashSet<>();
        for (AiMessageAttachment attachment : attachments) {
            if (attachment == null || attachment.getId() == null) {
                continue;
            }
            if (!deleteObjectQuietly(attachment, handledOss)) {
                failed++;
                continue;
            }
            recycled.add(attachment.getId());
        }
        int rows = 0;
        if (!recycled.isEmpty()) {
            AiMessageAttachment patch = new AiMessageAttachment();
            patch.setStatus(AiMessageAttachment.STATUS_RECYCLED);
            rows = attachmentMapper.update(patch, Wrappers.<AiMessageAttachment>lambdaUpdate()
                .in(AiMessageAttachment::getId, recycled));
        }
        return new ReclaimResult(attachments.size(), 0, rows, failed);
    }

    /**
     * 按状态取一批到期附件，最旧的在前；游标用 {@code (expire_at, id)} 复合键，
     * 使对象删除失败的行不被反复读取、期限相同的行也不被跳过。
     */
    private LambdaQueryWrapper<AiMessageAttachment> batch(String status, LocalDateTime deadline,
                                                          LocalDateTime lastExpireAt, Long lastId) {
        LambdaQueryWrapper<AiMessageAttachment> query = Wrappers.<AiMessageAttachment>lambdaQuery()
            .eq(AiMessageAttachment::getStatus, status)
            .le(AiMessageAttachment::getExpireAt, deadline)
            .orderByAsc(AiMessageAttachment::getExpireAt, AiMessageAttachment::getId)
            .last("limit " + BATCH_SIZE);
        if (lastExpireAt != null && lastId != null) {
            query.and(wrapper -> wrapper
                .lt(AiMessageAttachment::getExpireAt, lastExpireAt)
                .or(sameDeadline -> sameDeadline
                    .eq(AiMessageAttachment::getExpireAt, lastExpireAt)
                    .lt(AiMessageAttachment::getId, lastId)));
        }
        return query;
    }

    /**
     * 删除附件对应的对象存储文件；失败只告警并保留记录。
     *
     * @param attachment 附件
     * @param handledOss 本批已处理过的 ossId（防止同一对象被重复删除）
     * @return 是否可继续处理该行
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
