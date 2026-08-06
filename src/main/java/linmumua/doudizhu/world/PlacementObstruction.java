package linmumua.doudizhu.world;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * 放桌阻挡检测结果，记录失败原因与具体被挡住的方块，供红色高亮复用。
 * 阻挡判定按方块真实碰撞箱进行，草、花、火把、告示牌、水等可穿过方块不算阻挡；
 * 岩浆、细雪、蜘蛛网、火焰虽然没有碰撞箱，但不适合摆牌桌，仍按阻挡处理。
 */
public final class PlacementObstruction {
    /* 面向玩家的失败原因 */
    private final String reason;

    /* 实际造成阻挡的方块整格坐标 */
    private final List<Location> blockedBlocks;

    private PlacementObstruction(String reason, List<Location> blockedBlocks) {
        this.reason = reason;
        this.blockedBlocks = List.copyOf(blockedBlocks);
    }

    /**
     * 返回面向玩家的失败原因
     * @return 失败原因文案
     */
    public String reason() {
        return reason;
    }

    /**
     * 返回造成阻挡的方块坐标
     * @return 被挡方块整格坐标的不可变列表
     */
    public List<Location> blockedBlocks() {
        return blockedBlocks;
    }

    /**
     * 构造只有失败原因、没有具体被挡方块的结果
     * @param reason 面向玩家的失败原因
     * @return 被挡方块为空列表的结果
     */
    public static PlacementObstruction ofReason(String reason) {
        return new PlacementObstruction(reason, List.of());
    }

    /**
     * 检测一个区域是否被真实碰撞方块挡住
     * @param label 区域名称，用于拼装提示文案
     * @param center 区域中心
     * @param radiusXz 水平半径
     * @param minYOffset 相对中心的最低高度
     * @param maxYOffset 相对中心的最高高度
     * @param surfaceY 支撑面世界 Y 坐标，扫描下界不会低于它；不需要钳位时传 Double.NEGATIVE_INFINITY
     * @return 无阻挡时返回 null，否则返回带原因与被挡方块的结果
     */
    public static PlacementObstruction detect(
        String label,
        Location center,
        double radiusXz,
        double minYOffset,
        double maxYOffset,
        double surfaceY
    ) {
        List<Location> blocked = collectBlockingBlocks(center, radiusXz, minYOffset, maxYOffset, surfaceY);
        if (blocked.isEmpty()) {
            return null;
        }
        return new PlacementObstruction(label + "位置被方块挡住了，先清空附近空间。", blocked);
    }

    /**
     * 收集区域内所有与之真实碰撞的方块
     * @param center 区域中心
     * @param radiusXz 水平半径
     * @param minYOffset 相对中心的最低高度
     * @param maxYOffset 相对中心的最高高度
     * @param surfaceY 支撑面世界 Y 坐标，扫描下界不会低于它；不需要钳位时传 Double.NEGATIVE_INFINITY
     * @return 被挡方块的整格坐标列表，无阻挡时为空列表
     */
    public static List<Location> collectBlockingBlocks(
        Location center,
        double radiusXz,
        double minYOffset,
        double maxYOffset,
        double surfaceY
    ) {
        List<Location> blocked = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return blocked;
        }
        World world = center.getWorld();
        BoundingBox area = scanArea(
            center.getX(),
            center.getY(),
            center.getZ(),
            radiusXz,
            clampedMinYOffset(center.getY(), minYOffset, surfaceY),
            maxYOffset
        );
        int minX = firstBlockIndex(area.getMinX());
        int maxX = lastBlockIndex(area.getMaxX());
        int minY = firstBlockIndex(area.getMinY());
        int maxY = lastBlockIndex(area.getMaxY());
        int minZ = firstBlockIndex(area.getMinZ());
        int maxZ = lastBlockIndex(area.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!blocksPlacement(block, area)) {
                        continue;
                    }
                    blocked.add(block.getLocation());
                }
            }
        }
        return blocked;
    }

    /**
     * 把扫描下界钳到支撑面，包级可见以便单元测试直接校验钳位契约。
     *
     * 桌椅是**放在**支撑方块上的，支撑面及其下方的空间属于地板自己，不是障碍物。
     * 原来下界固定为中心下方 0.10 格，桌椅贴地时这 0.10 格会伸进脚下的地板方块，
     * 于是玩家在平地上放桌反被自己站的地板判成阻挡。
     * @param centerY 检测区域中心的世界 Y 坐标
     * @param minYOffset 相对中心的最低高度
     * @param surfaceY 支撑面世界 Y 坐标；传 Double.NEGATIVE_INFINITY 表示不钳位
     * @return 钳位后的最低高度偏移，对应的世界坐标不会低于 surfaceY
     */
    static double clampedMinYOffset(double centerY, double minYOffset, double surfaceY) {
        return Math.max(minYOffset, surfaceY - centerY);
    }

    /**
     * 计算检测区域覆盖的首个方块下标，包级可见以便单元测试校验边界。
     * 与 lastBlockIndex 对称地留出 epsilon：下界正好落在整格边界（如支撑面 65.0）时，
     * 浮点误差可能把它算成 64.999999 而多扫下面一格，把支撑方块重新拖进阻挡判定。
     * @param min 区域某一轴的最小世界坐标
     * @return 需要扫描的首个方块下标
     */
    static int firstBlockIndex(double min) {
        return (int) Math.floor(min + 1.0E-7);
    }

    /**
     * 计算检测区域覆盖的末个方块下标，包级可见以便单元测试校验边界。
     * 区域上界正好落在整格边界时不再多扫一格，避免贴边方块被误判为阻挡。
     * @param max 区域某一轴的最大世界坐标
     * @return 需要扫描的末个方块下标
     */
    static int lastBlockIndex(double max) {
        return (int) Math.floor(max - 1.0E-7);
    }

    /**
     * 把世界坐标区域平移到方块局部坐标，供 VoxelShape 相交判定使用
     * @param area 世界坐标下的检测区域
     * @param blockX 目标方块 X
     * @param blockY 目标方块 Y
     * @param blockZ 目标方块 Z
     * @return 以目标方块为原点的局部坐标区域
     */
    static BoundingBox toBlockLocalArea(BoundingBox area, int blockX, int blockY, int blockZ) {
        return area.clone().shift(-blockX, -blockY, -blockZ);
    }

    /**
     * 计算检测区域的世界坐标包围盒，包级可见以便单元测试直接校验几何契约
     * @param centerX 中心 X
     * @param centerY 中心 Y
     * @param centerZ 中心 Z
     * @param radiusXz 水平半径
     * @param minYOffset 相对中心的最低高度
     * @param maxYOffset 相对中心的最高高度
     * @return 检测区域包围盒
     */
    static BoundingBox scanArea(
        double centerX,
        double centerY,
        double centerZ,
        double radiusXz,
        double minYOffset,
        double maxYOffset
    ) {
        return new BoundingBox(
            centerX - radiusXz,
            centerY + minYOffset,
            centerZ - radiusXz,
            centerX + radiusXz,
            centerY + maxYOffset,
            centerZ + radiusXz
        );
    }

    /**
     * 判断方块是否属于不适合放桌的危险方块。
     * 这些方块没有碰撞箱，玩家可以穿过，但把牌桌放进去并不合理。
     * @param block 待判断方块
     * @return 属于危险方块时返回 true
     */
    private static boolean isHazardBlock(Block block) {
        return isHazardMaterial(block.getType());
    }

    /**
     * 判断方块类型是否属于不适合放桌的危险方块，包级可见以便单元测试直接校验清单
     * @param material 方块类型
     * @return 属于危险方块时返回 true
     */
    static boolean isHazardMaterial(Material material) {
        return switch (material) {
            case LAVA, POWDER_SNOW, COBWEB, FIRE, SOUL_FIRE -> true;
            default -> false;
        };
    }

    /**
     * 判断单个方块是否真实阻挡给定区域
     * @param block 待判断方块
     * @param area 世界坐标下的检测区域
     * @return 真实碰撞或属于危险方块时返回 true
     */
    private static boolean blocksPlacement(Block block, BoundingBox area) {
        if (isHazardBlock(block)) {
            return true;
        }
        if (block.isPassable()) {
            return false;
        }
        return block.getCollisionShape()
            .overlaps(toBlockLocalArea(area, block.getX(), block.getY(), block.getZ()));
    }
}
