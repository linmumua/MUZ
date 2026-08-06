package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 地主王冠的守护测试。
 *
 * <p>王冠是【地主身份标识】，不是装饰：玩家靠它一眼认出谁是地主。所以这里守的不是「画得好不好看」，
 * 而是三件会让它失效或让 HUD 歪掉的事：
 * <ol>
 *   <li>王冠占的是描边那两行，所以矩阵必须仍是 10x10 方阵 —— 变成 12x12 就超出预生成的字形范围，
 *       渲染出来是豆腐块；非方阵会让右侧列被截掉；</li>
 *   <li>脸不能被王冠盖掉 —— 王冠压在脸上等于毁了头像；</li>
 *   <li>王冠确实画出来了、而且是金色 —— 空实现也能让上面两条过。</li>
 * </ol>
 */
class PlayerHeadCrownTest {

    /** 造一张纯色的假脸，每个像素都不透明，便于检查「脸有没有被盖掉」。 */
    private static int[][] solidFace(int argb) {
        int size = PackAssets.AVATAR_HEAD_PIXELS;
        int[][] face = new int[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                face[row][col] = argb;
            }
        }
        return face;
    }

    private static int opaqueCount(int[][] matrix) {
        int count = 0;
        for (int[] row : matrix) {
            for (int pixel : row) {
                if ((pixel >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 戴冠后必须仍是 {@link PackAssets#AVATAR_OUTLINED_PIXELS} 边长的方阵。
     *
     * <p><b>守的是哪个 bug。</b>像素头像的字形是构建期按 10 行预生成的，{@code avatarPixelChar}
     * 只接受 {@code row < 10}。如果有人为了「让王冠不占描边的位置」把矩阵撑到 12 行，
     * 第 11、12 行就没有对应字形，玩家看到的是豆腐块。
     *
     * <p>方阵这一条同样是硬要求：{@code renderMiniMessage} 用 {@code head.length} 同时当行数和
     * 列数，非方阵会让右侧的列被静默截掉 —— 表现是王冠或脸缺一条边。
     */
    @Test
    void 戴冠后仍是10x10方阵() {
        int[][] crowned = PlayerHeadRenderer.withCrown(solidFace(0xFF808080));

        assertEquals(PackAssets.AVATAR_OUTLINED_PIXELS, crowned.length,
            "戴冠后行数不是 10：超出预生成的字形范围，渲染出来会是豆腐块");
        for (int row = 0; row < crowned.length; row++) {
            assertEquals(crowned.length, crowned[row].length,
                "第 " + row + " 行列数与行数不等：renderMiniMessage 用同一个数当行列数，非方阵会截掉右侧列");
        }
    }

    /**
     * 王冠不许盖住脸：8x8 的脸必须一个像素都不少。
     *
     * <p><b>守的是哪个 bug。</b>王冠画在最上两行、脸整体下移两行。如果偏移写错（比如脸没下移，
     * 或王冠画到了第 2、3 行），王冠就会压在额头上 —— 那等于用身份标识毁掉了头像本身。
     */
    @Test
    void 王冠不许盖住脸的任何像素() {
        int faceArgb = 0xFF3366CC;
        int[][] face = solidFace(faceArgb);
        int[][] crowned = PlayerHeadRenderer.withCrown(face);

        int size = PackAssets.AVATAR_HEAD_PIXELS;
        int faceOffset = (crowned.length - size) / 2;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                assertEquals(faceArgb, crowned[row + 2][col + faceOffset],
                    "脸上 (" + row + "," + col + ") 被改写了：王冠压在脸上，头像会被毁掉");
            }
        }
    }

    /**
     * 王冠必须真的画出来，且是「山」字形的金色图案。
     *
     * <p><b>守的是哪个 bug。</b>上面两条测的都是「没破坏什么」，一个直接 return 原矩阵、
     * 什么都不画的空实现照样能过。这条钉住王冠确实存在：
     * 最上一行是三个冠尖（不连续），第二行是整条冠带（连续 8 列），且用的是金色。
     *
     * <p>不逐像素死钉具体图案：那样改冠形就得改测试，测试会变成实现的复印件。
     * 钉的是「山字形」这个结构特征 —— 尖比带少、带是满的。
     */
    @Test
    void 王冠是山字形的金色图案() {
        int[][] crowned = PlayerHeadRenderer.withCrown(solidFace(0xFF808080));

        int prongs = opaqueCount(new int[][] {crowned[0]});
        int band = opaqueCount(new int[][] {crowned[1]});

        assertTrue(prongs > 0, "最上一行没有任何不透明像素：王冠压根没画出来");
        assertEquals(PackAssets.AVATAR_HEAD_PIXELS, band,
            "第二行不是一条完整的冠带：山字形的底横必须是连续的，否则看着像三根断掉的刺");
        assertTrue(prongs < band,
            "冠尖数量不少于冠带宽度：那就不是山字形而是一个实心方块（尖=" + prongs + "，带=" + band + "）");

        // 金色：与既有地主金边同色。冠尖和冠带必须同色，不然像两截东西拼起来的。
        int gold = 0xFFFFD24A;
        for (int col = 0; col < crowned[1].length; col++) {
            if ((crowned[1][col] >>> 24) != 0) {
                assertEquals(gold, crowned[1][col],
                    "冠带第 " + col + " 列不是金色：王冠要和地主金边同色才认得出是同一套视觉");
            }
        }
    }

    /**
     * 戴冠与不戴冠必须渲染成【不同】的字形串。
     *
     * <p><b>守的是哪个 bug。</b>缓存 key 里带了 {@code crowned}，而地主和农民很可能用同一张皮肤
     * （同一个 URL）。如果 {@code withCrown} 实际没改变任何像素（比如王冠色误写成全透明），
     * 缓存倒是分开了，画出来却一模一样 —— 王冠静默消失，且因为缓存分开了更难查。
     */
    @Test
    void 戴冠与不戴冠的矩阵必须不同() {
        int[][] face = solidFace(0xFF808080);
        int[][] crowned = PlayerHeadRenderer.withCrown(face);
        int[][] outlined = PlayerHeadRenderer.withOutline(face, 0xFFFFD24A);

        assertEquals(outlined.length, crowned.length,
            "戴冠与描边的矩阵边长必须一样：两者互斥地占同样那两行，宽度也就该一致");
        assertNotEquals(opaqueCount(outlined), opaqueCount(crowned),
            "戴冠与描边的不透明像素数完全相同：很可能 withCrown 实际走的是描边逻辑，王冠没画出来");
    }
}
