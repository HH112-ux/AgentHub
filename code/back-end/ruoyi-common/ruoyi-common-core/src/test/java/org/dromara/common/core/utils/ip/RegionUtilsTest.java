package org.dromara.common.core.utils.ip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 前提：仓库不携带 ip2region 二进制地址库，测试类路径下没有 xdb 文件，
 * 复现登录链路在生产环境缺库时静态初始化抛异常导致登录 500 的故障场景。
 * 若未来选择把地址库提交进 resources（Task C 方案 b），需同步调整本测试前提。
 *
 * @author AgentHub
 */
@DisplayName("RegionUtils 地址库容错降级")
class RegionUtilsTest {

    @Test
    @DisplayName("缺库时类初始化不抛异常且查询降级为未知")
    void shouldDegradeToUnknownWhenXdbMissing() {
        assertEquals("未知", RegionUtils.getRegion("8.8.8.8"));
        assertEquals("未知", RegionUtils.getRegion(new byte[]{8, 8, 8, 8}));
    }

    @Test
    @DisplayName("缺库时关闭方法不抛异常")
    void shouldCloseSafelyWhenXdbMissing() {
        assertDoesNotThrow(RegionUtils::close);
        assertDoesNotThrow(() -> RegionUtils.close(Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> RegionUtils.close(null));
    }
}
