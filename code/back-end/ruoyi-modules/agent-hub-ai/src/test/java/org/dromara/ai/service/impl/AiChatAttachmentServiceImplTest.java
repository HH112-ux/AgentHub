package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiVisionGuard;
import org.dromara.ai.core.chat.AiAttachmentReclaimer;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.domain.bo.AiChatAttachmentBo;
import org.dromara.ai.domain.vo.AiChatAttachmentVo;
import org.dromara.ai.mapper.AiMessageAttachmentMapper;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatAttachmentServiceImpl} 单元测试：类型/大小校验、文档解析与附件绑定。
 */
@DisplayName("AI 对话附件 Service")
@ExtendWith(MockitoExtension.class)
class AiChatAttachmentServiceImplTest {

    private static final Long OSS_ID = 1L;

    /**
     * 当前登录用户（OSS 记录的上传人须与之一致）
     */
    private static final Long USER_ID = 9L;

    @Mock
    private AiMessageAttachmentMapper baseMapper;

    @Mock
    private OssService ossService;

    @Mock
    private AiVisionGuard visionGuard;

    @Mock
    private AiAttachmentReclaimer attachmentReclaimer;

    private final AiProperties aiProperties = new AiProperties();

    private AiChatAttachmentServiceImpl service() {
        return new AiChatAttachmentServiceImpl(baseMapper, ossService, aiProperties, visionGuard, attachmentReclaimer);
    }

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("不支持的格式被拒绝")
    void shouldRejectUnsupportedSuffix() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("exe")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> register(1L));
        assertEquals("不支持的文件类型：.exe，图片仅支持 png / jpg / jpeg / gif / webp / heic / heif；"
            + "文档仅支持 PDF / docx / xlsx / pptx / 文本类文件", ex.getMessage());
    }

    @Test
    @DisplayName("借用他人 ossId 被拒绝且不删除对方对象")
    void shouldRejectForeignOss() {
        OssDTO foreign = oss("exe");
        foreign.setCreateBy(100L);
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(foreign));

        ServiceException ex = assertThrows(ServiceException.class, () -> register(1L));
        assertEquals("文件不存在或已被删除，请重新上传", ex.getMessage());
        verify(ossService, never()).deleteByIds(anyString());
    }

    @Test
    @DisplayName("图片超过大小上限被拒绝")
    void shouldRejectOversizeImage() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("png")));
        when(ossService.selectInternalUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://minio/x.png");
        byte[] oversized = new byte[(int) aiProperties.getChat().getImageMaxBytes() + 1];

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn(oversized);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> register(1L));
            assertEquals("图片大小超过限制（最大 512KB）", ex.getMessage());
        }
    }

    @Test
    @DisplayName("登记失败时回收本次上传的 OSS 对象")
    void shouldDiscardUploadedObjectWhenRegisterFails() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("exe")));

        assertThrows(ServiceException.class, () -> register(1L));
        verify(ossService).deleteByIds(String.valueOf(OSS_ID));
    }

    @Test
    @DisplayName("登记失败后回收对象本身出错不影响对外异常")
    void shouldKeepOriginalFailureWhenDiscardFails() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("exe")));
        doThrow(new IllegalStateException("oss down")).when(ossService).deleteByIds(String.valueOf(OSS_ID));

        ServiceException ex = assertThrows(ServiceException.class, () -> register(1L));
        assertEquals("不支持的文件类型：.exe，图片仅支持 png / jpg / jpeg / gif / webp / heic / heif；"
            + "文档仅支持 PDF / docx / xlsx / pptx / 文本类文件", ex.getMessage());
    }

    @Test
    @DisplayName("文档登记解析文本并落库")
    void shouldRegisterDocumentAndParse() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("txt")));
        // 下载走服务端内部地址（忽略对外域名），VO 回显走对外地址
        when(ossService.selectInternalUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://minio/x.txt");
        when(ossService.selectUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://oss/x.txt");
        byte[] content = "你好智造".getBytes(StandardCharsets.UTF_8);

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn(content);
            AiChatAttachmentVo vo = register(1L);

            assertEquals(AiMessageAttachment.TYPE_DOCUMENT, vo.getFileType());
            assertEquals(4, vo.getParsedCharCount());
            assertEquals("http://oss/x.txt", vo.getUrl());
        }
        // 登记成功不应误删已登记附件对应的对象
        verify(ossService, never()).deleteByIds(anyString());
    }

    @Test
    @DisplayName("单条消息附件数超限被拒绝")
    void shouldRejectTooManyAttachments() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service().bindToMessage(1L, 100L, List.of(1L, 2L, 3L, 4L, 5L, 6L)));
        assertEquals("单条消息最多携带 5 个附件", ex.getMessage());
    }

    @Test
    @DisplayName("跨会话附件被拒绝")
    void shouldRejectForeignSessionAttachment() {
        AiMessageAttachment foreign = new AiMessageAttachment();
        foreign.setId(1L);
        foreign.setSessionId(2L);
        when(baseMapper.selectByIds(any())).thenReturn(List.of(foreign));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service().bindToMessage(1L, 100L, List.of(1L)));
        assertEquals("附件不属于该会话，请重新上传", ex.getMessage());
    }

    @Test
    @DisplayName("重新签发附件地址：本人会话返回新签 URL")
    void shouldRefreshUrlForOwnedAttachment() {
        AiMessageAttachment attachment = new AiMessageAttachment();
        attachment.setId(9L);
        attachment.setSessionId(1L);
        attachment.setOssId(OSS_ID);
        attachment.setFileType(AiMessageAttachment.TYPE_IMAGE);
        attachment.setFileName("a.png");
        when(baseMapper.selectById(9L)).thenReturn(attachment);
        when(ossService.selectUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://oss/a.png?X-Amz-Expires=120");

        AiChatAttachmentVo vo = service().refreshUrl(1L, 9L);

        assertEquals("http://oss/a.png?X-Amz-Expires=120", vo.getUrl());
        assertEquals("a.png", vo.getFileName());
    }

    @Test
    @DisplayName("重新签发附件地址：跨会话附件被拒绝")
    void shouldRejectRefreshForForeignAttachment() {
        AiMessageAttachment foreign = new AiMessageAttachment();
        foreign.setId(9L);
        foreign.setSessionId(2L);
        when(baseMapper.selectById(9L)).thenReturn(foreign);

        ServiceException ex = assertThrows(ServiceException.class, () -> service().refreshUrl(1L, 9L));
        assertEquals("附件不存在或不属于该会话", ex.getMessage());
    }

    @Test
    @DisplayName("仅图片登记触发视觉能力校验，文档不触发")
    void shouldCheckVisionOnlyForImage() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("txt")));
        when(ossService.selectInternalUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://minio/x.txt");
        when(ossService.selectUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://oss/x.txt");
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn("你好".getBytes(StandardCharsets.UTF_8));
            register(1L);
        }
        verify(visionGuard, never()).assertImageSupported(any());

        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("png")));
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn(new byte[8]);
            register(1L);
        }
        verify(visionGuard).assertImageSupported(1L);
    }

    @Test
    @DisplayName("图片附件：模型未声明支持视觉时透传校验异常")
    void shouldRejectImageWhenVisionUnsupported() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("png")));
        when(ossService.selectInternalUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://minio/x.png");
        doThrow(new ServiceException("图片需要「支持视觉」的对话模型")).when(visionGuard).assertImageSupported(1L);

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn(new byte[8]);
            ServiceException ex = assertThrows(ServiceException.class, () -> register(1L));
            assertEquals("图片需要「支持视觉」的对话模型", ex.getMessage());
        }
    }

    private AiChatAttachmentBo bo() {
        AiChatAttachmentBo bo = new AiChatAttachmentBo();
        bo.setOssId(OSS_ID);
        bo.setFileName("x.txt");
        return bo;
    }

    /**
     * 以本人身份登记附件（归一化测试里登录态与 OSS 上传人）
     */
    private AiChatAttachmentVo register(Long sessionId) {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);
            return service().register(sessionId, bo());
        }
    }

    private OssDTO oss(String suffix) {
        OssDTO dto = new OssDTO();
        dto.setOssId(OSS_ID);
        dto.setFileSuffix(suffix);
        dto.setOriginalName("x." + suffix);
        dto.setCreateBy(USER_ID);
        return dto;
    }

}