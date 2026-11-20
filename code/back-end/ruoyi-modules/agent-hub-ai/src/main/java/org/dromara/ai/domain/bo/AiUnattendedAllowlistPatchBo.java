package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 无人值守写工具白名单的保存对象。
 *
 * <p>独立于 {@link AiSecurityConfigBo}：白名单写入权限与安全合规编辑权限分离，
 * 且必须携带变更理由用于审计，避免「悄悄放行写工具」无法追溯。</p>
 *
 * @author AgentHub
 */
@Data
public class AiUnattendedAllowlistPatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 允许无人值守自动执行的写工具编码（提交的是完整名单；空列表表示全部拒绝）
     */
    @NotNull(message = "工具编码列表不能为空")
    private List<String> toolCodes;

    /**
     * 变更理由（必填，记入服务端日志供审计追溯）
     */
    @NotBlank(message = "变更理由不能为空")
    @Size(max = 200, message = "变更理由不能超过 200 字")
    private String reason;

}
