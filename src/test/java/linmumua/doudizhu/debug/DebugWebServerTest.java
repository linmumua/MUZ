package linmumua.doudizhu.debug;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.debug.HotbarDebugOverlayWriter;
import linmumua.doudizhu.game.TrickHudPreview;
import linmumua.doudizhu.model.CardRank;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DebugWebServerTest {
    @Test
    void htmlIsEditableAndContainsSaveApis() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("trick-hud.enabled", true);
        values.put("trick-hud.avatar-scale", 6);
        values.put("trick-hud.avatar-gap", 6);
        values.put("trick-hud.card-step", 22);
        values.put("trick-hud.card-height", 53);
        values.put("trick-hud.offset-down", 50);
        values.put("trick-hud.avatar-offset-down", 122);
        values.put("trick-hud.offset-x", 0);
        values.put("trick-hud.card-offset-x", 0);
        values.put("trick-hud.avatar-offset-x", 0);
        values.put("trick-hud.avatar-outline.enabled", true);
        values.put("trick-hud.avatar-outline.color", "#000000");
        values.put("trick-hud.counter.enabled", true);
        values.put("trick-hud.counter.gap", 2);
        values.put("trick-hud.counter.hide-exhausted", false);
        values.put("trick-hud.counter.offset-x", 0);
        values.put("hotbar-hud.enabled", false);
        DebugHudConfigController.Snapshot snapshot = new DebugHudConfigController.Snapshot(
            values,
            List.of(),
            DebugHudConfigController.fields().values().stream().map(spec -> new DebugHudConfigController.FieldDto(
                spec.key(), spec.type().name().toLowerCase(java.util.Locale.ROOT), spec.fallback(), spec.min(), spec.max(), spec.step(),
                spec.label(), spec.group(), spec.control(), spec.options()
            )).toList()
        );

        String html = DebugWebServer.buildHtml(snapshot, "token");

        // 22 个可编辑键：原 19 个 + trick-hud.counter.offset-down + trick-hud.counter.scale + hotbar-hud.scale
        assertEquals(22, DebugHudConfigController.fields().size());
        assertFalse(html.contains("只读"));
        assertTrue(html.contains("/api/save"));
        assertTrue(html.contains("保存并应用"));
        assertTrue(html.contains("重新读取"));
        assertTrue(html.contains("MUZ Debug HUD") && html.contains("保存并应用"));
        assertTrue(html.contains("仅页面校准，不写入 HUD 配置"));
        assertTrue(html.contains("只开放 22 个 HUD 运行期字段"));
        assertTrue(html.contains("仅页面校准，不写入 HUD 配置"));
        assertFalse(html.contains("只开放 19 个 HUD 运行期字段"));
        assertFalse(html.contains("不写入 19 个 HUD 配置键"));
        assertTrue(html.contains("撤销"));
        assertTrue(html.contains("type='checkbox'"));
        assertTrue(html.contains("type='range'"));
        assertTrue(html.contains("type='number'"));
        assertTrue(html.contains("type='text'"));
        assertTrue(html.contains("id='snapToggle'"));
        assertTrue(html.contains("pageSnapEnabled"));
        assertTrue(html.contains("页面拖动吸附已"));
        assertTrue(html.contains("不写入 HUD 配置"));
        assertFalse(html.contains("id='snapToggle' data-key="));
        assertTrue(html.contains("snapThreshold"));
        assertTrue(html.contains("centerGuidesEnabled"));
        assertTrue(html.contains("altAxisLock"));
        assertTrue(html.contains("Minecraft"));
        // snap 目标：以视口中心为吸附点；旧版用 targetLeft/ty= 变量名，
        // 现版直接在 snap 分支内联计算 tx/ty。
        assertTrue(html.contains("tx=(v.width-d.width)/2-d.baseLeft")
            || html.contains("targetLeft") || html.contains("tx=Math.floor((v.width-lw)/2)"),
            "snap 必须有 X 方向居中目标");
        assertTrue(html.contains("ty=(v.height-d.height)/2-d.baseTop")
            || html.contains("targetTop") || html.contains("ty=Math.floor((v.height-lh)/2)"),
            "snap 必须有 Y 方向居中目标");
        assertTrue(html.contains("lostpointercapture"));
        assertTrue(html.contains("axis:null"));
        assertTrue(html.contains("dragCfg.altAxisLock"));
    }

    @Test
    void 内嵌页面是当前真实DebugWeb模板而非旧Java内联回退() throws IOException {
        try (var input = DebugWebServerTest.class.getClassLoader().getResourceAsStream("debug-hud-preview.html")) {
            assertNotNull(input, "测试 classpath 必须包含真实内嵌 debug-hud-preview.html");
            String html = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(html.contains("id=\"screen\""), "内嵌页面必须包含 Minecraft 逻辑舞台");
            assertTrue(html.contains("/api/preview-resources"), "内嵌页面必须消费真实资源 manifest");
            assertTrue(html.contains("hotbar-select"), "内嵌页面必须包含 Hotbar 选中框预览");
            assertFalse(html.contains("1.10.16"), "内嵌页面不得残留旧版本号");
            assertFalse(html.contains("1.10.3"), "内嵌页面不得残留更旧版本号");
        }
    }

    @Test
    void 前端交互改进_粘性操作栏与键盘快捷键与焦点样式() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(),
            List.of(),
            List.of()
        ), "token");

        // 粘性操作栏：从 <style> 内提取 .actions{...} 规则，精确断言 sticky 定位
        String styleBlock = extractStyleBlock(html);
        String actionsRule = extractRule(styleBlock, ".actions");
        assertNotNull(actionsRule, ".actions 样式规则必须存在于 <style> 内");
        assertTrue(actionsRule.contains("position:sticky"), ".actions 规则必须包含 position:sticky");
        assertTrue(actionsRule.contains("bottom:0"), ".actions 规则必须包含 bottom:0 使其粘在视口底部");

        // 键盘快捷键：Ctrl+S 保存、Ctrl+R 重载
        assertTrue(html.contains("e.ctrlKey&&e.key==='s'"), "必须支持 Ctrl+S 触发保存");
        assertTrue(html.contains("e.ctrlKey&&e.key==='r'"), "必须支持 Ctrl+R 触发重载");
        assertTrue(html.contains("e.preventDefault()"), "快捷键必须阻止浏览器默认行为");
        assertTrue(html.contains("document.addEventListener('keydown'"), "快捷键必须注册在 document 级别");

        // 按钮 title 提示快捷键
        assertTrue(html.contains("title='Ctrl+S'"), "保存按钮必须显示快捷键提示");
        assertTrue(html.contains("title='Ctrl+R'"), "重载按钮必须显示快捷键提示");

        // 焦点可见样式：keyboard navigation 必须有明确的焦点环
        assertTrue(html.contains("focus-visible"), "必须为键盘导航提供 focus-visible 焦点环样式");
        assertTrue(html.contains("button:focus-visible"), "按钮必须有 focus-visible 样式");
        assertTrue(html.contains("input:focus-visible"), "输入框必须有 focus-visible 样式");

        // 成功状态自动淡出：msg-fade 动画让成功消息不永久停留
        assertTrue(html.contains("msg-fade"), "成功消息必须添加淡出动画类");
        assertTrue(html.contains("@keyframes msgFade"), "必须定义 msgFade 关键帧动画");
        // 淡出只用于成功消息，错误消息保持常驻
        assertTrue(html.contains("className='warn'"), "错误消息不使用淡出，保持可见");

        // 全屏编辑器布局：页面根节点占满 viewport，表单为浮动面板，预览为全屏舞台。
        assertTrue(html.contains("main.mc-editor"), "main 必须使用全屏 MC 编辑器布局");
        assertTrue(html.contains("height:100dvh"), "全屏编辑器必须占满动态视口高度");
        assertTrue(html.contains(".editor-panel"), "必须存在可折叠浮动配置面板");
        assertTrue(html.contains(".preview-panel"), "必须存在全屏预览面板");
        assertTrue(html.contains("id='screen'"), "必须存在 MC 逻辑舞台");
        assertTrue(html.contains("screenZoom()"), "舞台必须按浏览器 viewport 自动适配");
        assertTrue(html.contains("word-break:break-all"), ".key 配置键名必须允许换行防止撑宽标签列");
        // 旧双列布局断言已过时：全屏编辑器必须由浮动配置面板与独立舞台组成，
        // 不再要求 main 使用 grid-template-columns；窄屏适配也不能靠隐藏内容裁切。
        String css = extractStyleBlock(html);
        String mainRule = extractRule(css, "main");
        assertTrue(mainRule == null || !mainRule.contains("grid-template-columns"),
            "全屏编辑器不能回退为旧双列 main 网格");
        assertTrue(html.contains("min-width:0"), "表单和面板必须用 min-width:0 防止窄屏撑宽");
        assertTrue(html.contains("select{width:100%;min-width:0"), "select/input 必须有 min-width:0");
        assertTrue(html.contains("panelToggle"), "必须提供配置面板折叠按钮");
        assertTrue(html.contains("fullscreenBtn"), "必须提供浏览器全屏按钮");
    }

    @Test
    void 全屏Minecraft编辑器包含右键坐标和Shift平移控制() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertTrue(html.contains("id='mcEditor'"), "页面根节点必须是全屏编辑器");
        assertTrue(html.contains("id='mcCoordinate'"), "必须提供右键坐标提示容器");
        assertTrue(html.contains("contextmenu"), "必须注册右键坐标事件");
        assertTrue(html.contains("showCoordinate"), "右键必须显示坐标信息");
        assertTrue(html.contains("左 ") && html.contains("右 "), "坐标提示必须显示左右边界");
        assertTrue(html.contains("e.shiftKey") && html.contains("ArrowLeft") && html.contains("ArrowRight"),
            "必须支持 Shift+方向键微调");
        assertTrue(html.contains("panning") && html.contains("Shift+拖动"),
            "必须支持 Shift+拖动空白区域平移视图");
        assertTrue(html.contains("let pageSnapEnabled=true"), "页面默认必须开启吸附");
        assertTrue(html.contains("requestFullscreen"), "必须提供浏览器全屏 API");
        assertTrue(html.contains("requestAnimationFrame"), "连续更新必须通过 requestAnimationFrame 合并");
        assertTrue(html.contains("background:transparent!important"), "辅助槽层不能遮住真实 hotbar PNG");
    }

    @Test
    void 保存页面文案和协调器时序明确要求异步资源流程() throws IOException {
        String server = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/DebugWebServer.java"));
        String coordinator = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/HudWebApplyCoordinator.java"));
        assertTrue(server.contains("applyCoordinator.submitSave(patch)"));
        assertTrue(server.contains("HTTP_APPLY_PROTECTION_TIMEOUT_SECONDS = 125L"),
            "HTTP 层保护等待必须略长于 coordinator 的 120 秒租约");
        assertTrue(server.contains(".get(HTTP_APPLY_PROTECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)"),
            "HTTP 必须等待 coordinator 返回的结果 Future，而不是提前以短超时结束");
        assertFalse(server.contains("MAIN_THREAD_TIMEOUT_SECONDS"), "不能保留原先 10 秒 HTTP 超时常量");
        assertFalse(server.contains(".cancel(true)"), "HTTP 超时不得取消 coordinator 底层任务");
        assertFalse(server.contains(".cancel(false)"), "HTTP 超时不得取消 coordinator 底层任务");
        assertFalse(server.contains("orTimeout("), "HTTP 层不得给 coordinator 底层 Future 加取消式超时");
        assertFalse(server.contains("controller.savePatch(patch)"), "HTTP 请求线程不能直接保存配置");
        int write = coordinator.indexOf("overlayWriter.writeAsync");
        int dispatch = coordinator.indexOf("selected.reloadGenerateAndVerify(offsetY, hotbarScale, executor, mainExecutor)");
        int apply = coordinator.indexOf("plugin.applyHudRuntimeStateFromWeb()");
        int snapshot = coordinator.indexOf("ApplyResult.success(controller.snapshot()");
        assertTrue(write >= 0 && dispatch > write && apply > dispatch && snapshot > apply,
            "必须按异步写资源、主线程 CE 重载、HUD 应用、发布快照的顺序执行");
        assertTrue(coordinator.contains("当前 HUD 没有独立的 Trick HUD CE 字形资源"));
        assertTrue(coordinator.contains("resolveOnMainThread"));
        assertTrue(coordinator.contains("isTaskActive(task)"));
        assertTrue(coordinator.contains("executor.shutdownNow()"));
        String overlayWriter = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/HotbarDebugOverlayWriter.java"));
        String bridge = Files.readString(Path.of("src/main/java/linmumua/doudizhu/compat/CraftEngineHudResourceBridge.java"));
        assertTrue(bridge.contains("access::reload"),
            "保存与磁盘重载必须先让 CraftEngine 重新读取 overlay，再生成资源包");
        assertFalse(bridge.contains("跳过 CE 重载"),
            "不能用旧的 CE 内存快照生成看似成功的资源包");
        assertFalse(overlayWriter.contains("Files.createTempFile(root, \"pack.yml.\", \".tmp\")"),
            "直接写入 muz 时不得覆盖正式 pack.yml");
        assertTrue(overlayWriter.contains("Files.createTempFile(imagesDirectory, \"hotbar_debug.yml.\", \".tmp\")"),
            "hotbar_debug.yml 必须先写入唯一临时文件");
        assertTrue(overlayWriter.contains("ATOMIC_MOVE"));
        assertTrue(coordinator.contains("controller.reloadFromDiskForWeb()"),
            "/api/reload 必须走 HUD 专用异步磁盘重载，而不是完整 reloadVisualState");
        assertFalse(coordinator.contains("reloadVisualState"),
            "HUD Web coordinator 不得调用完整视觉重载");
    }

    @Test
    void htmlStateGeometryMatchesRuntimeAssetsAndApprovedPreviewContract() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(),
            List.of(),
            List.of()
        ), "token");

        String marker = "<script type='application/json' id='muz-state'>";
        int stateStart = html.indexOf(marker);
        int stateEnd = html.indexOf("</script>", stateStart);
        assertTrue(stateStart >= 0, "HTML 必须包含 JSON 状态脚本");
        assertTrue(stateEnd > stateStart, "JSON 状态脚本必须有结束标签");
        JsonObject state = new Gson().fromJson(html.substring(stateStart + marker.length(), stateEnd), JsonObject.class);
        assertNotNull(state);

        JsonObject geometry = state.getAsJsonObject("geometry");
        assertNotNull(geometry);
        assertEquals(640, geometry.get("screenWidth").getAsInt());
        assertEquals(360, geometry.get("screenHeight").getAsInt());
        assertEquals(20, geometry.get("bossBarBaselineY").getAsInt());
        assertEquals(360, geometry.get("actionBarBottomY").getAsInt());
        JsonObject drag = state.getAsJsonObject("drag");
        assertNotNull(drag);
        assertTrue(drag.get("snapEnabled").getAsBoolean());
        assertEquals(4, drag.get("snapThreshold").getAsInt());
        assertTrue(drag.get("centerGuidesEnabled").getAsBoolean());
        assertTrue(drag.get("altAxisLock").getAsBoolean());

        JsonArray cards = geometry.getAsJsonArray("cards");
        JsonArray avatars = geometry.getAsJsonArray("avatars");
        JsonArray counters = geometry.getAsJsonArray("counters");
        assertNotNull(cards);
        assertNotNull(avatars);
        assertNotNull(counters);
        assertTrue(cards.size() > 0);
        assertTrue(avatars.size() > 0);
        assertTrue(counters.size() > 0);
        assertGeometryFields(geometry,
            "counterCellWidth", "counterCellHeight", "counterAdvance", "counterLabelHeight",
            "counterFrameHeight", "counterDigitHeight", "counterLabelAscent", "counterFrameTopDelta", "counterDigitInset",
            "hotbarWidth", "hotbarAdvance", "hotbarHeight", "hotbarBaseAscent",
            "hotbarMinOffsetY", "hotbarMaxOffsetY");
        assertEquals(PackAssets.COUNTER_CELL_WIDTH, geometry.get("counterCellWidth").getAsInt());
        assertEquals(PackAssets.COUNTER_CELL_HEIGHT, geometry.get("counterCellHeight").getAsInt());
        assertEquals(PackAssets.COUNTER_CELL_ADVANCE, geometry.get("counterAdvance").getAsInt());
        assertEquals(PackAssets.COUNTER_LABEL_HEIGHT, geometry.get("counterLabelHeight").getAsInt());
        assertEquals(PackAssets.COUNTER_FRAME_HEIGHT, geometry.get("counterFrameHeight").getAsInt());
        assertEquals(PackAssets.COUNTER_DIGIT_HEIGHT, geometry.get("counterDigitHeight").getAsInt());
        assertEquals(PackAssets.COUNTER_LABEL_ASCENT, geometry.get("counterLabelAscent").getAsInt());
        assertEquals(PackAssets.COUNTER_FRAME_TOP_DELTA, geometry.get("counterFrameTopDelta").getAsInt());
        assertEquals(PackAssets.COUNTER_DIGIT_INSET, geometry.get("counterDigitInset").getAsInt());

        assertEquals(PackAssets.cardGlyphHeightTierCount(), cards.size());
        for (int tier = 0; tier < cards.size(); tier++) {
            JsonObject card = cards.get(tier).getAsJsonObject();
            assertGeometryFields(card, "tier", "height", "width", "advance");
            assertEquals(tier, card.get("tier").getAsInt());
            assertEquals(PackAssets.cardGlyphHeightAt(tier), card.get("height").getAsInt());
            assertEquals(PackAssets.cardGlyphWidth(tier), card.get("width").getAsInt());
            assertEquals(PackAssets.cardGlyphAdvance(tier), card.get("advance").getAsInt());
        }

        int expectedAvatarCount = PackAssets.AVATAR_PIXEL_SCALE_TIERS.length;
        assertEquals(expectedAvatarCount, avatars.size());
        for (int index = 0; index < avatars.size(); index++) {
            int scale = PackAssets.AVATAR_PIXEL_SCALE_TIERS[index];
            JsonObject avatar = avatars.get(index).getAsJsonObject();
            assertGeometryFields(avatar, "scale", "plainAdvance", "outlinedAdvance", "rowHeight");
            assertEquals(scale, avatar.get("scale").getAsInt());
            assertEquals(PlayerHeadRenderer.advanceWidth(scale, false), avatar.get("plainAdvance").getAsInt());
            assertEquals(PlayerHeadRenderer.advanceWidth(scale, true), avatar.get("outlinedAdvance").getAsInt());
            assertEquals(PackAssets.AVATAR_ROW_TOTAL_PIXELS * scale, avatar.get("rowHeight").getAsInt());
        }

        assertEquals(15, counters.size());
        assertEquals(CardRank.values().length, counters.size());
        TrickHudPreview.CounterSnapshot fixture = TrickHudPreview.counterSnapshot();
        for (int index = 0; index < counters.size(); index++) {
            CardRank rank = CardRank.values()[index];
            JsonObject counter = counters.get(index).getAsJsonObject();
            assertGeometryFields(counter, "label", "remaining", "playedCount", "exhausted");
            assertEquals(rank.label(), counter.get("label").getAsString());
            assertEquals(fixture.remaining(rank), counter.get("remaining").getAsInt(), "Web 与调试棒必须共用 remaining fixture");
            assertEquals(fixture.played(rank), counter.get("playedCount").getAsInt(), "Web 与调试棒必须共用 played fixture");
            assertEquals(fixture.exhausted(rank), counter.get("exhausted").getAsBoolean(), "Web 与调试棒必须共用 exhausted fixture");
            assertFalse(counter.has("advance"), "cell advance 必须由外层固定 geometry 下发");
        }
        assertEquals(0, fixture.played(CardRank.THREE), "fixture 必须覆盖 0 已出");
        assertEquals(1, fixture.played(CardRank.FOUR), "fixture 必须覆盖 1 已出");
        assertEquals(4, fixture.played(CardRank.FIVE), "fixture 必须覆盖 4 已出");
        assertEquals(0, fixture.played(CardRank.SMALL_JOKER), "fixture 必须覆盖小王未出");
        assertEquals(1, fixture.played(CardRank.BIG_JOKER), "fixture 必须覆盖大王已出");

        assertEquals(182, geometry.get("hotbarWidth").getAsInt());
        assertEquals(183, geometry.get("hotbarAdvance").getAsInt());
        assertEquals(22, geometry.get("hotbarHeight").getAsInt());
        assertEquals(PackAssets.HOTBAR_HUD_GLYPH_WIDTH, geometry.get("hotbarWidth").getAsInt());
        assertEquals(PackAssets.HOTBAR_HUD_GLYPH_ADVANCE, geometry.get("hotbarAdvance").getAsInt());
        assertEquals(HotbarDebugOverlayWriter.GLYPH_HEIGHT, geometry.get("hotbarHeight").getAsInt());
        assertEquals(HotbarDebugOverlayWriter.BASE_ASCENT, geometry.get("hotbarBaseAscent").getAsInt());
        assertEquals(HotbarDebugOverlayWriter.minOffsetY(), geometry.get("hotbarMinOffsetY").getAsInt());
        assertEquals(HotbarDebugOverlayWriter.maxOffsetY(), geometry.get("hotbarMaxOffsetY").getAsInt());

        int previewScriptStart = html.indexOf("function geo()");
        assertTrue(previewScriptStart >= 0, "HTML 必须包含几何运行时代码");
        String previewScript = html.substring(previewScriptStart);
        assertFalse(previewScript.contains("6*scale"));
        assertFalse(previewScript.contains("cellW=12"));
        assertFalse(previewScript.contains("SCREEN_H-48"));
        assertFalse(previewScript.contains("abBase"));
        assertFalse(previewScript.contains("g.cardWidth"));
        assertFalse(previewScript.contains("g.cardAdvance"));
        assertTrue(previewScript.contains("g.cards"));
        assertTrue(previewScript.contains("g.avatars"));
        assertTrue(previewScript.contains("g.counters"));
        // 新版必须按当前 scale 从 counterTiers 查表，缺档显式失败，不得静默回退旧字段。
        assertTrue(previewScript.contains("cntTier=(g.counterTiers||[]).find(x=>Number(x.scale)===cntScale)"));
        assertTrue(previewScript.contains("if(!cntTier)throw new Error"));
        assertTrue(previewScript.contains("counterCellWidth=Number(cntTier.cellWidth)"));
        assertTrue(previewScript.contains("counterCellHeight=Number(cntTier.cellHeight)"));
        assertTrue(previewScript.contains("counterAdvance=Number(cntTier.advance)"));
        assertTrue(previewScript.contains("counterLabelAscent=Number(cntTier.labelAscent)"));
        assertTrue(previewScript.contains("cell.playedCount"));
        assertTrue(previewScript.contains("textContent=String(cell.playedCount)"));
        assertFalse(previewScript.contains("digits=hidden?'':String(cell.remaining)"));
        assertTrue(previewScript.contains("cell.exhausted"));
        assertTrue(previewScript.contains("cnt-label"));
        assertTrue(previewScript.contains("cnt-frame"));
        assertTrue(previewScript.contains("cnt-digit"));
        assertTrue(previewScript.contains("el.children[0].style.display=hidden?'none':''")
                && previewScript.contains("el.children[1].style.display=hidden?'none':''")
                && previewScript.contains("el.children[2].style.display=hidden?'none':''"),
            "hide-exhausted 时三层内容都必须隐藏，只保留外层 cell 占位");
        assertTrue(previewScript.contains("el.hidden=false") && previewScript.contains("data-index"),
            "隐藏状态必须保留稳定外层 cell 占位");
        assertFalse(previewScript.contains("cellW=12"));
        assertFalse(previewScript.contains("cnH=14"));
        assertFalse(previewScript.contains("cell.label)+':'+cell.remaining"));
        assertTrue(previewScript.contains("Math.floor((v.width-maxW)/2)"));
        // 旧断言编码了 `v.height-g.hotbarHeight+ascentDelta`——实现改为 rowGeom 查表的 r.hbH/r.hbAdv。
        assertTrue(previewScript.contains("y:v.height-r.hbH+h"));
        assertTrue(previewScript.contains("r.hbAdv"));
        assertTrue(previewScript.contains("r.cardHeight-Number(vals['trick-hud.offset-down'])"));
        assertTrue(previewScript.contains("r.avatarHeight-Number(vals['trick-hud.avatar-offset-down'])"));
        // counter.offset-down 已折入服务端下发的 labelAscent，页面直接消费该值，不能二次扣减。
        assertTrue(previewScript.contains("base-r.counterLabelAscent"));
        assertFalse(previewScript.contains("r.counterLabelAscent-Number(vals['trick-hud.counter.offset-down'])"));
        assertFalse(previewScript.contains("const cnY=cdY+r.cardHeight+r.counterGap"),
            "记牌行 baseline 必须按 counter label ascent 与 avatar-offset-down 对齐实际 provider，不能跟牌行高度相加");
        assertTrue(previewScript.contains("hbBaseAscent"),
            "Hotbar 预览必须消费服务端下发的 baseAscent 几何");
        assertTrue(previewScript.contains("r.hbTexture") && previewScript.contains("r.hbSelectTexture"),
            "Hotbar 资源必须消费服务端下发的真实纹理名");
        // 旧断言编码了硬编码常量 `const slotW=18,slotH=20,slotStep=20,slotsStart=2`——
        // 实现改为从 hotbars[] 按 scale 查表读取 r.hbSlotW/r.hbSlotStep 等，不再有此硬编码行。
        // 新断言验证从 rowGeom 查表消费的字段名。
        assertTrue(previewScript.contains("r.hbSlotW"));
        assertTrue(previewScript.contains("r.hbSlotStep"));
        assertTrue(previewScript.contains("r.hbSlotsStartX"));
        assertTrue(previewScript.contains("for(let i=0;i<r.hbSlotCount;i++)"));
    }

    @Test
    void counter预览按snapshotOffset与当前表单差值计算真实Y() throws Exception {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");
        String marker = "<script type='application/json' id='muz-state'>";
        int stateStart = html.indexOf(marker);
        int stateEnd = html.indexOf("</script>", stateStart);
        JsonObject geometry = new Gson().fromJson(
            html.substring(stateStart + marker.length(), stateEnd), JsonObject.class
        ).getAsJsonObject("geometry");
        int scriptStart = html.indexOf("function geo()");
        int scriptEnd = html.indexOf("function staticCards", scriptStart);
        assertTrue(scriptStart >= 0 && scriptEnd > scriptStart, "必须能提取几何与 staticBoxes JS");

        String nodeScript = "const assert=require('node:assert/strict');"
            + "const state={values:{'trick-hud.counter.offset-down':122},geometry:" + geometry + "};"
            + "const document={getElementById:id=>({value:id==='viewportWidth'?'640':'360'})};"
            + "const previewPanel={getBoundingClientRect:()=>({width:1000,height:800})};"
            + html.substring(scriptStart, scriptEnd)
            + "const base={'trick-hud.enabled':true,'trick-hud.card-step':22,'trick-hud.card-height':53,"
            + "'trick-hud.avatar-scale':6,'trick-hud.avatar-gap':6,'trick-hud.avatar-outline.enabled':true,"
            + "'trick-hud.counter.enabled':true,'trick-hud.counter.scale':100,'trick-hud.counter.gap':2,"
            + "'trick-hud.offset-x':0,'trick-hud.card-offset-x':0,'trick-hud.offset-down':50,"
            + "'trick-hud.avatar-offset-x':0,'trick-hud.avatar-offset-down':122,"
            + "'trick-hud.counter.offset-x':0,'hotbar-hud.enabled':false,'hotbar-hud.scale':100};"
            + "const snapshot=rowGeom({...base,'trick-hud.counter.offset-down':122});"
            + "assert.equal(snapshot.counterLabelAscent,-110);"
            + "function top(offset){return staticBoxes({...base,'trick-hud.counter.offset-down':offset}).boxes.counter.y}"
            + "assert.equal(top(0),8);assert.equal(top(122),130);"
            + "process.stdout.write('counter Y numeric checks passed');";
        java.nio.file.Path script = Files.createTempFile("muz-counter-preview-", ".js");
        try {
            Files.writeString(script, nodeScript, java.nio.charset.StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("node", script.toString())
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "Node 数值测试超时");
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("counter Y numeric checks passed"), output);
        } finally {
            Files.deleteIfExists(script);
        }

        for (Path page : List.of(Path.of("debug-hud-preview.html"),
            Path.of("src/main/resources/debug-hud-preview.html"))) {
            String standalone = Files.readString(page);
            assertFalse(standalone.contains("||33") || standalone.contains("||34") || standalone.contains("||36"),
                page + " 不得保留旧 counter 几何 fallback");
            assertTrue(standalone.contains("未在 counterTiers 中声明"),
                page + " 缺少 counterTiers 缺档提示");
        }
    }

    @Test
    void clampDelta正常小层允许移动且仅对超大层居中() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        String clamp = extractFunction(html, "function clampDelta(");
        String normalized = clamp.replaceAll("\\s+", "");
        assertTrue(normalized.contains("min=-base"),
            "clampDelta 的最小位移必须保证图层左边不越界");
        assertTrue(normalized.contains("max=extent-size-base"),
            "clampDelta 的最大位移必须保证图层右边不越界");
        assertTrue(normalized.contains("min<=max?Math.max(min,Math.min(max,delta))"),
            "正常小层必须保留指针位移并在边界内 clamp，不能一律锁到中心");
        assertTrue(normalized.contains(":(extent-size)/2-base"),
            "只有图层大于视口时才允许退化为居中位置");
    }

    @Test
    void pointer手势门控按钮和pointerId并在取消时清理() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        int bindStart = html.lastIndexOf("function startStaticDrag(");
        int moveStart = html.lastIndexOf("function moveStaticPointer(");
        assertTrue(bindStart >= 0 && moveStart > bindStart, "必须存在稳定拖动绑定和全局 pointermove");
        String bind = html.substring(bindStart, moveStart);
        assertTrue(bind.contains("e.button!==0") || bind.contains("e.button !== 0"),
            "拖动只能由主指针左键启动，不能让右键/中键改写 HUD");
        assertTrue(bind.contains("pointerId"), "pointerdown 必须记录 pointerId 并建立捕获关系");

        int moveEnd = html.indexOf("function finishStaticPointer()", moveStart);
        assertTrue(moveEnd > moveStart, "必须有独立 finishStaticPointer 收口手势");
        String move = html.substring(moveStart, moveEnd);
        assertTrue(move.contains("pointerId") && (move.contains("e.pointerId") || move.contains("active.pointerId")),
            "pointermove 必须拒绝其他 pointerId 的事件");
        String finish = extractFunction(html, "function finishStaticPointer()");
        assertTrue(html.contains("screen.addEventListener('pointercancel',finishStaticPointer)"),
            "pointercancel 必须终止拖动，而不是遗留 dragging 状态");
        assertTrue(finish.contains("dragging=null") || finish.contains("dragging=null;"),
            "取消/松手后必须清空 dragging 状态");
    }

    @Test
    void 预览刷新保持稳定DOM并单独更新图片节点() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        String preview = extractFunction(html, "function renderPreview()");
        assertFalse(preview.contains("screen.innerHTML"),
            "每帧 renderPreview 不能替换 screen.innerHTML，否则会丢失隐式 pointer capture");
        assertFalse(preview.contains("screen.innerHTML="),
            "拖动期间必须复用已有图层节点，而不是重新拼接整棵 DOM");
        assertTrue(preview.contains("querySelector") || preview.contains("getElementById")
                || html.contains("function ensurePreview"),
            "稳定预览必须通过已存在节点查询/更新");
        assertTrue(html.contains("hb-img") && html.contains("hb-select"),
            "稳定更新路径仍必须保留底图与选中框图片节点");
        assertTrue(html.contains(".src=") || html.contains("setAttribute('src'"),
            "图片资源切换必须更新已有 img 节点的 src，而非依赖整棵 innerHTML");
    }

    @Test
    void 工具面板控件唯一且具备可访问图层语义() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        for (String id : new String[]{"layerTabs", "layerCoords", "guiScale", "snapToggle", "coordX", "coordY"}) {
            assertEquals(1, countOccurrences(html, "id='" + id + "'"), id + " 必须恰好只有一个实例");
        }
        assertTrue(html.contains("role='tablist'") || html.contains("role=\"tablist\""),
            "layerTabs 必须声明 tablist 语义");
        assertTrue(html.contains("role='tab'") || html.contains("role=\"tab\""),
            "每个图层切换控件必须声明 tab 语义");
        assertTrue(html.contains("aria-selected"), "活动图层必须通过 aria-selected 暴露给辅助技术");
        assertTrue(html.contains("aria-controls"), "图层标签必须关联唯一的预览/坐标区域");
        assertTrue(html.contains("aria-label='层水平偏移") || html.contains("aria-label=\"层水平偏移"),
            "坐标输入必须有可访问的水平偏移标签");
        assertTrue(html.contains("aria-label='客户端 GUI 倍率") || html.contains("aria-label=\"客户端 GUI 倍率"),
            "scale 工具必须有可访问标签");
        assertTrue(html.contains("aria-label='Minecraft 风格吸附") || html.contains("aria-label=\"Minecraft 风格吸附"),
            "snap 工具必须有可访问标签");
    }

    @Test
    void 右键坐标显示配置偏移并按真实窗口尺寸限幅() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        String coordinate = extractFunction(html, "function showCoordinate(");
        assertTrue(coordinate.contains("DRAG_KEYS") || coordinate.contains("layerXYKeys"),
            "右键坐标必须从当前层配置键读取偏移，而不是只显示屏幕绝对坐标");
        assertTrue(coordinate.contains("offset") || coordinate.contains("偏移"),
            "右键提示必须明确包含配置偏移语义");
        assertTrue(coordinate.contains("getBoundingClientRect") || coordinate.contains("offsetWidth"),
            "工具提示限幅必须使用实际渲染尺寸，不能依赖固定 190/100 魔数");
        assertTrue(coordinate.contains("window.innerWidth") && coordinate.contains("window.innerHeight"),
            "右键提示必须按真实窗口宽高限幅");
        assertTrue(coordinate.contains("Math.min") && coordinate.contains("Math.max"),
            "右键提示位置必须同时做上下界 clamp");
    }

    @Test
    void Shift箭头不劫持输入或busy且不修改viewPan配置() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        int keydownStart = html.lastIndexOf("document.addEventListener('keydown'");
        assertTrue(keydownStart >= 0, "必须存在 Shift 箭头键处理器");
        int keydownEnd = html.indexOf("});", keydownStart);
        assertTrue(keydownEnd > keydownStart, "Shift 键处理器必须闭合");
        String keydown = html.substring(keydownStart, keydownEnd + 3);
        assertTrue(html.contains("function nudgeActive(dx,dy){if(busy||dragging||resizing||panning"),
            "busy 时不能劫持 Shift 箭头键");
        assertTrue(html.contains("!e.target.closest('input,select,textarea,button,[contenteditable=true]')"),
            "输入/选择控件获得焦点时不能劫持原生方向键行为");
        assertTrue(keydown.contains("e.preventDefault()"), "真正用于微调时才应阻止默认滚动行为");

        String nudge = extractFunction(html, "function nudgeActive(");
        assertTrue(nudge.contains("DRAG_KEYS") && nudge.contains("setField"),
            "Shift 微调必须写入当前层对应配置偏移");
        assertFalse(nudge.contains("viewPanX") || nudge.contains("viewPanY"),
            "Shift 箭头微调不能把页面视图平移状态写进 HUD 配置");
    }

    @Test
    void Fullscreen失败保留普通viewport并提供退出路径() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        int button = html.lastIndexOf("fullscreenBtn");
        assertTrue(button >= 0, "必须存在全屏按钮");
        String fullscreen = html.substring(button, Math.min(html.length(), button + 1200));
        assertTrue(fullscreen.contains("requestFullscreen"), "必须调用 Fullscreen API");
        assertTrue(fullscreen.contains("fullscreenElement") && fullscreen.contains("exitFullscreen"),
            "已进入全屏时必须提供退出路径");
        assertTrue(fullscreen.contains("catch") && fullscreen.contains("setPrompt"),
            "Fullscreen API 失败必须显示提示并保留普通编辑模式");
        assertFalse(fullscreen.contains("throw "), "全屏失败不能抛出并破坏普通 viewport 编辑器");
    }

    @Test
    void 连续输入通过requestAnimationFrame合并且不直接重绘() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        String schedule = extractFunction(html, "function scheduleRender()");
        assertTrue(schedule.contains("renderQueued") && schedule.contains("if(renderQueued)return"),
            "重复输入必须共用 renderQueued 闸门");
        assertTrue(schedule.contains("requestAnimationFrame"), "连续输入必须合并到 requestAnimationFrame");
        assertTrue(schedule.contains("renderQueued=false"), "帧回调必须释放 renderQueued 闸门");

        String changed = extractFunction(html, "function changed(");
        assertTrue(changed.contains("scheduleRender()"), "字段输入必须进入合并刷新队列");
        assertFalse(changed.contains("renderPreview()"), "字段输入不能每次事件直接重建/重绘预览");
    }

    @Test
    void hotbar两张图片都必须在加载失败时显示可见错误() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");
        int hotbarStart = html.lastIndexOf("function staticHotbar(");
        int hotbarEnd = html.indexOf("function bindStaticEvents", hotbarStart);
        assertTrue(hotbarStart >= 0 && hotbarEnd > hotbarStart, "必须存在稳定 Hotbar 预览渲染段");
        String hotbar = html.substring(hotbarStart, hotbarEnd);
        assertTrue(countOccurrences(hotbar, "/api/resource/") >= 2,
            "底图和选中框都必须通过同源资源路由加载");
        assertTrue(countOccurrences(hotbar, "node.onerror") >= 1 && hotbar.contains("setImage(img,err") && hotbar.contains("setImage(selected,selectErr"),
            "两张 Hotbar 图片都必须处理加载失败");
        int selected = hotbar.indexOf("hb-select");
        assertTrue(selected >= 0, "必须存在选中框图片");
        String selectedPart = hotbar.substring(selected);
        assertTrue(hotbar.contains("setImage(selected,selectErr"), "选中框图片必须处理加载失败");
        assertTrue(hotbar.contains("hb-select-error") && hotbar.contains("selectErr"),
            "选中框加载失败也必须显示可见错误，不能静默隐藏");
        assertTrue(hotbar.contains("hb-img-error") && hotbar.contains("hb-select-error")
                && hotbar.contains("error.style.display='flex'"),
            "底图与选中框必须各有可见的资源错误反馈");
    }

    @Test
    void 页面吸附默认开启且不继承旧快照关闭状态() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertTrue(html.contains("let pageSnapEnabled=true"), "页面吸附默认必须开启");
        assertFalse(html.contains("pageSnapEnabled=dragCfg.snapEnabled"),
            "页面吸附开关不能继承旧快照的关闭状态");
        assertFalse(html.contains("pageSnapEnabled=state.drag"),
            "页面吸附状态只属于当前页面，不得从服务端配置快照恢复");
    }

    /** 从内联脚本中提取函数声明到下一个函数声明之间的源码片段。 */
    private static String extractFunction(String html, String functionMarker) {
        int start = html.lastIndexOf(functionMarker);
        assertTrue(start >= 0, "HTML 必须包含函数：" + functionMarker);
        int next = html.indexOf("function ", start + functionMarker.length());
        int scriptEnd = html.indexOf("</script>", start);
        int end = next >= 0 && next < scriptEnd ? next : scriptEnd;
        assertTrue(end > start, "函数源码片段必须可提取：" + functionMarker);
        return html.substring(start, end);
    }

    /** 统计源码中固定契约标记出现次数，避免只断言至少出现一次。 */
    private static int countOccurrences(String text, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }

    private static void assertGeometryFields(JsonObject object, String... fields) {
        for (String field : fields) {
            assertTrue(object.has(field), "几何对象必须包含字段：" + field);
            assertNotNull(object.get(field), "几何字段不能为 null：" + field);
        }
    }

    @Test
    void pointerMoveOnlyUpdatesDraggedLayerUntilFinishDrag() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(),
            List.of(),
            List.of()
        ), "token");

        int setFieldStart = html.indexOf("function setField(key,val)");
        int moveStart = html.indexOf("function moveStaticPointer(");
        int finishStart = html.indexOf("function finishStaticPointer()", moveStart);
        assertTrue(setFieldStart >= 0, "HTML 必须包含拖动字段更新函数");
        assertTrue(moveStart > setFieldStart, "HTML 必须包含稳定 pointermove 处理");
        assertTrue(finishStart > moveStart, "稳定手势收口必须位于 pointermove 之后");
        String setFieldBlock = html.substring(setFieldStart, moveStart);
        // 旧断言编码了 markDirty(el) 函数名——实现改为 updateDirty(key) 并早返回，
        // 逻辑等价：拖动中只更新 dirty 集合，不走 changed() 重建预览。
        assertTrue(setFieldBlock.contains("if(dragging||resizing){updateDirty(key);return true}"),
            "拖动或缩放中的 setField 必须只走 updateDirty 轻量路径，不重建预览");

        String pointerMoveBlock = html.substring(moveStart, finishStart);
        assertTrue(pointerMoveBlock.contains("setField(d.keys[0],d.bx+dx)"));
        assertTrue(pointerMoveBlock.contains("if(d.keys[1])setField(d.keys[1],d.by+dy)"));
        assertFalse(pointerMoveBlock.contains("changed("), "pointermove 不能直接走会重建预览的 changed 路径");
        assertTrue(pointerMoveBlock.contains("d.el.style.transform='translate('"),
            "pointermove 必须只修改当前拖动层的临时 transform");
        assertTrue(pointerMoveBlock.contains("cssScale()*screenZoom()"),
            "指针位移必须按 CSS 缩放与舞台缩放共同换算");
        assertFalse(pointerMoveBlock.contains("renderPreview()"), "pointermove 期间不能调用 renderPreview");
        assertFalse(pointerMoveBlock.contains("renderWarnings()"), "pointermove 期间不能调用 renderWarnings");

        String finishBlock = html.substring(finishStart, html.indexOf("function renderPreview()", finishStart));
        assertFalse(finishBlock.contains("screen.innerHTML"), "手势收口不能替换稳定预览 DOM");
        assertTrue(finishBlock.contains("flushStaticRender()"), "手势收口必须立即刷新一次稳定节点");
        assertTrue(finishBlock.contains("releasePointerCapture"), "手势收口必须释放 pointer capture");
        assertTrue(html.contains("screen.addEventListener('pointercancel',finishStaticPointer)"),
            "pointercancel 必须复用稳定手势收口");
    }

    @Test
    void 零位移pointermove不触发snap造成意外dirty() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // hasMoved 字段：pointerdown 初始化为 false，pointermove 检查亚像素阈值
        assertTrue(html.contains("hasMoved:false"),
            "pointerdown 必须初始化 hasMoved 为 false");
        assertTrue(html.contains("!d.hasMoved"),
            "pointermove 必须在首次有效位移前检查 hasMoved");
        assertTrue(html.contains("d.hasMoved=true"),
            "超过阈值后必须标记 hasMoved=true");
    }

    @Test
    void Alt锁轴后snap不在被锁轴方向施加吸附() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // snap 分支必须排除被轴锁锁定的方向
        assertTrue(html.contains("d.axis!=='y'&&Math.abs(dx-tx)"),
            "X 方向 snap 必须排除 Y 轴锁（axis==='y' 时不 snap X）");
        assertTrue(html.contains("d.axis!=='x'&&Math.abs(dy-ty)"),
            "Y 方向 snap 必须排除 X 轴锁（axis==='x' 时不 snap Y）");
    }

    @Test
    void 保存成功和失败消息同步到msg和dragHint() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // 保存成功后 dragHint 必须和 msg 显示同样的文本
        assertTrue(html.contains("dragHint.textContent=text}catch"),
            "保存成功后 dragHint 必须和 msg 显示同一段完整文本");

        // 服务端 messages 必须附加到显示文本
        assertTrue(html.contains("srvMsgs=(j.messages||[])"),
            "必须提取服务端 messages 数组");
        assertTrue(html.contains("if(srvMsgs.length)text+="),
            "服务端有消息时必须追加到显示文本");

        // 错误消息同步到两处
        assertTrue(html.contains("msg.textContent=e.message;dragHint.textContent=e.message"),
            "错误消息必须同时写入 msg 和 dragHint");

        // 撤销也同步
        assertTrue(html.contains("msg.textContent=text;dragHint.textContent=text"),
            "撤销消息必须同时写入 msg 和 dragHint");
    }

    @Test
    void CtrlS始终阻止浏览器默认行为即使busy() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // keydown 处理器的 preventDefault 必须在 busy 检查之前
        int keydownStart = html.indexOf("document.addEventListener('keydown'");
        assertTrue(keydownStart >= 0, "必须注册 keydown 监听");
        String keydownBlock = html.substring(keydownStart, html.indexOf("));", keydownStart) + 3);
        assertFalse(keydownBlock.startsWith("document.addEventListener('keydown',e=>{if(busy)return;"),
            "keydown 不能在 busy 时整体 return——必须始终 preventDefault 阻止浏览器保存/刷新");
        // 仍然包含 preventDefault
        assertTrue(keydownBlock.contains("e.preventDefault()"),
            "快捷键必须阻止浏览器默认行为");
    }

    @Test
    void 视口和页面缩放变化触发预览重绘() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertTrue(html.contains("['viewportWidth','viewportHeight','guiScale','pageScale'].forEach"),
            "视口和页面缩放控件必须统一进入预览刷新队列");
        assertTrue(html.contains("addEventListener('input',scheduleRender)"),
            "连续视口输入必须通过 requestAnimationFrame 刷新");
    }

    // ── 辅助方法：从 buildHtml 输出中提取 <style> 块和单条 CSS 规则 ──

    /** 提取 {@code <style>...</style>} 之间的完整 CSS 文本。 */
    private static String extractStyleBlock(String html) {
        int start = html.indexOf("<style>");
        int end = html.indexOf("</style>", start);
        assertTrue(start >= 0 && end > start, "HTML 必须包含 <style> 块");
        return html.substring(start + "<style>".length(), end);
    }

    /**
     * 从 CSS 文本中提取指定选择器的 {@code selector{...}} 规则体（含花括号）。
     * 只匹配选择器后紧跟 {@code {} 的首个规则，不进入媒体查询内。
     * 返回 null 表示未找到。
     */
    private static String extractRule(String css, String selector) {
        // 在媒体查询之前的顶层样式中查找；简单实现：找 selector{ 后到下一个 }。
        int pos = css.indexOf(selector + "{");
        if (pos < 0) return null;
        int end = css.indexOf("}", pos);
        if (end < 0) return null;
        return css.substring(pos, end + 1);
    }

    /**
     * 断言一条 CSS 规则中不包含会破坏 sticky 的 overflow 声明。
     * 检查 overflow / overflow-x / overflow-y 三个属性的 hidden / auto / scroll 值。
     */
    private static void assertNoStickyBreakingOverflow(String rule, String selector) {
        // 匹配 overflow[-x|-y] 后跟 : 和值（到分号或花括号）
        Pattern pat = Pattern.compile("overflow(?:-[xy])?\\s*:\\s*([^;}]+)");
        Matcher mat = pat.matcher(rule);
        while (mat.find()) {
            String value = mat.group(1).trim();
            assertFalse(value.contains("hidden"),
                selector + " 不能设 " + mat.group(0).trim() + "（会让 .actions sticky 失效）");
            assertFalse(value.contains("auto"),
                selector + " 不能设 " + mat.group(0).trim() + "（会让 .actions sticky 失效）");
            assertFalse(value.contains("scroll"),
                selector + " 不能设 " + mat.group(0).trim() + "（会让 .actions sticky 失效）");
        }
    }

    // ── 阶段 B4+C 新增测试：层选择、坐标面板、资源路由、hotbar 真实 PNG、滚轮选中槽 ──

    @Test
    void html包含四层选择标签页和坐标面板() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // 四个层标签页
        assertTrue(html.contains("id='layerTabs'"), "HTML 必须包含层选择标签容器");
        assertTrue(html.contains("data-layer='card'"), "必须包含牌行层标签");
        assertTrue(html.contains("data-layer='avatar'"), "必须包含头像层标签");
        assertTrue(html.contains("data-layer='counter'"), "必须包含记牌层标签");
        assertTrue(html.contains("data-layer='hotbar'"), "必须包含 Hotbar 层标签");

        // 坐标面板
        assertTrue(html.contains("id='layerCoords'"), "HTML 必须包含层坐标面板");
        assertTrue(html.contains("id='coordX'"), "必须包含 X 坐标输入框");
        assertTrue(html.contains("id='coordY'"), "必须包含 Y 坐标输入框");
        assertTrue(html.contains("id='coordW'"), "必须包含宽度显示");
        assertTrue(html.contains("id='coordH'"), "必须包含高度显示");
    }

    @Test
    void 层选择通过selectLayer函数同步标签和预览高亮() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // selectLayer 函数存在且正确切换 active 和 selected 类
        assertTrue(html.contains("function selectLayer(kind)"),
            "必须存在 selectLayer 函数");
        assertTrue(html.contains("t.classList.toggle('active',active)"),
            "selectLayer 必须切换标签页的 active 类");
        assertTrue(html.contains("el.classList.toggle('selected',el.dataset.drag===kind)"),
            "selectLayer 必须切换预览层的 selected 类");
        assertTrue(html.contains("updateCoordPanel()"),
            "selectLayer 必须调用 updateCoordPanel 更新坐标面板");
    }

    @Test
    void 坐标面板输入精确写回配置键() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // layerXYKeys 映射四层的配置键
        assertTrue(html.contains("function layerXYKeys(kind)"),
            "必须存在 layerXYKeys 函数");
        assertTrue(html.contains("avatar:['trick-hud.avatar-offset-x','trick-hud.avatar-offset-down']"),
            "头像层必须映射到 avatar-offset-x 和 avatar-offset-down");
        assertTrue(html.contains("card:['trick-hud.card-offset-x','trick-hud.offset-down']"),
            "牌行层必须映射到 card-offset-x 和 offset-down");
        assertTrue(html.contains("counter:['trick-hud.counter.offset-x','trick-hud.counter.offset-down']"),
            "记牌行层水平和纵向偏移映射正确");
        assertTrue(html.contains("hotbar:['hotbar-hud.offset-x','hotbar-hud.offset-y']"),
            "Hotbar 层必须映射到 hotbar-hud.offset-x 和 offset-y");

        // coordX/coordY 输入事件触发 setField
        assertTrue(html.contains("coordX.addEventListener('input'"),
            "X 坐标输入必须绑定 input 事件");
        assertTrue(html.contains("coordY.addEventListener('input'"),
            "Y 坐标输入必须绑定 input 事件");
    }

    @Test
    void 拖动层时同步选中当前层() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertFalse(html.contains("function bindDrag()"), "不得恢复旧的逐层 bindDrag 实现");
        int eventStart = html.indexOf("function bindStaticEvents()");
        int eventEnd = html.indexOf("function startStaticDrag(", eventStart);
        assertTrue(eventStart >= 0 && eventEnd > eventStart, "必须存在稳定 DOM 事件委托");
        String eventBlock = html.substring(eventStart, eventEnd);
        assertTrue(eventBlock.contains("startStaticDrag(e,layer)"), "pointerdown 必须委托到稳定拖动入口");
        assertTrue(html.contains("selectLayer(kind)"), "拖动开始必须同步当前图层");
    }

    @Test
    void 拖动期间更新坐标面板不重建DOM() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        int moveStart = html.indexOf("function moveStaticPointer(");
        int finishStart = html.indexOf("function finishStaticPointer()", moveStart);
        assertTrue(moveStart >= 0 && finishStart > moveStart);
        String moveBlock = html.substring(moveStart, finishStart);
        assertTrue(moveBlock.contains("updateCoordPanel()"), "pointermove 必须在拖动期间更新坐标面板");
        assertFalse(moveBlock.contains("renderPreview()"), "pointermove 期间不能调用 renderPreview");
        assertFalse(moveBlock.contains("innerHTML"), "pointermove 期间不能重建 DOM");
    }

    @Test
    void hotbar预览使用同源PNG路由和选中框() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // 底图 PNG：URL 从 geometry 的 hbTexture 字段动态拼接，不硬编码文件名
        assertTrue(html.contains("/api/resource/'+esc(r.hbTexture)"),
            "Hotbar 底图 URL 必须从 rowGeom 的 hbTexture 字段拼接");
        assertTrue(html.contains("img.className='hb-img'"),
            "底图 img 必须使用 hb-img 样式类");

        // 选中框 PNG：同理从 hbSelectTexture 字段拼接
        assertTrue(html.contains("/api/resource/'+esc(r.hbSelectTexture)"),
            "Hotbar 选中框 URL 必须从 rowGeom 的 hbSelectTexture 字段拼接");
        assertTrue(html.contains("selected.className='hb-select'"),
            "选中框 img 必须使用 hb-select 样式类");

        // 持槽指示器
        assertTrue(html.contains("hb-slot-indicator"),
            "Hotbar 预览必须包含持槽指示文本");
    }

    @Test
    void 滚轮切换选中槽仅在hotbar层上方拦截() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // 滚轮监听器绑定在 screen 上，仅在 hotbar 层上方拦截
        assertTrue(html.contains("addEventListener('wheel'"),
            "必须注册 wheel 事件监听");
        assertTrue(html.contains("closest('.layer[data-drag=\"hotbar\"]')"),
            "滚轮必须检测目标是否在 hotbar 层内");
        assertTrue(html.contains("hotbarSelectedSlot"),
            "滚轮必须修改 hotbarSelectedSlot");
        assertTrue(html.contains("Math.max(0,Math.min(8,"),
            "持槽必须限制在 0..8 范围内");
        // 滚轮不写入保存 patch：验证 currentPatch 函数体内不含 hotbarSelectedSlot
        int cpStart = html.indexOf("function currentPatch()");
        assertTrue(cpStart >= 0, "必须存在 currentPatch 函数");
        int cpEnd = html.indexOf("function collectAll()", cpStart);
        assertTrue(cpEnd > cpStart);
        String currentPatchBlock = html.substring(cpStart, cpEnd);
        assertFalse(currentPatchBlock.contains("hotbarSelectedSlot"),
            "currentPatch 函数体内不能引用 hotbarSelectedSlot——滚轮选槽仅页面演示");
    }

    @Test
    void 同源资源路由白名单包含当前profile的hotbarPNG() {
        java.util.Set<String> names = DebugWebServer.resourceWhitelistNames();
        long hotbarCount = names.stream()
            .filter(name -> name.endsWith("hotbar_slots.png") || name.endsWith("hotbar_select.png"))
            .count();
        assertEquals(PackAssets.HOTBAR_SCALE_TIERS.length * 2, hotbarCount,
            "资源白名单必须包含当前 profile 每档 × 2 个 hotbar 文件");
        // 100% 默认档
        assertTrue(names.contains("muz:font/hotbar_slots.png"), "缺少 100% 底图");
        assertTrue(names.contains("muz:font/hotbar_select.png"), "缺少 100% 选中框");
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            String prefix = scale == PackAssets.DEFAULT_HUD_SCALE ? "muz:font/" : "muz:font/scale_" + scale + "/";
            assertTrue(names.contains(prefix + "hotbar_slots.png"), "缺少 " + scale + "% 底图");
            assertTrue(names.contains(prefix + "hotbar_select.png"), "缺少 " + scale + "% 选中框");
        }
    }

    @Test
    void CSS包含层选择和坐标面板样式() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");
        String css = extractStyleBlock(html);

        assertNotNull(extractRule(css, ".layer-tabs"),
            "CSS 必须包含 .layer-tabs 规则");
        assertNotNull(extractRule(css, ".layer-tab"),
            "CSS 必须包含 .layer-tab 规则");
        assertNotNull(extractRule(css, ".layer-coords"),
            "CSS 必须包含 .layer-coords 规则");
        assertNotNull(extractRule(css, ".layer.selected"),
            "CSS 必须包含 .layer.selected 规则");
        assertNotNull(extractRule(css, ".hb-img"),
            "CSS 必须包含 .hb-img 规则");
        assertNotNull(extractRule(css, ".hb-select"),
            "CSS 必须包含 .hb-select 规则");
    }

    @Test
    void renderForm完成后同步层选择状态() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // renderForm 必须在最后调用 selectLayer
        int renderFormStart = html.indexOf("function renderForm()");
        assertTrue(renderFormStart >= 0);
        // 提取从 renderForm 到下一个 function 定义之间的文本
        int renderFormEnd = html.indexOf("function field(f)", renderFormStart);
        assertTrue(renderFormEnd > renderFormStart);
        String renderFormBlock = html.substring(renderFormStart, renderFormEnd);
        assertTrue(renderFormBlock.contains("selectLayer(activeLayer)"),
            "renderForm 必须调用 selectLayer(activeLayer) 同步层选择状态");
    }

    @Test
    void hotbar选中框定位使用slotStep和预览变量() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // 选中框定位使用 hotbarSelectedSlot 与 rowGeom 查表的 hbSlotStep
        assertTrue(html.contains("hotbarSelectedSlot*r.hbSlotStep"),
            "选中框 left 必须基于 hotbarSelectedSlot 和 rowGeom 查表的 hbSlotStep 计算");
        // PNG 加载失败显示可见资源缺失提示，不静默隐藏
        assertTrue(html.contains("hb-img-error"),
            "加载失败必须显示可见的资源缺失提示");
        assertTrue(html.contains("底图缺失"),
            "加载失败提示必须包含明确的缺失说明文本");
    }

    @Test
    void setBusy禁用包括坐标面板输入() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertTrue(html.contains("#coordX,#coordY"),
            "setBusy 必须禁用坐标面板输入框");
    }

    // ── 阶段 B4+C 第二轮新增测试 ──

    @Test
    void 缩放手柄CSS和JS框架存在() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");
        String css = extractStyleBlock(html);

        // CSS 手柄样式
        assertNotNull(extractRule(css, ".resize-handle"), "CSS 必须包含 .resize-handle 规则");
        assertNotNull(extractRule(css, ".resize-handle.se"), "CSS 必须包含 .resize-handle.se 规则");

        // JS SCALE_KEYS 映射
        assertTrue(html.contains("SCALE_KEYS"), "必须存在缩放档位键映射");
        assertTrue(html.contains("card:'trick-hud.card-height'"), "牌行缩放对应 card-height");
        assertTrue(html.contains("avatar:'trick-hud.avatar-scale'"), "头像缩放对应 avatar-scale");
        assertTrue(html.contains("counter:'trick-hud.counter.scale'"), "记牌缩放对应 counter.scale");
        assertTrue(html.contains("hotbar:'hotbar-hud.scale'"), "Hotbar 缩放对应 hotbar.scale");

        // layerHandles 函数
        assertFalse(html.contains("function layerHandles(kind)"), "稳定 DOM 不应恢复旧 layerHandles 函数");
        assertTrue(html.contains("['nw','ne','sw','se','n','s','w','e'].forEach"), "稳定场景必须一次创建八个缩放手柄");
        assertTrue(html.contains("h.dataset.resize=dir"), "手柄必须有 data-resize 属性");
    }

    @Test
    void 缩放pointermove不重建DOM且松手后完整render() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertFalse(html.contains("function bindResize()"), "不得恢复旧的逐层 bindResize 实现");
        assertTrue(html.contains("function bindStaticEvents()"), "必须由稳定 DOM 事件委托负责缩放");
        assertTrue(html.contains("kind!==activeLayer"), "非活动层不可开始 resize");

        int resizeMoveStart = html.indexOf("function moveStaticResize(e)");
        int finishStart = html.indexOf("function nudgeActive(", resizeMoveStart);
        assertTrue(resizeMoveStart >= 0 && finishStart > resizeMoveStart, "必须存在稳定缩放与统一收口");
        String resizeMoveBlock = html.substring(resizeMoveStart, finishStart);
        assertFalse(resizeMoveBlock.contains("renderPreview()"), "缩放 pointermove 不能调用 renderPreview");
        assertFalse(resizeMoveBlock.contains("innerHTML"), "缩放 pointermove 不能操作 innerHTML");
        assertTrue(resizeMoveBlock.contains(".style.width="), "缩放 pointermove 必须直接修改 CSS 宽度");
        assertTrue(html.contains("el.style.transform=''"), "每次稳定 render 必须清空旧 layer transform");
    }

    @Test
    void counter使用独立offsetDown而非耦合avatar() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // DRAG_KEYS 中 counter 的 Y 键
        assertTrue(html.contains("counter:['trick-hud.counter.offset-x','trick-hud.counter.offset-down']"),
            "DRAG_KEYS 中 counter Y 必须是独立的 counter.offset-down");

        // CounterTier.labelAscent 已包含 snapshot offset；当前表单偏移只能通过
        // 「当前表单 offset - snapshot offset」差值叠加，避免重复下移并保留未保存拖动预览。
        String previewScript = html.substring(html.indexOf("function geo()"));
        assertTrue(previewScript.contains("counterLabelAscent=Number(cntTier.labelAscent)"),
            "counter 预览 Y 定位必须消费 counterTiers 的真实 labelAscent");
        assertTrue(previewScript.contains("snapshotCounterOffset:snapshotCounterOffset"),
            "rowGeom 必须保留生成快照时的 counter offset");
        assertTrue(previewScript.contains("r.counterLabelAscent+Number(vals['trick-hud.counter.offset-down'])-r.snapshotCounterOffset"),
            "counter Y 必须使用当前表单 offset 与 snapshot offset 的差值");
        // 不使用 avatar-offset-down 定位 counter（已解耦）
        int counterSectionStart = previewScript.indexOf("if(vals['trick-hud.counter.enabled']){");
        if (counterSectionStart >= 0) {
            String counterSection = previewScript.substring(counterSectionStart,
                previewScript.indexOf("if(vals['hotbar-hud.enabled'])", counterSectionStart));
            assertFalse(counterSection.contains("avatar-offset-down"),
                "counter 预览区段不应引用 avatar-offset-down");
        }
    }

    @Test
    void geometry按scale从counterTiers和hotbars数组查表() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        int scaleKeys = html.indexOf("const SCALE_KEYS={");
        int renderPreview = html.indexOf("function renderPreview()");
        assertTrue(scaleKeys >= 0 && renderPreview > scaleKeys,
            "SCALE_KEYS 必须位于 renderPreview 外部，供缩放事件处理器访问");

        // counterTiers 查表
        assertTrue(html.contains("g.counterTiers"), "必须从 geometry 读取 counterTiers 数组");
        assertTrue(html.contains("cntTier.cellWidth"), "counter 几何必须从 cntTier 对象读取");

        // hotbars 查表
        assertTrue(html.contains("g.hotbars"), "必须从 geometry 读取 hotbars 数组");
        assertTrue(html.contains("hb.slotWidth"), "hotbar 几何必须从 hb 查表对象读取");
        assertTrue(html.contains("hb.texture"), "hotbar 纹理名必须从查表对象读取");
        assertTrue(html.contains("hb.selectTexture"), "选中框纹理名必须从查表对象读取");

        // 不再有硬编码魔数
        assertFalse(html.contains("const slotW=18"), "不能硬编码 slotW=18");
        assertFalse(html.contains("const selW=20"), "不能硬编码 selW=20");
    }

    @Test
    void onerror显示可见缺失提示而非静默隐藏() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertTrue(html.contains("hb-img-error"), "必须包含资源缺失提示样式类");
        assertTrue(html.contains("底图缺失"), "必须包含可读的缺失说明文本");
        // 底图 onerror 切换可见错误提示，不是简单 display:none
        assertTrue(html.contains("node.onerror") && html.contains("error.style.display='flex'"),
            "onerror 必须显示稳定节点对应的错误提示元素");
    }

    @Test
    void coordXY标注为配置偏移不误导为绝对坐标() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertTrue(html.contains("X 偏移") || html.contains("X偏移"),
            "X 坐标标签必须标注为偏移");
        assertTrue(html.contains("Y 偏移") || html.contains("Y偏移"),
            "Y 坐标标签必须标注为偏移");
        assertTrue(html.contains("配置值") || html.contains("配置偏移"),
            "坐标输入 aria-label 必须提示是配置值");
    }

    @Test
    void 资源路由白名单拒绝非法文件名() {
        // 资源键使用完整 muz:font/... 路径，并严格受当前 profile 白名单约束
        java.util.Set<String> names = DebugWebServer.resourceWhitelistNames();
        // 合法路径能命中
        assertTrue(names.contains("muz:font/hotbar_slots.png"));
        int firstScale = PackAssets.HOTBAR_SCALE_TIERS[0];
        String firstPrefix = firstScale == PackAssets.DEFAULT_HUD_SCALE ? "muz:font/" : "muz:font/scale_" + firstScale + "/";
        assertTrue(names.contains(firstPrefix + "hotbar_select.png"));
        // 纯文件名不再是白名单键
        assertFalse(names.contains("hotbar_slots.png"), "纯文件名不能命中白名单");
        // 非法路径不在白名单
        assertFalse(names.contains("config.yml"), "config.yml 不能在资源白名单内");
        assertFalse(names.contains("../etc/passwd"), "路径遍历不能在资源白名单内");
        assertFalse(names.contains(""), "空路径不能在资源白名单内");
        assertFalse(names.contains("muz:font/scale_200/hotbar_slots.png"), "非法档位不在白名单内");
    }

    @Test
    void 资源路由handleResource安全校验() {
        // 源码扫描验证 handleResource 的安全校验逻辑
        try {
            String server = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/DebugWebServer.java"));
            int handleResourceStart = server.indexOf("private void handleResource(");
            assertTrue(handleResourceStart >= 0, "必须存在 handleResource 方法");
            String handleResourceBlock = server.substring(handleResourceStart,
                server.indexOf("\n    }", handleResourceStart) + 6);
            // GET 方法校验
            assertTrue(handleResourceBlock.contains("!\"GET\".equalsIgnoreCase"),
                "handleResource 必须检查 GET 方法");
            assertTrue(handleResourceBlock.contains("sendMethodNotAllowed"),
                "非 GET 请求必须返回 405");
            // 路径提取使用前缀截取，不是 lastIndexOf
            assertTrue(handleResourceBlock.contains("path.substring(prefix.length())"),
                "必须按 /api/resource/ 前缀提取完整相对路径");
            // 路径遍历防护
            assertTrue(handleResourceBlock.contains("name.contains(\"..\")"),
                "必须拒绝包含 .. 的路径遍历");
            // 白名单查找
            assertTrue(handleResourceBlock.contains("RESOURCE_WHITELIST.get(name)"),
                "必须查白名单 Map");
            assertTrue(handleResourceBlock.contains("classpathResource == null"),
                "白名单未命中必须拒绝");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void CSS包含缩放手柄和资源缺失提示样式() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");
        String css = extractStyleBlock(html);

        assertNotNull(extractRule(css, ".hb-img-error"), "CSS 必须包含 .hb-img-error 规则");
        assertNotNull(extractRule(css, ".resize-handle"), "CSS 必须包含 .resize-handle 规则");
    }

    @Test
    void previewResourceManifest和独立路由必须存在并受白名单约束() throws IOException {
        String server = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/DebugWebServer.java"));

        assertTrue(server.contains("/api/preview-resources"),
            "必须提供 preview-resource manifest 接口");
        assertTrue(server.contains("/api/preview-resource/"),
            "必须提供独立的 preview-resource 图片路由");
        assertTrue(server.contains("createContext(\"/api/preview-resources\""),
            "manifest 必须注册为独立 HTTP context");
        assertTrue(server.contains("createContext(\"/api/preview-resource/\""),
            "图片预览路由必须注册为独立 HTTP context");

        for (String field : new String[]{"family", "id", "texture", "width", "height", "advance", "scale", "status"}) {
            assertTrue(server.contains(field), "manifest 条目必须包含字段：" + field);
        }
        assertTrue(server.contains("PREVIEW_RESOURCE_WHITELIST")
                || server.contains("PREVIEW_RESOURCES")
                || server.contains("previewResourceWhitelist"),
            "preview-resource 必须拥有独立的固定白名单");
        assertTrue(server.contains("unavailable") || server.contains("不可用"),
            "未生成的 profile 资源必须显式标记不可用，不能静默回退");
        assertTrue(server.contains("image/png"), "预览资源响应必须固定为 image/png");
        assertTrue(server.contains("X-Content-Type-Options"), "预览资源响应必须保留 nosniff 安全头");
        assertTrue(server.contains("name.contains(\"..\")") || server.contains("contains(\"..\")"),
            "预览资源路由必须拒绝路径遍历");
    }

    @Test
    void previewResourceManifest只允许当前profile的真实牌记牌器和hotbar资源() throws IOException {
        String profile = Files.readString(Path.of("muz-resource-profile.yml"));
        String build = Files.readString(Path.of("build.gradle.kts"));
        String server = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/DebugWebServer.java"));

        assertTrue(profile.contains("heights: [53]"), "当前 profile 必须明确声明真实牌高档位");
        assertTrue(profile.contains("offsets: [0, 50]"), "当前 profile 必须保留牌面基准与当前偏移档");
        assertTrue(profile.contains("scales: [4, 6]"), "当前 profile 必须明确声明稀疏头像 fixture 档位");
        assertTrue(profile.contains("scales: [100]"), "当前 profile 必须明确声明记牌器档位");
        assertTrue(profile.contains("scale: 100"), "当前 profile 必须明确声明 hotbar 档位");

        // 资源白名单必须来自构建期真实产物，而不是浏览器 CSS 伪造的矩形。
        for (String path : new String[]{
            "textures/font/cards/",
            "textures/font/avatar/pixel_",
            "textures/font/avatar/crown_",
            "textures/font/counter",
            "hotbar_slots.png",
            "hotbar_select.png"
        }) {
            assertTrue(build.contains(path), "构建期必须生成真实资源：" + path);
            assertTrue(server.contains(path) || server.contains(path.replace("textures/", "")),
                "preview manifest/白名单必须引用真实资源：" + path);
        }
        assertTrue(server.contains("PackAssets.hotbarTexturePath")
                || server.contains("PackAssets.hotbarSelectTexturePath")
                || server.contains("hotbarTexturePath"),
            "hotbar manifest 必须从 PackAssets 真实路径生成");
        assertTrue(server.contains("counterRankTexturePath")
                || server.contains("counterDigitTexturePath")
                || server.contains("counterFrameTexturePath")
                || server.contains("counterTexturePath"),
            "counter manifest 必须使用分层 PNG 路径 helper");
        assertFalse(server.contains("/api/preview-resource/" + "' + fileName"),
            "前端不得把任意文件名直接拼入预览资源 URL");
    }

    @Test
    void avatar预览必须使用固定fixture和mask而不是猜测玩家皮肤() throws IOException {
        String server = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/DebugWebServer.java"));
        String preview = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        assertTrue(server.contains("pixel_") && server.contains("crown_"),
            "预览数据必须复用构建期 pixel/crown fixture，不能猜测玩家皮肤");
        assertTrue(server.contains("avatarMask")
                || server.contains("mask")
                || server.contains("pixel_") && server.contains("crown_"),
            "头像资源必须明确使用 pixel/crown fixture 或 mask");
        assertTrue(preview.contains("avatar") && preview.contains("sampleCards"),
            "页面状态必须下发头像与牌面 fixture 数据");
        assertFalse(server.contains("textures/player/") || server.contains("textures/skins/"),
            "Debug Web 不得开放任意玩家皮肤路径");
    }

    @Test
    void 磨砂玻璃控制层覆盖编辑控件但不模糊像素舞台() throws IOException {
        String html;
        try (var input = DebugWebServerTest.class.getClassLoader().getResourceAsStream("debug-hud-preview.html")) {
            assertNotNull(input, "测试 classpath 必须包含真实内嵌页面");
            html = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String css = extractStyleBlock(html);

        assertTrue(css.contains("--glass"), "必须定义内嵌页面的玻璃背景变量");
        assertTrue(css.contains("backdrop-filter:blur(")
                || css.contains("backdrop-filter: blur(")
                || css.contains("-webkit-backdrop-filter"),
            "控制层必须使用 backdrop-filter 磨砂效果");
        assertTrue(css.contains("#151a1dcc"), "玻璃背景必须保留半透明背景色");

        for (String selector : new String[]{".toolbar", ".panel", ".coordinate", ".hint", ".actions"}) {
            assertTrue(css.contains(selector), "玻璃控制层必须覆盖：" + selector);
        }
        String screenRule = extractRule(css, ".mc-editor");
        assertNotNull(screenRule, "像素舞台规则必须存在");
        assertFalse(screenRule.contains("backdrop-filter"), "像素舞台不得被玻璃层 blur");
        assertFalse(screenRule.contains("filter:blur"), "像素舞台不得被 CSS blur");
        String actionsRule = extractRule(css, ".actions");
        assertNotNull(actionsRule, "操作栏规则必须存在");
        assertTrue(actionsRule.contains("position:sticky"), "玻璃操作栏仍必须保持 sticky");
        assertTrue(actionsRule.contains("bottom:0"), "玻璃操作栏仍必须吸附底部");
    }
}
