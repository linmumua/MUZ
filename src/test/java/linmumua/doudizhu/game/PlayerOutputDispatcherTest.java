package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import linmumua.doudizhu.scheduler.MuzScheduler;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.inventory.Inventory;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 玩家输出门面必须按 UUID 重新解析 Player，并委托到 player scheduler lane。 */
class PlayerOutputDispatcherTest {
    @Test
    void runPlayerUsesPlayerLaneAndResolvesCurrentPlayerAtExecution() {
        UUID playerId = UUID.randomUUID();
        Player first = player(true);
        Player current = player(true);
        AtomicReference<Player> resolved = new AtomicReference<>(first);
        CapturedTask captured = new CapturedTask();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> resolved.get(),
            (target, delay, task) -> {
                captured.target = target;
                captured.delay = delay;
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);
        AtomicReference<Player> received = new AtomicReference<>();

        MuzScheduler.TaskHandle handle = dispatcher.runPlayer(playerId, 7L, received::set);
        resolved.set(current);
        captured.fire();

        assertNotNull(handle);
        assertSame(first, captured.target);
        assertEquals(7L, captured.delay);
        assertSame(current, received.get());
        assertEquals(0, dispatcher.trackedTaskCount());
    }

    @Test
    void uuidFirstEnqueueDoesNotResolveUntilOwnerLaneRuns() {
        UUID playerId = UUID.randomUUID();
        AtomicBoolean inPlayerLane = new AtomicBoolean();
        AtomicInteger resolverCalls = new AtomicInteger();
        Player current = player(true);
        CapturedTask captured = new CapturedTask();
        PlayerTaskRegistry registry = PlayerTaskRegistry.uuidFirst(
            ignored -> {
                resolverCalls.incrementAndGet();
                assertTrue(inPlayerLane.get(), "UUID-first lookup 必须发生在 owner lane");
                return current;
            },
            (scheduledId, delay, task) -> {
                captured.playerId = scheduledId;
                captured.delay = delay;
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);
        AtomicReference<Player> received = new AtomicReference<>();

        MuzScheduler.TaskHandle handle = dispatcher.enqueuePlayer(playerId, 7L, received::set);

        assertNotNull(handle);
        assertEquals(0, resolverCalls.get(), "排队线程不得解析 Player");
        assertEquals(playerId, captured.playerId);
        assertEquals(7L, captured.delay);
        inPlayerLane.set(true);
        captured.fire();
        inPlayerLane.set(false);

        assertEquals(1, resolverCalls.get());
        assertSame(current, received.get());
        assertEquals(0, registry.trackedTaskCount());
    }

    @Test
    void uuidFirstEnqueueKeepsCancellationGenerationAndNoAutoReschedule() {
        UUID playerId = UUID.randomUUID();
        List<CapturedTask> scheduled = new ArrayList<>();
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicInteger actionCalls = new AtomicInteger();
        PlayerTaskRegistry registry = PlayerTaskRegistry.uuidFirst(
            ignored -> {
                resolverCalls.incrementAndGet();
                return player(true);
            },
            (scheduledId, delay, task) -> {
                CapturedTask captured = new CapturedTask();
                captured.playerId = scheduledId;
                captured.task = task;
                scheduled.add(captured);
                return captured;
            }
        );

        registry.enqueuePlayer(playerId, ignored -> actionCalls.incrementAndGet());
        registry.cancel(playerId);
        registry.enqueuePlayer(playerId, ignored -> actionCalls.incrementAndGet());
        scheduled.get(0).fire();
        scheduled.get(1).fire();

        assertEquals(1, resolverCalls.get());
        assertEquals(1, actionCalls.get(), "旧取消代次的迟到回调不得影响新任务");
        assertEquals(0, registry.trackedTaskCount());
    }

    @Test
    void reconnectedPlayerReceivesOutputWithoutCallingOldPlayer() {
        UUID playerId = UUID.randomUUID();
        CapturedTask captured = new CapturedTask();
        AtomicReference<Player> resolved = new AtomicReference<>();
        AtomicInteger oldPlayerCalls = new AtomicInteger();
        AtomicInteger currentPlayerCalls = new AtomicInteger();
        Player oldPlayer = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                oldPlayerCalls.incrementAndGet();
                return defaultValue(method.getReturnType(), method.getName());
            }
        );
        Player currentPlayer = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendMessage")) {
                    currentPlayerCalls.incrementAndGet();
                }
                return defaultValue(method.getReturnType(), method.getName());
            }
        );
        resolved.set(oldPlayer);
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> resolved.get(),
            (target, delay, task) -> {
                captured.target = target;
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);

        dispatcher.sendMessage(playerId, Component.text("重连后消息"));
        resolved.set(currentPlayer);
        captured.fire();

        assertSame(oldPlayer, captured.target);
        assertEquals(0, oldPlayerCalls.get(), "旧 Player 不得被调用任何 Bukkit API");
        assertEquals(1, currentPlayerCalls.get());
    }

    @Test
    void offlinePlayerDoesNotCreateTask() {
        UUID playerId = UUID.randomUUID();
        CapturedTask captured = new CapturedTask();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> null,
            (target, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );

        assertEquals(null, registry.runPlayer(playerId, ignored -> { }));
        assertEquals(0, registry.trackedTaskCount());
        assertEquals(null, captured.task);
    }

    @Test
    void cancelByUuidCancelsAllPendingTasksAndRejectsLateCallbacks() {
        UUID playerId = UUID.randomUUID();
        CapturedTask captured = new CapturedTask();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );
        AtomicReference<Player> received = new AtomicReference<>();

        MuzScheduler.TaskHandle handle = registry.runPlayer(playerId, received::set);
        registry.cancel(playerId);
        captured.fire();

        assertTrue(handle.isCancelled());
        assertTrue(captured.cancelled);
        assertEquals(null, received.get());
        assertEquals(0, registry.trackedTaskCount());
    }

    @Test
    void lookupAndCancelShareAtomicRegistrationBoundary() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<PlayerTaskRegistry> registryRef = new AtomicReference<>();
        AtomicBoolean cancelDuringLookup = new AtomicBoolean();
        CapturedTask captured = new CapturedTask();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> {
                if (cancelDuringLookup.compareAndSet(false, true)) {
                    registryRef.get().cancel(playerId);
                }
                return player(true);
            },
            (target, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );
        registryRef.set(registry);

        assertEquals(null, registry.runPlayer(playerId, ignored -> { }));
        assertEquals(0, registry.trackedTaskCount(), "取消发生在 lookup 内时不得晚于 register 生效");
        assertEquals(null, captured.task, "被取消的 lookup 不得触发 scheduler 投递");
    }

    @Test
    void cancelAllClosesAcceptanceAndRejectsNewTasks() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger schedulerCalls = new AtomicInteger();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> {
                schedulerCalls.incrementAndGet();
                return new CapturedTask();
            }
        );

        MuzScheduler.TaskHandle first = registry.runPlayer(playerId, ignored -> { });
        registry.cancelAll();
        MuzScheduler.TaskHandle second = registry.runPlayer(playerId, ignored -> { });

        assertNotNull(first);
        assertTrue(first.isCancelled());
        assertEquals(null, second, "cancelAll 后注册表必须拒绝新任务");
        assertEquals(1, schedulerCalls.get());
        assertEquals(0, registry.trackedTaskCount());
    }

    @Test
    void retiredPlayerSchedulerDoesNotAutomaticallyRescheduleAfterReconnect() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<Player> resolved = new AtomicReference<>(player(true));
        AtomicInteger schedulerCalls = new AtomicInteger();
        AtomicInteger actionCalls = new AtomicInteger();
        CapturedTask retiredTask = new CapturedTask();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> resolved.get(),
            (target, delay, task) -> {
                schedulerCalls.incrementAndGet();
                retiredTask.target = target;
                retiredTask.task = task;
                return retiredTask;
            }
        );

        registry.runPlayer(playerId, action -> actionCalls.incrementAndGet());
        resolved.set(player(true));

        assertEquals(1, schedulerCalls.get(), "重连不得为已退休的旧 scheduler 自动重排");
        assertEquals(0, actionCalls.get(), "旧 scheduler 丢弃任务时不应伪造执行");
    }

    @Test
    void cancelGenerationRejectsLateOldCallbackAfterNewTaskRegistration() {
        UUID playerId = UUID.randomUUID();
        List<CapturedTask> scheduled = new ArrayList<>();
        AtomicInteger actionCalls = new AtomicInteger();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> {
                CapturedTask captured = new CapturedTask();
                captured.task = task;
                scheduled.add(captured);
                return captured;
            }
        );

        registry.runPlayer(playerId, ignored -> actionCalls.incrementAndGet());
        registry.cancel(playerId);
        registry.runPlayer(playerId, ignored -> actionCalls.incrementAndGet());
        scheduled.get(0).task.run();
        scheduled.get(1).task.run();

        assertEquals(1, actionCalls.get(), "旧取消代次的迟到回调不得影响重连后的新任务");
    }

    @Test
    void dispatcherExposesOutputOperationsWithoutDirectPlayerLookup() {
        UUID playerId = UUID.randomUUID();
        CapturedTask captured = new CapturedTask();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);

        dispatcher.sendActionBar(playerId, null);
        assertNotNull(captured.task);
        captured.fire();
        assertFalse(captured.cancelled);
    }

    @Test
    void playSoundResolvesOnlinePlayerAndReadsLocationInsidePlayerLane() {
        UUID playerId = UUID.randomUUID();
        AtomicBoolean inPlayerLane = new AtomicBoolean();
        AtomicInteger resolverCallsInLane = new AtomicInteger();
        AtomicInteger onlineChecksInLane = new AtomicInteger();
        AtomicInteger locationReads = new AtomicInteger();
        AtomicInteger playCalls = new AtomicInteger();
        AtomicBoolean playCalledInsideLane = new AtomicBoolean();
        AtomicReference<String> playedKey = new AtomicReference<>();
        AtomicReference<Float> playedVolume = new AtomicReference<>();
        AtomicReference<Float> playedPitch = new AtomicReference<>();
        CapturedTask captured = new CapturedTask();
        Player target = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                if (method.getName().equals("isOnline")) {
                    if (inPlayerLane.get()) {
                        onlineChecksInLane.incrementAndGet();
                    }
                    return true;
                }
                if (method.getName().equals("getLocation")) {
                    if (inPlayerLane.get()) {
                        locationReads.incrementAndGet();
                    }
                    return null;
                }
                if (method.getName().equals("playSound")) {
                    playCalls.incrementAndGet();
                    playCalledInsideLane.set(inPlayerLane.get());
                    playedKey.set((String) args[1]);
                    playedVolume.set((Float) args[2]);
                    playedPitch.set((Float) args[3]);
                    return null;
                }
                return defaultValue(method.getReturnType(), method.getName());
            }
        );
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> {
                if (inPlayerLane.get()) {
                    resolverCallsInLane.incrementAndGet();
                }
                return target;
            },
            (scheduled, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);

        dispatcher.playSound(playerId, "muz:test", 0.35f, 1.25f);
        inPlayerLane.set(true);
        captured.fire();
        inPlayerLane.set(false);

        assertEquals(1, resolverCallsInLane.get());
        assertTrue(onlineChecksInLane.get() >= 1);
        assertEquals(1, locationReads.get());
        assertEquals(1, playCalls.get());
        assertTrue(playCalledInsideLane.get());
        assertEquals("muz:test", playedKey.get());
        assertEquals(0.35f, playedVolume.get());
        assertEquals(1.25f, playedPitch.get());
    }

    @Test
    void stopSoundCallsApiInsidePlayerLane() {
        UUID playerId = UUID.randomUUID();
        AtomicBoolean inPlayerLane = new AtomicBoolean();
        AtomicInteger onlineChecksInLane = new AtomicInteger();
        AtomicInteger stopCalls = new AtomicInteger();
        AtomicBoolean stopCalledInsideLane = new AtomicBoolean();
        CapturedTask captured = new CapturedTask();
        Player target = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                if (method.getName().equals("isOnline")) {
                    if (inPlayerLane.get()) {
                        onlineChecksInLane.incrementAndGet();
                    }
                    return true;
                }
                if (method.getName().equals("stopSound")) {
                    stopCalls.incrementAndGet();
                    stopCalledInsideLane.set(inPlayerLane.get());
                    return null;
                }
                return defaultValue(method.getReturnType(), method.getName());
            }
        );
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> target,
            (scheduled, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);

        dispatcher.stopSound(playerId, "muz:test");
        inPlayerLane.set(true);
        captured.fire();
        inPlayerLane.set(false);

        assertTrue(onlineChecksInLane.get() > 0);
        assertEquals(1, stopCalls.get());
        assertTrue(stopCalledInsideLane.get());
    }

    @Test
    void extendedPlayerApisCallBukkitOnlyInsidePlayerLane() {
        UUID playerId = UUID.randomUUID();
        AtomicBoolean inPlayerLane = new AtomicBoolean();
        List<String> calls = new ArrayList<>();
        CapturedTask captured = new CapturedTask();
        Player target = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("isOnline")) {
                    return true;
                }
                if (List.of(
                    "sendMessage", "sendActionBar", "showTitle", "showBossBar", "hideBossBar",
                    "openInventory", "closeInventory", "spawnParticle", "performCommand"
                ).contains(name)) {
                    assertTrue(inPlayerLane.get(), name + " 必须在 player lane 执行");
                    calls.add(name);
                }
                return defaultValue(method.getReturnType(), name);
            }
        );
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> target,
            (scheduled, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);
        Title title = Title.title(Component.text("标题"), Component.text("副标题"));
        BossBar bar = BossBar.bossBar(Component.text("进度"), 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        Inventory inventory = (Inventory) Proxy.newProxyInstance(
            Inventory.class.getClassLoader(),
            new Class<?>[]{Inventory.class},
            (proxy, method, args) -> defaultValue(method.getReturnType(), method.getName())
        );
        inPlayerLane.set(true);
        dispatcher.sendMessage(playerId, Component.text("消息"));
        captured.fire();
        dispatcher.sendActionBar(playerId, Component.text("动作栏"));
        captured.fire();
        dispatcher.showTitle(playerId, title);
        captured.fire();
        dispatcher.showBossBar(playerId, bar);
        captured.fire();
        dispatcher.hideBossBar(playerId, bar);
        captured.fire();
        dispatcher.openInventory(playerId, inventory);
        captured.fire();
        dispatcher.closeInventory(playerId);
        captured.fire();
        dispatcher.spawnParticle(playerId, Particle.CLOUD, new Location(null, 0, 0, 0), 1, 0, 0, 0, 0, null);
        captured.fire();
        dispatcher.performCommand(playerId, "help");
        captured.fire();
        inPlayerLane.set(false);

        assertEquals(List.of(
            "sendMessage", "sendActionBar", "showTitle", "showBossBar", "hideBossBar",
            "openInventory", "closeInventory", "spawnParticle", "performCommand"
        ), calls);
    }

    @Test
    void crossRegionProducerPublishesOnlyWhenPlayerLaneRuns() {
        UUID playerId = UUID.randomUUID();
        AtomicBoolean inPlayerLane = new AtomicBoolean();
        AtomicInteger messageCalls = new AtomicInteger();
        CapturedTask captured = new CapturedTask();
        Player target = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendMessage")) {
                    assertTrue(inPlayerLane.get(), "跨区域回调不得直接调用玩家 API");
                    messageCalls.incrementAndGet();
                }
                return defaultValue(method.getReturnType(), method.getName());
            }
        );
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> target,
            (scheduled, delay, task) -> {
                captured.task = task;
                return captured;
            }
        );
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry);

        Runnable simulatedRegionCallback = () -> dispatcher.sendMessage(playerId, Component.text("跨区域消息"));
        simulatedRegionCallback.run();

        assertNotNull(captured.task, "跨区域生产者必须先排入 player lane");
        assertEquals(0, messageCalls.get(), "排队阶段不得在 region 生产者线程调用玩家 API");
        inPlayerLane.set(true);
        captured.fire();
        inPlayerLane.set(false);

        assertEquals(1, messageCalls.get());
    }

    @Test
    void synchronousSchedulerCallbackStillRemovesRegistration() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> {
                task.run();
                return new CapturedTask();
            }
        );

        MuzScheduler.TaskHandle handle = registry.runPlayer(playerId, ignored -> calls.incrementAndGet());

        assertEquals(1, calls.get());
        assertNotNull(handle);
        assertEquals(0, registry.trackedTaskCount());
    }

    @Test
    void nullSchedulerHandleIsRejectedAndRemoved() {
        UUID playerId = UUID.randomUUID();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> null
        );

        MuzScheduler.TaskHandle handle = registry.runPlayer(playerId, ignored -> { });

        assertNotNull(handle);
        assertTrue(handle.isCancelled());
        assertEquals(0, registry.trackedTaskCount());
    }

    @Test
    void delegateTerminationPropagatesToRegistry() {
        UUID playerId = UUID.randomUUID();
        CapturedTask captured = new CapturedTask();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> captured
        );

        registry.runPlayer(playerId, ignored -> { });
        assertEquals(1, registry.trackedTaskCount());
        captured.terminate();
        assertEquals(0, registry.trackedTaskCount());
    }

    @Test
    void cancellationRacingBackendRegistrationLeavesNoLateRegistration() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch backendEntered = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        AtomicReference<CapturedTask> delegate = new AtomicReference<>();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> player(true),
            (target, delay, task) -> {
                backendEntered.countDown();
                await(releaseBackend);
                CapturedTask captured = new CapturedTask();
                captured.task = task;
                delegate.set(captured);
                return captured;
            }
        );

        Thread submitter = new Thread(() -> registry.runPlayer(playerId, ignored -> { }));
        submitter.start();
        assertTrue(backendEntered.await(1, TimeUnit.SECONDS));
        registry.cancel(playerId);
        releaseBackend.countDown();
        submitter.join(1000);

        assertFalse(submitter.isAlive());
        assertEquals(0, registry.trackedTaskCount());
        assertNotNull(delegate.get());
        assertTrue(delegate.get().cancelled);
    }

    private static Object defaultValue(Class<?> returnType, String methodName) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (methodName.equals("toString")) {
            return "test-player";
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Player player(boolean online) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, args) -> {
                if (method.getName().equals("isOnline")) {
                    return online;
                }
                if (method.getReturnType() == boolean.class) {
                    return false;
                }
                if (method.getReturnType() == byte.class) {
                    return (byte) 0;
                }
                if (method.getReturnType() == short.class) {
                    return (short) 0;
                }
                if (method.getReturnType() == int.class) {
                    return 0;
                }
                if (method.getReturnType() == long.class) {
                    return 0L;
                }
                if (method.getReturnType() == float.class) {
                    return 0.0f;
                }
                if (method.getReturnType() == double.class) {
                    return 0.0d;
                }
                if (method.getName().equals("toString")) {
                    return "test-player";
                }
                return null;
            }
        );
    }

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private UUID playerId;
        private Player target;
        private long delay;
        private Runnable task;
        private Runnable termination;
        private boolean cancelled;

        private void fire() {
            if (!cancelled && task != null) {
                task.run();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onTermination(Runnable listener) {
            termination = listener;
        }

        private void terminate() {
            if (termination != null) {
                termination.run();
            }
        }
    }
}
