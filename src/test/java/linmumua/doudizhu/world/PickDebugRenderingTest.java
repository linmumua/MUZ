package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调试判定区可视化的结构性约束。
 *
 * <p>这些点靠源码扫描守，因为它们要么依赖 Bukkit 实体（跑不起来），
 * 要么是「不能写成什么样」的性质，值域断言表达不了。
 *
 * <p>历史踩坑：最早想用 TextDisplay + 空文本 + setBackgroundColor 画判定区背景板，
 * 但空文本时背景板包围盒为 0，背景色永远不可见且不报错（静默消失，极难从日志发现），
 * 被迫改用 ItemDisplay + 发光描边画细线。后来用户反馈细线看不清，要求改回实心面，
 * 这次用 TextDisplay + 单空格 " "：空格在默认字体里有非零 advance 宽度，背景板有真实面积，
 * 再用 transformation.scale 拉成矩形，绕开了空文本那个坑。
 */
class PickDebugRenderingTest {

    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    private static String source() throws IOException {
        return Files.readString(MANAGER);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("找不到方法: " + signature);
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(brace, i + 1);
                }
            }
        }
        throw new AssertionError("方法体没闭合: " + signature);
    }

    /** 取方法签名本身（参数表在起始花括号之前，methodBody 取不到）。 */
    private static String methodDeclaration(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("找不到方法: " + signature);
        }
        int brace = source.indexOf('{', start);
        return source.substring(start, brace);
    }

    /**
     * 线框必须复用生产判据算包络，不能自己另算一套。
     *
     * <p>为什么值得锁：调试的全部价值在于「看到的就是真判定」。线框若自己拼一份几何，
     * 一旦哪天生产判据改了而这里没跟，就会变成「照着框点却点不到」，
     * 反过来误导人去改本来正确的判定。
     *
     * <p>失败条件：在 refreshPickDebug 里手写包络尺寸，而不调用这三个生产函数。
     */
    @Test
    @DisplayName("线框复用生产包络函数，不另算一套几何")
    void wireframeReusesProductionEnvelopes() throws IOException {
        String body = methodBody(source(), "private void refreshPickDebug(");

        assertTrue(body.contains("HandCardPickGeometry.envelope("),
            "线框没有复用未选中包络的生产算法");
        assertTrue(body.contains("HandCardPickGeometry.envelopeForSelected("),
            "线框没有复用已选中包络的生产算法");
        assertTrue(body.contains("HandCardPickGeometry.unifiedEnvelopes("),
            "线框没有画统一后的实际生效包络：那圈才决定点不点得到，缺了就看不出真实边界");
        assertTrue(body.contains("pickHandCard("),
            "线框没有复用 pickHandCard 的命中结果，可能与悬停判断不一致");
    }

    /**
     * 静止时必须整帧跳过，不能每 tick 重算重建。
     *
     * <p>线框有几百个实体，每 tick spawn/remove 会明显拖慢服务器。
     * 签名比对是那道闸门，去掉它性能问题会静默回来——没有报错，只是服务器变卡。
     *
     * <p>失败条件：删掉签名短路，或把 return 改成继续往下走。
     */
    @Test
    @DisplayName("签名不变时整帧跳过：静止时零开销")
    void unchangedSignatureSkipsEntireFrame() throws IOException {
        String body = methodBody(source(), "private void refreshPickDebug(");

        int compare = body.indexOf("pickDebugSignatures.get(");
        assertTrue(compare >= 0, "缺少签名比对：线框会每 tick 重算，数百实体反复增删会拖慢服务器");
        // 比对之后必须紧跟 return，而不是只记录一下就继续
        String afterCompare = body.substring(compare, Math.min(body.length(), compare + 120));
        assertTrue(afterCompare.contains("return"),
            "签名相同时没有直接 return，等于闸门没生效");
        // 记录新签名必须在 return 之后，否则第一帧之后永远相同、再也不更新
        int store = body.indexOf("pickDebugSignatures.put(");
        assertTrue(store > compare, "新签名应当在比对通过后才记录");
    }

    /**
     * 实体必须池化复用，不能每帧重建。
     *
     * <p>失败条件：把 teleport 复用改成先清空池再全量 spawn。
     */
    @Test
    @DisplayName("线框实体池化复用，靠 teleport 而不是重建")
    void wireEntitiesArePooledNotRespawned() throws IOException {
        String body = methodBody(source(), "private void applyPickDebugPool(");

        assertTrue(body.contains("teleportIfMoved(line, target, CARD_TRACK_EPSILON_SQUARED)"),
            "池化没有走小死区 teleport：线框段长约 0.03 格，通用 0.2 格死区会吞掉相邻段位移，框会卡在上一帧位置");
        assertFalse(body.contains("pool.clear()"),
            "池被整体清空说明退化成每帧重建，池化就没意义了");
        assertTrue(body.contains("while (pool.size() > pending.size())"),
            "池没有收缩逻辑：牌数变少时上一帧的线框会留在原地");
    }

    /**
     * 面板必须用单空格 TextDisplay 画，绝不能回到空文本那套。
     *
     * <p>为什么这次 TextDisplay 可行而历史上不可行：区别在空文本 vs 单空格。
     * 空文本（{@code Component.empty()}）advance 为 0，背景板面积为 0，
     * {@code setBackgroundColor} 没有面积可画、{@code scale} 乘上去仍是 0，
     * 永远静默不可见。这是真实踩过、已踩死一次的坑，本条测试继续守它。
     * 单空格（{@code Component.text(" ")}）在默认字体里有非零 advance 宽度
     * （约 4 像素），背景板因此有真实面积，scale 可以把它拉成任意矩形——这是现用方案。
     *
     * <p>失败条件：把文本改回 {@code Component.empty()}，或丢失任意一项关键设置。
     */
    @Test
    @DisplayName("面板用单空格 TextDisplay 背景板，禁用空文本")
    void panelsAreDrawnWithSpaceTextDisplay() throws IOException {
        String source = source();
        String spawn = methodBody(source, "private TextDisplay spawnPickDebugPanel(");

        assertFalse(spawn.contains("Component.empty()"),
            "又用回空文本了：TextDisplay 空文本的背景板面积为 0，永远不可见且不报错——这是历史上真实踩过的坑");
        assertTrue(spawn.contains("Component.text(\" \")"),
            "没有用单空格撑开背景板：空格有非零 advance 宽度，背景板才有真实面积，scale 才能拉成矩形");
        assertTrue(source.contains("setBackgroundColor("),
            "没有设背景色：实心矩形靠 setBackgroundColor 上色，缺了就是无色（它在 stylePickDebugPanel 里，逐块按颜色设置）");
        assertTrue(spawn.contains("Display.Billboard.FIXED"),
            "面板必须用 FIXED 朝向：跟着视角转的话面板会脱离牌面，看不出真实边界");
        assertTrue(spawn.contains("hideEntity("),
            "面板没有对其他玩家隐藏：调试面板不该让同桌的人也看到");
        assertTrue(spawn.contains("setSeeThrough(true)"),
            "没有 setSeeThrough(true)：面板会被牌挡在背后白画了，必须能穿透实体");
    }

    /**
     * 面板 yaw 必须与手牌同源（{@code handCardYaw}），这是本次修复的核心不变量。
     *
     * <p><b>守的是哪个 bug</b>：面板设了 {@code Display.Billboard.FIXED}，
     * FIXED 意味着朝向<b>完全</b>由实体自身 yaw 决定、不跟视角转。历史上
     * {@code applyPickDebugPool} 构造 Location 时没带 yaw，实体 yaw 取默认 0，
     * 而牌本体用的是 {@code handCardYaw(placed.yaw(), seatIndex)}
     * （座位 1/2 差 ±90°，桌子 yaw 非 0 时座位 0 也差）。
     * 于是面板被侧着看，而 {@code stylePickDebugPanel} 给的厚度 scale 只有 {@code 0.01f}，
     * 侧棱几乎零宽——<b>面板等于隐形，且不报任何错</b>。
     *
     * <p>为什么这个行为重要：调试面板的全部价值是「看到判定区」。朝向错了它不是画歪，
     * 而是彻底不可见，症状与「功能没生效」完全一样，会把人引去查错误的地方。
     * 这也是原测试假绿的原因——它只断言了 FIXED 存在，没断言 yaw 被设置。
     *
     * <p>失败条件：yaw 不再从 handCardYaw 取（例如写死 0 或另算一套角度），
     * 或算出来了但没传进 applyPickDebugPool。
     */
    @Test
    @DisplayName("面板 yaw 与手牌同源：FIXED 朝向下 yaw 错了面板就隐形")
    void panelYawMatchesHandCardYaw() throws IOException {
        String source = source();
        String refresh = methodBody(source, "private void refreshPickDebug(");

        // 必须复用生产的角度换算，不能另写一份 switch/±90
        assertTrue(refresh.contains("handCardYaw(placed.yaw(), seatIndex)"),
            "面板朝向没有复用 handCardYaw：FIXED 朝向下 yaw 必须与牌本体同源，"
                + "否则面板被侧着看，厚度 0.01f 等于隐形");

        // 算出来还得真的传下去，否则等于没算
        java.util.regex.Matcher yawVar = java.util.regex.Pattern
            .compile("float\\s+(\\w+)\\s*=\\s*handCardYaw\\(placed\\.yaw\\(\\),\\s*seatIndex\\)")
            .matcher(refresh);
        assertTrue(yawVar.find(), "refreshPickDebug 没有把 handCardYaw 的结果存下来");
        String yawName = yawVar.group(1);
        assertTrue(refresh.contains("applyPickDebugPool(") && refresh.contains(yawName + ")"),
            "算出的 yaw 没有传给 applyPickDebugPool：面板朝向仍会停在默认 0");

        // 池化那一层必须真的把 yaw 落到实体朝向上。
        // 注意参数表在 methodBody 的起始花括号之前，所以签名要单独取。
        assertTrue(methodDeclaration(source, "private void applyPickDebugPool(").contains("float panelYaw"),
            "applyPickDebugPool 没有接收 yaw 参数，面板朝向无从设置");
        String pool = methodBody(source, "private void applyPickDebugPool(");
        assertTrue(pool.contains("panelYaw"),
            "applyPickDebugPool 收了 yaw 却没用");
    }

    /**
     * 新建面板时 yaw 必须已经写进 Location。
     *
     * <p>为什么重要：{@code spawnPickDebugPanel} 是靠 {@code world.spawn(location, ...)}
     * 定初始朝向的。Location 不带 yaw，第一帧生成出来的面板就是 yaw=0 的隐形状态。
     *
     * <p>失败条件：把 Location 构造改回四参数（不带 yaw/pitch）版本。
     */
    @Test
    @DisplayName("新建面板的 Location 带上 yaw，第一帧就朝向正确")
    void spawnLocationCarriesYaw() throws IOException {
        String pool = methodBody(source(), "private void applyPickDebugPool(");

        assertTrue(pool.contains("new Location(world, worldX, rect.y(), worldZ, panelYaw"),
            "构造面板 Location 时没带 yaw：spawn 出来的面板朝向为默认 0，"
                + "FIXED 朝向下会被侧着看而完全不可见");
    }

    /**
     * teleport 复用路径也必须纠正朝向，不能只比位置。
     *
     * <p><b>这是最容易漏的一条</b>：{@code teleportIfMoved} 只比较位置，
     * 并且<b>刻意</b>把目标 Location 的 yaw/pitch 覆盖成实体当前值
     * （见其实现里的 {@code moved.setYaw(current.getYaw())}）。
     * 所以哪怕 Location 带对了 yaw，复用池里的旧实体时朝向也<b>不会</b>被纠正——
     * 老面板会一直停在 yaw=0，修复看起来「没生效」。
     *
     * <p>为什么重要：池化是常态而非例外，牌数不变时每一帧走的都是复用分支。
     * 少了这步，玩家只有在手牌数量变化触发池伸缩时才偶尔看见面板，
     * 症状是「有时能看见有时看不见」，比完全失效更难查。
     *
     * <p>失败条件：复用分支里去掉显式的朝向纠正，只留 teleportIfMoved。
     */
    @Test
    @DisplayName("复用旧面板时显式纠正朝向：teleportIfMoved 会保留旧 yaw")
    void reusedPanelsGetYawCorrected() throws IOException {
        String source = source();
        String pool = methodBody(source, "private void applyPickDebugPool(");

        int teleport = pool.indexOf("teleportIfMoved(line, target, CARD_TRACK_EPSILON_SQUARED)");
        assertTrue(teleport >= 0, "复用分支的 teleport 调用不见了");
        // 复用分支里 teleport 之后必须紧跟朝向纠正，否则旧实体 yaw 永远是 0
        String afterTeleport = pool.substring(teleport);
        int reuseBranchEnd = afterTeleport.indexOf("} else {");
        String reuseBranch = reuseBranchEnd < 0 ? afterTeleport : afterTeleport.substring(0, reuseBranchEnd);
        assertTrue(reuseBranch.contains("applyStableYaw(line, panelYaw)"),
            "复用分支没有显式纠正朝向：teleportIfMoved 刻意保留实体原有 yaw/pitch，"
                + "光靠它纠不回来，池里的老面板会一直停在 yaw=0 被侧着看（厚度 0.01f 等于隐形）");

        // 锁死 teleportIfMoved 确实不管朝向：这条假设一旦变了，上面那个断言的理由就得重写
        String teleportImpl = methodBody(source, "private void teleportIfMoved(Entity entity, Location target, double epsilonSquared)");
        assertTrue(teleportImpl.contains("moved.setYaw(current.getYaw())"),
            "teleportIfMoved 不再保留原 yaw 了：请重新评估面板复用路径是否还需要 applyStableYaw");
    }

    /**
     * viewRange 不能设得比可视距离还小。
     *
     * <p>这也是实机踩出来的：viewRange 是以 16 格为基准的倍率，
     * 设 0.35 等于 5.6 格，玩家正常坐在桌前就已经超距，线框直接不下发给客户端。
     * 同样是静默失效，没有任何报错。
     *
     * <p>失败条件：把 viewRange 改回 1.0 以下。
     */
    @Test
    @DisplayName("viewRange 不小于 16 格基准，避免坐在桌前就超距")
    void viewRangeCoversNormalPlayDistance() throws IOException {
        String spawn = methodBody(source(), "private TextDisplay spawnPickDebugPanel(");

        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("setViewRange\\(([0-9.]+)f?\\)").matcher(spawn);
        assertTrue(matcher.find(), "没有显式设 viewRange");
        double range = Double.parseDouble(matcher.group(1));
        assertTrue(range >= 1.0,
            "viewRange=" + range + " 是 16 格的倍率，小于 1.0 意味着不足 16 格，"
                + "玩家坐在桌前就可能超距看不见线框");
    }

    /**
     * 关闭与离桌都必须收掉实体。
     *
     * <p>签名机制有个副作用：签名不变就不重算，所以残留实体不会自愈，
     * 必须显式删。失败条件：去掉任一处 clearPickDebug 调用。
     */
    @Test
    @DisplayName("关闭与离桌都收掉线框实体")
    void wireEntitiesAreClearedOnDisableAndLeave() throws IOException {
        String source = source();

        String toggle = methodBody(source, "public boolean togglePickDebug(");
        assertTrue(toggle.contains("clearPickDebug("),
            "关闭显示时没有删实体，线框会永久留在世界里");

        String tick = methodBody(source, "public void tick()");
        assertTrue(tick.contains("clearPickDebug("),
            "离桌时没有删实体：签名不变则不会重算，残留不会自愈");

        String clear = methodBody(source, "public void clearPickDebug(");
        assertTrue(clear.contains("remove()"),
            "clearPickDebug 没有真的删实体");
        assertTrue(clear.contains("pickDebugSignatures.remove("),
            "clearPickDebug 没有清签名：下次开启会因签名相同而不画");
    }
}
