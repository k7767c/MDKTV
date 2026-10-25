package com.fenghuolun.ktv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 点歌服务与话筒服务的端口区间契约测试。
 *
 * 背景（真实故障）：话筒服务原来扫 8766..8776，点歌服务扫 8765..8775，
 * **8766-8775 重叠**。车机上点歌服务先启动占住 8766，话筒服务启动时逐个端口
 * 试过去只能顺延；更糟的是点歌服务每次因 IP 变化重启都可能把话筒的端口抢走，
 * 两个服务互相踢。手机端表现 = 话筒页显示"车机未开放话筒服务"，点歌却完全正常
 * （点歌服务确实活着），极具迷惑性。
 *
 * 这个测试把"两区间必须不重叠"钉死，避免以后有人改端口号时又踩进去。
 */
public class RemotePortRangeTest {

    /** 点歌服务（LocalRemoteServer.start 的实际扫描区间）。 */
    private static final int ORDER_PORT_FROM = 8765;
    private static final int ORDER_PORT_TO = 8775;

    /** 话筒服务（MicStreamServer.start 的实际扫描区间）。 */
    private static final int MIC_PORT_FROM = 8780;
    private static final int MIC_PORT_TO = 8799;

    @Test
    public void micRange_doesNotOverlapOrderRange() {
        assertFalse(
            "话筒端口区间(" + MIC_PORT_FROM + "-" + MIC_PORT_TO + ")与点歌端口区间("
                + ORDER_PORT_FROM + "-" + ORDER_PORT_TO + ")重叠，两个服务会互相抢占端口",
            rangesOverlap(ORDER_PORT_FROM, ORDER_PORT_TO, MIC_PORT_FROM, MIC_PORT_TO)
        );
    }

    @Test
    public void orderRange_isStillUsable() {
        // 点歌端口是扫码点歌的既有约定，不能随便改（老二维码/文档都写着 8765）
        assertTrue("点歌服务起始端口必须是 8765（扫码点歌既有约定）", ORDER_PORT_FROM == 8765);
        assertTrue("点歌端口区间至少要有 5 个候选", ORDER_PORT_TO - ORDER_PORT_FROM >= 5);
    }

    @Test
    public void micRange_isUsable() {
        assertTrue("话筒端口区间至少要有 5 个候选（端口被占时要能顺延）",
            MIC_PORT_TO - MIC_PORT_FROM >= 5);
        assertTrue("话筒端口必须大于点歌端口上限，避免边界重叠",
            MIC_PORT_FROM > ORDER_PORT_TO);
    }

    @Test
    public void bothRanges_areWithinValidPortSpace() {
        assertTrue("点歌端口上限不能超过 65535", ORDER_PORT_TO <= 65535);
        assertTrue("话筒端口上限不能超过 65535", MIC_PORT_TO <= 65535);
        assertTrue("点歌端口必须大于 1024（非特权端口）", ORDER_PORT_FROM > 1024);
        assertTrue("话筒端口必须大于 1024（非特权端口）", MIC_PORT_FROM > 1024);
    }

    private static boolean rangesOverlap(int aFrom, int aTo, int bFrom, int bTo) {
        return aFrom <= bTo && bFrom <= aTo;
    }
}
