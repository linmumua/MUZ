package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import org.junit.jupiter.api.Test;

/**
 * 机器人头像那一槽的守护测试。
 *
 * <p>这次改动让机器人从「构建期手绘的固定尺寸位图图标」换成了「真实玩家皮肤渲染的像素头像」，
 * 走和真人完全相同的渲染路径。真正容易坏的两件事：
 * <ol>
 *   <li>皮肤取不到时【那一槽会不会空着】——空槽在 HUD 上就是一个黑洞，比图标难看得多；</li>
 *   <li>换成像素头像后宽度变了，槽内居中要跟着对 —— 报错的宽度会让整行歪。</li>
 * </ol>
 */
class TrickHudBotAvatarTest {
    private static final int SCALE = 6;
    private static final int DOWN_TIER = 1;

    private static TrickHudService.Seat bot(PlayerRole role) {
        return new TrickHudService.Seat(UUID.randomUUID(), true, role);
    }

    private static TrickHudService.Seat human(PlayerRole role) {
        return new TrickHudService.Seat(UUID.randomUUID(), false, role);
    }

    /**
     * 皮肤没就绪时必须回退到位图图标，那一槽不许空。
     *
     * <p><b>守的是哪个 bug。</b>{@code miniMessageForBot} 和真人那条路一样是【异步下载 + 缓存】，
     * 第一次调用几乎总是返回 null（刚开局那一帧）；皮肤站连不通、返回 404 或超时也是 null。
     * 换成真实皮肤之后如果顺手把兜底删了，这些情况下机器人那一槽会整块空着。
     *
     * <p>断言到「图标字体 + 该角色的字形」这个粒度：只断言「非空串」的话，
     * 回退成一个没套 {@code <font:>} 的裸字符照样能过，而那在客户端上是豆腐块。
     */
    @Test
    void 皮肤没就绪时回退到位图图标且槽位不空() {
        for (PlayerRole role : new PlayerRole[]{null, PlayerRole.LANDLORD, PlayerRole.FARMER}) {
            TrickHudView.Avatar slot = TrickHudService.avatarSlotOf(
                bot(role), SCALE, true, null, DOWN_TIER);

            assertFalse(slot.isEmpty(),
                "角色 " + role + "：皮肤没就绪时槽位空了，HUD 上会出现一个黑洞");
            assertTrue(slot.text().contains("<font:" + PackAssets.BOT_AVATAR_FONT + ">"),
                "角色 " + role + "：兜底图标没套自己的字体标签，客户端会显示成豆腐块");
            assertTrue(slot.text().contains(PackAssets.botAvatarChar(role, DOWN_TIER)),
                "角色 " + role + "：兜底用错了角色字形或偏移档，图标会和真人头像上下错开");
            assertEquals(PackAssets.botAvatarAdvanceWidth(role), slot.advancePixels(),
                "角色 " + role + "：兜底图标报出的宽度不是图标自己的宽度，槽内居中会偏");
        }
    }

    /**
     * 真人玩家不在线（{@code Bukkit.getPlayer} 返回 null）时同样要兜底。
     *
     * <p><b>守的是哪个 bug。</b>改动前这条分支是「非 bot 才尝试皮肤，失败了落到 bot 图标」；
     * 重构成「统一先取 rendered，再统一兜底」时很容易把真人这条漏掉，
     * 表现是玩家掉线那一瞬间 HUD 少一个头像。
     */
    @Test
    void 真人取不到皮肤时也要兜底而不是留空槽() {
        TrickHudView.Avatar slot = TrickHudService.avatarSlotOf(
            human(PlayerRole.FARMER), SCALE, true, null, DOWN_TIER);
        assertFalse(slot.isEmpty(), "真人取不到皮肤时槽位空了：玩家掉线那一瞬间 HUD 会缺一个头像");
        assertEquals(PackAssets.botAvatarAdvanceWidth(PlayerRole.FARMER), slot.advancePixels());
    }

    /**
     * 取到皮肤时，报出的宽度必须是【像素头像的宽度】，不是位图图标的宽度。
     *
     * <p><b>守的是哪个 bug。</b>机器人以前恒用 {@code botAvatarAdvanceWidth}（10/11 像素）。
     * 换成像素头像后宽度变成 {@code advanceWidth(scale, outlined)}（scale-6 描边是 60 像素），
     * 忘记同步的话槽内居中会按 11 像素算 lead，机器人头像整体右偏约 25 像素、压到隔壁槽上。
     *
     * <p>顺带钉住「宽度随 scale 与 outlined 变」：写死某一档的值在服主改 avatar-scale 后会错。
     */
    @Test
    void 取到皮肤时槽宽是像素头像宽度而不是图标宽度() {
        String rendered = "<font:" + PackAssets.AVATAR_PIXEL_FONT + ">fake</font>";
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
             scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            for (boolean outlined : new boolean[]{true, false}) {
                TrickHudView.Avatar slot = TrickHudService.avatarSlotOf(
                    bot(PlayerRole.LANDLORD), scale, outlined, rendered, DOWN_TIER);

                assertEquals(rendered, slot.text(), "取到皮肤时不该改动头像文本");
                assertEquals(PlayerHeadRenderer.advanceWidth(scale, outlined), slot.advancePixels(),
                    "倍数 " + scale + " outlined=" + outlined
                        + "：机器人拿到像素头像后报出的宽度不对（还在用位图图标那 10/11 像素？），"
                        + "槽内居中会把头像推到隔壁槽上");
                assertNotEquals(PackAssets.botAvatarAdvanceWidth(PlayerRole.LANDLORD),
                    slot.advancePixels(),
                    "倍数 " + scale + "：报出的宽度恰好等于位图图标宽度，说明没换成像素头像的宽度");
            }
        }
    }

    /** 空座位（人数不足）该真的留空 —— 给不存在的人画头像反而误导。 */
    @Test
    void 空座位仍然留空而不是画一个机器人图标() {
        assertTrue(TrickHudService.avatarSlotOf(null, SCALE, true, null, DOWN_TIER).isEmpty(),
            "null 座位应当留空");
        assertTrue(TrickHudService.avatarSlotOf(
                TrickHudService.Seat.EMPTY, SCALE, true, "<font:x>y</font>", DOWN_TIER).isEmpty(),
            "没人的座位即使传进来头像文本也该留空：给不存在的人画头像是误导");
    }

    /**
     * 传给皮肤分配器的名单必须是【同桌全部机器人】，且只含机器人。
     *
     * <p><b>守的是哪个 bug。</b>不重脸是靠「一次看到同桌所有 bot」实现的。如果逐槽各算一次
     * （每次名单只有自己），两个 bot 就会各自取自己的首选下标、有概率撞成同一张脸。
     * 名单里混进真人 UUID 也不行：真人会占掉一个皮肤下标，把 bot 挤到别的皮肤上，
     * 而真人是否在线还会让名单在帧间变动 —— 机器人就会换脸。
     */
    @Test
    void 皮肤分配名单是同桌全部机器人且不含真人() {
        TrickHudService.Seat botA = bot(PlayerRole.LANDLORD);
        TrickHudService.Seat botB = bot(PlayerRole.FARMER);
        TrickHudService.Seat person = human(PlayerRole.FARMER);

        List<UUID> ids = TrickHudService.botIdsOf(botA, person, botB);
        assertEquals(2, ids.size(), "名单里的机器人数量不对：漏了同桌的 bot 就无法保证不重脸");
        assertTrue(ids.contains(botA.playerId()) && ids.contains(botB.playerId()),
            "名单漏了某个同桌机器人");
        assertFalse(ids.contains(person.playerId()),
            "真人 UUID 混进了皮肤名单：会占掉一个皮肤下标，还会让名单随真人在线状态漂移");

        // 空槽不该进名单，否则名单长度随人数变、分配结果跟着抖。
        assertEquals(0, TrickHudService.botIdsOf(TrickHudService.Seat.EMPTY, null).size(),
            "空槽进了名单");

        // 这份名单必须真的让两个 bot 分到不同皮肤 —— 这是整条链路的最终目的。
        assertNotEquals(
            PlayerHeadRenderer.botSkinVariant(ids, botA.playerId()),
            PlayerHeadRenderer.botSkinVariant(ids, botB.playerId()),
            "同桌两个机器人分到了同一张皮肤：玩家会以为是同一个人");
    }
}
