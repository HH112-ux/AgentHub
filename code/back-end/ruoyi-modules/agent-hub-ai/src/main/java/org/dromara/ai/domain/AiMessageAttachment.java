package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI 会话消息附件对象 ai_message_attachment
 *
 * <p>用户与小Z 聊天时上传的图片 / 文档：图片以 Base64 data URI 下发给模型，文档解析为纯文本内联进本轮对话。</p>
 *
 * <p>生命周期（§12.6 / §12.7 I-10）为四态：上传登记即 {@code PENDING}（占用作者暂存配额，{@code expire_at} 为暂存期限）；
 * 随消息发送转 {@code BOUND}；所绑会话或整轮被删除转 {@code ORPHANED} 并按留档宽限重写 {@code expire_at}；
 * 回收任务删掉对象存储文件后转 {@code RECYCLED}，只留文件名 / 大小等元数据。
 * 从未发送过的 {@code PENDING} 到期后由回收任务连行一起删除（没有任何消息引用它，留行只会永久占配额账目）。</p>
 *
 * @author AgentHub
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_message_attachment")
public class AiMessageAttachment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 附件类型：图片
     */
    public static final String TYPE_IMAGE = "image";

    /**
     * 附件类型：文档
     */
    public static final String TYPE_DOCUMENT = "document";

    /**
     * 生命周期：已上传登记、尚未随消息发送（占用作者暂存配额，见 §12.6）
     */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * 生命周期：已绑定到用户消息，随会话存续，不参与回收扫描
     */
    public static final String STATUS_BOUND = "BOUND";

    /**
     * 生命周期：所绑会话或整轮已删除，留档宽限期内对象文件仍在（导出与误删回看用）
     */
    public static final String STATUS_ORPHANED = "ORPHANED";

    /**
     * 生命周期：对象文件已回收，仅保留元数据行，不再对外给出访问地址
     */
    public static final String STATUS_RECYCLED = "RECYCLED";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 所属用户消息（ai_message.id）；为空表示上传后尚未发送的暂存件
     */
    private Long messageId;

    /**
     * 附件类型（image 图片 / document 文档）
     */
    private String fileType;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件后缀（小写，不含点）
     */
    private String fileSuffix;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * MIME 类型（图片为 image/*；文档可空）
     */
    private String mimeType;

    /**
     * 对象存储主键（sys_oss.oss_id）
     */
    private Long ossId;

    /**
     * 文档解析后的纯文本（图片为空）
     */
    private String parsedContent;

    /**
     * 生命周期状态（{@link #STATUS_PENDING} / {@link #STATUS_BOUND} / {@link #STATUS_ORPHANED} / {@link #STATUS_RECYCLED}）
     */
    private String status;

    /**
     * 回收期限：{@code PENDING} 为上传时间 + 暂存 TTL，{@code ORPHANED} 为转孤立时间 + 留档宽限；
     * {@code BOUND} 保留登记时的原值但不参与扫描（扫描按 {@code status} 过滤）
     */
    private LocalDateTime expireAt;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}