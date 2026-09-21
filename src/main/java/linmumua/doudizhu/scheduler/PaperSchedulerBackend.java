package linmumua.doudizhu.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 基于 Paper threaded-region scheduler 的默认后端。
 *
 * <p>global 使用 GlobalRegionScheduler，世界坐标使用 RegionScheduler，实体与玩家使用
 * EntityScheduler，异步任务使用 AsyncScheduler。异步 API 使用毫秒，统一把 MUZ 的 tick
 * 转换为 50 毫秒；其它四类 API 直接使用 Paper tick 参数。
 */
public final class PaperSchedulerBackend implements SchedulerBackend {
    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final Server server;

    public PaperSchedulerBackend(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
    }

    @Override
    public MuzScheduler.TaskHandle runGlobal(
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(task, "task");
        ScheduledTask scheduled = period > 0
            ? server.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.accept(handle(ignored)), delay, period)
            : server.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.accept(handle(ignored)), delay);
        return handle(scheduled);
    }

    @Override
    public MuzScheduler.TaskHandle runRegion(
        Location location,
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(task, "task");
        ScheduledTask scheduled = period > 0
            ? server.getRegionScheduler().runAtFixedRate(plugin, location, ignored -> task.accept(handle(ignored)), delay, period)
            : server.getRegionScheduler().runDelayed(plugin, location, ignored -> task.accept(handle(ignored)), delay);
        return handle(scheduled);
    }

    @Override
    public MuzScheduler.TaskHandle runEntity(
        Entity entity,
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        ScheduledTask scheduled = period > 0
            ? entity.getScheduler().runAtFixedRate(plugin, ignored -> task.accept(handle(ignored)), () -> { }, delay, period)
            : entity.getScheduler().runDelayed(plugin, ignored -> task.accept(handle(ignored)), () -> { }, delay);
        return handle(scheduled);
    }

    @Override
    public MuzScheduler.TaskHandle runPlayer(
        Player player,
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        ScheduledTask scheduled = period > 0
            ? player.getScheduler().runAtFixedRate(plugin, ignored -> task.accept(handle(ignored)), () -> { }, delay, period)
            : player.getScheduler().runDelayed(plugin, ignored -> task.accept(handle(ignored)), () -> { }, delay);
        return handle(scheduled);
    }

    @Override
    public MuzScheduler.TaskHandle runAsync(
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(task, "task");
        long delayMillis = ticksToMillis(delay);
        ScheduledTask scheduled = period > 0
            ? server.getAsyncScheduler().runAtFixedRate(
                plugin,
                ignored -> task.accept(handle(ignored)),
                delayMillis,
                ticksToMillis(period),
                TimeUnit.MILLISECONDS
            )
            : server.getAsyncScheduler().runDelayed(
                plugin,
                ignored -> task.accept(handle(ignored)),
                delayMillis,
                TimeUnit.MILLISECONDS
            );
        return handle(scheduled);
    }

    private static MuzScheduler.TaskHandle handle(ScheduledTask task) {
        return new MuzScheduler.TaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }

    private static void validateSchedule(long delay, long period) {
        if (delay < 0 || period < 0) {
            throw new IllegalArgumentException(
                "调度 tick 不能为负数: delay=" + delay + ", period=" + period
            );
        }
        if (period == 0) {
            return;
        }
    }

    private static long ticksToMillis(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("调度 tick 不能为负数: " + ticks);
        }
        try {
            return Math.multiplyExact(ticks, MILLIS_PER_TICK);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("调度 tick 超出毫秒范围: " + ticks, exception);
        }
    }
}
