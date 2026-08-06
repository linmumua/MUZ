package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static boolean containsColor(int[][] matrix, int argb) {
        for (int[] row : matrix) {
            for (int pixel : row) {
                if (pixel == argb) {
                    return true;
                }
            }
        }
        return false;
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
     * 戴冠【不许改变矩阵尺寸】—— 这是三个头像能对齐的前提。
     *
     * <p><b>守的是哪个 bug。</b>第一版王冠是往矩阵上面加两行做的（8x8 变 10x10），结果戴冠的
     * 地主脸被挤低两像素、那一槽也宽一截，三个头像并排时一眼看出没对齐 —— 服主截图报的就是这个。
     *
     * <p>尺寸一致还连带保证了另外两件事：宽度不用为地主分叉（{@code advanceWidth} 只看描边），
     * 行数不会超出构建期预生成的字形范围（{@code avatarPixelChar} 只接受 {@code row < 10}）。
     */
    @Test
    void 戴冠不改变矩阵尺寸() {
        int size = PackAssets.AVATAR_HEAD_PIXELS;
        int[][] crowned = PlayerHeadRenderer.withCrown(solidFace(0xFF808080));

        assertEquals(size, crowned.length,
            "戴冠后行数变了：地主的脸会比农民高低错开，三个头像并排就是没对齐");
        for (int row = 0; row < crowned.length; row++) {
            assertEquals(size, crowned[row].length,
                "第 " + row + " 行列数变了：renderMiniMessage 用同一个数当行列数，会截掉右侧列");
        }
    }

    /**
     * 王冠只许盖头顶那两行，下面的五官一个像素都不许动。
     *
     * <p><b>守的是哪个 bug。</b>王冠现在是直接盖在头像上的，盖的行数写多了就会吃掉眼睛 ——
     * 那等于用身份标识毁掉了头像本身。头顶两行本来就是头发，被王冠压住是对的。
     */
    @Test
    void 王冠只盖头顶两行不动五官() {
        int faceArgb = 0xFF3366CC;
        int[][] crowned = PlayerHeadRenderer.withCrown(solidFace(faceArgb));

        // 第 2 行往下是五官区，必须原样保留。
        for (int row = 2; row < crowned.length; row++) {
            for (int col = 0; col < crowned[row].length; col++) {
                assertEquals(faceArgb, crowned[row][col],
                    "(" + row + "," + col + ") 被王冠改写了：盖到五官上，头像会被毁掉");
            }
        }
    }

    /**
     * 王冠不改入参：调用方那份原始头像必须保持干净。
     *
     * <p><b>守的是哪个 bug。</b>渲染层同一张皮肤要出戴冠和不戴冠两个版本（地主和农民常用同一张
     * 皮肤）。如果 {@code withCrown} 就地改了传进来的矩阵，先渲染的那次会把王冠【焊死】在
     * 那份像素上，后面不戴冠的版本也会带着王冠 —— 农民头上莫名多顶王冠，而且极难查。
     */
    @Test
    void 戴冠不许改动传进来的矩阵() {
        int faceArgb = 0xFF3366CC;
        int[][] face = solidFace(faceArgb);
        PlayerHeadRenderer.withCrown(face);

        for (int row = 0; row < face.length; row++) {
            for (int col = 0; col < face[row].length; col++) {
                assertEquals(faceArgb, face[row][col],
                    "入参 (" + row + "," + col + ") 被改了：同一张皮肤的不戴冠版本会跟着长出王冠");
            }
        }
    }

    /**
     * 王冠图案必须左右对称。
     *
     * <p><b>守的是哪个 bug。</b>图案是手写的字符串，很容易两边空格数写不一样（第一版就是
     * {@code "#  #  # "}，右边多一列空格）。头像是并排摆的，王冠偏一边一眼就看出来。
     */
    @Test
    void 王冠图案左右对称() {
        int[][] crowned = PlayerHeadRenderer.withCrown(solidFace(0));

        for (int row = 0; row < 2; row++) {
            int[] pixels = crowned[row];
            for (int col = 0; col < pixels.length / 2; col++) {
                int mirror = pixels.length - 1 - col;
                assertEquals(pixels[col] >>> 24 != 0, pixels[mirror] >>> 24 != 0,
                    "第 " + row + " 行第 " + col + " 与第 " + mirror + " 列不对称：王冠会看着偏向一边");
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
        // 用全透明的空头像：这样最上两行的不透明像素只可能来自王冠本身。
        int[][] crowned = PlayerHeadRenderer.withCrown(solidFace(0));

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
     * 戴冠后的像素必须和原头像【不同】。
     *
     * <p><b>守的是哪个 bug。</b>缓存 key 里带了 {@code crowned}，而地主和农民很可能用同一张皮肤
     * （同一个 URL）。如果 {@code withCrown} 实际没改变任何像素（比如王冠色误写成全透明），
     * 缓存倒是分开了，画出来却一模一样 —— 王冠静默消失，且因为缓存分开了更难查。
     */
    @Test
    void 戴冠后的像素必须与原头像不同() {
        int[][] face = solidFace(0xFF808080);
        int[][] crowned = PlayerHeadRenderer.withCrown(face);

        boolean differs = false;
        for (int row = 0; row < face.length && !differs; row++) {
            for (int col = 0; col < face[row].length; col++) {
                if (face[row][col] != crowned[row][col]) {
                    differs = true;
                    break;
                }
            }
        }
        assertTrue(differs, "戴冠后像素与原头像完全一致：王冠没画出来，但缓存已经分成两条，很难查");
    }

    /**
     * 王冠可以和描边【同时】开，且描边会把王冠一起勾出来。
     *
     * <p><b>守的是哪个 bug。</b>第一版王冠占的是描边那两行，两者互斥、只能二选一。现在王冠
     * 盖在头顶不改尺寸，就该能叠加。顺序也有讲究：先戴冠再描边，描边才会沿着王冠的轮廓走；
     * 反过来先描边，王冠自己没有边、看着像贴上去的。
     */
    @Test
    void 王冠可以与描边叠加() {
        int[][] face = solidFace(0xFF808080);
        int outline = 0xFF000000;

        // 渲染层的顺序：先戴冠，再描边。
        int[][] both = PlayerHeadRenderer.withOutline(PlayerHeadRenderer.withCrown(face), outline);
        int[][] onlyOutlined = PlayerHeadRenderer.withOutline(face, outline);

        assertEquals(PackAssets.AVATAR_OUTLINED_PIXELS, both.length,
            "戴冠+描边后行数不是 10：超出预生成的字形范围，会渲染成豆腐块");
        assertEquals(onlyOutlined.length, both.length,
            "戴冠+描边与只描边的行数不同：地主那一槽会和农民高低错开");
        // 【不能比不透明像素数】：脸本来就是不透明的，王冠盖上去只改颜色、不改不透明像素数，
        // 两边计数会一样。要查的是王冠那个金色到底还在不在。
        assertTrue(containsColor(both, 0xFFFFD24A),
            "戴冠+描边后找不到王冠的金色：王冠在描边路径上被吞掉了");
        assertFalse(containsColor(onlyOutlined, 0xFFFFD24A),
            "只描边却出现了王冠金色：说明王冠被无条件画上了，农民也会戴冠");
    }
}
