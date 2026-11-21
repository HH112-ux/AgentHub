package org.dromara.ai.core.chat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.mapper.AiMessageAttachmentMapper;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.system.api.OssService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiAttachmentReclaimer} 单元测试：§12.6 四态生命周期的收痕口径
 * （到期暂存件连行删掉、到期孤立件只转 RECYCLED 留元数据、对象删除失败一律保留原状态等下一轮）。
 *
 * <p>取片条件在这里是**可观测**的：{@code AiTestSupport} 为实体注册了 MyBatis-Plus 的 lambda 缓存，
 * 因此 captured wrapper 的 {@code getSqlSegment()} 就是真实下发的那段条件 SQL，
 * 「最旧的先收」与「翻页游标」都能按行为断言，不必退化成扫源码。</p>
 */
@DisplayName("AI 附件回收器")
class AiAttachmentReclaimerTest {

    private final AiMessageAttachmentMapper attachmentMapper = mock(AiMessageAttachmentMapper.class);

    private final OssService ossService = mock(OssService.class);

    private final AiProperties aiProperties = new AiProperties();

    private final AiAttachmentReclaimer reclaimer =
        new AiAttachmentReclaimer(attachmentMapper, ossService, aiProperties);

    @BeforeAll
    static void initStaticContext() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("到期暂存件：先删对象再物理删行")
    void shouldDeleteRowOfExpiredPending() {
        when(attachmentMapper.selectList(any())).thenReturn(List.of(pending(11L, 101L), pending(12L, 102L)));

        AiAttachmentReclaimer.ReclaimResult result = reclaimer.reclaimPending(24, 1);

        assertEquals(2, result.scanned());
        assertEquals(0, result.recycled());
        assertEquals(0, result.failed());
        // 顺序是契约：先删行后删对象一旦中途失败，桶里留下的文件在库里再也找不到主人，比留一行更糟
        InOrder order = inOrder(ossService, attachmentMapper);
        order.verify(ossService).deleteByIds("101");
        order.verify(ossService).deleteByIds("102");
        order.verify(attachmentMapper).deletePhysicallyByIds(List.of(11L, 12L));
        // 暂存件不留在表里：没有消息引用它，留一行只会永久占着配额账目
        verify(attachmentMapper, never()).update(any(AiMessageAttachment.class), any());
    }

    @Test
    @DisplayName("取片只收到期 PENDING，且按到期时间、主键升序（最旧的先收）")
    void shouldSweepOldestPendingFirst() {
        when(attachmentMapper.selectList(any())).thenReturn(List.of(pending(11L, 101L)));

        reclaimer.reclaimPending(24, 1);

        LambdaQueryWrapper<AiMessageAttachment> query = captureQuery();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("status ="), "取片必须按状态过滤，否则 BOUND 也会被收掉：" + sql);
        assertTrue(sql.contains("expire_at <="), "到期判定必须落在 expire_at 上：" + sql);
        assertTrue(sql.contains("ORDER BY expire_at ASC,id ASC"), "必须最旧的先收：" + sql);
        assertTrue(query.getParamNameValuePairs().containsValue(AiMessageAttachment.STATUS_PENDING),
            "暂存件那一遍只应扫 PENDING：" + query.getParamNameValuePairs());
    }

    @Test
    @DisplayName("满页后用到期时间与主键组成的游标翻页，同期限的行不被跳过")
    void shouldPageWithCompositeCursor() {
        int size = AiAttachmentReclaimer.BATCH_SIZE;
        when(attachmentMapper.selectList(any()))
            .thenReturn(fullPage(size))
            .thenReturn(List.of(pending(99L, 199L)));

        reclaimer.reclaimPending(24, 5);

        ArgumentCaptor<Wrapper<AiMessageAttachment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(attachmentMapper, times(2)).selectList(captor.capture());
        String second = captor.getAllValues().get(1).getSqlSegment();
        assertTrue(second.contains("OR"), "翻页游标必须带上「期限相同则比主键」的一支，否则会整批跳过同期限的行：" + second);
        assertTrue(second.contains("id <"), "游标应向更旧的方向推进：" + second);
    }

    @Test
    @DisplayName("对象删除失败的暂存件保留原行，等下一轮重试")
    void shouldKeepPendingRowWhenObjectDeleteFails() {
        when(attachmentMapper.selectList(any())).thenReturn(List.of(pending(11L, 101L)));
        doThrow(new IllegalStateException("oss down")).when(ossService).deleteByIds("101");

        AiAttachmentReclaimer.ReclaimResult result = reclaimer.reclaimPending(24, 1);

        assertEquals(1, result.scanned());
        assertEquals(1, result.failed());
        verify(attachmentMapper, never()).deletePhysicallyByIds(anyList());
    }

    @Test
    @DisplayName("暂存阈值 <=0 时关闭暂存件那一遍，不查库也不删对象")
    void shouldSkipPendingSweepWhenTtlDisabled() {
        AiAttachmentReclaimer.ReclaimResult result = reclaimer.reclaimPending(0, 5);

        assertEquals(0, result.scanned());
        verifyNoInteractions(attachmentMapper, ossService);
    }

    @Test
    @DisplayName("满页继续翻页，短页即止（不白跑满批数上限）")
    void shouldKeepPagingUntilShortPage() {
        int size = AiAttachmentReclaimer.BATCH_SIZE;
        when(attachmentMapper.selectList(any()))
            .thenReturn(fullPage(size))
            .thenReturn(fullPage(size))
            .thenReturn(List.of());

        AiAttachmentReclaimer.ReclaimResult result = reclaimer.reclaimPending(24, 5);

        verify(attachmentMapper, times(3)).selectList(any());
        assertEquals(2 * size, result.scanned());
    }

    @Test
    @DisplayName("到期孤立件：删对象后转 RECYCLED，保留元数据行")
    void shouldRecycleOrphanWithoutDeletingRow() {
        when(attachmentMapper.selectList(any())).thenReturn(List.of(orphan(21L, 201L)));
        when(attachmentMapper.update(any(AiMessageAttachment.class), any())).thenReturn(1);

        AiAttachmentReclaimer.ReclaimResult result = reclaimer.reclaimOrphans(1);

        assertEquals(1, result.scanned());
        assertEquals(1, result.recycled());
        // 先标已回收再删对象的话，中途失败会留下「文件还在桶里、记录却说已回收」的行：既删不掉也找不回
        InOrder order = inOrder(ossService, attachmentMapper);
        order.verify(ossService).deleteByIds("201");
        order.verify(attachmentMapper).update(any(AiMessageAttachment.class), any());
        verify(attachmentMapper, never()).deletePhysicallyByIds(anyList());
        assertEquals(AiMessageAttachment.STATUS_RECYCLED, capturePatch().getStatus());
        assertTrue(captureQuery().getSqlSegment().contains("ORDER BY expire_at ASC"),
            "孤立件同样最旧的先收");
    }

    @Test
    @DisplayName("会话删除把附件转 ORPHANED 并写留档宽限期限")
    void shouldOrphanSessionAttachmentsWithGrace() {
        int graceHours = aiProperties.getChat().getOrphanAttachmentTtlHours();
        LocalDateTime before = LocalDateTime.now();

        reclaimer.orphanBySessionIds(List.of(7L));

        AiMessageAttachment patch = capturePatch();
        assertEquals(AiMessageAttachment.STATUS_ORPHANED, patch.getStatus());
        assertNotNull(patch.getExpireAt(), "转孤立必须写回收期限，否则到期扫描取不到它");
        assertTrue(Duration.between(before.plusHours(graceHours), patch.getExpireAt()).abs().toMinutes() < 5,
            "留档期限应为「转孤立时间 + 宽限小时」，实际=" + patch.getExpireAt());
    }

    @Test
    @DisplayName("转孤立只覆盖 PENDING 与 BOUND，已回收的行不回退")
    void shouldOnlyOrphanLiveAttachments() {
        reclaimer.orphanByMessageIds(List.of(3L));

        LambdaQueryWrapper<AiMessageAttachment> query = captureUpdateScope();
        assertTrue(query.getSqlSegment().contains("status IN"), "必须先按状态圈定，否则 RECYCLED 会被拉回待回收");
        assertTrue(query.getParamNameValuePairs().containsValue(AiMessageAttachment.STATUS_PENDING));
        assertTrue(query.getParamNameValuePairs().containsValue(AiMessageAttachment.STATUS_BOUND));
        assertTrue(!query.getParamNameValuePairs().containsValue(AiMessageAttachment.STATUS_RECYCLED),
            "RECYCLED 的对象文件已不在，不能重新转回 ORPHANED");
    }

    @Test
    @DisplayName("留档宽限配成负数按不留宽限处理，不把期限写到过去")
    void shouldClampNegativeGrace() {
        aiProperties.getChat().setOrphanAttachmentTtlHours(-1);
        LocalDateTime before = LocalDateTime.now();

        reclaimer.orphanByMessageIds(List.of(3L));

        AiMessageAttachment patch = capturePatch();
        assertEquals(AiMessageAttachment.STATUS_ORPHANED, patch.getStatus());
        assertTrue(!patch.getExpireAt().isBefore(before),
            "宽限为负应夹到 0（期限不早于当前），实际=" + patch.getExpireAt());
    }

    @Test
    @DisplayName("空集合不查库")
    void shouldIgnoreEmptyTargets() {
        assertEquals(0, reclaimer.orphanBySessionIds(List.of()));
        assertEquals(0, reclaimer.orphanByMessageIds(null));
        verifyNoInteractions(attachmentMapper, ossService);
    }

    /**
     * 取回收器写给库上的那枚补丁实体（{@code update(entity, wrapper)} 的第一个入参）。
     */
    private AiMessageAttachment capturePatch() {
        ArgumentCaptor<AiMessageAttachment> captor = ArgumentCaptor.forClass(AiMessageAttachment.class);
        verify(attachmentMapper).update(captor.capture(), any());
        return captor.getValue();
    }

    /**
     * 转孤立那条 UPDATE 圈定的范围条件。
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<AiMessageAttachment> captureUpdateScope() {
        ArgumentCaptor<Wrapper<AiMessageAttachment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(attachmentMapper).update(any(AiMessageAttachment.class), captor.capture());
        return (LambdaQueryWrapper<AiMessageAttachment>) captor.getValue();
    }

    /**
     * 取回收器下发的那个查询条件。
     */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<AiMessageAttachment> captureQuery() {
        ArgumentCaptor<Wrapper<AiMessageAttachment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(attachmentMapper).selectList(captor.capture());
        return (LambdaQueryWrapper<AiMessageAttachment>) captor.getValue();
    }

    private List<AiMessageAttachment> fullPage(int size) {
        AiMessageAttachment[] list = new AiMessageAttachment[size];
        for (int i = 0; i < size; i++) {
            list[i] = pending((long) i, (long) (1000 + i));
        }
        return List.of(list);
    }

    private AiMessageAttachment pending(Long id, Long ossId) {
        return row(id, ossId, AiMessageAttachment.STATUS_PENDING, LocalDateTime.now().minusHours(1));
    }

    private AiMessageAttachment orphan(Long id, Long ossId) {
        return row(id, ossId, AiMessageAttachment.STATUS_ORPHANED, LocalDateTime.now().minusHours(1));
    }

    private AiMessageAttachment row(Long id, Long ossId, String status, LocalDateTime expireAt) {
        AiMessageAttachment attachment = new AiMessageAttachment();
        attachment.setId(id);
        attachment.setOssId(ossId);
        attachment.setStatus(status);
        attachment.setExpireAt(expireAt);
        attachment.setFileSize(1024L);
        return attachment;
    }

}
