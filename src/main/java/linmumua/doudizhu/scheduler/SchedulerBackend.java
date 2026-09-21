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
