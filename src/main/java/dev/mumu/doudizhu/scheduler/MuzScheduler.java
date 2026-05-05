package dev.mumu.doudizhu.scheduler;

import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class MuzScheduler {
    private final Plugin plugin;

    public MuzScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    public TaskHandle runSync(Runnable runnable) {
        return new BukkitTaskHandle(plugin.getServer().getScheduler().runTask(plugin, runnable));
    }

    public TaskHandle runLater(long delay, Runnable runnable) {
        return new BukkitTaskHandle(plugin.getServer().getScheduler().runTaskLater(plugin, runnable, delay));
    }

    public TaskHandle runTimer(long delay, long period, Runnable runnable) {
        return new BukkitTaskHandle(plugin.getServer().getScheduler().runTaskTimer(plugin, runnable, delay, period));
    }

    public TaskHandle runTimer(long delay, long period, Consumer<TaskHandle> consumer) {
        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                consumer.accept(this::cancel);
            }
        };
        return new BukkitTaskHandle(runnable.runTaskTimer(plugin, delay, period));
    }

    public TaskHandle runAsync(Runnable runnable) {
        return new BukkitTaskHandle(plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    public interface TaskHandle {
        void cancel();
    }

    private record BukkitTaskHandle(BukkitTask task) implements TaskHandle {
        @Override
        public void cancel() {
            task.cancel();
        }
    }
}
