package org.dromara.ai.controller;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.memory.spi.AiMemoryBackend;
import org.dromara.ai.core.memory.spi.MemoryBackendStats;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.bo.AiMemoryRestoreBo;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;
import org.dromara.ai.domain.vo.AiMemoryQualityVo;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.R;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiMemoryController} 单元测试：长期记忆配置、记忆文件与手动合并。
 */
@DisplayName("AI 长期记忆 Controller")
class AiMemoryControllerTest {

    private final IAiMemoryService memoryService = mock(IAiMemoryService.class);

    private final AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

    private final AiMemoryBackend memoryBackend = mock(AiMemoryBackend.class);

    private final AiMemoryController controller =
        new AiMemoryController(memoryService, store, memoryBackend);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询长期记忆配置")
    void shouldGetConfig() {
        AiMemoryConfigBo bo = new AiMemoryConfigBo();
        when(store.memory()).thenReturn(bo);
        assertSame(bo, controller.getConfig().getData());
    }

    @Test
    @DisplayName("保存长期记忆配置")
    void shouldUpdateConfig() {
        AiMemoryConfigBo bo = new AiMemoryConfigBo();
        assertTrue(R.isSuccess(controller.updateConfig(bo)));
        verify(store).updateMemory(bo);
    }

    @Test
    @DisplayName("查询记忆文件清单")
    void shouldListFiles() {
        List<AiMemoryFileVo> files = List.of(new AiMemoryFileVo());
        when(memoryService.listFiles(1L, 2L)).thenReturn(files);
        assertSame(files, controller.listFiles(1L, 2L).getData());
    }

    @Test
    @DisplayName("读取记忆文件内容")
    void shouldReadContent() {
        AiMemoryContentVo vo = new AiMemoryContentVo();
        when(memoryService.readContent(1L, 2L, "MEMORY.md")).thenReturn(vo);
        assertSame(vo, controller.readContent(1L, 2L, "MEMORY.md").getData());
    }

    @Test
    @DisplayName("保存记忆文件内容")
    void shouldWriteContent() {
        AiMemoryContentBo bo = new AiMemoryContentBo();
        assertTrue(R.isSuccess(controller.writeContent(bo)));
        verify(memoryService).writeContent(bo);
    }

    @Test
    @DisplayName("手动触发记忆合并")
    void shouldConsolidate() {
        assertTrue(R.isSuccess(controller.consolidate(1L, 2L)));
        verify(memoryService).consolidate(1L, 2L);
    }

    @Test
    @DisplayName("查询记忆统计走记忆后端")
    void shouldGetStatsFromBackend() {
        MemoryBackendStats stats = MemoryBackendStats.empty(2L);
        when(memoryBackend.stats(2L)).thenReturn(stats);
        assertSame(stats, controller.stats(2L).getData());
    }

    @Test
    @DisplayName("查询记忆运行健康指标")
    void shouldGetQuality() {
        AiMemoryQualityVo quality = new AiMemoryQualityVo();
        quality.setDays(7);
        when(memoryService.quality(2L, 7)).thenReturn(quality);
        assertSame(quality, controller.quality(2L, 7).getData());
    }

    @Test
    @DisplayName("恢复版本：勾选确认才把 true 交给服务层")
    void shouldPassRestoreConfirmation() {
        AiMemoryRestoreBo bo = restoreBo();
        bo.setConfirmed(true);

        controller.restoreVersion("v1", bo);

        verify(memoryService).restoreVersion(1L, 2L, "v1", "CONTENT", "回滚误删", true);
    }

    /**
     * 确认位缺失要传 false 而不是「没填就当默认同意」：入口注解管不到日后新增的批量入口，
     * 一旦这里写成 {@code bo.getConfirmed() != Boolean.FALSE}，二次确认就只剩下页面自觉。
     */
    @Test
    @DisplayName("恢复版本：确认位未填按未确认处理")
    void shouldTreatMissingRestoreConfirmationAsUnconfirmed() {
        controller.restoreVersion("v1", restoreBo());

        verify(memoryService).restoreVersion(1L, 2L, "v1", "CONTENT", "回滚误删", false);
    }

    private AiMemoryRestoreBo restoreBo() {
        AiMemoryRestoreBo bo = new AiMemoryRestoreBo();
        bo.setAgentId(1L);
        bo.setUserId(2L);
        bo.setMode("CONTENT");
        bo.setNote("回滚误删");
        return bo;
    }

}