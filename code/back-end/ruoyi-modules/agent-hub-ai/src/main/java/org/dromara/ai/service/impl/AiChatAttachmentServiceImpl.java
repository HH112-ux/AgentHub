package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiVisionGuard;
import org.dromara.ai.core.chat.AiAttachmentReclaimer;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.domain.bo.AiChatAttachmentBo;
import org.dromara.ai.domain.vo.AiChatAttachmentVo;
import org.dromara.ai.mapper.AiMessageAttachmentMapper;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.ai.util.AiDocumentParser;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 对话附件 Service 实现
 *
 * @author AgentHub
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatAttachmentServiceImpl implements IAiChatAttachmentService {

    /**
     * 允许的图片后缀（与 AgentScope 多模态图片格式对齐）
     */
    private static final Set<String> IMAGE_SUFFIXES = Set.of("png", "jpg", "jpeg", "gif", "webp", "heic", "heif");

    /**
     * 图片后缀 → MIME 类型
     */
    private static final Map<String, String> IMAGE_MIME = Map.of(
        "png", "image/png",
        "jpg", "image/jpeg",
        "jpeg", "image/jpeg",
        "gif", "image/gif",
        "webp", "image/webp",
        "heic", "image/heic",
        "heif", "image/heif");

    private final AiMessageAttachmentMapper baseMapper;

    private final OssService ossService;

    private final AiProperties aiProperties;

    /**
     * 图片附件的「支持视觉」能力校验（多模态易踩坑：非视觉模型会白屏式报错）
     */
    private final AiVisionGuard visionGuard;

    /**
     * 附件回收（对象存储 + 库行），删除暂存件时复用统一口径
     */
    private final AiAttachmentReclaimer attachmentReclaimer;

    @Override
    public AiChatAttachmentVo register(Long sessionId, AiChatAttachmentBo bo) {
        OssDTO oss = resolveOss(bo.getOssId());
        try {
            return registerChecked(sessionId, bo, oss);
        } catch (RuntimeException e) {
            // 文件先落 OSS 再由本方法校验/解析/入库；任一步失败都不会留下附件行，
            // 故同步回收本次上传的对象，避免「上传成功、登记失败」的孤儿（回收任务只扫已登记的附件行）
            discardUploadedObject(bo.getOssId());
            throw e;
        }
    }

    /**
     * 登记主体：类型 / 大小 / 视觉能力校验、文档解析与落库。
     *
     * @param sessionId 会话主键
     * @param bo        登记参数
     * @param oss       已校验存在的 OSS 元信息
     * @return 附件视图
     */
    private AiChatAttachmentVo registerChecked(Long sessionId, AiChatAttachmentBo bo, OssDTO oss) {
        String suffix = resolveSuffix(bo, oss);
        String fileName = resolveFileName(bo, oss, suffix);
        boolean image = IMAGE_SUFFIXES.contains(suffix);
        boolean document = AiDocumentParser.supported(suffix);
        if (!image && !document) {
            throw new ServiceException("不支持的文件类型：." + suffix
                + "，图片仅支持 png / jpg / jpeg / gif / webp / heic / heif；文档仅支持 PDF / docx / xlsx / pptx / 文本类文件");
        }
        // 实际字节交由服务端下载后校验（OSS 元信息不含大小，且不信任客户端声明）
        byte[] bytes = downloadBytes(bo.getOssId());
        long limit = image ? aiProperties.getChat().getImageMaxBytes() : aiProperties.getChat().getDocumentMaxBytes();
        if (bytes.length > limit) {
            throw new ServiceException((image ? "图片" : "文档") + "大小超过限制（最大 " + humanSize(limit) + "）");
        }
        if (image) {
            // 图片走多模态 ImageBlock，非视觉模型会报错或被忽略：在此按「支持视觉」声明提前拦下并给出明确提示
            visionGuard.assertImageSupported(sessionId);
        }
        AiMessageAttachment entity = new AiMessageAttachment();
        entity.setSessionId(sessionId);
        entity.setFileType(image ? AiMessageAttachment.TYPE_IMAGE : AiMessageAttachment.TYPE_DOCUMENT);
        entity.setFileName(fileName);
        entity.setFileSuffix(suffix);
        entity.setFileSize((long) bytes.length);
        entity.setMimeType(image ? IMAGE_MIME.get(suffix) : null);
        entity.setOssId(bo.getOssId());
        entity.setStatus(AiMessageAttachment.STATUS_PENDING);
        entity.setExpireAt(pendingExpireAt());
        if (document) {
            String text = AiDocumentParser.extract(bytes, suffix);
            if (StringUtils.isBlank(text)) {
                throw new ServiceException("未从文档中解析出文本内容，可能是扫描件或空文档");
            }
            entity.setParsedContent(text);
        }
        // 配额拦在落库之前：登记成功后这行就算作者的暂存件，先查后插才不会把上限挤过去一格
        assertPendingQuota(bytes.length);
        baseMapper.insert(entity);
        return toVo(entity, resolveUrlQuietly(entity.getOssId()));
    }

    /**
     * 暂存件的到期时刻：{@code expire_at = 上传时间 + pending-attachment-ttl-hours}（§12.6）。
     *
     * <p>阈值 &lt;=0 表示关闭暂存件自动回收，此时不写期限——回收任务按 {@code expire_at} 取片，
     * 空值即不参与，语义与 {@link AiAttachmentReclaimer#reclaimPending(int, int)} 的开关一致。</p>
     */
    private LocalDateTime pendingExpireAt() {
        int ttlHours = aiProperties.getChat().getPendingAttachmentTtlHours();
        return ttlHours <= 0 ? null : LocalDateTime.now().plusHours(ttlHours);
    }

    /**
     * 单用户暂存件配额（§12.6「单用户 {@code PENDING} ≤20 个且 ≤50MB；超限拒绝新上传并提示清理」）。
     *
     * <p>算的是**已经登记的**暂存件，本次上传的字节另加：两条上限各自独立生效，哪个先撞就按哪个提示。
     * 只数 {@code PENDING}——已随消息发送的 {@code BOUND} 与删除后转 {@code ORPHANED} 的都不该占用户的上传额度。
     * 任一路上限 &lt;=0 表示该条不限。</p>
     *
     * @param incomingBytes 本次上传的实际字节数
     */
    private void assertPendingQuota(long incomingBytes) {
        int maxCount = aiProperties.getChat().getPendingAttachmentMaxCount();
        long maxBytes = aiProperties.getChat().getPendingAttachmentMaxBytes();
        if (maxCount <= 0 && maxBytes <= 0) {
            return;
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            // 取不到登录身份时不比对配额：后面的写库同样按登录态鉴权，此处不为拿不到的身份造一条拦截口径
            return;
        }
        if (maxCount > 0) {
            Long used = baseMapper.selectCount(Wrappers.<AiMessageAttachment>lambdaQuery()
                .eq(AiMessageAttachment::getCreateBy, userId)
                .eq(AiMessageAttachment::getStatus, AiMessageAttachment.STATUS_PENDING));
            long pending = used == null ? 0L : used;
            if (pending + 1 > maxCount) {
                throw new ServiceException("未发送的附件已达上限（" + maxCount + " 个），请先删除暂存附件或把它们随消息发送出去");
            }
        }
        if (maxBytes > 0) {
            Long used = baseMapper.sumFileSizeByUserAndStatus(userId, AiMessageAttachment.STATUS_PENDING);
            long pendingBytes = used == null ? 0L : used;
            if (pendingBytes + incomingBytes > maxBytes) {
                throw new ServiceException("未发送的附件合计已达上限（" + humanSize(maxBytes)
                    + "），请先删除暂存附件或把它们随消息发送出去");
            }
        }
    }

    /**
     * 回收登记失败时本次上传的 OSS 对象。
     *
     * <p>失败只记日志、不覆盖原始异常：对象回收本身失败也不该改变「登记失败」这个对外结果，
     * 遗留对象属于已知残留（见文档 14 §82），不在此处重试。</p>
     *
     * @param ossId 本次上传的 OSS 主键
     */
    private void discardUploadedObject(Long ossId) {
        if (ossId == null) {
            return;
        }
        try {
            ossService.deleteByIds(String.valueOf(ossId));
        } catch (Exception e) {
            log.warn("附件登记失败后回收上传对象失败，ossId={}", ossId, e);
        }
    }

    @Override
    public List<AiMessageAttachment> bindToMessage(Long sessionId, Long messageId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(attachmentIds.stream().filter(Objects::nonNull).toList()));
        if (distinct.isEmpty()) {
            return List.of();
        }
        int max = aiProperties.getChat().getMaxAttachmentsPerMessage();
        if (distinct.size() > max) {
            throw new ServiceException("单条消息最多携带 " + max + " 个附件");
        }
        List<AiMessageAttachment> list = baseMapper.selectByIds(distinct);
        if (list.size() != distinct.size()) {
            throw new ServiceException("附件不存在或已被删除，请重新上传");
        }
        for (AiMessageAttachment attachment : list) {
            if (!sessionId.equals(attachment.getSessionId())) {
                throw new ServiceException("附件不属于该会话，请重新上传");
            }
            if (attachment.getMessageId() != null && !attachment.getMessageId().equals(messageId)) {
                throw new ServiceException("附件已被其他消息使用，请重新上传");
            }
            // 会话或整轮删除后附件进入回收流程：对象文件可能已经被删掉，绑上去就是一条永远打不开的引用
            if (isRecycling(attachment)) {
                throw new ServiceException("附件已进入回收流程，请重新上传");
            }
        }
        for (AiMessageAttachment attachment : list) {
            if (messageId.equals(attachment.getMessageId())) {
                continue;
            }
            AiMessageAttachment update = new AiMessageAttachment();
            update.setId(attachment.getId());
            update.setMessageId(messageId);
            // 发送即脱离暂存配额：BOUND 不参与到期扫描，expire_at 保留登记时的原值只作展示
            update.setStatus(AiMessageAttachment.STATUS_BOUND);
            baseMapper.updateById(update);
        }
        return list;
    }

    /**
     * 是否已进入回收流程（{@code ORPHANED} 待宽限到期、{@code RECYCLED} 对象已回收）。
     *
     * @param attachment 附件
     * @return 是否不可再被发送或手动移除
     */
    private boolean isRecycling(AiMessageAttachment attachment) {
        return AiMessageAttachment.STATUS_ORPHANED.equals(attachment.getStatus())
            || AiMessageAttachment.STATUS_RECYCLED.equals(attachment.getStatus());
    }

    @Override
    public List<AiMessageAttachment> listByMessageId(Long messageId) {
        if (messageId == null) {
            return List.of();
        }
        return baseMapper.selectList(Wrappers.<AiMessageAttachment>lambdaQuery()
            .eq(AiMessageAttachment::getMessageId, messageId)
            .ne(AiMessageAttachment::getStatus, AiMessageAttachment.STATUS_RECYCLED)
            .orderByAsc(AiMessageAttachment::getId));
    }

    @Override
    public Map<Long, List<AiChatAttachmentVo>> listVoMapByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        // 对象已回收（RECYCLED）的行只剩元数据，回显出来是一个点开就报错的气泡，不再列出；
        // ORPHANED 仍在留档宽限期内、文件还在桶里，照旧可见
        List<AiMessageAttachment> list = baseMapper.selectList(Wrappers.<AiMessageAttachment>lambdaQuery()
            .in(AiMessageAttachment::getMessageId, messageIds)
            .ne(AiMessageAttachment::getStatus, AiMessageAttachment.STATUS_RECYCLED)
            .orderByAsc(AiMessageAttachment::getId));
        Map<Long, List<AiChatAttachmentVo>> result = new LinkedHashMap<>();
        for (AiMessageAttachment attachment : list) {
            result.computeIfAbsent(attachment.getMessageId(), key -> new ArrayList<>())
                .add(toVo(attachment, resolveUrlQuietly(attachment.getOssId())));
        }
        return result;
    }

    @Override
    public byte[] downloadBytes(Long ossId) {
        String url = resolveUrl(ossId);
        byte[] bytes;
        try {
            bytes = HttpUtil.downloadBytes(url);
        } catch (Exception e) {
            throw new ServiceException("文件下载失败：" + e.getMessage());
        }
        if (bytes == null || bytes.length == 0) {
            throw new ServiceException("文件下载失败，请检查对象存储配置与文件是否仍然存在");
        }
        return bytes;
    }

    @Override
    public AiChatAttachmentVo refreshUrl(Long sessionId, Long attachmentId) {
        AiMessageAttachment attachment = attachmentId == null ? null : baseMapper.selectById(attachmentId);
        if (attachment == null || !Objects.equals(sessionId, attachment.getSessionId())) {
            throw new ServiceException("附件不存在或不属于该会话");
        }
        return toVo(attachment, resolveUrlQuietly(attachment.getOssId()));
    }

    @Override
    public void deletePending(Long sessionId, Long attachmentId) {
        AiMessageAttachment attachment = attachmentId == null ? null : baseMapper.selectById(attachmentId);
        if (attachment == null) {
            return;
        }
        if (!sessionId.equals(attachment.getSessionId())) {
            throw new ServiceException("附件不属于该会话");
        }
        if (attachment.getMessageId() != null) {
            throw new ServiceException("附件已随消息发送，无法删除");
        }
        // 会话或整轮已删除、进入回收流程的暂存件由任务按留档期限收，不在这里抢：手动再收一遍只会和任务争同一批对象
        if (isRecycling(attachment)) {
            throw new ServiceException("附件已进入回收流程，无需重复删除");
        }
        // 统一回收口径：先删对象存储文件，再物理删行（失败时保留行等任务重试）
        attachmentReclaimer.reclaim(List.of(attachment));
    }

    /**
     * 取 OSS 文件元信息（不存在即拒绝）。
     *
     * <p>同时校验文件归属：只允许使用**本人上传**的对象。否则可借他人的 {@code ossId}
     * 把对方文件登记进自己的会话，或在校验失败时由 {@link #discardUploadedObject(Long)} 把对方文件删掉。</p>
     *
     * @param ossId 对象存储主键
     * @return OSS 元信息
     */
    private OssDTO resolveOss(Long ossId) {
        if (ossId == null) {
            throw new ServiceException("文件未上传，请重新上传后再提交");
        }
        List<OssDTO> list = ossService.selectByIds(String.valueOf(ossId));
        if (list == null || list.isEmpty() || list.get(0) == null) {
            throw new ServiceException("文件不存在或已被删除，请重新上传");
        }
        OssDTO oss = list.get(0);
        Long userId = LoginHelper.getUserId();
        if (userId == null || !userId.equals(oss.getCreateBy())) {
            // 对外不区分「不存在」与「非本人」，避免通过错误信息探测他人 ossId
            throw new ServiceException("文件不存在或已被删除，请重新上传");
        }
        return oss;
    }

    /**
     * 解析文件后缀（优先 OSS 记录，其次原始文件名）。
     *
     * @param bo  登记参数
     * @param oss OSS 元信息
     * @return 小写后缀（不含点）
     */
    private String resolveSuffix(AiChatAttachmentBo bo, OssDTO oss) {
        String suffix = normalizeSuffix(oss.getFileSuffix());
        if (suffix.isEmpty()) {
            suffix = normalizeSuffix(nameOf(bo.getFileName()));
        }
        if (suffix.isEmpty()) {
            suffix = normalizeSuffix(oss.getOriginalName());
        }
        if (suffix.isEmpty()) {
            throw new ServiceException("无法识别文件类型，请重新上传");
        }
        return suffix;
    }

    /**
     * 解析展示用文件名（登记参数 → OSS 原名 → OSS 存储名）。
     *
     * @param bo     登记参数
     * @param oss    OSS 元信息
     * @param suffix 已解析的后缀
     * @return 文件名
     */
    private String resolveFileName(AiChatAttachmentBo bo, OssDTO oss, String suffix) {
        if (StringUtils.isNotBlank(bo.getFileName())) {
            return bo.getFileName();
        }
        if (StringUtils.isNotBlank(oss.getOriginalName())) {
            return oss.getOriginalName();
        }
        return "attachment." + suffix;
    }

    /**
     * 附件实体 → 视图对象。
     *
     * @param entity 附件实体
     * @param url    访问地址（可空）
     * @return 视图对象
     */
    private AiChatAttachmentVo toVo(AiMessageAttachment entity, String url) {
        AiChatAttachmentVo vo = new AiChatAttachmentVo();
        vo.setId(entity.getId());
        vo.setFileType(entity.getFileType());
        vo.setFileName(entity.getFileName());
        vo.setFileSuffix(entity.getFileSuffix());
        vo.setFileSize(entity.getFileSize());
        vo.setUrl(url);
        vo.setParsedCharCount(StringUtils.isBlank(entity.getParsedContent()) ? 0 : entity.getParsedContent().length());
        return vo;
    }

    /**
     * 解析 OSS 服务端读写地址（下载用，忽略对外域名，不存在即报错）。
     */
    private String resolveUrl(Long ossId) {
        String url = resolveInternalUrlQuietly(ossId);
        if (StringUtils.isBlank(url)) {
            throw new ServiceException("文件不存在或已被删除，请重新上传");
        }
        return url;
    }

    /**
     * 解析 OSS 服务端读写地址（下载用，忽略对外域名，不存在时返回 null 而不报错）。
     */
    private String resolveInternalUrlQuietly(Long ossId) {
        if (ossId == null) {
            return null;
        }
        String urls = ossService.selectInternalUrlByIds(String.valueOf(ossId));
        if (StringUtils.isBlank(urls)) {
            return null;
        }
        int index = urls.indexOf(',');
        return index > 0 ? urls.substring(0, index) : urls;
    }

    /**
     * 解析 OSS 访问地址（历史回显用，对外展示地址，不存在时返回 null 而不报错）。
     */
    private String resolveUrlQuietly(Long ossId) {
        if (ossId == null) {
            return null;
        }
        String urls = ossService.selectUrlByIds(String.valueOf(ossId));
        if (StringUtils.isBlank(urls)) {
            return null;
        }
        int index = urls.indexOf(',');
        return index > 0 ? urls.substring(0, index) : urls;
    }

    /**
     * 取文件名（容错空值）。
     */
    private String nameOf(String fileName) {
        return StringUtils.isBlank(fileName) ? "" : fileName;
    }

    /**
     * 规范化后缀。
     *
     * @param suffix 原始后缀或文件名
     * @return 小写且不含点的后缀；无法识别时为空串
     */
    private String normalizeSuffix(String suffix) {
        if (StringUtils.isBlank(suffix)) {
            return "";
        }
        String normalized = suffix.trim().toLowerCase(Locale.ROOT);
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex >= 0) {
            normalized = normalized.substring(dotIndex + 1);
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 人类可读的大小文案。
     *
     * @param bytes 字节数
     * @return 如 {@code 512KB} / {@code 5MB}
     */
    private String humanSize(long bytes) {
        long mb = 1024L * 1024L;
        if (bytes >= mb && bytes % mb == 0) {
            return (bytes / mb) + "MB";
        }
        return (bytes / 1024L) + "KB";
    }

}