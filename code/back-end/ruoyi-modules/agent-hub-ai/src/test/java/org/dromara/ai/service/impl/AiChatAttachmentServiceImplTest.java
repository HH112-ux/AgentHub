package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    @DisplayName("登记即写入 PENDING 与到期时刻（上传时间 + 暂存 TTL）")
    void shouldStampPendingLifecycleOnRegister() {
        stubDocumentUpload("你好");

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn("你好".getBytes(StandardCharsets.UTF_8));
            register(1L);
        }

        AiMessageAttachment saved = captureInserted();
        assertEquals(AiMessageAttachment.STATUS_PENDING, saved.getStatus());
        int ttlHours = aiProperties.getChat().getPendingAttachmentTtlHours();
        LocalDateTime before = LocalDateTime.now().plusHours(ttlHours).minusMinutes(5);
        LocalDateTime after = LocalDateTime.now().plusHours(ttlHours).plusMinutes(5);
        assertTrue(!saved.getExpireAt().isBefore(before) && !saved.getExpireAt().isAfter(after),
            "到期时刻应为「登记时间 + " + ttlHours + " 小时」：" + saved.getExpireAt());
    }

    @Test
    @DisplayName("暂存 TTL 关闭（<=0）时不写到期时刻")
    void shouldLeaveExpireAtNullWhenTtlDisabled() {
        aiProperties.getChat().setPendingAttachmentTtlHours(0);
        aiProperties.getChat().setPendingAttachmentMaxCount(0);
        aiProperties.getChat().setPendingAttachmentMaxBytes(0);
        stubDocumentUpload("你好");

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn("你好".getBytes(StandardCharsets.UTF_8));
            register(1L);
        }

        assertNull(captureInserted().getExpireAt(), "关闭回收时不得写期限，否则扫描会把永不过期的件当成到期件");
    }

    @Test
    @DisplayName("未发送附件个数超配额：拒绝登记且不落库")
    void shouldRejectWhenPendingCountQuotaExceeded() {
        stubDocumentUpload("你好");
        when(baseMapper.selectCount(any())).thenReturn((long) aiProperties.getChat().getPendingAttachmentMaxCount());

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn("你好".getBytes(StandardCharsets.UTF_8));
            ServiceException ex = assertThrows(ServiceException.class, () -> register(1L));
            assertEquals("未发送的附件已达上限（20 个），请先删除暂存附件或把它们随消息发送出去", ex.getMessage());
        }

        verify(baseMapper, never()).insert(any(AiMessageAttachment.class));
    }

    @Test
    @DisplayName("未发送附件合计字节超配额：本次字节计入后再比上限")
    void shouldRejectWhenPendingBytesQuotaExceeded() {
        stubDocumentUpload("你好");
        // 已用额度差一字节就满，加上本次上传即越界：配额必须算「已用 + 本次」而不是「已用」
        when(baseMapper.sumFileSizeByUserAndStatus(USER_ID, AiMessageAttachment.STATUS_PENDING))
            .thenReturn(aiProperties.getChat().getPendingAttachmentMaxBytes() - 1);

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn("你好".getBytes(StandardCharsets.UTF_8));
            ServiceException ex = assertThrows(ServiceException.class, () -> register(1L));
            assertEquals("未发送的附件合计已达上限（50MB），请先删除暂存附件或把它们随消息发送出去", ex.getMessage());
        }

        verify(baseMapper, never()).insert(any(AiMessageAttachment.class));
    }

    @Test
    @DisplayName("两条配额上限都关闭时不查配额")
    void shouldSkipQuotaWhenLimitsDisabled() {
        aiProperties.getChat().setPendingAttachmentMaxCount(0);
        aiProperties.getChat().setPendingAttachmentMaxBytes(0);
        stubDocumentUpload("你好");

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn("你好".getBytes(StandardCharsets.UTF_8));
            register(1L);
        }

        verify(baseMapper, never()).selectCount(any());
        verify(baseMapper, never()).sumFileSizeByUserAndStatus(any(), anyString());
        verify(baseMapper).insert(any(AiMessageAttachment.class));
    }

    @Test
    @DisplayName("随消息发送即转 BOUND，脱离暂存配额与到期扫描")
    void shouldMarkBoundWhenSent() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(attachment(AiMessageAttachment.STATUS_PENDING)));

        service().bindToMessage(1L, 100L, List.of(7L));

        ArgumentCaptor<AiMessageAttachment> captor = ArgumentCaptor.forClass(AiMessageAttachment.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals(AiMessageAttachment.STATUS_BOUND, captor.getValue().getStatus());
        assertEquals(100L, captor.getValue().getMessageId());
    }

    @Test
    @DisplayName("已进入回收流程的附件不能随消息发送")
    void shouldRejectRecyclingAttachmentWhenBinding() {
        for (String status : List.of(AiMessageAttachment.STATUS_ORPHANED, AiMessageAttachment.STATUS_RECYCLED)) {
            when(baseMapper.selectByIds(any())).thenReturn(List.of(attachment(status)));

            ServiceException ex = assertThrows(ServiceException.class,
                () -> service().bindToMessage(1L, 100L, List.of(7L)));
            assertEquals("附件已进入回收流程，请重新上传", ex.getMessage(), "status=" + status);
        }
        verify(baseMapper, never()).updateById(any(AiMessageAttachment.class));
    }

    @Test
    @DisplayName("历史回显排除已回收留档行，孤立留档行仍可见")
    void shouldExcludeRecycledFromHistory() {
        service().listByMessageId(5L);
        LambdaQueryWrapper<AiMessageAttachment> query = historyQuery();
        assertTrue(query.getSqlSegment().contains("<>"), "已回收行必须从历史查询里排掉");
        assertTrue(query.getParamNameValuePairs().containsValue(AiMessageAttachment.STATUS_RECYCLED));
        assertTrue(!query.getParamNameValuePairs().containsValue(AiMessageAttachment.STATUS_ORPHANED),
            "留档宽限期内的孤立附件文件还在桶里，不能一起排掉");
    }

    @Test
    @DisplayName("已进入回收流程的暂存件不再手动删除，交给回收任务")
    void shouldRejectDeleteWhenAlreadyRecycling() {
        AiMessageAttachment orphaned = attachment(AiMessageAttachment.STATUS_ORPHANED);
        when(baseMapper.selectById(7L)).thenReturn(orphaned);

        ServiceException ex = assertThrows(ServiceException.class, () -> service().deletePending(1L, 7L));
        assertEquals("附件已进入回收流程，无需重复删除", ex.getMessage());
        verify(attachmentReclaimer, never()).reclaim(any());
    }

    /**
     * 造一条属于本会话、未绑定消息的附件。
     *
     * @param status 生命周期状态
     */
    private AiMessageAttachment attachment(String status) {
        AiMessageAttachment attachment = new AiMessageAttachment();
        attachment.setId(7L);
        attachment.setSessionId(1L);
        attachment.setOssId(OSS_ID);
        attachment.setStatus(status);
        return attachment;
    }

    /**
     * 取历史查询下发的条件。
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<AiMessageAttachment> historyQuery() {
        ArgumentCaptor<Wrapper<AiMessageAttachment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<AiMessageAttachment>) captor.getValue();
    }

    /**
     * 取落库的那条附件行。
     */
    private AiMessageAttachment captureInserted() {
        ArgumentCaptor<AiMessageAttachment> captor = ArgumentCaptor.forClass(AiMessageAttachment.class);
        verify(baseMapper).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * 打通「文本文件登记」所需的 OSS 元信息（不校验配额时的最小桩）。
     *
     * @param fileName 登记文件名
     */
    private void stubDocumentUpload(String fileName) {
        OssDTO oss = oss("txt");
        oss.setOriginalName(fileName);
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss));
        when(ossService.selectInternalUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://minio/x.txt");
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