package linmumua.doudizhu.game;

import org.bukkit.util.Vector;

/**
 * 椅侧面板的**纯几何**：把锚点（中心 / 三轴角度 / 世界宽高）变成世界右手系坐标轴，
 * 并把视线命中的局部坐标映射到 MuMenu 同款 3×3 九宫格里。
 *
 * <p>【为什么单独一个纯函数类】面板显示（{@code ItemDisplay} 摆放）、命中判定（射线求交后的格位）、
 * 以及 Dialog 改完几何后的重绘都必须用**同一份**坐标轴与同一份分区规则。任何一处自己再推导一次
 * yaw，都会出现「看到的格子」和「点到的格子」差一格这种只在实际游玩时才发现的错位。
 * 这里不引用 Bukkit 服务端对象，只依赖向量与标量，因此可以在没有服务端的测试里逐条验算。
 *
 * <p>【轴的定义（与 Minecraft 一致）】{@link ChairPanelAnchor#yawDegrees()} 是
 * **观察者看向面板时的 yaw**，因此面板法线就是观察者视线的反向：
 * {@code normal = -forward(yaw, pitch)}，{@code forward = (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)}。
 * 右手轴 {@code right = worldUp × normal}，上轴 {@code up = normal × right}，两者复合出的是
 * **行列式 +1 的右手正交基**——这正是「左右镜像」要求的：镜像翻转位置与朝向，但文字与图标保持正向，
 * 不能用负缩放或翻转 UV 充数。
 *
 * <p>【1.10.73：画布不再是「等分九宫格」】用户要求内置面板与 MuMenu {@code muz-chair-gadget.yml}
 * **同款**。那份菜单的画布是 300×200，九张卡片**不等距**：中心 x = -90 / 0 / 90，
 * y = 30 / -14 / -58，每张 80×36。等分网格（每格 100×66.7）与它差得很远，
 * 用等分网格会把卡片画到错误位置、命中区也会整体偏移。
 * 所以本类把「300×200 画布坐标」固化成唯一布局来源：
 * <ul>
 *   <li>{@link #CANVAS_WIDTH} / {@link #CANVAS_HEIGHT}：参考画布；</li>
 *   <li>{@link #SLOT_WIDTH} / {@link #SLOT_HEIGHT} 与 {@link #cardCenterX}/{@link #cardCenterY}：卡片矩形；</li>
 *   <li>画布 → 世界的映射是**按配置尺寸缩放**（x 用 {@code width/300}、y 用 {@code height/200}），
 *       默认 1.5×1.0 世界格即 0.005 格/像素——显示与命中共用这一套缩放。</li>
 * </ul>
 * 卡片之间的空隙**不属于任何一格**：命中返回 {@link #OUTSIDE}，绝不做「就近取一格」。
 *
 * <p>【九宫格】3 列 × 3 行共九格，索引 0..7 是八个真实道具槽，索引 8（右下角）是语音入口。
 * 用户界面上它是「一排排的格子」，内部一律 0-based，命令层的用户索引 1-based 由
 * {@code GadgetPanelService} 转换，不要在这里混用两套编号。
 */
public final class ChairPanelSurface {
    /** 列数。与八槽 + 语音入口的九格布局绑定，改动必须同步测试与文档。 */
    public static final int COLUMNS = 3;

    /** 行数。 */
    public static final int ROWS = 3;

    /** 总格数（含语音）。 */
    public static final int CELL_COUNT = COLUMNS * ROWS;

    /** 语音入口所在格（右下角）。 */
    public static final int VOICE_CELL = CELL_COUNT - 1;

    /** 落在面板矩形之外（含卡片之间的空隙）。 */
    public static final int OUTSIDE = -1;

    /**
     * 参考画布宽度（像素）。
     *
     * <p>与 MuMenu {@code muz-chair-gadget.yml} 的 {@code canvas.width} 同值。
     * 画布宽高比 3:2 必须与默认面板 1.5×1.0 世界格一致——不一致时缩放会在 x/y 上不同比例，
     * 卡片会被拉成长方形（本类按轴分别缩放，不会拒绝，但视觉上就不再是「同款」）。
     */
    public static final int CANVAS_WIDTH = 300;

    /** 参考画布高度（像素）。 */
    public static final int CANVAS_HEIGHT = 200;

    /** 单张槽位卡片在画布里的宽度（像素）。 */
    public static final int SLOT_WIDTH = 80;

    /** 单张槽位卡片在画布里的高度（像素）。 */
    public static final int SLOT_HEIGHT = 36;

    /** 三列卡片的中心 x（画布像素）：-90 / 0 / 90。 */
    private static final double[] CARD_CENTER_X = {-90.0, 0.0, 90.0};

    /** 三行卡片的中心 y（画布像素，向上为正）：30 / -14 / -58。 */
    private static final double[] CARD_CENTER_Y = {30.0, -14.0, -58.0};

    /** 标题基线 y（画布像素）。 */
    public static final double TITLE_Y = 82.0;

    /** 副标题（当前选中）基线 y（画布像素）。 */
    public static final double SUBTITLE_Y = 64.0;

    /** 底部提示基线 y（画布像素）。 */
    public static final double HINT_Y = -86.0;

    /** 卡片内「名称」行相对卡片中心的 y 偏移（画布像素）。 */
    public static final double SLOT_NAME_DY = 6.0;

    /** 卡片内「状态」行相对卡片中心的 y 偏移（画布像素）。 */
    public static final double SLOT_STATE_DY = -8.0;

    /** 边框厚度（画布像素）；与菜单的 {@code panel-border.thickness} 同值。 */
    public static final int BORDER_THICKNESS = 2;

    /**
     * 边框外框宽（画布像素）。
     *
     * <p>MuMenu 参考菜单写的是 {@code width: 298}（= 300 − 2），即边框比底板内缩 1 像素，
     * **不是**「底板外扩」。旧实现画成 304×204 的实心板并压在底板背后，等于把面板放大了 4 像素，
     * 边缘还会露在底板之外——与参考视觉直接不符。
     */
    public static final int BORDER_CANVAS_WIDTH = CANVAS_WIDTH - BORDER_THICKNESS;

    /** 边框外框高（画布像素）：{@code 198 = 200 − 2}。 */
    public static final int BORDER_CANVAS_HEIGHT = CANVAS_HEIGHT - BORDER_THICKNESS;

    /**
     * 卡片内图标方框边长（画布像素）。
     *
     * <p>【为什么图标要与文字分列】卡片只有 80×36，名称行在 {@link #SLOT_NAME_DY}（+6）、
     * 状态行在 {@link #SLOT_STATE_DY}（−8），两行字高约 11 / 8 像素，中间只剩约 4.5 像素的空档。
     * 把图标居中摆在卡片正中会同时压住两行文字（旧实现就是这样）。
     * 因此图标固定放卡片**左侧方框**，两行文字放在**右列**，两者在水平方向完全分开。
     */
    public static final double SLOT_ICON_SIZE = 22.0;

    /** 图标方框中心相对卡片中心的 x 偏移（画布像素，向左）。 */
    public static final double SLOT_ICON_DX = -27.0;

    /** 文字列中心相对卡片中心的 x 偏移（画布像素）。 */
    public static final double SLOT_TEXT_DX = 12.0;

    /** 文字列可用宽度（画布像素）。 */
    public static final double SLOT_TEXT_WIDTH = 52.0;

    private static final double EPSILON = 1.0e-9;

    private ChairPanelSurface() {
    }

    /**
     * 面板的一组世界坐标轴。
     *
     * @param right  面板自身向右（观察者视角）的单位向量
     * @param up     面板自身向上的单位向量
     * @param normal 面板正面法线（指向观察者）的单位向量
     */
    public record Axes(Vector right, Vector up, Vector normal) {
    }

    /**
     * 由锚点的三轴角度合成世界坐标轴。
     *
     * <p>{@code pitch} 会让面板前倾/后仰，{@code roll} 绕面板法线自转。三轴都**真实生效**：
     * 旧实现只能竖直（up 恒为 (0,1,0)），配置里的俯仰与翻滚无处可放；内置渲染不再有那个缺口。
     *
     * @throws IllegalArgumentException 任一角度非有限数
     */
    public static Axes axes(float yawDegrees, float pitchDegrees, float rollDegrees) {
        requireFinite(yawDegrees, "yaw");
        requireFinite(pitchDegrees, "pitch");
        requireFinite(rollDegrees, "roll");
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        // 法线 = -视线方向；视线方向按 Minecraft 约定：yaw 0 朝 +Z，pitch 为正表示向下看。
        Vector normal = new Vector(
            Math.sin(yaw) * Math.cos(pitch),
            Math.sin(pitch),
            -Math.cos(yaw) * Math.cos(pitch));
        if (normal.lengthSquared() < EPSILON) {
            throw new IllegalArgumentException("面板法线退化：yaw=" + yawDegrees + " pitch=" + pitchDegrees);
        }
        normal.normalize();
        Vector right = new Vector(0.0, 1.0, 0.0).crossProduct(normal);
        if (right.lengthSquared() < EPSILON) {
            // pitch 恰为 ±90°：世界 up 与法线平行，叉积退化。取任意稳定水平轴，避免面板整体塌成零向量。
            right = new Vector(1.0, 0.0, 0.0).crossProduct(normal);
        }
        right.normalize();
        Vector up = normal.clone().crossProduct(right).normalize();
        double roll = Math.toRadians(rollDegrees);
        if (Math.abs(roll) > EPSILON) {
            right = rotateAround(right, normal, roll);
            up = rotateAround(up, normal, roll);
        }
        return new Axes(right, up, normal);
    }

    // ------------------------------------------------------------------ 画布映射

    /** 画布 x 方向的世界缩放（格/像素）：{@code widthBlocks / 300}。 */
    public static double scaleX(double widthBlocks) {
        requirePositive(widthBlocks, "面板宽度");
        return widthBlocks / CANVAS_WIDTH;
    }

    /** 画布 y 方向的世界缩放（格/像素）：{@code heightBlocks / 200}。 */
    public static double scaleY(double heightBlocks) {
        requirePositive(heightBlocks, "面板高度");
        return heightBlocks / CANVAS_HEIGHT;
    }

    /**
     * 画布坐标 → 面板局部坐标（局部原点在面板中心，X 向右、Y 向上）。
     *
     * @return 长度为 2 的数组：{@code [localX, localY]}
     */
    public static double[] canvasToLocal(double canvasX, double canvasY,
                                        double widthBlocks, double heightBlocks) {
        if (!Double.isFinite(canvasX) || !Double.isFinite(canvasY)) {
            throw new IllegalArgumentException("画布坐标必须为有限数字");
        }
        return new double[] {canvasX * scaleX(widthBlocks), canvasY * scaleY(heightBlocks)};
    }

    /** 某一格的卡片中心在画布上的 x（像素）。 */
    public static double cardCenterX(int cell) {
        requireCell(cell);
        return CARD_CENTER_X[cell % COLUMNS];
    }

    /** 某一格的卡片中心在画布上的 y（像素，向上为正）。 */
    public static double cardCenterY(int cell) {
        requireCell(cell);
        return CARD_CENTER_Y[cell / COLUMNS];
    }

    /**
     * 某一格卡片中心的面板局部坐标（世界格）。
     *
     * @param cell   {@code 0..8}
     * @param width  面板世界宽度（格）
     * @param height 面板世界高度（格）
     * @return 长度为 2 的数组：{@code [localX, localY]}
     */
    public static double[] cellCenter(int cell, double width, double height) {
        return canvasToLocal(cardCenterX(cell), cardCenterY(cell), width, height);
    }

    /**
     * 单张卡片的世界尺寸。
     *
     * @return 长度为 2 的数组：{@code [width, height]}（世界格）
     */
    public static double[] cardSize(double width, double height) {
        return new double[] {
            SLOT_WIDTH * scaleX(width),
            SLOT_HEIGHT * scaleY(height)
        };
    }

    /**
     * 把面板局部坐标映射成格位索引。
     *
     * <p>【与 MuMenu 的命中矩形逐一重合】判定用的是九张 80×36 卡片本身，不是等分网格。
     * 落在所有卡片之外（包括卡片之间的 10 像素间隙、标题/副标题/底部提示区）返回 {@link #OUTSIDE}，
     * 调用方据此拒绝点击——不能默默就近取一格，否则玩家点空隙会选到旁边的道具。
     *
     * @param localX      面板局部 X（世界格，向右为正）
     * @param localY      面板局部 Y（世界格，向上为正）
     * @param widthBlocks 面板世界宽度（格）
     * @param heightBlocks 面板世界高度（格）
     * @return {@code 0..8}，或 {@link #OUTSIDE}
     */
    public static int cellAt(double localX, double localY, double widthBlocks, double heightBlocks) {
        if (!Double.isFinite(localX) || !Double.isFinite(localY)
            || !Double.isFinite(widthBlocks) || !Double.isFinite(heightBlocks)
            || widthBlocks <= 0.0 || heightBlocks <= 0.0) {
            return OUTSIDE;
        }
        double halfCardWidth = SLOT_WIDTH * scaleX(widthBlocks) * 0.5;
        double halfCardHeight = SLOT_HEIGHT * scaleY(heightBlocks) * 0.5;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            double[] center = cellCenter(cell, widthBlocks, heightBlocks);
            if (Math.abs(localX - center[0]) <= halfCardWidth + EPSILON
                && Math.abs(localY - center[1]) <= halfCardHeight + EPSILON) {
                return cell;
            }
        }
        return OUTSIDE;
    }

    /** 某一格的图标缩放：装在 {@link #SLOT_ICON_SIZE} 方框里，且与右列文字水平分开。 */
    public static float iconScale(double width, double height) {
        double[] size = cardSize(width, height);
        double iconBox = SLOT_ICON_SIZE * scaleY(height);
        // 图标不得超出方框，也不得吃掉右列文字：取方框与卡片可用宽度的较小者。
        double byBox = iconBox;
        double byCard = size[0] * 0.30;
        return (float) Math.max(0.02, Math.min(byBox, byCard));
    }

    /**
     * 卡片图标方框中心的面板局部坐标。
     *
     * @return 长度为 2 的数组：{@code [localX, localY]}
     */
    public static double[] iconCenter(int cell, double width, double height) {
        double[] card = cellCenter(cell, width, height);
        return new double[] {
            card[0] + SLOT_ICON_DX * scaleX(width),
            card[1]
        };
    }

    /**
     * 卡片图标方框的世界边长（格）。
     */
    public static double iconBoxSize(double width, double height) {
        return SLOT_ICON_SIZE * scaleY(height);
    }

    /**
     * 卡片文字列中心的面板局部 x（世界格）。
     *
     * <p>文字列与图标方框**水平不重叠**：图标右边缘 = {@code SLOT_ICON_DX + SLOT_ICON_SIZE/2}
     * = −16 像素，文字列左边缘 = {@code SLOT_TEXT_DX − SLOT_TEXT_WIDTH/2} = −14 像素，
     * 中间留 2 像素间隙。{@code ChairPanelSurfaceTest} 用这两个常量验算，改任一个都会变红。
     */
    public static double textColumnCenterX(int cell, double width) {
        return cellCenter(cell, width, 1.0)[0] + SLOT_TEXT_DX * scaleX(width);
    }

    /** 卡片文字列的世界宽度（格）。 */
    public static double textColumnWidth(double width) {
        return SLOT_TEXT_WIDTH * scaleX(width);
    }

    /**
     * 单空格 {@code TextDisplay} 背景矩形的 x 方向校准系数。
     *
     * <p>【来源】本机 MuMenu 的 {@code BukkitMenuRenderer.quad()} 用
     * {@code text(Component.space()) + setDefaultBackground(false) + backgroundColor(Color.fromARGB)}
     * 画任意 ARGB 矩形，其 {@code quadMatrix(w,h)} 为
     * {@code translate(-w/2,-h/2) · scale(w,h) · translate(0.4,0) · scale(8.08,3.66)}。
     * 三个常数即「单空格背景四边形的本机口径」：宽 {@code 1/8.08}、高 {@code 1/3.66}、
     * x 起点偏移 {@code -0.4/8.08}。MUZ 直接移植同一组常数，不引入 MuMenu 依赖。
     */
    public static final double QUAD_SCALE_X = 8.08;

    /** 单空格 {@code TextDisplay} 背景矩形的 y 方向校准系数；见 {@link #QUAD_SCALE_X}。 */
    public static final double QUAD_SCALE_Y = 3.66;

    /** 单空格背景矩形在 x 上的像素起点补偿（MuMenu 的 {@code translate(0.4,0,0)}）。 */
    public static final double QUAD_X_SHIFT_PIXELS = 0.4;

    /**
     * Interaction 判定盒的包围参数（面板被三轴旋转后的世界竖直投影）。
     *
     * @param anchorYOffset 判定盒**底边**相对面板中心的 Y 偏移（必须为负才会把下半面板包住）
     * @param coverWidth    判定盒在 X/Z 两个方向上的边长（Interaction 的盒子是方形截面）
     * @param coverHeight   判定盒的竖直高度，等于显示矩形竖直投影的 maxY − minY
     */
    public record InteractionBox(double anchorYOffset, double coverWidth, double coverHeight) {
    }

    /**
     * 算出让 Interaction 判定盒完整包住显示矩形所需的放置参数。
     *
     * <p>【为什么必须单独算】{@code Interaction} 的 AABB **从实体位置沿 +Y 向上生长**
     * （仓库既有契约，见 {@code PhysicalTableManager} 里「判定框是从底边往上长的」注释），
     * 而显示平面（{@code TextDisplay} 背景矩形 / {@code ItemDisplay} 图标 / 文字）是以实体位置
     * **居中**的。若把判定盒也放在面板中心并直接给它面板高度，实际覆盖区间是
     * {@code [centerY, centerY + h]}，而可视板是 {@code [centerY − h/2, centerY + h/2]}——
     * 下半块（第 2、3 行，含语音格）完全没有判定盒，玩家点不到。
     *
     * <p>所以这里取显示矩形**四个角**在轴向坐标下的位置：竖直方向取 {@code min/max} 定盒高，
     * 水平方向取 {@code max(|x|, |z|)} 的两倍作方截面边长（Interaction 只能用方形截面，
     * 旋转后必须覆盖最大横向投影），再把锚点放到 {@code minY}。
     * 命中精度不靠这个盒子——射线仍先与面板平面求交、再交给 {@link #cellAt} 逐格裁决；
     * 盒子只负责「让视线能打到板子」。
     */
    public static InteractionBox interactionBox(double widthBlocks, double heightBlocks,
                                               float yawDegrees, float pitchDegrees, float rollDegrees) {
        requirePositive(widthBlocks, "面板宽度");
        requirePositive(heightBlocks, "面板高度");
        Axes axes = axes(yawDegrees, pitchDegrees, rollDegrees);
        double halfWidth = widthBlocks * 0.5;
        double halfHeight = heightBlocks * 0.5;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxHorizontal = 0.0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                Vector corner = axes.right().clone().multiply(halfWidth * sx)
                    .add(axes.up().clone().multiply(halfHeight * sy));
                minY = Math.min(minY, corner.getY());
                maxY = Math.max(maxY, corner.getY());
                maxHorizontal = Math.max(maxHorizontal,
                    Math.max(Math.abs(corner.getX()), Math.abs(corner.getZ())));
            }
        }
        return new InteractionBox(minY, maxHorizontal * 2.0, maxY - minY);
    }

    /**
     * 把画布像素矩形换算成 {@code TextDisplay} 的 {@code Transformation}。
     *
     * <p>合成 {@code T(-w/2,-h/2)·S(w,h)·T(0.4,0)·S(8.08,3.66)} 后，等价于
     * {@code translation = (-0.1w, -0.5h)}、{@code scale = (8.08w, 3.66h)}（无 roll 时）。
     * 若配置了 roll，translation 必须**先绕面板法线转过去**再作为平移使用：
     * 最终顶点是 {@code T + R·S·p}，只有当 {@code T = R·T_base} 时矩形中心才仍在原点；
     * 否则一开 roll 整块板就会朝一侧甩出去。
     *
     * @return 长度为 2 的数组：{@code [tx, ty]}
     */
    public static double[] quadTranslation(double width, double height, double rollDegrees) {
        requirePositive(width, "矩形宽");
        requirePositive(height, "矩形高");
        if (!Double.isFinite(rollDegrees)) {
            throw new IllegalArgumentException("roll 必须为有限数字");
        }
        // T_base：-w/2 + 0.4w 的 x 偏移来自单空格背景的起点补偿。
        double baseX = -width * 0.5 + QUAD_X_SHIFT_PIXELS * width;
        double baseY = -height * 0.5;
        return rotateInPlane(baseX, baseY, rollDegrees);
    }

    /** 单空格背景矩形的世界缩放：{@code [8.08w, 3.66h]}。 */
    public static double[] quadScale(double width, double height) {
        requirePositive(width, "矩形宽");
        requirePositive(height, "矩形高");
        return new double[] {width * QUAD_SCALE_X, height * QUAD_SCALE_Y};
    }

    /** 把平面内向量按 roll（绕面板法线）旋转；roll=0 时原样返回。 */
    public static double[] rotateInPlane(double x, double y, double rollDegrees) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(rollDegrees)) {
            throw new IllegalArgumentException("平面旋转的分量必须为有限数字");
        }
        double radians = Math.toRadians(rollDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new double[] {x * cos - y * sin, x * sin + y * cos};
    }

    /**
     * 把显示实体（BlockDisplay）的 translation 从「方块模型原点在角上」换算成「模型中心落在实体位置」。
     *
     * <p>【为什么是 {@code -scale/2} 而不是常数 -0.5】Bukkit {@code Transformation} 的合成顺序是
     * {@code T · R_left · S · R_right}，即顶点先被 scale 缩放、再被 translation 平移：
     * 方块模型占 {@code [0,1]³}，缩放后占 {@code [0, scale]}，中心在 {@code scale/2}。
     * 要让中心落在实体原点，translation 必须是 {@code -scale/2}。
     * 写死 {@code -0.5} 只在 {@code scale == 1} 时成立：面板是 1.5×1.0×0.02，
     * x 会偏 +0.25 格、z 会偏 −0.49 格（这正是「底板错位」的真实成因）。
     *
     * @return 长度为 3 的数组：{@code [tx, ty, tz]}
     */
    public static double[] plateTranslation(double width, double height, double thickness) {
        requirePositive(width, "板宽");
        requirePositive(height, "板高");
        requirePositive(thickness, "板厚");
        return new double[] {-width * 0.5, -height * 0.5, -thickness * 0.5};
    }

    /**
     * 边框四条边的画布矩形：{@code [centerX, centerY, width, height]}（像素）。
     *
     * <p>参考菜单的 {@code frame} 是**描边**而不是实心板：外框 298×198、线宽 2 像素、
     * 画在底板**前层**（{@code z: 0.5}）。这里拆成四条细条，拼出的外沿正好是 298×198，
     * 与 300×200 底板之间各留 1 像素内缩。
     *
     * @return 长度为 4 的数组，顺序固定：上、下、左、右
     */
    public static double[][] borderStrips() {
        double halfW = BORDER_CANVAS_WIDTH * 0.5;
        double halfH = BORDER_CANVAS_HEIGHT * 0.5;
        double thickness = BORDER_THICKNESS;
        return new double[][] {
            {0.0, halfH - thickness * 0.5, BORDER_CANVAS_WIDTH, thickness},
            {0.0, -halfH + thickness * 0.5, BORDER_CANVAS_WIDTH, thickness},
            {-halfW + thickness * 0.5, 0.0, thickness, BORDER_CANVAS_HEIGHT},
            {halfW - thickness * 0.5, 0.0, thickness, BORDER_CANVAS_HEIGHT}
        };
    }

    private static void requireCell(int cell) {
        if (cell < 0 || cell >= CELL_COUNT) {
            throw new IllegalArgumentException("格位索引必须在 0.." + (CELL_COUNT - 1) + "，实际为 " + cell);
        }
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + "必须为正有限数，实际为 " + value);
        }
    }

    private static Vector rotateAround(Vector vector, Vector axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Vector k = axis.clone().normalize();
        return vector.clone().multiply(cos)
            .add(k.clone().crossProduct(vector).multiply(sin))
            .add(k.clone().multiply(k.dot(vector) * (1.0 - cos)));
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须为有限数字，实际为 " + value);
        }
    }
}
