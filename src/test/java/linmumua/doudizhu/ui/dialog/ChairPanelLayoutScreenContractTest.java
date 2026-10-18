package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 椅子面板布局编辑屏的源码契约。
 *
 * <p>锁的都是「删掉就会真的出事」的接线要求，手法与项目既有 Dialog 契约一致
 * （{@code DialogScreenLaneContractTest} / {@code AdminDialogContractTest} 同一套）：
 * <ol>
 *   <li>只实现 {@link MuzDialogScreen}、只经 {@link MuzDialogFactory} 建屏，不自己
 *       {@code Dialog.create}、不自己管会话代次、不自己碰调度器；</li>
 *   <li>不出现裸 {@code Player} API：展示/关闭/提示由 core 的 player lane 负责；</li>
 *   <li>屏里不做文件 IO，也不自己调 {@code saveYamlConfig}/{@code saveWithComments}：
 *       落盘归配置层的既有异步流程；</li>
 *   <li>保存必须经 {@link ChairPanelLayoutPort#saveThenApply}（成对：成功才应用），
 *       且**不得**等待结果（不许 {@code get()}/{@code join()}）——那会把磁盘 IO 拉回 player lane；</li>
 *   <li>数值编辑必须同时给出 ± 一步、显式 ×10、精确文本确认、滑块确认，且滑块用相对索引；</li>
 *   <li>镜像对应与尺寸联动必须写进玩家可见文案，不能只活在注释里。</li>
 * </ol>
 */
class ChairPanelLayoutScreenContractTest {
    private static final Path OVERVIEW =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/ChairPanelLayoutDialogScreen.java");
    private static final Path FIELD_SCREEN =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/ChairPanelFieldDialogScreen.java");
    private static final Path ADAPTER =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/ChairPanelLayoutStoreAdapter.java");

    /** 裸 Player API：出现即视为绕过 core 的 player lane。 */
    private static final List<String> BARE_PLAYER_API = List.of(
        "player.showDialog(",
        "player.closeDialog(",
        "player.sendMessage(",
        "player.sendActionBar(",
        "player.sendTitle(",
        "player.playSound(",
        "player.closeInventory(",
        "player.openInventory(",
        "player.getOpenInventory("
    );

    /** 回调内不允许出现的同步 IO / 落盘入口（落盘归配置层的既有异步流程）。 */
    private static final List<String> FORBIDDEN_IO = List.of(
        "Files.",
        "new File(",
        "FileOutputStream",
        "FileReader",
        "saveYamlConfig(",
        "saveWithComments(",
        "Thread.sleep("
    );

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    @Test
    void bothScreensUseTheCoreDialogSeams() throws IOException {
        for (Path path : List.of(OVERVIEW, FIELD_SCREEN)) {
            String source = read(path);
            String name = path.getFileName().toString();
            assertTrue(source.contains("implements MuzDialogScreen"), name + " 必须实现 MuzDialogScreen");
            assertTrue(source.contains("MuzDialogFactory.screen("), name + " 必须经 MuzDialogFactory 建屏");
            assertFalse(source.contains("Dialog.create("), name + " 不得自己 Dialog.create");
            assertTrue(source.contains("permission()"), name + " 必须声明权限");
            // 会话代次/调度器归 core：屏里出现这些说明它自己接管了生命周期。
            assertFalse(source.contains("sessions."), name + " 不得自己操作会话注册表");
            assertFalse(source.contains("runTask"), name + " 不得自己调调度器");
            assertFalse(source.contains("enqueuePlayer"), name + " 不得自己投递 player lane");
        }
    }

    /**
     * 权限门槛必须存在且只有一处真源。
     *
     * <p>单字段屏不必重复写一遍字面量：引用 {@code ChairPanelLayoutDialogScreen.PERMISSION}
     * 比复制字符串更抗漂移（复制的那份改漏了就等于开了后门）。
     */
    @Test
    void adminPermissionIsDeclaredOnceAndReusedByBothScreens() throws IOException {
        String overview = read(OVERVIEW);
        String field = read(FIELD_SCREEN);

        assertTrue(overview.contains("public static final String PERMISSION = \"muz.admin\""),
            "概览屏必须把管理权限声明成 muz.admin 单一真源");
        assertTrue(field.contains("ChairPanelLayoutDialogScreen.PERMISSION"),
            "单字段屏必须复用同一个权限真源，不得自己另写一份");
        assertTrue(field.contains("permission()"), "单字段屏必须声明权限");
    }

    @Test
    void bothScreensAvoidBarePlayerApiAndSynchronousIo() throws IOException {
        for (Path path : List.of(OVERVIEW, FIELD_SCREEN)) {
            String source = read(path);
            String name = path.getFileName().toString();
            for (String api : BARE_PLAYER_API) {
                assertFalse(source.contains(api), name + " 出现裸 Player API：" + api);
            }
            for (String io : FORBIDDEN_IO) {
                assertFalse(source.contains(io), name + " 在回调里做同步 IO：" + io);
            }
        }
    }

    /**
     * 保存必须成对且非阻塞。
     *
     * <p>禁 {@code port.save(}：那样很容易只保存不应用（或反之）；禁 {@code .join()/.get()}：
     * 在 player lane 上等磁盘会把整条 lane 卡住。
     */
    @Test
    void savesGoThroughThePairedNonBlockingSeam() throws IOException {
        for (Path path : List.of(OVERVIEW, FIELD_SCREEN)) {
            String source = read(path);
            String name = path.getFileName().toString();
            assertTrue(source.contains("ChairPanelLayoutPort.saveThenApply("),
                name + " 必须经 saveThenApply 成对保存");
            assertFalse(source.contains("port.save("),
                name + " 不得直接调 port.save（会漏掉 apply，出现半截状态）");
            assertFalse(source.contains(".join()"), name + " 不得等待异步保存结果");
            assertFalse(source.contains(".get()"), name + " 不得等待异步保存结果");
        }
    }

    /** 异步回调重开面板前必须复核会话，避免「关掉面板又被异步结果顶回来」。 */
    @Test
    void reopeningAfterAsyncSaveIsGuardedByTheSessionGeneration() throws IOException {
        for (Path path : List.of(OVERVIEW, FIELD_SCREEN)) {
            String source = read(path);
            String name = path.getFileName().toString();
            assertTrue(source.contains("requestCurrent(ctx.playerId(), ctx.generation())"),
                name + " 必须在异步回调里用会话代次复核，才允许重开面板");
        }
    }

    /** 数值编辑屏必须同时给出四种调节方式，并且滑块用相对索引（不能用绝对步索引回写）。 */
    @Test
    void numericEditorOffersStepMultiplierAndBothConfirmations() throws IOException {
        String source = read(FIELD_SCREEN);

        assertTrue(source.contains("一步"), "缺少 ± 一步按钮");
        assertTrue(source.contains("×10"), "缺少显式 ×10 倍率按钮");
        assertTrue(source.contains("确认数字"), "缺少精确文本的独立确认");
        assertTrue(source.contains("确认滑块"), "缺少滑块的独立确认");

        assertTrue(source.contains("ChairPanelLayout.applyRaw(port.read(), field, ctx.text(view, \"value\"))"),
            "文本确认必须走模型的 applyRaw（解析/校验/精度只此一处）");
        assertTrue(source.contains("ctx.number(view, \"slider\")"), "滑块确认没有读滑块输入");
        assertTrue(source.contains("AdminSettingArithmetic.sliderWindow("), "滑块窗口没有用 core 的相对窗口");
        assertTrue(source.contains(".valueAt("), "滑块读回没走 valueAt（相对索引唯一入口）");
        assertFalse(source.contains("fromStepIndex("), "又用回了绝对步索引回写（会吸值 / 大值丢精度）");
        assertFalse(source.contains("toStepIndex("), "又用回了绝对步索引换算");
    }

    /** 非法输入必须走「提示 + 不写回」，不能静默钳制或直接崩掉回调。 */
    @Test
    void invalidInputIsReportedInsteadOfWrittenBack() throws IOException {
        String source = read(FIELD_SCREEN);
        assertTrue(source.contains("catch (IllegalArgumentException exception)"),
            "文本/滑块非法输入必须被捕获并提示");
        assertTrue(source.contains("ctx.error("), "非法输入必须提示给玩家");

        int catchIndex = source.indexOf("catch (IllegalArgumentException exception)");
        assertTrue(catchIndex > 0, "缺少非法输入分支");
        String afterCatch = source.substring(catchIndex);
        int nextMethod = afterCatch.indexOf("\n    private ");
        String catchBlock = nextMethod > 0 ? afterCatch.substring(0, nextMethod) : afterCatch;
        assertFalse(catchBlock.contains("saveThenApply"), "catch 块里不得写回非法值（应当只提示）");
    }

    /** 镜像对应与尺寸联动必须出现在玩家可见文案里，而不是只在注释中。 */
    @Test
    void mirrorCorrespondenceAndSizeLinkageAreVisibleToPlayers() throws IOException {
        String overview = read(OVERVIEW);
        assertTrue(overview.contains("lateral 取反"), "镜像文案必须说明 lateral 如何对应");
        assertTrue(overview.contains("yaw 取反"), "镜像文案必须说明 yaw 取反");
        assertTrue(overview.contains("roll 取反"), "镜像文案必须说明 roll 取反");
        assertTrue(overview.contains("vertical / depth / pitch 保持不变"), "必须说明哪些分量不变");
        assertTrue(overview.contains("文字正向"), "必须说明镜像不做贴图翻转（文字正向）");
        assertTrue(overview.contains("尺寸联动"), "必须说明尺寸是等比联动");
        assertTrue(overview.contains("3 : 2"), "必须写明出厂比例 3:2 与画布一致的要求");

        String field = read(FIELD_SCREEN);
        assertTrue(field.contains("作用范围"), "单字段屏必须说明该字段作用在哪一侧");
    }

    /** 屏不得自己拼装布局/位姿：所有修改都必须经过模型（与 schema 同一套默认值与校验）。 */
    @Test
    void screensNeverBuildLayoutsOrPanelLayoutsByHand() throws IOException {
        for (Path path : List.of(OVERVIEW, FIELD_SCREEN)) {
            String source = read(path);
            String name = path.getFileName().toString();
            assertFalse(source.contains("new ChairPanelLayout("),
                name + " 不得自己 new 布局（会绕过 schema 校验与精度归一）");
            assertFalse(source.contains("new PanelLayout("),
                name + " 不得自己 new 侧布局（depth/vertical 顺序极易写反，必须走字段枚举）");
            assertFalse(source.contains("new ChairSidePanelSettings("),
                name + " 不得自己 new 配置快照（默认值与校验归 schema）");
        }
    }

    /**
     * 适配器不得在 player lane 上等待磁盘：只许派发 + 接回调。
     *
     * <p>这一条与上面的屏契约互补：屏不等待、适配器也不能等待，整条链上就没有同步写盘。
     */
    @Test
    void storeAdapterNeverBlocksOnTheSaveFuture() throws IOException {
        String source = read(ADAPTER);
        assertTrue(source.contains("saveAsync("), "适配器必须用配置层的异步保存");
        assertFalse(source.contains(".join()"), "适配器不得等待 future");
        assertFalse(source.contains(".get()"), "适配器不得等待 future");
        assertFalse(source.contains("Files."), "适配器不得自己做文件 IO");
        assertTrue(source.contains("result.success()"), "必须按 SaveResult 的成功标志分流");
        assertTrue(source.contains("result.message()"), "失败原因必须原样透出给管理员");
    }
}
