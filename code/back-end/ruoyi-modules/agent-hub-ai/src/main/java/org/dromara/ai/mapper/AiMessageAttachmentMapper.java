package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    /**
     * 汇总某用户处于指定生命周期状态的附件字节数（暂存配额用，§12.6）。
     *
     * <p>手写 SQL 只取聚合值，避免为了算总量把整行（含文档解析出的 {@code parsed_content} 正文）读进内存；
     * {@code coalesce} 与逻辑删除条件是双方言共用的写法，不走 MyBatis-Plus 的自动拼接。</p>
     *
     * @param userId 用户主键（{@code create_by}）
     * @param status 生命周期状态
     * @return 合计字节数，无记录时为 0
     */
    @Select("select coalesce(sum(file_size), 0) from ai_message_attachment "
        + "where create_by = #{userId} and status = #{status} and del_flag = '0'")
    Long sumFileSizeByUserAndStatus(@Param("userId") Long userId, @Param("status") String status);

}