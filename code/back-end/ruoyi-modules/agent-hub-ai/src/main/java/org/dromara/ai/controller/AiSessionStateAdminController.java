package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.agent.AiSessionStateReaper;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话模型侧记忆的运维接口。
 *
 * <p>闲置会话的状态由后台任务每天自动清理；本接口供运维与验收**立即执行一轮**。
 * 多副本部署时由 Redis 锁抢占，已被其它实例占用或开关关闭时返回 {@code executed=false}（不报错）。</p>
 *
 * @author AgentHub
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/session/state")
public class AiSessionStateAdminController extends BaseController {

    private final AiSessionStateReaper reaper;

    /**
     * 立即回收闲置会话的模型侧记忆。
     *
     * @return 回收统计（executed / scanned / cleared / failed）
     */
    @SaCheckPermission("ai:session:state:reap")
    @PostMapping("/reap")
    public R<Map<String, Object>> reap() {
        AiSessionStateReaper.ReapResult result = reaper.runNow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executed", result != null);
        data.put("scanned", result == null ? 0 : result.scanned());
        data.put("cleared", result == null ? 0 : result.cleared());
        data.put("failed", result == null ? 0 : result.failed());
        return R.ok(data);
    }

}
