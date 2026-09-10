package linmumua.doudizhu.debug;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Debug Web 资源任务的进程内共享租约。
 *
 * <p>租约按插件数据目录寻址，而不是按 DebugWebServer 实例寻址。这样服务器重载重新创建
 * WebServer 时，旧协调器尚未结束的 CraftEngine Future 仍会占住同一份租约，新实例不能与
 * 它重叠写盘或重载。租约只描述占用与代次，不接触 Bukkit、CraftEngine 或文件系统。
 */
final class HudWebApplyLease {
    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<String, HudWebApplyLease> REGISTRY = new HashMap<>();

    static HudWebApplyLease forKey(String key) {
        Objects.requireNonNull(key, "key");
        synchronized (REGISTRY_LOCK) {
            return REGISTRY.computeIfAbsent(key, ignored -> new HudWebApplyLease());
        }
    }

    /** 仅供测试使用：测试使用唯一 key 时不需要调用；用于避免测试进程残留状态。 */
    static void clearForTests() {
        synchronized (REGISTRY_LOCK) {
            REGISTRY.clear();
        }
    }

    private long generation;
    private Lease current;

    private HudWebApplyLease() {
    }

    synchronized Lease tryAcquire() {
        if (current != null) {
            return null;
        }
        Lease lease = new Lease(this, ++generation);
        current = lease;
        return lease;
    }

    synchronized boolean isBusy() {
        return current != null;
    }

    synchronized boolean isCurrent(Lease lease) {
        return current == lease && !lease.abandoned;
    }

    synchronized void abandon(Lease lease) {
        if (current == lease) {
            lease.abandoned = true;
        }
    }

    synchronized void release(Lease lease) {
        if (current == lease) {
            current = null;
        }
    }

    static final class Lease {
        private final HudWebApplyLease owner;
        private final long generation;
        private boolean abandoned;

        private Lease(HudWebApplyLease owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }

        long generation() {
            return generation;
        }

        boolean isAbandoned() {
            synchronized (owner) {
                return abandoned;
            }
        }
    }
}
