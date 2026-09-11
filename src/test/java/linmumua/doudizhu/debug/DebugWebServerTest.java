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
        assertTrue(html.contains("异步写入配置与当前 hotbar 覆盖层"));
        assertTrue(html.contains("客户端需重新下载资源包"));
        assertTrue(html.contains("只开放 22 个 HUD 运行期字段"));
        assertTrue(html.contains("不写入 22 个 HUD 配置键"));
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
        assertTrue(html.contains("tx=(v.width-dragging.width)/2-dragging.baseLeft")
            || html.contains("targetLeft") || html.contains("tx=Math.floor((v.width-lw)/2)"),
            "snap 必须有 X 方向居中目标");
        assertTrue(html.contains("ty=(v.height-dragging.height)/2-dragging.baseTop")
            || html.contains("targetTop") || html.contains("ty=Math.floor((v.height-lh)/2)"),
            "snap 必须有 Y 方向居中目标");
        assertTrue(html.contains("lostpointercapture"));
        assertTrue(html.contains("axis:null"));
        assertTrue(html.contains("dragCfg.altAxisLock"));
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

        // 窄屏响应式布局与溢出防护
        assertTrue(html.contains("max-width:900px"), "必须有窄屏媒体查询");
        assertTrue(html.contains("max-width:500px"), "必须有极窄屏（手机）媒体查询");
        assertTrue(html.contains("minmax(0,1.2fr)"), "main 左列必须用 minmax(0,...) 防止隐式最小宽度");
        assertTrue(html.contains("minmax(0,.8fr)"), "main 右列必须用 minmax(0,...) 防止隐式最小宽度");
        assertFalse(html.contains("minmax(460px"), "main 网格不能硬编码 460px 最小宽度（旧溢出源已移除）");
        assertFalse(html.contains("minmax(360px"), "main 网格不能硬编码 360px 最小宽度（旧溢出源已移除）");
        assertTrue(html.contains("word-break:break-all"), ".key 配置键名必须允许换行防止撑宽标签列");
        assertTrue(html.contains("overflow:auto") && html.contains("max-width:100%"),
            ".preview 必须在面板内横滚（overflow:auto + max-width:100%），不撑破页面");
        assertTrue(html.contains("select{width:100%;min-width:0"), "select/input 必须有 min-width:0");

        // 【sticky 祖先链防回归】：从 <style> 提取 body/.panel/main 规则，
        // 精确排除 overflow/overflow-x/overflow-y 的 hidden/auto/scroll 值。
        // 这些属性会创建新的滚动容器，把 .actions sticky 限制在该容器内而非视口。
        for (String selector : new String[]{"body", ".panel", "main"}) {
            String rule = extractRule(styleBlock, selector);
            assertNotNull(rule, selector + " 样式规则必须存在于 <style> 内");
            assertNoStickyBreakingOverflow(rule, selector);
        }
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
        assertTrue(overlayWriter.contains("Files.createTempFile(root, \"pack.yml.\", \".tmp\")"),
            "pack.yml 必须先写入唯一临时文件");
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

        int expectedAvatarCount = PackAssets.AVATAR_PIXEL_MAX_SCALE - PackAssets.AVATAR_PIXEL_MIN_SCALE + 1;
        assertEquals(expectedAvatarCount, avatars.size());
        for (int index = 0; index < avatars.size(); index++) {
            int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE + index;
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
        // 新版按当前 scale 从 counterTiers 查表，旧顶层字段仅作兼容回退。
        assertTrue(previewScript.contains("cntTier=(g.counterTiers||[]).find(x=>Number(x.scale)===cntScale)"));
        assertTrue(previewScript.contains("counterCellWidth=Number(cntTier?cntTier.cellWidth:g.counterCellWidth)"));
        assertTrue(previewScript.contains("counterCellHeight=Number(cntTier?cntTier.cellHeight:g.counterCellHeight)"));
        assertTrue(previewScript.contains("counterAdvance=Number(cntTier?cntTier.advance:g.counterAdvance)"));
        assertTrue(previewScript.contains("counterLabelAscent=Number(cntTier?cntTier.labelAscent:g.counterLabelAscent)"));
        assertTrue(previewScript.contains("cell.playedCount"));
        assertTrue(previewScript.contains("digits=String(cell.playedCount)"));
        assertFalse(previewScript.contains("digits=hidden?'':String(cell.remaining)"));
        assertTrue(previewScript.contains("cell.exhausted"));
        assertTrue(previewScript.contains("cnt-label"));
        assertTrue(previewScript.contains("cnt-frame"));
        assertTrue(previewScript.contains("cnt-digit"));
        assertTrue(previewScript.contains("if(!hidden){html+='<div class=cnt-label"),
            "hide-exhausted 时三层内容都必须不画，只保留外层 cell 占位");
        assertTrue(previewScript.contains("data-hidden"),
            "隐藏状态应留在外层 cell 上，便于确认占位仍存在");
        assertFalse(previewScript.contains("cellW=12"));
        assertFalse(previewScript.contains("cnH=14"));
        assertFalse(previewScript.contains("cell.label)+':'+cell.remaining"));
        assertTrue(previewScript.contains("Math.floor((v.width-maxW)/2)"));
        // 旧断言编码了 `v.height-g.hotbarHeight+ascentDelta`——实现改为 rowGeom 查表的 r.hbH/r.hbAdv。
        assertTrue(previewScript.contains("v.height-r.hbH+ascentDelta"));
        assertTrue(previewScript.contains("r.hbAdv"));
        assertTrue(previewScript.contains("cardAscent=r.cardHeight-Number(vals['trick-hud.offset-down'])"));
        assertTrue(previewScript.contains("const cdY=bossBaseline-cardAscent"));
        assertTrue(previewScript.contains("const avatarAscent=r.avatarHeight-Number(vals['trick-hud.avatar-offset-down'])"));
        assertTrue(previewScript.contains("const avY=bossBaseline-avatarAscent"));
        // 记牌行使用独立的 counter.offset-down，不再耦合 avatar-offset-down
        assertTrue(previewScript.contains("const counterAscent=r.counterLabelAscent-Number(vals['trick-hud.counter.offset-down'])"));
        assertTrue(previewScript.contains("const cnY=bossBaseline-counterAscent"));
        assertFalse(previewScript.contains("const cnY=cdY+r.cardHeight+r.counterGap"),
            "记牌行 baseline 必须按 counter label ascent 与 avatar-offset-down 对齐实际 provider，不能跟牌行高度相加");
        assertTrue(previewScript.contains("currentAscent=baseAscent-hy"));
        assertTrue(previewScript.contains("const cols=['#E03A3A','#E06A2A','#E08A2A','#D8D030','#3CC050','#30C0A8','#3888E0','#7050D8','#C04AA0']"));
        // 旧断言编码了硬编码常量 `const slotW=18,slotH=20,slotStep=20,slotsStart=2`——
        // 实现改为从 hotbars[] 按 scale 查表读取 r.hbSlotW/r.hbSlotStep 等，不再有此硬编码行。
        // 新断言验证从 rowGeom 查表消费的字段名。
        assertTrue(previewScript.contains("r.hbSlotW"));
        assertTrue(previewScript.contains("r.hbSlotStep"));
        assertTrue(previewScript.contains("r.hbSlotsStartX"));
        assertTrue(previewScript.contains("for(let i=0;i<r.hbSlotCount;i++)"));
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
        int pointerMoveStart = html.indexOf("window.addEventListener('pointermove'");
        int finishDragStart = html.indexOf("function finishDrag()");
        assertTrue(setFieldStart >= 0, "HTML 必须包含拖动字段更新函数");
        assertTrue(pointerMoveStart > setFieldStart, "HTML 必须注册 pointermove 拖动处理");
        assertTrue(finishDragStart > pointerMoveStart, "finishDrag 必须位于 pointermove 之后");
        String setFieldBlock = html.substring(setFieldStart, pointerMoveStart);
        // 旧断言编码了 markDirty(el) 函数名——实现改为 updateDirty(key) 并早返回，
        // 逻辑等价：拖动中只更新 dirty 集合，不走 changed() 重建预览。
        assertTrue(setFieldBlock.contains("if(dragging||resizing){updateDirty(key);return true}"),
            "拖动或缩放中的 setField 必须只走 updateDirty 轻量路径，不重建预览");

        String pointerMoveBlock = html.substring(pointerMoveStart, finishDragStart);
        assertTrue(pointerMoveBlock.contains("setField(k[0],dragging.bx+dx)"));
        assertTrue(pointerMoveBlock.contains("if(k[1])setField(k[1],dragging.by+dy)"));
        assertFalse(pointerMoveBlock.contains("changed("), "pointermove 不能直接走会重建预览的 changed 路径");
        // 旧断言期望 querySelector 定位拖动层——实现改为用 dragging.el 直接引用，
        // 避免拖动期间重建 DOM 后引用丢失。效果相同但更可靠。
        assertTrue(pointerMoveBlock.contains("dragging.el.style.transform='translate('") ||
                pointerMoveBlock.contains("dragging.el.classList.add('drag')"),
            "pointermove 必须只修改当前拖动层的 transform");

        assertFalse(pointerMoveBlock.contains("renderPreview()"),
            "pointermove 期间不能调用 renderPreview");
        assertFalse(pointerMoveBlock.contains("renderWarnings()"),
            "pointermove 期间不能调用 renderWarnings");

        int finishDragEnd = html.indexOf("window.addEventListener('pointerup',finishDrag)", finishDragStart);
        assertTrue(finishDragEnd > finishDragStart, "必须绑定 pointerup 完成拖动");
        String finishDragBlock = html.substring(finishDragStart, finishDragEnd);
        assertTrue(finishDragBlock.contains("renderPreview();renderWarnings();"),
            "finishDrag 才能刷新预览和警告");
        assertTrue(html.contains("window.addEventListener('pointercancel',finishDrag)"),
            "pointercancel 必须复用 finishDrag");
    }

    @Test
    void 零位移pointermove不触发snap造成意外dirty() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // hasMoved 字段：pointerdown 初始化为 false，pointermove 检查亚像素阈值
        assertTrue(html.contains("hasMoved:false"),
            "pointerdown 必须初始化 hasMoved 为 false");
        assertTrue(html.contains("!dragging.hasMoved"),
            "pointermove 必须在首次有效位移前检查 hasMoved");
        assertTrue(html.contains("dragging.hasMoved=true"),
            "超过阈值后必须标记 hasMoved=true");
    }

    @Test
    void Alt锁轴后snap不在被锁轴方向施加吸附() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // snap 分支必须排除被轴锁锁定的方向
        assertTrue(html.contains("dragging.axis!=='y'&&Math.abs(dx-tx)"),
            "X 方向 snap 必须排除 Y 轴锁（axis==='y' 时不 snap X）");
        assertTrue(html.contains("dragging.axis!=='x'&&Math.abs(dy-ty)"),
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
        String keydownBlock = html.substring(keydownStart, html.indexOf(";}", keydownStart) + 2);
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

        assertTrue(html.contains("getElementById('pageScale').onchange"),
            "pageScale 变化必须触发预览重绘");
        assertTrue(html.contains("getElementById('viewportWidth').onchange"),
            "viewportWidth 变化必须触发预览重绘");
        assertTrue(html.contains("getElementById('viewportHeight').onchange"),
            "viewportHeight 变化必须触发预览重绘");
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
        assertTrue(html.contains("classList.toggle('active',t.dataset.layer===kind)"),
            "selectLayer 必须切换标签页的 active 类");
        assertTrue(html.contains("classList.toggle('selected',el.dataset.drag===kind)"),
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

        // bindDrag 中 pointerdown 必须调用 selectLayer
        int bindDragStart = html.indexOf("function bindDrag()");
        assertTrue(bindDragStart >= 0, "HTML 必须包含 bindDrag 函数");
        String bindDragBlock = html.substring(bindDragStart, html.indexOf("window.addEventListener('pointermove'", bindDragStart));
        assertTrue(bindDragBlock.contains("selectLayer(kind)"),
            "pointerdown 必须调用 selectLayer 同步层标签页");
    }

    @Test
    void 拖动期间更新坐标面板不重建DOM() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // pointermove 中调用 updateCoordPanel 但不调用 renderPreview
        int pointerMoveStart = html.indexOf("window.addEventListener('pointermove'");
        int finishDragStart = html.indexOf("function finishDrag()");
        assertTrue(pointerMoveStart >= 0 && finishDragStart > pointerMoveStart);
        String pointerMoveBlock = html.substring(pointerMoveStart, finishDragStart);
        assertTrue(pointerMoveBlock.contains("updateCoordPanel()"),
            "pointermove 必须在拖动期间更新坐标面板");
        assertFalse(pointerMoveBlock.contains("renderPreview()"),
            "pointermove 期间不能调用 renderPreview");
    }

    @Test
    void hotbar预览使用同源PNG路由和选中框() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // 底图 PNG：URL 从 geometry 的 hbTexture 字段动态拼接，不硬编码文件名
        assertTrue(html.contains("/api/resource/'+esc(r.hbTexture)"),
            "Hotbar 底图 URL 必须从 rowGeom 的 hbTexture 字段拼接");
        assertTrue(html.contains("class=hb-img"),
            "底图 img 必须使用 hb-img 样式类");

        // 选中框 PNG：同理从 hbSelectTexture 字段拼接
        assertTrue(html.contains("/api/resource/'+esc(r.hbSelectTexture)"),
            "Hotbar 选中框 URL 必须从 rowGeom 的 hbSelectTexture 字段拼接");
        assertTrue(html.contains("class=hb-select"),
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
    void 同源资源路由白名单包含三档hotbar共6张PNG() {
        // 旧断言只有 2 张——三档缩放（75/100/125）× 两个文件后白名单扩展到 6 条
        java.util.Set<String> names = DebugWebServer.resourceWhitelistNames();
        assertEquals(6, names.size(), "资源白名单必须包含 3 档 × 2 文件 = 6 个条目");
        // 100% 默认档
        assertTrue(names.contains("muz:font/hotbar_slots.png"), "缺少 100% 底图");
        assertTrue(names.contains("muz:font/hotbar_select.png"), "缺少 100% 选中框");
        // 75% 档
        assertTrue(names.contains("muz:font/scale_75/hotbar_slots.png"), "缺少 75% 底图");
        assertTrue(names.contains("muz:font/scale_75/hotbar_select.png"), "缺少 75% 选中框");
        // 125% 档
        assertTrue(names.contains("muz:font/scale_125/hotbar_slots.png"), "缺少 125% 底图");
        assertTrue(names.contains("muz:font/scale_125/hotbar_select.png"), "缺少 125% 选中框");
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
        assertTrue(html.contains("function layerHandles(kind)"), "必须存在 layerHandles 函数");
        assertTrue(html.contains("data-resize="), "手柄必须有 data-resize 属性");
    }

    @Test
    void 缩放pointermove不重建DOM且松手后完整render() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // bindResize 存在
        assertTrue(html.contains("function bindResize()"), "必须存在 bindResize 函数");
        assertTrue(html.contains("bindResize()"), "renderPreview 后必须调用 bindResize");

        // resizing pointermove 不调用 renderPreview
        int resizeMoveStart = html.indexOf("if(!resizing||busy)return");
        assertTrue(resizeMoveStart >= 0, "缩放 pointermove 必须检查 resizing 和 busy");
        int finishResizeStart = html.indexOf("function finishResize()");
        assertTrue(finishResizeStart > resizeMoveStart, "finishResize 必须在缩放 pointermove 之后");
        String resizeMoveBlock = html.substring(resizeMoveStart, finishResizeStart);
        assertFalse(resizeMoveBlock.contains("renderPreview()"),
            "缩放 pointermove 不能调用 renderPreview");
        assertFalse(resizeMoveBlock.contains("innerHTML"),
            "缩放 pointermove 不能操作 innerHTML");
        assertTrue(resizeMoveBlock.contains(".style.width="),
            "缩放 pointermove 必须直接修改 CSS 宽度");

        // finishResize 完整重建
        int finishResizeEnd = html.indexOf("window.addEventListener('pointerup',finishResize)", finishResizeStart);
        assertTrue(finishResizeEnd > finishResizeStart);
        String finishResizeBlock = html.substring(finishResizeStart, finishResizeEnd);
        assertTrue(finishResizeBlock.contains("renderPreview()"),
            "finishResize 必须调用 renderPreview");
    }

    @Test
    void counter使用独立offsetDown而非耦合avatar() {
        String html = DebugWebServer.buildHtml(new DebugHudConfigController.Snapshot(
            Map.of(), List.of(), List.of()
        ), "token");

        // DRAG_KEYS 中 counter 的 Y 键
        assertTrue(html.contains("counter:['trick-hud.counter.offset-x','trick-hud.counter.offset-down']"),
            "DRAG_KEYS 中 counter Y 必须是独立的 counter.offset-down");

        // 预览渲染使用 counter.offset-down
        assertTrue(html.contains("vals['trick-hud.counter.offset-down']"),
            "counter 预览 Y 定位必须读取 counter.offset-down");
        // 不使用 avatar-offset-down 定位 counter（已解耦）
        String previewScript = html.substring(html.indexOf("function geo()"));
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
        assertTrue(html.contains("this.nextElementSibling.style.display"),
            "onerror 必须显示相邻的错误提示元素");
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
        // 旧断言编码了尾段文件名键——三档支持后键改为完整 muz:font/... 路径
        java.util.Set<String> names = DebugWebServer.resourceWhitelistNames();
        // 合法路径能命中
        assertTrue(names.contains("muz:font/hotbar_slots.png"));
        assertTrue(names.contains("muz:font/scale_75/hotbar_select.png"));
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
}
