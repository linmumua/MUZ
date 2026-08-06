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
     * 皮肤【还在下载】时留空，不许退回那张位图图标。
     *
     * <p><b>守的是哪个 bug。</b>服主报「以前那个机器人图标有时候还会显示出来」：皮肤是异步
     * 下载的，第一次调用几乎总是返回 null，于是这里退回位图图标。那张图标是构建期固定
     * 10/11 像素的、不随 avatar-scale 缩放，闪出来时比真人头像小一圈且风格不一致，
     * 表现就是「出牌瞬间有个小图标跳一下」。
     *
     * <p>这个分支全是暂态，下一帧就被真头像替掉，所以正确做法是留空。若有人为了「槽位不许空」
     * 把图标兜底加回来，这条就必须红 —— 那个闪现会原样复活。
     *
     * <p>【宽度仍要是真头像的宽度】：槽宽恒定才能保证皮肤到位时不整行左右跳动。
     * 报成图标宽度（10/11）会让那一槽先窄后宽，比闪图标更明显。
     */
    @Test
    void 皮肤还在下载时留空而不是闪那张位图图标() {
        for (PlayerRole role : new PlayerRole[]{null, PlayerRole.LANDLORD, PlayerRole.FARMER}) {
            TrickHudView.Avatar slot = TrickHudService.avatarSlotOf(
                bot(role), SCALE, true, null, DOWN_TIER, false, false);

            assertTrue(slot.isEmpty(),
                "角色 " + role + "：皮肤下载期间画了兜底图标，出牌瞬间会闪一个尺寸风格都不一致的小图标");
            assertFalse(slot.text().contains(PackAssets.BOT_AVATAR_FONT),
                "角色 " + role + "：仍在引用位图图标字体，说明兜底没真的去掉");
            assertEquals(PlayerHeadRenderer.advanceWidth(SCALE, true), slot.advancePixels(),
                "角色 " + role + "：留空时报的宽度必须仍是真头像宽度，否则皮肤到位时整行会左右跳");
        }
    }

    /**
     * 戴王冠的地主，那一槽的宽度必须按 10 行算 —— 即使描边是关着的。
     *
     * <p><b>守的是哪个 bug。</b>王冠和描边一样把矩阵从 8x8 撑成 10x10，但它们是【互斥】的两条路
     * （见 {@code withCrown}）。服主现在把描边关了、只留王冠，此时 {@code outlined=false} 而矩阵
     * 仍是 10 行。如果宽度只看 {@code outlined}，地主那一槽就会按 8 行报宽，比实际窄一个 scale，
     * 槽内居中把它往左推 —— 表现是「地主的头像跟另外两个没对齐」，而且只有地主歪，很难定位。
     */
    @Test
    void 关了描边的地主戴冠时宽度仍按10行算() {
        String rendered = "<font:muz_avatar>x</font>";
        int tenRows = PlayerHeadRenderer.advanceWidth(SCALE, true);

        // 描边关着 + 戴冠：矩阵是 10 行，宽度必须按 10 行报。
        TrickHudView.Avatar landlord = TrickHudService.avatarSlotOf(
            human(PlayerRole.LANDLORD), SCALE, false, rendered, DOWN_TIER, false, true);
        assertEquals(tenRows, landlord.advancePixels(),
            "戴冠地主按 8 行报宽：那一槽会比实际窄一个 scale，居中把地主头像往左推，只有他歪");

        // 描边关着 + 不戴冠（农民）：矩阵是 8 行，按 8 行报才对。
        TrickHudView.Avatar farmer = TrickHudService.avatarSlotOf(
            human(PlayerRole.FARMER), SCALE, false, rendered, DOWN_TIER, false, false);
        assertEquals(PlayerHeadRenderer.advanceWidth(SCALE, false), farmer.advancePixels(),
            "没戴冠也没描边的农民却按 10 行报宽：那一槽会比实际宽，居中把农民头像往右推");

        assertNotEquals(landlord.advancePixels(), farmer.advancePixels(),
            "地主与农民报出同样的宽度：说明 crowned 没被算进宽度，两者矩阵行数其实不同");
    }

    /**
     * 真人玩家【掉线】时必须画图标占位，不能留空。
     *
     * <p><b>守的是哪个 bug。</b>掉线与「皮肤还没下载好」都让 rendered 变成 null，但含义相反：
     * 掉线是持续状态，玩家回来之前那一槽一直没有头像，留空就会在 HUD 上留一个长期的洞，
     * 看着像 HUD 坏了。所以这两种 null 必须分开处理，不能因为「都拿不到皮肤」就一刀切。
     *
     * <p>这条和上面那条是一对：把 offline 这个入参去掉、两种情况又合并成一种时，
     * 必然有一条会红。
     */
    @Test
    void 真人掉线时画图标占位而不是留空槽() {
        TrickHudView.Avatar slot = TrickHudService.avatarSlotOf(
            human(PlayerRole.FARMER), SCALE, true, null, DOWN_TIER, true, false);

        assertFalse(slot.isEmpty(), "掉线期间槽位空了：那是持续状态，HUD 上会留一个长期的洞");
        assertTrue(slot.text().contains("<font:" + PackAssets.BOT_AVATAR_FONT + ">"),
            "占位图标没套自己的字体标签，客户端会显示成豆腐块");
        assertTrue(slot.text().contains(PackAssets.botAvatarChar(PlayerRole.FARMER, DOWN_TIER)),
            "占位用错了角色字形或偏移档，图标会和真人头像上下错开");
        assertEquals(PackAssets.botAvatarAdvanceWidth(PlayerRole.FARMER), slot.advancePixels(),
            "占位图标报出的宽度不是图标自己的宽度，槽内居中会偏");
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
                    bot(PlayerRole.LANDLORD), scale, outlined, rendered, DOWN_TIER, false, false);

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
        assertTrue(TrickHudService.avatarSlotOf(null, SCALE, true, null, DOWN_TIER, false, false).isEmpty(),
            "null 座位应当留空");
        assertTrue(TrickHudService.avatarSlotOf(
                TrickHudService.Seat.EMPTY, SCALE, true, "<font:x>y</font>", DOWN_TIER, false, false).isEmpty(),
            "没人的座位即使传进来头像文本也该留空：给不存在的人画头像是误导");
        // 空座位 + crowned=true：没人的座位不该画出一顶悬空的王冠。
        assertTrue(TrickHudService.avatarSlotOf(null, SCALE, false, null, DOWN_TIER, false, true).isEmpty(),
            "null 座位即使标成 crowned 也该留空：那个位置压根没人，画王冠是误导");

        // 空座位 + offline=true：没人的座位不该因为「offline」而画出占位图标。
        // offline 只对【真的有人但掉线了】的座位有意义。
        assertTrue(TrickHudService.avatarSlotOf(null, SCALE, true, null, DOWN_TIER, true, false).isEmpty(),
            "null 座位即使标成 offline 也该留空：那个位置压根没人，画图标是误导");
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
