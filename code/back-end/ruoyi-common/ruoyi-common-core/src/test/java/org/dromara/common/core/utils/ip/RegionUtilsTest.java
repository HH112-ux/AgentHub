package org.dromara.common.core.utils.ip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ip2region_v4.xdb 已随仓分发（登记于 docs/open-source-licenses.md），测试类路径包含地址库。
 * 缺库/初始化失败的降级路径无法靠真实缺库触发，用反射置空实例构造，验证后恢复原实例。
 *
 * @author AgentHub
 */
@DisplayName("RegionUtils 地址库容错与降级")
class RegionUtilsTest {

    @Test
    @DisplayName("地址库正常加载时查询返回非空归属地")
    void shouldReturnRegionWhenXdbLoaded() {
        String region = RegionUtils.getRegion("8.8.8.8");
        assertFalse(region.isBlank());
        assertNotEquals("未知", region, "地址库随仓分发后 8.8.8.8 应命中真实归属地，而非降级值");
        assertFalse(RegionUtils.getRegion(new byte[]{8, 8, 8, 8}).isBlank());
    }

    @Test
    @DisplayName("实例缺失时查询降级为未知且关闭安全")
    void shouldDegradeToUnknownWhenInstanceMissing() throws Exception {
        Field field = RegionUtils.class.getDeclaredField("ip2Region");
        field.setAccessible(true);
        Object saved = field.get(null);
        field.set(null, null);
        try {
            assertEquals("未知", RegionUtils.getRegion("8.8.8.8"));
            assertEquals("未知", RegionUtils.getRegion(new byte[]{8, 8, 8, 8}));
            assertDoesNotThrow(RegionUtils::close);
            assertDoesNotThrow(() -> RegionUtils.close(Duration.ofSeconds(1)));
            assertDoesNotThrow(() -> RegionUtils.close(null));
        } finally {
            field.set(null, saved);
        }
    }
}
