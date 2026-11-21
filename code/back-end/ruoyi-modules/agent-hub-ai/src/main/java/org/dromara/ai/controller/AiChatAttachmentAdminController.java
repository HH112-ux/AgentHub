package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.chat.AiAttachmentReclaimTask;
import org.dromara.ai.core.chat.AiAttachmentReclaimer;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天附件运维接口。
 *
 * <p>暂存附件由后台任务每小时自动回收；本接口供运维与验收**立即执行一轮**，
 * 不必等到下一个定时点。多副本部署时由 Redis 锁抢占，已被其它实例占用时返回
 * {@code executed=false}（不报错）。</p>
 *
 * @author AgentHub
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/chat/attachment")
public class AiChatAttachmentAdminController extends BaseController {

    private final AiAttachmentReclaimTask reclaimTask;

    /**
     * 立即执行一轮到期附件回收：先收超期未发送的暂存件（删对象 + 删行），再收过了留档宽限的孤立件（删对象 + 转 RECYCLED）。
     *
     * @return 回收统计（executed / scanned / deleted / recycled / failed）
     */
    @SaCheckPermission("ai:chat:attachment:reclaim")
    @PostMapping("/reclaim")
    public R<Map<String, Object>> reclaim() {
        AiAttachmentReclaimer.ReclaimResult result = reclaimTask.runNow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executed", result != null);
        data.put("scanned", result == null ? 0 : result.scanned());
        data.put("deleted", result == null ? 0 : result.deleted());
        data.put("recycled", result == null ? 0 : result.recycled());
        data.put("failed", result == null ? 0 : result.failed());
        return R.ok(data);
    }

}
