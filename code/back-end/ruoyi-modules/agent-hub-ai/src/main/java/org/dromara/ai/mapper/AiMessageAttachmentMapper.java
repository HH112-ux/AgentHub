package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;

/**
 * AI 会话消息附件 Mapper 接口
 *
 * @author AgentHub
 */
public interface AiMessageAttachmentMapper extends BaseMapperPlus<AiMessageAttachment, AiMessageAttachment> {

    /**
     * 物理删除附件行（绕过 {@code @TableLogic}）。
     *
     * <p>回收场景专用：对象文件已删除或不再需要，逻辑删除会让「暂存件」继续占行；
     * 附件不承担审计职责，故直接物理删除。</p>
     *
     * @param ids 主键集合
     * @return 删除行数
     */
    @Delete("<script>delete from ai_message_attachment where id in "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deletePhysicallyByIds(@Param("ids") Collection<Long> ids);

}