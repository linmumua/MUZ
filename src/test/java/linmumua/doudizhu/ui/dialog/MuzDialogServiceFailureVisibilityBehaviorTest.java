package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.game.PlayerTaskRegistry;
import linmumua.doudizhu.scheduler.MuzScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 「面板打不开」的失败可见性回归：任何失败都必须同时满足三件事——玩家看得见、服务端留痕
 * （SEVERE，不受 {@code debug.enabled} 门禁影响）、异常不逃到调度器。
 *
 * <p>【为什么能这样测】Paper 的 Dialog 构建依赖服务端注册的 {@code DialogInstancesProvider}，
 * 纯 JVM 里造不出真实 Dialog。这里的屏故意在 {@code build} 里抛异常，正好覆盖「构建面板失败」
 * 这条以前只发 WARNING（出厂配置下被 {@code MuzLogGate} 全部静默）的出口；展示动作则经
 * {@code MuzDialogService} 的可注入接缝，不经过真实 {@code player.showDialog}。
 *
 * <p>【这些断言能失败吗】把失败分支改回静默 {@code return} 或 {@code warning}、或把关闭期判据
 * 挪回 lane 内（变成照样投递玩家任务），相应用例都会红。
 */
class MuzDialogServiceFailureVisibilityBehaviorTest {

    private static final String SCREEN_KEY = "admin.home";

    /** 构建必失败的屏：覆盖「build 抛异常」出口。 */
    private static MuzDialogScreen throwingScreen(String key) {
        return new MuzDialogScreen() {
            @Override
            public String screenKey() {
                return key;
            }

            @Override
            public io.papermc.paper.dialog.Dialog build(MuzDialogContext ctx) {
                throw new IllegalStateException("模拟构建面板失败");
            }
        };
    }

    @Test
    void 构建失败时必须给玩家可见提示并按SEVERE带栈留痕() throws Exception {
        Harness harness = Harness.create();
        harness.service().openRoot(harness.playerId, throwingScreen(SCREEN_KEY));
        // 失败提示本身也会经 dispatcher 排一个 player lane 任务，因此要排空队列而不是只 fire 一次。
        harness.scheduler.fireUntilIdle();

        assertEquals(1, harness.playerMessages.size(), "构建失败必须给玩家一句可见提示，不能静默");
        assertTrue(harness.playerMessages.get(0).contains("面板打开失败"),
            "玩家提示应说明面板打开失败，实际：" + harness.playerMessages);

        List<LogRecord> severe = harness.logs.atLeast(Level.SEVERE);
        assertEquals(1, severe.size(), "构建失败必须留一条 SEVERE，实际：" + harness.logs.texts());
        assertNotNull(severe.get(0).getThrown(), "SEVERE 必须带异常本体，否则无法定位");
        assertTrue(harness.logs.texts().stream().anyMatch(text -> text.contains(SCREEN_KEY)),
            "日志必须带屏标识，便于定位是哪一屏，实际：" + harness.logs.texts());
    }

    @Test
    void 关闭期拒绝展示且不再投递玩家任务() throws Exception {
        Harness harness = Harness.create();
        harness.service().shutdown();
        harness.service().openRoot(harness.playerId, throwingScreen(SCREEN_KEY));

        // 关闭期连 player lane 都不该排任务：排进去也只会被同一判据挡掉，纯属噪声。
        assertEquals(0, harness.scheduler.enqueued, "关闭期不得再投递玩家任务");
        assertTrue(harness.logs.atLeast(Level.SEVERE).size() >= 1, "关闭期拒绝展示必须留痕");
    }

    @Test
    void 插件停用时不投递玩家任务且留痕() throws Exception {
        Harness harness = Harness.create();
        setField(DoudizhuPlugin.class, harness.plugin, "isEnabled", false);

        harness.service().openRoot(harness.playerId, throwingScreen(SCREEN_KEY));

        assertEquals(0, harness.scheduler.enqueued, "插件停用期不得投递玩家任务");
        assertTrue(harness.logs.atLeast(Level.SEVERE).size() >= 1, "停用期拒绝展示必须留痕");
    }

    @Test
    void 管理入口在Dialog服务缺失时必须留痕并提示玩家() throws Exception {
        Harness harness = Harness.create();
        // 复刻「服务未装配」：openAdminMenu 里 muzDialogService 为 null。
        setField(DoudizhuPlugin.class, harness.plugin, "muzDialogService", null);

        harness.plugin.openAdminMenu(harness.player);
        // 服务缺失分支走玩家 lane 门面发提示，需要把已排任务执行掉才能观察到。
        harness.scheduler.fireAll();

        assertEquals(1, harness.playerMessages.size(), "服务缺失时 /muz admin 不能静默，必须提示玩家");
        assertTrue(harness.playerMessages.get(0).contains("管理菜单暂时不可用"),
            "提示应说明管理菜单不可用，实际：" + harness.playerMessages);
        assertTrue(harness.logs.atLeast(Level.SEVERE).size() >= 1, "服务缺失必须按 SEVERE 留痕");
    }

    /** 夹具：无服务端的插件 + 只登记不自动执行的 player 调度 + 日志/消息捕获。 */
    private static final class Harness {
        private DoudizhuPlugin plugin;
        private Player player;
        private UUID playerId;
        private RecordingScheduler scheduler;
        private MuzDialogService service;
        private final List<String> playerMessages = new ArrayList<>();
        private final CapturedLogs logs = new CapturedLogs();

        private Harness() {
        }

        private static Harness create() throws Exception {
            Harness harness = new Harness();
            harness.plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
            setField(DoudizhuPlugin.class, harness.plugin, "logger", harness.logs.logger());
            // JavaPlugin.isEnabled() 直接读这个字段；绝不能用 setEnabled(true)（会触发 onEnable）。
            setField(DoudizhuPlugin.class, harness.plugin, "isEnabled", true);
            setField(DoudizhuPlugin.class, harness.plugin, "shuttingDown", false);
            harness.playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            harness.player = harness.playerStub(harness.playerId);
            harness.scheduler = new RecordingScheduler();
            PlayerTaskRegistry tasks = PlayerTaskRegistry.uuidFirst(
                id -> harness.player,
                harness.scheduler);
            PlayerOutputDispatcher output = new PlayerOutputDispatcher(tasks);
            harness.service = new MuzDialogService(
                harness.plugin,
                output,
                new DialogSessionRegistry(),
                (player, dialog) -> { });
            setField(DoudizhuPlugin.class, harness.plugin, "muzDialogService", harness.service);
            setField(DoudizhuPlugin.class, harness.plugin, "playerOutputDispatcher", output);
            return harness;
        }

        private MuzDialogService service() {
            return service;
        }

        private Player playerStub(UUID id) {
            return (Player) Proxy.newProxyInstance(
                MuzDialogServiceFailureVisibilityBehaviorTest.class.getClassLoader(),
                new Class<?>[] { Player.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return id;
                        case "getName":
                            return "admin";
                        case "hasPermission":
                        case "isPermissionSet":
                        case "isOnline":
                        case "isValid":
                            return true;
                        case "sendMessage":
                            collectMessages(args);
                            return null;
                        case "toString":
                            return "PlayerStub";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            break;
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) {
                        return false;
                    }
                    if (ret == int.class) {
                        return 0;
                    }
                    return null;
                });
        }

        private void collectMessages(Object[] args) {
            if (args == null) {
                return;
            }
            for (Object arg : args) {
                if (arg instanceof Component component) {
                    playerMessages.add(PlainTextComponentSerializer.plainText().serialize(component));
                } else if (arg instanceof String text) {
                    playerMessages.add(text);
                }
            }
        }
    }

    /** 只登记、不自动执行；由用例显式 fire。 */
    private static final class RecordingScheduler implements PlayerTaskRegistry.UuidPlayerScheduler {
        private final List<Runnable> queued = new ArrayList<>();
        private int enqueued;

        @Override
        public MuzScheduler.TaskHandle run(UUID playerId, long delayTicks, Runnable task) {
            enqueued++;
            queued.add(task);
            return new NoopHandle();
        }

        private void fireAll() {
            List<Runnable> snapshot = new ArrayList<>(queued);
            queued.clear();
            snapshot.forEach(Runnable::run);
        }

        /** 排空队列：任务在执行过程中可能继续安排新的 player lane 任务（如失败提示）。 */
        private void fireUntilIdle() {
            int guard = 0;
            while (!queued.isEmpty()) {
                if (++guard > 32) {
                    throw new IllegalStateException("player lane 任务没有收敛");
                }
                fireAll();
            }
        }
    }

    /** 最小句柄：本夹具不关心取消语义，只需非 null 且未取消。 */
    private static final class NoopHandle implements MuzScheduler.TaskHandle {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void onTermination(Runnable listener) {
        }
    }

    /** 捕获本插件 logger 的记录（含级别与异常本体）。 */
    private static final class CapturedLogs {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger = Logger.getLogger("MUZ-test-" + UUID.randomUUID());

        private CapturedLogs() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        private Logger logger() {
            return logger;
        }

        private List<LogRecord> atLeast(Level level) {
            List<LogRecord> matched = new ArrayList<>();
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= level.intValue()) {
                    matched.add(record);
                }
            }
            return matched;
        }

        private List<String> texts() {
            List<String> texts = new ArrayList<>();
            for (LogRecord record : records) {
                texts.add(String.valueOf(record.getMessage()));
            }
            return texts;
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    /** 沿继承链找字段：{@code logger} 等来自 {@code JavaPlugin}，不在 {@code DoudizhuPlugin} 上声明。 */
    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Class<?> current = owner;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }
}
