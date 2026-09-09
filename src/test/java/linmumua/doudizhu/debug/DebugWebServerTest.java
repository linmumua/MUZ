package linmumua.doudizhu.debug;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.debug.HotbarDebugOverlayWriter;
import linmumua.doudizhu.model.CardRank;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        assertEquals(19, DebugHudConfigController.fields().size());
        assertFalse(html.contains("只读"));
        assertTrue(html.contains("/api/save"));
        assertTrue(html.contains("保存并应用"));
        assertTrue(html.contains("重新读取"));
        assertTrue(html.contains("异步写入配置与当前 hotbar 覆盖层"));
        assertTrue(html.contains("客户端需重新下载资源包"));
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
        assertTrue(html.contains("targetLeft") || html.contains("tx=Math.floor((g.screenWidth-lw)/2)"));
        assertTrue(html.contains("targetTop") || html.contains("ty=Math.floor((g.screenHeight-lh)/2)"));
        assertTrue(html.contains("lostpointercapture"));
        assertTrue(html.contains("axis:null"));
        assertTrue(html.contains("dragCfg.altAxisLock"));
    }

    @Test
    void 保存页面文案和协调器时序明确要求异步资源流程() throws IOException {
        String server = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/DebugWebServer.java"));
        String coordinator = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/HudWebApplyCoordinator.java"));
        assertTrue(server.contains("applyCoordinator.submitSave(patch)"));
        assertFalse(server.contains("controller.savePatch(patch)"), "HTTP 请求线程不能直接保存配置");
        int write = coordinator.indexOf("overlayWriter.writeAsync");
        int dispatch = coordinator.indexOf("reloadCraftEngineOnMainThread");
        int apply = coordinator.indexOf("plugin.applyHudRuntimeStateFromWeb()");
        int snapshot = coordinator.indexOf("ApplyResult.success(controller.snapshot()");
        assertTrue(write >= 0 && dispatch > write && apply > dispatch && snapshot > apply,
            "必须按异步写资源、主线程 CE 重载、HUD 应用、发布快照的顺序执行");
        assertTrue(coordinator.contains("当前 HUD 没有独立的 Trick HUD CE 字形资源"));
        assertTrue(coordinator.contains("resolveOverlayRootOnMainThread"));
        assertTrue(coordinator.contains("isActive(taskGeneration)"));
        assertTrue(coordinator.contains("executor.shutdownNow()"));
        String overlayWriter = Files.readString(Path.of("src/main/java/linmumua/doudizhu/debug/HotbarDebugOverlayWriter.java"));
        assertTrue(overlayWriter.contains("Files.createTempFile(root, \"pack.yml.\", \".tmp\")"),
            "pack.yml 必须先写入唯一临时文件");
        assertTrue(overlayWriter.contains("Files.createTempFile(imagesDirectory, \"hotbar_debug.yml.\", \".tmp\")"),
            "hotbar_debug.yml 必须先写入唯一临时文件");
        assertTrue(overlayWriter.contains("ATOMIC_MOVE"));
        assertTrue(coordinator.contains("controller::reloadFromDiskForWeb"),
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
        assertGeometryFields(geometry, "hotbarWidth", "hotbarAdvance", "hotbarHeight", "hotbarBaseAscent",
            "hotbarMinOffsetY", "hotbarMaxOffsetY");

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
        int[] expectedRemaining = {4, 4, 4, 4, 4, 4, 4, 12, 1, 1, 1, 1, 1, 1, 0};
        for (int index = 0; index < counters.size(); index++) {
            CardRank rank = CardRank.values()[index];
            JsonObject counter = counters.get(index).getAsJsonObject();
            assertGeometryFields(counter, "label", "remaining", "advance");
            assertEquals(rank.label(), counter.get("label").getAsString());
            assertEquals(expectedRemaining[index], counter.get("remaining").getAsInt());
        }
        assertTrue(counters.asList().stream().anyMatch(element -> element.getAsJsonObject().get("advance").getAsInt() == 16));
        assertTrue(counters.asList().stream().anyMatch(element -> element.getAsJsonObject().get("advance").getAsInt() == 28));
        assertTrue(counters.asList().stream().anyMatch(element -> element.getAsJsonObject().get("advance").getAsInt() == 22));

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
        assertTrue(previewScript.contains("Math.floor((g.screenWidth-maxW)/2)"));
        assertTrue(previewScript.contains("g.actionBarBottomY-g.hotbarHeight+ascentDelta"));
        assertTrue(previewScript.contains("g.hotbarAdvance"));
        assertTrue(previewScript.contains("cardAscent=r.cardHeight-Number(vals['trick-hud.offset-down'])"));
        assertTrue(previewScript.contains("const cdY=bossBaseline-cardAscent"));
        assertTrue(previewScript.contains("const avatarAscent=r.avatarHeight-Number(vals['trick-hud.avatar-offset-down'])"));
        assertTrue(previewScript.contains("const avY=bossBaseline-avatarAscent"));
        assertTrue(previewScript.contains("const cnY=cdY+r.cardHeight+r.counterGap"));
        assertTrue(previewScript.contains("currentAscent=baseAscent-hy"));
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
        assertTrue(setFieldBlock.contains("if(dragging){markDirty(el)}else{changed({target:el})}"),
            "拖动中的 setField 必须只走 markDirty 轻量路径");

        String pointerMoveBlock = html.substring(pointerMoveStart, finishDragStart);
        assertTrue(pointerMoveBlock.contains("setField(k[0],dragging.bx+dx)"));
        assertTrue(pointerMoveBlock.contains("if(k[1])setField(k[1],dragging.by+dy)"));
        assertFalse(pointerMoveBlock.contains("changed("), "pointermove 不能直接走会重建预览的 changed 路径");
        assertTrue(pointerMoveBlock.contains("const cur=document.querySelector('.layer[data-drag=\"'+dragging.kind+'\"]')"),
            "pointermove 必须只定位当前拖动层");
        assertTrue(pointerMoveBlock.contains("cur.style.transform='translate('") ,
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
}
