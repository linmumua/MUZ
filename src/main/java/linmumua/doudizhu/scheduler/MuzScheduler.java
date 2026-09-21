package linmumua.doudizhu.scheduler;

import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * MUZ 的调度门面。
 *
 * <p>第一阶段把调度后端从业务调用点抽出，并明确区分 global、region、entity、player
 * 与 async。默认仍使用 Paper 后端；测试或后续区域线程适配可以注入其它
 * {@link SchedulerBackend}，而无需修改业务代码。
 */
public final class MuzScheduler {
    private final SchedulerBackend backend;

    public MuzScheduler(Plugin plugin) {
        this(new PaperSchedulerBackend(plugin));
    }

    public MuzScheduler(SchedulerBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** 在 Paper global scheduler 上立即执行。 */
    public TaskHandle runGlobal(Runnable runnable) {
        return runGlobal(0L, runnable);
    }

    /** 在 Paper global scheduler 上延迟执行。 */
    public TaskHandle runGlobal(long delay, Runnable runnable) {
        return backend.runGlobal(delay, 0L, ignored -> runnable.run());
    }

    /** 在 Paper global scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runGlobalLater(long delay, Runnable runnable) {
        return runGlobal(delay, runnable);
    }

    /** 在 Paper global scheduler 上周期执行。 */
    public TaskHandle runGlobalTimer(long delay, long period, Runnable runnable) {
        return backend.runGlobal(delay, period, ignored -> runnable.run());
    }

    /** 在 Paper global scheduler 上周期执行，并暴露当前任务的取消句柄。 */
    public TaskHandle runGlobalTimer(long delay, long period, Consumer<TaskHandle> consumer) {
        return backend.runGlobal(delay, period, consumer);
    }

    /** 在指定世界区块所属 region 上立即执行。 */
    public TaskHandle runRegion(Location location, Runnable runnable) {
        return runRegion(location, 0L, runnable);
    }

    /** 在指定世界区块所属 region 上延迟执行。 */
    public TaskHandle runRegion(Location location, long delay, Runnable runnable) {
        return backend.runRegion(location, delay, 0L, ignored -> runnable.run());
    }

    /** 在指定世界区块所属 region 上延迟执行，显式命名入口。 */
    public TaskHandle runRegionLater(Location location, long delay, Runnable runnable) {
        return runRegion(location, delay, runnable);
    }

    /** 在指定世界区块所属 region 上周期执行。 */
    public TaskHandle runRegionTimer(Location location, long delay, long period, Runnable runnable) {
        return backend.runRegion(location, delay, period, ignored -> runnable.run());
    }

    /** 在指定实体所属 entity scheduler 上立即执行。 */
    public TaskHandle runEntity(Entity entity, Runnable runnable) {
        return runEntity(entity, 0L, runnable);
    }

    /** 在指定实体所属 entity scheduler 上延迟执行。 */
    public TaskHandle runEntity(Entity entity, long delay, Runnable runnable) {
        return backend.runEntity(entity, delay, 0L, ignored -> runnable.run());
    }

    /** 在指定实体所属 entity scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runEntityLater(Entity entity, long delay, Runnable runnable) {
        return runEntity(entity, delay, runnable);
    }

    /** 在指定实体所属 entity scheduler 上周期执行。 */
    public TaskHandle runEntityTimer(Entity entity, long delay, long period, Runnable runnable) {
        return backend.runEntity(entity, delay, period, ignored -> runnable.run());
    }

    /** 在指定玩家所属 entity scheduler 上立即执行。 */
    public TaskHandle runPlayer(Player player, Runnable runnable) {
        return runPlayer(player, 0L, runnable);
    }

    /** 在指定玩家所属 entity scheduler 上延迟执行。 */
    public TaskHandle runPlayer(Player player, long delay, Runnable runnable) {
        return backend.runPlayer(player, delay, 0L, ignored -> runnable.run());
    }

    /** 在指定玩家所属 entity scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runPlayerLater(Player player, long delay, Runnable runnable) {
        return runPlayer(player, delay, runnable);
    }

    /** 在指定玩家所属 entity scheduler 上周期执行。 */
    public TaskHandle runPlayerTimer(Player player, long delay, long period, Runnable runnable) {
        return backend.runPlayer(player, delay, period, ignored -> runnable.run());
    }

    /** 在 Paper async scheduler 上立即执行。 */
    public TaskHandle runAsync(Runnable runnable) {
        return runAsync(0L, runnable);
    }

    /** 在 Paper async scheduler 上延迟执行。 */
    public TaskHandle runAsync(long delay, Runnable runnable) {
        return backend.runAsync(delay, 0L, ignored -> runnable.run());
    }

    /** 在 Paper async scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runAsyncLater(long delay, Runnable runnable) {
        return runAsync(delay, runnable);
    }

    /** 在 Paper async scheduler 上周期执行。 */
    public TaskHandle runAsyncTimer(long delay, long period, Runnable runnable) {
        return backend.runAsync(delay, period, ignored -> runnable.run());
    }

    /**
     * 兼容旧调用：原 runSync 语义对应 global scheduler，而不是 region/entity scheduler。
     */
    public TaskHandle runSync(Runnable runnable) {
        return runGlobal(runnable);
    }

    /** 兼容旧调用：原 runLater 语义对应 global scheduler。 */
    public TaskHandle runLater(long delay, Runnable runnable) {
        return runGlobal(delay, runnable);
    }

    /** 兼容旧调用：原 runTimer 语义对应 global scheduler。 */
    public TaskHandle runTimer(long delay, long period, Runnable runnable) {
        return runGlobalTimer(delay, period, runnable);
    }

    /** 兼容旧调用：原 runTimer 的自取消回调仍使用同一 TaskHandle。 */
    public TaskHandle runTimer(long delay, long period, Consumer<TaskHandle> consumer) {
        return runGlobalTimer(delay, period, consumer);
    }

    public interface TaskHandle {
        void cancel();

        /** 返回任务是否已经取消；测试后端可按自身生命周期实现。 */
        default boolean isCancelled() {
            return false;
        }
    }
}
