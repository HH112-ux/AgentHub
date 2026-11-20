package org.dromara.ai.core.mcp;

import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpSelfBootstrap} 单元测试：幂等插入与凭据构造。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP 自演示引导")
class McpSelfBootstrapTest {

    @Mock
    private AiMcpClientMapper mcpClientMapper;

    @Mock
    private AiSecretManager secretManager;

    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 注入最小内存容器：供 {@code IdGeneratorUtil} / {@code LambdaQueryWrapper} 等静态初始化使用，不连真实外部依赖。
     */
    @BeforeAll
    static void initStaticContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("不存在同名连接时插入：白名单为带命名空间的导出名、Authorization 头为密文")
    void shouldInsertWhenAbsent() {
        when(mcpClientMapper.selectOne(any())).thenReturn(null);
        AiMcpServerConfigBo config = new AiMcpServerConfigBo();
        config.setToken("AgentHub");
        when(runtimeConfigStore.mcpServer()).thenReturn(config);
        when(secretManager.encrypt("{\"Authorization\":\"Bearer AgentHub\"}")).thenReturn("cipher");

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(123L);
            new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);
        }

        ArgumentCaptor<AiMcpClient> captor = ArgumentCaptor.forClass(AiMcpClient.class);
        verify(mcpClientMapper).insert(captor.capture());
        AiMcpClient inserted = captor.getValue();
        assertEquals(Long.valueOf(123L), inserted.getId());
        assertEquals("platform_system_info", inserted.getServerName());
        assertEquals(AiMcpClient.TRANSPORT_HTTP, inserted.getTransport());
        assertEquals("http://localhost:8080/mcp", inserted.getUrl());
        assertEquals("agenthub_get_system_info", inserted.getEnableTools());
        assertEquals("cipher", inserted.getHeadersJson());
        assertEquals(AiMcpClient.STATUS_ENABLED, inserted.getStatus());
    }

    @Test
    @DisplayName("已存在同名连接且白名单已是导出名时不再插入、不改写（幂等）")
    void shouldSkipWhenPresent() {
        when(mcpClientMapper.selectOne(any())).thenReturn(client(7L, "agenthub_get_system_info"));

        new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);

        verify(mcpClientMapper, never()).insert(any(AiMcpClient.class));
        verify(mcpClientMapper, never()).updateById(any(AiMcpClient.class));
    }

    @Test
    @DisplayName("已存在同名连接且白名单为旧版未加前缀时迁移为导出名")
    void shouldMigrateLegacyToolNameWhenPresent() {
        when(mcpClientMapper.selectOne(any())).thenReturn(client(7L, "get_system_info"));

        new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);

        ArgumentCaptor<AiMcpClient> captor = ArgumentCaptor.forClass(AiMcpClient.class);
        verify(mcpClientMapper).updateById(captor.capture());
        assertEquals(Long.valueOf(7L), captor.getValue().getId());
        assertEquals("agenthub_get_system_info", captor.getValue().getEnableTools());
        verify(mcpClientMapper, never()).insert(any(AiMcpClient.class));
    }

    @Test
    @DisplayName("已存在同名连接且白名单被人工改过时不改写")
    void shouldKeepManuallyEditedToolName() {
        when(mcpClientMapper.selectOne(any())).thenReturn(client(7L, "agenthub_get_system_info,other"));

        new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);

        verify(mcpClientMapper, never()).updateById(any(AiMcpClient.class));
        verify(mcpClientMapper, never()).insert(any(AiMcpClient.class));
    }

    /**
     * 构造已存在的自演示连接。
     *
     * @param id         主键
     * @param enableTools 白名单
     * @return 连接实体
     */
    private AiMcpClient client(Long id, String enableTools) {
        AiMcpClient client = new AiMcpClient();
        client.setId(id);
        client.setServerName("platform_system_info");
        client.setEnableTools(enableTools);
        return client;
    }

    @Test
    @DisplayName("出口口令为空时不写 Authorization 头")
    void shouldOmitHeaderWhenTokenBlank() {
        when(mcpClientMapper.selectOne(any())).thenReturn(null);
        AiMcpServerConfigBo config = new AiMcpServerConfigBo();
        config.setToken("");
        when(runtimeConfigStore.mcpServer()).thenReturn(config);

        try (MockedStatic<IdGeneratorUtil> id = mockStatic(IdGeneratorUtil.class)) {
            id.when(IdGeneratorUtil::nextLongId).thenReturn(456L);
            new McpSelfBootstrap(mcpClientMapper, secretManager, runtimeConfigStore).run(null);
        }

        ArgumentCaptor<AiMcpClient> captor = ArgumentCaptor.forClass(AiMcpClient.class);
        verify(mcpClientMapper).insert(captor.capture());
        assertNull(captor.getValue().getHeadersJson());
    }

}