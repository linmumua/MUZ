package linmumua.doudizhu.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * /muz 命令的关闭期生命周期行为测试。
 *
 * <p>【被修复的真实缺陷】paper-plugin.yml 不支持 commands 段，/muz 只能经 CommandMap 手动注册。
 * CommandMap 不会随插件 disable 自动摘除命令，于是插件被禁用后玩家/控制台仍能选中 /muz；
 * 派发会去解析插件类加载器里的类，而 Paper 已经关闭了插件 JAR（PluginClassLoader 持有的 JarFile
 * 已 close），解析失败即 {@code java.util.zip.ZipException: zip file closed}。
 *
 * <p>本测试用 {@link Unsafe} 夹具 + 复刻真实 CommandMap 语义的替身，锁定四条契约：
 * <ol>
 *   <li>插件启用时 /muz 仍正常工作（修复不是把命令直接关死）；</li>
 *   <li>插件禁用后 onCommand / onTabComplete 的门禁先于业务逻辑生效，命令不再执行、补全返回空；</li>
 *   <li>注册保存命令引用，重复注册不会把上一份命令泄漏在 CommandMap 里；</li>
 *   <li>onDisable 能从 CommandMap 真正摘除命令；摘除失败只记日志、不抛异常、不打断关闭。</li>
 * </ol>
 */
class MuzCommandLifecycleBehaviorTest {

    // ---- 门禁行为 ----

    @Test
    void 插件启用时命令仍然正常工作() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.pluginEnabled(true);
        CapturingSender sender = sender();

        boolean handled = fixture.executor.onCommand(sender.sender, fixture.command, "muz", new String[] { "help" });

        assertTrue(handled, "命令必须被当成已处理");
        assertTrue(sender.text().contains("MUZ 常用命令"),
            "插件启用时 /muz help 必须照常输出，实际=" + sender.messages);
        assertTrue(sender.text().contains("/muz set"),
            "帮助正文也必须照常输出，实际=" + sender.messages);
    }

    @Test
    void 插件禁用后命令不再执行() throws Exception {
        Fixture fixture = Fixture.create();
        // 默认即「已禁用」：isEnabled=false、shuttingDown=false。
        CapturingSender sender = sender();

        // list 会调用 plugin.getTableManager()（夹具里为 null）。门禁一旦失效就会抛 NPE，
        // 所以「不抛异常且没有牌桌输出」正是「业务逻辑没有被执行」的证据。
        boolean handled = runExpectingGate(fixture, sender, "list");

        assertTrue(handled, "禁用后命令仍应被当成已处理，避免服务端再报 unknown command");
        assertEquals(1, sender.messages.size(),
            "禁用后只允许输出一条关闭提示，实际=" + sender.messages);
        assertTrue(sender.text().contains("关闭"),
            "必须提示命令不可用，实际=" + sender.messages);
        assertFalse(sender.text().contains("牌桌"),
            "禁用后绝不能进入业务分支，实际=" + sender.messages);
    }

    @Test
    void shuttingDown标志同样拒绝执行() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.pluginEnabled(true);
        fixture.setShuttingDown(true);
        CapturingSender sender = sender();

        boolean handled = runExpectingGate(fixture, sender, "list");

        assertTrue(handled);
        assertEquals(1, sender.messages.size(),
            "插件仍 enabled 但已开始关闭时也必须拒绝执行，实际=" + sender.messages);
        assertTrue(sender.text().contains("关闭"), "实际=" + sender.messages);
    }

    /**
     * 以 /muz list 触发 onCommand：该分支会调用 {@code plugin.getTableManager()}（夹具里为 null）。
     * 门禁一旦失效就会抛 NPE，所以这里把「进入业务分支」翻译成一条语义明确的失败信息，
     * 而不是让它以裸 NPE 的形式暴露（NPE 无法说明是哪条契约被破坏）。
     */
    private static boolean runExpectingGate(Fixture fixture, CapturingSender sender, String sub) throws Exception {
        try {
            return fixture.executor.onCommand(sender.sender, fixture.command, "muz", new String[] { sub });
        } catch (RuntimeException failure) {
            throw new AssertionError(
                "禁用/关闭后命令不得进入业务分支（夹具里 getTableManager() 为 null，进入即 NPE）；"
                    + "说明 isRuntimeAvailable() 门禁没有生效", failure);
        }
    }

    @Test
    void 插件禁用后补全返回空() throws Exception {
        Fixture fixture = Fixture.create();
        CapturingSender sender = sender();

        List<String> completions = fixture.executor.onTabComplete(sender.sender, fixture.command, "muz", new String[] { "" });

        assertTrue(completions.isEmpty(), "禁用后补全必须为空，实际=" + completions);
    }

    // ---- 注册 / 注销行为 ----

    @Test
    void 注册保存引用且重复注册不泄漏() throws Exception {
        Fixture fixture = Fixture.create();

        fixture.registerMuzCommand();
        Command first = fixture.registeredCommand();

        assertNotNull(first, "注册后必须能从 CommandMap 找到 /muz");
        assertSame(first, fixture.muzCommandField(),
            "registerMuzCommand 必须把命令实例存进 muzCommand 字段，否则关闭时无从注销");
        Map<String, Command> known = fixture.commandMap.getKnownCommands();
        assertTrue(known.values().stream().anyMatch(value -> value == first),
            "knownCommands 必须登记本命令");

        fixture.registerMuzCommand();
        Command second = fixture.registeredCommand();

        assertNotNull(second, "重复注册后仍应能找到 /muz");
        assertFalse(second == first, "重复注册必须换成新实例");
        assertSame(second, fixture.muzCommandField(), "字段必须指向最新实例");
        assertTrue(known.values().stream().noneMatch(value -> value == first),
            "旧命令实例必须从 CommandMap 摘掉，否则就是泄漏（也导致关闭时摘错对象）");
        assertTrue(known.values().stream().allMatch(value -> value == second),
            "knownCommands 里不允许残留任何非最新实例，实际键=" + known.keySet());
    }

    @Test
    void 注销从CommandMap摘除命令() throws Exception {
        Fixture fixture = Fixture.create();
        fixture.registerMuzCommand();
        Command registered = fixture.registeredCommand();
        assertNotNull(registered);

        fixture.unregisterMuzCommand();

        assertNull(fixture.muzCommandField(), "注销后字段必须清空");
        Map<String, Command> known = fixture.commandMap.getKnownCommands();
        assertTrue(known.values().stream().noneMatch(value -> value == registered),
            "注销必须真正从 knownCommands 摘掉命令，实际残留键=" + known.keySet());
        assertNull(known.get("muz"), "muz 主键必须摘掉");
        assertNull(known.get("muz:muz"), "fallbackPrefix 键也必须摘掉");
    }

    @Test
    void 注销失败只记日志不抛异常() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        Fixture fixture = Fixture.create(logs.logger(), ThrowingCommandMap::new);
        fixture.registerMuzCommand();

        fixture.unregisterMuzCommand(); // 不抛出即通过

        assertTrue(logs.records().stream().anyMatch(record -> record.getLevel() == Level.WARNING
                && record.getMessage() != null && record.getMessage().contains("注销 /muz")),
            "摘除失败必须留痕（WARNING），实际=" + logs.messages());
    }

    @Test
    void CommandMap不可用时注销留痕且不抛异常() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        Fixture fixture = Fixture.create(logs.logger(), () -> null);
        // 直接放入命令引用（绕过注册）：本用例只验「注销时拿不到 CommandMap」这条兜底分支。
        fixture.setMuzCommandField(muzCommandStub());

        fixture.unregisterMuzCommand(); // 不抛出即通过

        assertTrue(logs.messages().stream().anyMatch(text -> text.contains("CommandMap 不可用")),
            "CommandMap 不可用必须留痕，实际=" + logs.messages());
        assertNull(fixture.muzCommandField(), "即使摘除失败也必须清空引用，避免悬挂");
    }

    @Test
    void 重复注销是幂等的且不重复留痕() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        Fixture fixture = Fixture.create(logs.logger(), FakeCommandMap::new);
        fixture.registerMuzCommand();

        fixture.unregisterMuzCommand();
        int afterFirst = logs.records().size();
        fixture.unregisterMuzCommand();
        fixture.unregisterMuzCommand();

        assertEquals(afterFirst, logs.records().size(),
            "已注销后再调用不该反复留痕，实际=" + logs.messages());
    }

    @Test
    void onDisable摘除失败不打断关闭() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        Fixture fixture = Fixture.create(logs.logger(), ThrowingCommandMap::new);
        fixture.registerMuzCommand();

        // 真实 onDisable：CommandMap 抛异常时仍必须整体跑完（其余清理步骤不受影响）。
        fixture.plugin.onDisable();

        assertTrue(logs.messages().stream().anyMatch(text -> text.contains("注销 /muz")),
            "onDisable 必须因摘除失败留痕，实际=" + logs.messages());
    }

    // ---- 夹具 ----

    private static final class Fixture {
        private final DoudizhuPlugin plugin;
        private final DoudizhuCommand executor;
        private final Command command;
        private final CommandMap commandMap;

        private Fixture(DoudizhuPlugin plugin, DoudizhuCommand executor, Command command, CommandMap commandMap) {
            this.plugin = plugin;
            this.executor = executor;
            this.command = command;
            this.commandMap = commandMap;
        }

        private static Fixture create() throws Exception {
            return create(new CapturedLogs().logger(), FakeCommandMap::new);
        }

        private static Fixture create(Logger logger, Supplier<CommandMap> mapFactory) throws Exception {
            DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
            setField(plugin, "logger", logger);
            CommandMap commandMap = mapFactory.get();
            setField(plugin, "server", serverStub(commandMap));
            DoudizhuCommand executor = new DoudizhuCommand(plugin);
            return new Fixture(plugin, executor, muzCommandStub(), commandMap);
        }

        /**
         * 只改 JavaPlugin 的 isEnabled 字段，绝不调用 setEnabled(boolean)：后者会连带触发
         * onEnable()/onDisable()，而 Unsafe 分配出的插件没有可用的启动装配。
         */
        private void pluginEnabled(boolean enabled) throws Exception {
            setField(plugin, "isEnabled", enabled);
        }

        private void setShuttingDown(boolean value) throws Exception {
            setField(plugin, "shuttingDown", value);
        }

        private void registerMuzCommand() throws Exception {
            invoke("registerMuzCommand");
        }

        private void unregisterMuzCommand() throws Exception {
            invoke("unregisterMuzCommand");
        }

        private Object invoke(String name) throws Exception {
            Method method = DoudizhuPlugin.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(plugin);
        }

        private Command registeredCommand() {
            return commandMap.getKnownCommands().get("muz");
        }

        private Command muzCommandField() throws Exception {
            return (Command) field(DoudizhuPlugin.class, "muzCommand").get(plugin);
        }

        private void setMuzCommandField(Command command) throws Exception {
            setField(plugin, "muzCommand", command);
        }
    }

    /**
     * 复刻 Paper {@code SimpleCommandMap} 的可见语义：register(String,String,Command) 会同时登记
     * label 与 fallbackPrefix:label 两个键，并回调 {@code command.register(this)}。
     *
     * <p>真实服务器上 knownCommands 是一张转发到 Brigadier dispatcher 的 map，按 key remove 会同步
     * 删掉 Brigadier 节点——那正是「不再被派发」的关键，所以替身保留「登记多个键、按 key 摘除」的语义。
     */
    private static class FakeCommandMap implements CommandMap {
        final Map<String, Command> knownCommands = new LinkedHashMap<>();

        @Override
        public void registerAll(String fallbackPrefix, List<Command> commands) {
            for (Command command : commands) {
                register(command.getName(), fallbackPrefix, command);
            }
        }

        @Override
        public boolean register(String fallbackPrefix, Command command) {
            return register(command.getName(), fallbackPrefix, command);
        }

        @Override
        public boolean register(String label, String fallbackPrefix, Command command) {
            String key = label.trim().toLowerCase(Locale.ROOT);
            Command existing = knownCommands.get(key);
            if (existing != null && existing != command) {
                return false;
            }
            knownCommands.put(key, command);
            knownCommands.put((fallbackPrefix + ":" + label).toLowerCase(Locale.ROOT), command);
            command.register(this);
            return true;
        }

        @Override
        public boolean dispatch(CommandSender sender, String commandLine) {
            return false;
        }

        @Override
        public void clearCommands() {
            knownCommands.clear();
        }

        @Override
        public Command getCommand(String name) {
            return knownCommands.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String cmdLine) {
            return List.of();
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String cmdLine, org.bukkit.Location location) {
            return List.of();
        }

        @Override
        public Map<String, Command> getKnownCommands() {
            return knownCommands;
        }
    }

    /** 读 knownCommands 就抛异常的替身：模拟「注销路径本身失败」。 */
    private static final class ThrowingCommandMap extends FakeCommandMap {
        @Override
        public Map<String, Command> getKnownCommands() {
            throw new IllegalStateException("模拟 CommandMap 摘除失败");
        }
    }

    private static Server serverStub(CommandMap commandMap) {
        return (Server) Proxy.newProxyInstance(
            MuzCommandLifecycleBehaviorTest.class.getClassLoader(),
            new Class<?>[] { Server.class },
            (proxy, method, args) -> {
                if ("getCommandMap".equals(method.getName())) {
                    return commandMap;
                }
                return proxyDefault(proxy, method, args);
            });
    }

    /** 供 onCommand 形参用的命令替身：本测试只关心 executor 的行为。 */
    private static Command muzCommandStub() {
        return new Command("muz", "管理 MUZ 牌桌与对局。", "/muz help", List.of("MUZ")) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
    }

    /** 收集 sendMessage 文本的发送者替身。 */
    private static CapturingSender sender() {
        CapturingSender capture = new CapturingSender();
        capture.sender = (CommandSender) Proxy.newProxyInstance(
            MuzCommandLifecycleBehaviorTest.class.getClassLoader(),
            new Class<?>[] { CommandSender.class },
            (proxy, method, args) -> {
                if ("sendMessage".equals(method.getName())) {
                    collect(method, args, capture);
                    return null;
                }
                if ("hasPermission".equals(method.getName()) || "isPermissionSet".equals(method.getName())) {
                    return true;
                }
                if ("getName".equals(method.getName())) {
                    return "console";
                }
                return proxyDefault(proxy, method, args);
            });
        return capture;
    }

    private static void collect(Method method, Object[] args, CapturingSender capture) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof Component component) {
                capture.messages.add(PlainTextComponentSerializer.plainText().serialize(component));
            } else if (arg instanceof String text) {
                capture.messages.add(text);
            }
        }
    }

    private static final class CapturingSender {
        private final List<String> messages = new ArrayList<>();
        private CommandSender sender;

        private String text() {
            return String.join("\n", messages);
        }
    }

    private static Object proxyDefault(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return "stub";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            default:
                break;
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (List.class.isAssignableFrom(returnType)) {
            return List.of();
        }
        return null;
    }

    /** 收集日志的 JUL logger：只验证「记没记、记了什么」。 */
    private static final class CapturedLogs {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger;

        private CapturedLogs() {
            this.logger = Logger.getLogger("muz-command-lifecycle-" + System.nanoTime());
            this.logger.setUseParentHandlers(false);
            this.logger.setLevel(Level.ALL);
            this.logger.addHandler(new Handler() {
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

        private List<LogRecord> records() {
            return records;
        }

        private List<String> messages() {
            List<String> snapshot = new ArrayList<>();
            for (LogRecord record : records) {
                snapshot.add(record.getMessage() == null ? "" : record.getMessage());
            }
            return snapshot;
        }
    }

    private static Field field(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field found = current.getDeclaredField(fieldName);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        field(target.getClass(), fieldName).set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
