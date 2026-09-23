package linmumua.doudizhu.scheduler;

import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * MUZ 调度后端的最小注入边界。
 *
 * <p>所有 delay/period 都使用服务器 tick。period 为 0 表示一次性任务，
 * 正数表示重复任务；实现必须保留 TaskHandle 的取消语义。
 */
public interface SchedulerBackend {
    /**
     * 返回该后端所属的 owner 标识。后端未提供标识时由 MuzScheduler 使用实现类名称兜底。
     */
    default String ownerId() {
        return getClass().getName();
    }

    /**
     * 关闭后端并取消后端仍持有的任务。默认实现保持旧测试后端的兼容性。
     */
    default void close() {
    }

    MuzScheduler.TaskHandle runGlobal(
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    );

    MuzScheduler.TaskHandle runRegion(
        Location location,
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    );

    MuzScheduler.TaskHandle runEntity(
        Entity entity,
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    );

    MuzScheduler.TaskHandle runPlayer(
        Player player,
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    );

    MuzScheduler.TaskHandle runAsync(
        long delay,
        long period,
        Consumer<MuzScheduler.TaskHandle> task
    );
}
