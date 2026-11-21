package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 记忆版本恢复参数。
 *
 * @author AgentHub
 */
@Data
public class AiMemoryRestoreBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "智能体不能为空")
    private Long agentId;

    @NotNull(message = "用户不能为空")
    private Long userId;

    /**
     * 恢复模式：CONTENT 只恢复正文 / CONTENT_AND_WATERMARK 同时恢复合并水位
     */
    @NotBlank(message = "恢复模式不能为空")
    @Pattern(regexp = "^(CONTENT|CONTENT_AND_WATERMARK)$",
        message = "恢复模式只能是 CONTENT / CONTENT_AND_WATERMARK")
    private String mode = "CONTENT";

    @Size(max = 200, message = "备注不能超过 200 字")
    private String note;

    /**
     * 二次确认位（§12.2 / §12.7 I-11）：必须显式为 {@code true} 才执行覆盖。
     *
     * <p>故意不加 {@code @NotNull}：判据落在 {@code IAiMemoryService#restoreVersion} 里，
     * 缺字段时也走同一条领域报错，而不是被 bean 校验挡在 HTTP 入口——
     * 日后新增的批量或渠道入口绕过本控制器时，确认要求不能跟着一起被绕过。</p>
     */
    private Boolean confirmed;
}
