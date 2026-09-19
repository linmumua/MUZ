package linmumua.doudizhu.game;

import java.util.Objects;
import linmumua.doudizhu.assets.HotbarFontMetrics;
import linmumua.doudizhu.assets.HudResourceRequest;

/**
 * 四层 HUD 覆盖层的运行期就绪快照。
 *
 * <p>资源协调器只有在覆盖层已由 CraftEngine 重载、资源包生成并通过校验后才调用
 * {@link #markVerified(HudResourceRequest)}。渲染服务只读取这份 volatile 快照，避免把
 * 「请求已经提交」误当成「客户端可用」。
 */
public final class HudOverlayRuntimeState {
    private volatile VerifiedSnapshot verifiedSnapshot;

    /** 原子替换当前已验证的四层请求；null 会清空就绪状态。 */
    public void markVerified(HudResourceRequest request) {
        markVerified(request, null);
    }

    /**
     * 原子发布完整资源请求与对应客户端字体测量快照。
     *
     * <p>字体快照允许为 {@code null}：26.1.2 以外的客户端目标没有已验证字体时，
     * Hotbar 仍可发送固定图标并把正文降级到聊天。请求与快照必须在主线程一次发布，
     * 避免 ActionBar 看到半更新状态。
     */
    public void markVerified(HudResourceRequest request, HotbarFontMetrics hotbarFontMetrics) {
        verifiedSnapshot = new VerifiedSnapshot(Objects.requireNonNull(request, "request"), hotbarFontMetrics);
    }

    /** 清除已验证请求和字体快照，使动态字体在下一次匹配前保持不可用。 */
    public void clear() {
        verifiedSnapshot = null;
    }

    /** 当前已验证请求是否精确覆盖指定 Trick 三层 raw Y。 */
    public boolean matchesTrick(int cards, int avatars, int counter) {
        VerifiedSnapshot snapshot = verifiedSnapshot;
        HudResourceRequest request = snapshot == null ? null : snapshot.request();
        return request != null
            && request.cardOffsetDown() == cards
            && request.avatarOffsetDown() == avatars
            && request.counterOffsetDown() == counter;
    }

    /** 当前已验证请求是否精确覆盖另一个 Trick 资源请求的三层 raw Y。 */
    public boolean matchesTrick(HudResourceRequest expected) {
        return expected != null && matchesTrick(
            expected.cardOffsetDown(), expected.avatarOffsetDown(), expected.counterOffsetDown());
    }

    /** 当前已验证请求是否精确覆盖指定 Hotbar raw Y 与构建期 scale。 */
    public boolean matchesHotbar(int y, int scale) {
        VerifiedSnapshot snapshot = verifiedSnapshot;
        HudResourceRequest request = snapshot == null ? null : snapshot.request();
        return request != null
            && request.hotbarOffsetY() == y
            && request.hotbarScale() == scale;
    }

    /** 返回当前已验证请求；未就绪时返回 null。 */
    public HudResourceRequest verifiedRequest() {
        VerifiedSnapshot snapshot = verifiedSnapshot;
        return snapshot == null ? null : snapshot.request();
    }

    /** 返回与当前请求同批次发布的客户端字体测量快照；未知时为 null。 */
    public HotbarFontMetrics verifiedHotbarFontMetrics() {
        VerifiedSnapshot snapshot = verifiedSnapshot;
        return snapshot == null ? null : snapshot.metrics();
    }

    private record VerifiedSnapshot(HudResourceRequest request, HotbarFontMetrics metrics) {
    }
}
