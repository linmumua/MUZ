package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.HudResourceRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import taboolib.library.reflex.AnalyseMode;
import taboolib.library.reflex.ClassAnalyser;
import taboolib.library.reflex.ClassMethod;
import taboolib.library.reflex.ReflexClass;

/**
 * CraftEngine HUD 资源包的直接 API 桥接。
 *
 * <p>这份桥接只在确认 CraftEngine 插件已启用后由 Debug Web 协调器惰性创建；类本身不参与
 * 插件生命周期。CraftEngine 是 compileOnly 依赖，因此调用方必须把直接 API 的创建包在
 * {@link LinkageError} 降级边界内，CraftEngine 缺失时不能影响 MUZ 其它功能。
 *
 * <p>一次资源任务的顺序固定为：CraftEngine 真实 reload Future 成功、异步生成资源包、校验
 * 生成路径和配置中的上传路径内容，全部完成后 Future 才成功。CraftEngine 的生成方法会
 * 吞掉部分 IOException，所以不能把 generateResourcePack() 正常返回当成成功证据。
 *
 * <p>【为什么 CraftEngine 全部改成反射调用】：本项目按 0.0.67 编译（build.gradle.kts 的
 * compileOnly），但现场跑的是 26.x（26.8.2 / 26.9.1）。26.x 把
 * {@code reloadPlugin(Executor, Executor, boolean)} 改成了
 * {@code reloadPlugin(Executor, Executor, boolean, boolean)}（见下），于是原先的静态调用
 * {@code engine.reloadPlugin(ioExecutor, mainExecutor, false)} 在运行期直接抛
 * {@link NoSuchMethodError}，Web 保存与启动期 HUD 资源恢复全部失败并回滚配置。静态调用点
 * 抛出的 NoSuchMethodError 无法被就地判读成「版本不兼容」，只能整体降级；因此这里改成
 * 运行期按签名优先级查找重载，并缓存一次查找结果，让版本差异变成一条可解释的失败信息
 * 而不是链接错误。
 *
 * <p>【勘误（本轮）：26.9.1 的 PackManager 形状不同，本类尚未适配】上面的重载适配只解决
 * {@code reloadPlugin} 一项。CraftEngine 26.9.1 上 {@code PackManager.generateResourcePack()}、
 * {@code PackManager.resourcePackPath()} 与 {@code Config.fileToUpload()} 三个方法都【不存在】
 * （0.0.67 与 26.8.x 存在；javap 证据同见 build/tmp-ce-api）。因此本类目前【只支持 0.0.67 与
 * 26.8.x 的 PackManager 形状】，在 26.9.1 上生成/读取资源包路径必然失败，需要另行按新 API
 * 适配，尚未实现。为避免这类版本差异再次表现为「路径为空」这种无法排查的静默降级，缺失时
 * 一律抛出带 CraftEngine 版本号与缺失方法名的明确错误；同时保证同一条「不支持的 CE API」
 * 告警在进程内只 warning 一次，之后同类原因降为 fine，不会在启动期与每一次 HUD 资源恢复里刷屏。
 *
 * <p>三种真实签名（javap -p 反编译证据，临时目录 build/tmp-ce-api）：
 * <ul>
 *   <li>0.0.67（编译依赖）：{@code CompletableFuture<ReloadResult> reloadPlugin(Executor, Executor, boolean)}</li>
 *   <li>26.8.2：{@code ReloadResult} 型 {@code reloadPlugin(Executor, Executor, boolean, boolean)}</li>
 *   <li>26.9.1：同上 4 参，另加 6 参重载；4 参实现委托给 6 参并补两个 {@code false}</li>
 * </ul>
 * 三种版本的返回类型都是 {@code CompletableFuture<CraftEngine$ReloadResult>}，且
 * {@code ReloadResult} 都是带 {@code boolean success()} 的 Record，因此成功判定可以统一用
 * 反射调 {@code success()}——不依赖 26.x 新增的 {@code issues()}（本项目按 0.0.67 编译时甚至
 * 编译不过该方法，见 AGENTS.md 已记录的「0.0.67 未提供 issues()」）。
 */
public final class CraftEngineHudResourceBridge implements HudResourcePackBridge {
    private static final String CRAFT_ENGINE_PLUGIN = "CraftEngine";

    /** CraftEngine 主类全名；用字符串而非 import，避免把 0.0.67 的静态签名带进运行期。 */
    private static final String CRAFT_ENGINE_CLASS = "net.momirealms.craftengine.core.plugin.CraftEngine";
    /** 上传包路径来源类；与 CraftEngine 同属 compileOnly，缺失时按不可用处理。 */
    private static final String CONFIG_CLASS = "net.momirealms.craftengine.core.plugin.config.Config";

    private final DoudizhuPlugin plugin;
    private final ResourceVerifier verifier;
    private final RequestVerifier requestVerifier;
    private volatile EngineAccess engineAccess;
    private volatile boolean linkageFailureLogged;

    /**
     * 已经 warning 过的「不支持的 CraftEngine API」原因（进程级，见 {@link #warnUnsupportedApiOnce(String)}）。
     *
     * <p>用并发集合是因为 CE 的重载回调与我们自己的保存流程可能来自不同线程。
     */
    private static final Set<String> WARNED_UNSUPPORTED_API = ConcurrentHashMap.newKeySet();

    /**
     * 创建生产桥接。构造阶段不读取资源包文件；实际 CE API 访问延迟到
     * {@link #preflightFailureOnMainThread()} 或资源任务开始时。
     */
    public CraftEngineHudResourceBridge(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        HudResourcePackVerifier created = new HudResourcePackVerifier(plugin::getResource);
        this.verifier = created::verify;
        this.requestVerifier = created::verify;
        this.engineAccess = null;
    }

    /**
     * 仅供同包测试注入纯 Java fake，避免测试运行时必须加载 CraftEngine compileOnly 类。
     */
    CraftEngineHudResourceBridge(DoudizhuPlugin plugin, ResourceVerifier verifier,
                                 EngineAccess engineAccess) {
        this(plugin, verifier,
            (path, request) -> verifier.verify(path, request.hotbarOffsetY()),
            engineAccess);
    }

    CraftEngineHudResourceBridge(DoudizhuPlugin plugin, ResourceVerifier verifier,
                                 RequestVerifier requestVerifier, EngineAccess engineAccess) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.requestVerifier = Objects.requireNonNull(requestVerifier, "requestVerifier");
        this.engineAccess = engineAccess;
    }

    @Override
    public String preflightFailureOnMainThread() {
        if (!isCraftEngineEnabled()) {
            return "CraftEngine 未启用，未写入 HUD 配置。";
        }
        try {
            EngineAccess access = resolveEngineAccess();
            if (access == null) {
                return "CraftEngine 实例尚未就绪，未写入 HUD 配置。";
            }
            if (access.isReloading()) {
                return "CraftEngine 资源仍在重载，未写入 HUD 配置。";
            }
            return null;
        } catch (LinkageError error) {
            logLinkageFailure(error);
            return "CraftEngine API 不兼容，已跳过 HUD 资源同步。";
        } catch (RuntimeException exception) {
            return "读取 CraftEngine 状态失败，未写入 HUD 配置：" + messageOf(exception);
        }
    }

    /**
     * 按一次性三层请求穿透既有 reload → generate → verify 链；Bridge 不复制资源事务算法。
     */
    @Override
    public CompletableFuture<Void> reloadGenerateAndVerify(HudResourceRequest request, Executor ioExecutor,
                                                             Executor mainExecutor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ioExecutor, "ioExecutor");
        Objects.requireNonNull(mainExecutor, "mainExecutor");
        final EngineAccess access;
        try {
            String failure = preflightFailureOnMainThread();
            if (failure != null) {
                return failedFuture(new IOException(failure));
            }
            access = resolveEngineAccess();
            if (access == null) {
                return failedFuture(new IOException("CraftEngine 实例尚未就绪。"));
            }
            if (access.isReloading()) {
                return failedFuture(new IOException("CraftEngine 资源仍在重载。"));
            }
        } catch (LinkageError error) {
            logLinkageFailure(error);
            return failedFuture(new IOException("CraftEngine API 不兼容。", error));
        } catch (RuntimeException exception) {
            return failedFuture(new IOException("读取 CraftEngine 状态失败：" + messageOf(exception), exception));
        }

        return HudResourcePackSync.run(
            access::reload,
            access::generateResourcePack,
            access::generatedPackPath,
            access::uploadPackPath,
            requestVerifier::verify,
            request,
            ioExecutor,
            mainExecutor
        );
    }

    // 旧 offset/scale 入口由 HudResourcePackBridge 接口统一 fail-closed；生产桥接不得绕过三层请求。

    private boolean isCraftEngineEnabled() {
        try {
            var detected = plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
            return detected != null && detected.isEnabled();
        } catch (LinkageError error) {
            logLinkageFailure(error);
            return false;
        }
    }

    private EngineAccess resolveEngineAccess() {
        EngineAccess current = engineAccess;
        if (current != null) {
            return current;
        }
        if (!isCraftEngineEnabled()) {
            return null;
        }
        Object engine = CraftEngineReflection.instance();
        if (engine == null) {
            return null;
        }
        EngineAccess created = new DirectEngineAccess(engine);
        engineAccess = created;
        // 【为什么在这里告警】CE 版本形状不匹配属于「配置对了也必然失败」的环境问题。启动期恢复与
        // 每次 Debug Web 保存都会走到这里，直接 warning 会刷屏；而完全无声又会让玩家以为保存成功。
        // 所以按「原因」在【进程内】只 warning 一次，之后同类原因降为 fine。
        if (created instanceof DirectEngineAccess direct) {
            warnUnsupportedApiOnce(direct.unsupportedReason());
        }
        return created;
    }

    /**
     * 同一条「不支持的 CraftEngine API」在进程内只 warning 一次，其余降为 fine。
     *
     * <p>【为什么用静态集合而不是实例字段】Bridge 是【每次 HUD 资源恢复/Web 保存新建一个】的
     * （见类头「由 Debug Web 协调器惰性创建」），实例级别的「只警告一次」在这种生命周期下等于
     * 每次恢复都刷一条。因此这里按原因文本做进程级去重；原因文本里带 CE 版本号，所以换成别的
     * 版本时仍然会各自告警一次。
     */
    private void warnUnsupportedApiOnce(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        if (WARNED_UNSUPPORTED_API.add(reason)) {
            plugin.getLogger().warning(reason);
        } else {
            plugin.getLogger().fine(reason);
        }
    }

    private void logLinkageFailure(LinkageError error) {
        if (linkageFailureLogged) {
            return;
        }
        linkageFailureLogged = true;
        plugin.getLogger().warning("CraftEngine HUD 资源桥接不可用，已降级：" + messageOf(error));
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(throwable);
        return failed;
    }

    @FunctionalInterface
    interface ResourceVerifier {
        void verify(Path packPath, int offsetY) throws IOException;

        /** 旧 verifier 默认按 100% hotbar 档校验；生产实现可覆盖以校验指定档位。 */
        default void verify(Path packPath, int offsetY, int hotbarScale) throws IOException {
            verify(packPath, offsetY);
        }
    }

    @FunctionalInterface
    interface RequestVerifier {
        void verify(Path packPath, HudResourceRequest request) throws IOException;
    }

    interface EngineAccess {
        boolean isReloading();

        CompletableFuture<HudResourcePackSync.ReloadResult> reload(Executor ioExecutor, Executor mainExecutor);

        void generateResourcePack() throws Exception;

        Path generatedPackPath();

        Path uploadPackPath();
    }

    /**
     * 0.0.67 / 26.x 通用反射入口。
     *
     * <p>只按方法名 + 参数量查找，并缓存查找结果；找不到时抛出带「实际发现的重载签名列表」的
     * {@link UnsupportedOperationException}，绝不静默当成成功。方法名、参数数量都是 CE 全代
     * 稳定的（三个版本的方法名与返回类型完全一致，只有 boolean 参数个数变化），因此按 arity
     * 选择既精确又不需要逐版本硬编码类结构。
     */
    private static final class CraftEngineReflection {
        private CraftEngineReflection() {
        }

        /** {@code CraftEngine.instance()}；CraftEngine 未加载时返回 null，不抛链接错误。 */
        static Object instance() {
            try {
                // 【为什么仍是 Class.forName】：CraftEngine 是 compileOnly，MUZ 的类加载器里
                // 完全看不到它，必须先按 CE 插件类加载器把 Class 取回来；TabooLib 的 ReflexClass
                // 接的是一个已拿到的 Class<?>，不替代这一步。
                Class<?> type = Class.forName(CRAFT_ENGINE_CLASS, false, loader());
                return firstMethod(type, "instance").invokeStatic(new Object[0]);
            } catch (Throwable exception) {
                return null;
            }
        }

        private static ClassLoader loader() {
            // 与 CraftEngineOffsetService 同口径：优先用实际插件类加载器，避免 MUZ 自己的
            // ClassLoader 里看不到 compileOnly 依赖。
            var detected = org.bukkit.Bukkit.getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
            if (detected != null) {
                return detected.getClass().getClassLoader();
            }
            return CraftEngineReflection.class.getClassLoader();
        }

        /**
         * 现场 CraftEngine 的版本号，仅用于把「不支持的 API」错误写成可排查的文本。
         *
         * <p>【为什么返回值而不是抛异常】版本号是诊断信息：拿不到它仍然要报出「缺哪个方法」，
         * 所以这里一律降级成「版本未知」，绝不让取版本本身成为新的失败点。
         */
        static String detectedVersion() {
            try {
                var detected = org.bukkit.Bukkit.getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
                if (detected == null) {
                    return "版本未知（未检测到插件实例）";
                }
                String version = detected.getPluginMeta().getVersion();
                return version == null || version.isBlank() ? "版本未知" : version;
            } catch (Throwable exception) {
                return "版本未知";
            }
        }
    }

    /**
     * 把 {@code Class<?>} 分析成 TabooLib 的 {@link ReflexClass}（按 Class 缓存）。
     *
     * <p>用 {@code REFLECTION_ONLY} 而不是默认的 {@code ASM_FIRST}：CraftEngine 的类来自它自己的
     * 插件类加载器，ASM 分析要把 {@code .class} 当资源读出来，跨加载器不保证拿得到（实测动态
     * 代理类在 ASM_ONLY 下直接抛 ClassNotFoundException）；纯反射分析只要 Class 对象本身。
     *
     * <p>【为什么要缓存】{@code DirectEngineAccess} 的构造与 {@code reload} 都会做类结构分析，
     * 每次重建一遍是浪费。以 {@code Class} 为键是安全的：CraftEngine reload 若换掉实现类，
     * 我们拿到的是另一个 {@code Class}，缓存自然不命中；{@link ReloadInvoker#resolve} 仍然
     * 【每次按当前实例的运行时类】重新挑选重载，只是复用同一份结构分析结果。
     */
    private static ReflexClass analyse(Class<?> type) {
        return ANALYSED_CLASSES.computeIfAbsent(type, key -> new ReflexClass(
            ClassAnalyser.INSTANCE.analyse(key, AnalyseMode.REFLECTION_ONLY), AnalyseMode.REFLECTION_ONLY));
    }

    /** 已解析的类结构缓存；键是 {@code Class}，所以 CE 换实现类后不会命中旧结构。 */
    private static final ConcurrentHashMap<Class<?>, ReflexClass> ANALYSED_CLASSES = new ConcurrentHashMap<>();

    /**
     * 取指定名字的【无参】方法，并向上查找父类与接口。
     *
     * <p>【为什么不能只用 {@code getStructure().getMethodsMap()}】它只描述【该类自己声明】的成员，
     * 父类/接口里的访问器（{@code CE.instance()}、{@code ReloadResult.success()}、
     * {@code Config.fileToUpload()} 等都可能是继承来的）会漏掉；而且同名重载列表的顺序没有语义
     * 保证，取 {@code get(0)} 等于依赖枚举顺序。这里从目标类开始逐层向上找、按参数个数筛，
     * 同一层里同名同 arity 至多一个方法，因此结果确定；最派生的声明优先。
     */
    private static ClassMethod firstMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (ClassMethod method : methodsDeclaredIn(current, name)) {
                if (method.getParameterTypes().length == 0) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new UnsupportedOperationException(
            "CraftEngine API 缺失：" + type.getName() + "#" + name + "()");
    }

    /** 取某个类【自己声明】的全部同名方法；没有则返回空列表。 */
    private static List<ClassMethod> methodsDeclaredIn(Class<?> type, String name) {
        List<ClassMethod> methods = analyse(type).getStructure().getMethodsMap().get(name);
        return methods == null ? List.of() : methods;
    }

    /**
     * 取指定名字的全部重载，并向上查找父类与接口。
     *
     * <p>【为什么要向上查找】原来的实现只看本类声明，若 CE 把 {@code reloadPlugin} 放在父类里就会
     * 一个都找不到、误报「未提供受支持的重载」。这里把整条继承链上的同名重载汇总起来（按签名去重），
     * 让 {@link ReloadInvoker#resolve} 的 arity 选择能看到全部候选。
     *
     * <p>顺序按「最派生层优先」，同层内按 arity 升序，因此诊断信息是确定的；
     * {@code resolve} 本身按 arity 显式筛选，不依赖这个顺序。
     */
    private static List<ClassMethod> methodsNamed(Class<?> type, String name) {
        List<ClassMethod> collected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            List<ClassMethod> declared = new ArrayList<>(methodsDeclaredIn(current, name));
            declared.sort(Comparator.comparingInt(method -> method.getParameterTypes().length));
            for (ClassMethod method : declared) {
                if (seen.add(signatureOf(method))) {
                    collected.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return collected;
    }

    /** 方法签名文本，用于去重与诊断（形参类型 + 返回类型）。 */
    private static String signatureOf(ClassMethod method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(parameters[index].getSimpleName());
        }
        return builder.append(") -> ").append(method.getReturnType().getSimpleName()).toString();
    }

    /**
     * reloadPlugin 的运行期适配器。
     *
     * <p>参数形状的偏好顺序是显式的：3 参（0.0.67 原生）→ 4 参（26.8.2 / 26.9.1 稳定形状）
     * → 6 参（26.9.1 兜底）。这里不能用「随便取一个同名方法」来选——分析结果里同名重载的
     * 顺序没有语义保证，26.9.1 同时有 4 参与 6 参时可能先拿到 6 参，从而绕开 4 参的
     * 委托语义。因此按 {@link #SHAPE_PREFERENCE} 逐个 arity 精确查找。
     *
     * <p>调用时按形状补齐 boolean 参数：第 3 位 {@code false}（与原调用一致），第 4 位 {@code true}
     * （26.x 上控制是否触发 CE 重载事件；0.0.67 的 3 参实现无条件触发，传 true 才与之等价），
     * 其余补 {@code false}。（勘误：此前注释称「多余位统一补 false、语义完全一致」，经字节码核对不成立。）
     */
    static final class ReloadInvoker {
        /**
         * 受支持的 arity，按偏好从高到低排列。
         *
         * <p>3 参是编译依赖 0.0.67 的形状，优先命中说明运行的是同代版本；4 参是 26.8.2 /
         * 26.9.1 的稳定入口，也是 26.9.1 6 参重载的委托起点；6 参只在 26.9.1 单独暴露时兜底。
         */
        private static final int[] SHAPE_PREFERENCE = { 3, 4, 6 };

        private final Object engine;
        private final ClassMethod method;
        private final int arity;
        private final List<String> discovered;

        private ReloadInvoker(Object engine, ClassMethod method, int arity, List<String> discovered) {
            this.engine = engine;
            this.method = method;
            this.arity = arity;
            this.discovered = discovered;
        }

        /**
         * 找到可用重载；找不到直接抛异常并把发现到的签名列表附在消息里。
         *
         * <p>【为什么用 TabooLib 的结构分析而不是裸 {@code Class#getMethods()}】：{@code getMethods()}
         * 的返回顺序在 JVM 规范里是未定义的，所以要拿到「全部同名重载」再自己按 arity 排序；
         * TabooLib 的 {@code ReflexClass.getStructure().getMethodsMap()} 给出本类声明的全部同名重载
         * （实测在静态类与动态代理上都能枚举出 3/4/6 参重载），{@link #methodsNamed} 再把它向上扩到
         * 父类与接口，并顺带提供参数类型与返回类型，于是签名文本不必在这里另写一份。
         *
         * @throws UnsupportedOperationException 没有任何受支持的 reloadPlugin 重载
         */
        static ReloadInvoker resolve(Object engine, Class<?> engineType) {
            List<ClassMethod> candidates = methodsNamed(engineType, "reloadPlugin");
            List<String> discovered = new ArrayList<>(candidates.size());
            for (ClassMethod candidate : candidates) {
                discovered.add(signatureOf(candidate));
            }
            // 按显式偏好逐个 arity 找：不依赖同名重载的分析顺序。
            for (int arity : SHAPE_PREFERENCE) {
                for (ClassMethod candidate : candidates) {
                    if (candidate.getParameterTypes().length == arity) {
                        return new ReloadInvoker(engine, candidate, arity, List.copyOf(discovered));
                    }
                }
            }
            throw new UnsupportedOperationException(
                "CraftEngine 未提供受支持的 reloadPlugin 重载（期望 (Executor, Executor, boolean) / "
                    + "(Executor, Executor, boolean, boolean) / "
                    + "(Executor, Executor, boolean, boolean, boolean, boolean)；实际发现："
                    + (discovered.isEmpty() ? "无" : String.join(", ", discovered)) + "）");
        }

        // 【为什么删掉了这里的私有 signatureOf】它与外层类里的同名方法逐字符重复，而嵌套类里的
        // 同名声明会【遮蔽】外层方法，于是「两份实现」很容易在改动时只改一处、诊断信息悄悄走样。
        // 现在统一用外层那一份。

        Object engine() {
            return engine;
        }

        /** 命中的参数个数；用于测试与诊断，不参与调用。 */
        int arity() {
            return arity;
        }

        /** 实际存在的全部 reloadPlugin 重载签名，仅供诊断。 */
        List<String> discovered() {
            return discovered;
        }

        /**
         * 按命中形状调用 reloadPlugin，返回已归一化的 reload 结果。
         *
         * @return 已完成或未完成的 CompletableFuture；调用点只消费 {@link HudResourcePackSync.ReloadResult}
         */
        @SuppressWarnings("unchecked")
        CompletableFuture<HudResourcePackSync.ReloadResult> invoke(Executor ioExecutor, Executor mainExecutor) {
            Object[] arguments = new Object[arity];
            arguments[0] = ioExecutor;
            arguments[1] = mainExecutor;
            // 第 3 位与原项目调用一致传 false（是否重载 recipes，两代语义相同）。
            // 第 4 位在 26.x 上是「是否触发 CraftEngineReloadEvent」（字节码 iload_2 → ifeq → callReloadEvent）；
            // 0.0.67 的 3 参实现无条件触发该事件，因此这里传 true 以保持与编译依赖版本等价的行为，
            // 让监听 CE 重载事件的第三方插件照常收到通知。第 5、6 位（26.9.1 的 6 参形状）补 false。
            for (int index = 2; index < arity; index++) {
                arguments[index] = index == 3 ? Boolean.TRUE : Boolean.FALSE;
            }
            final Object raw;
            try {
                // 【语义差异，必须知道】：TabooLib 的 ClassMethod.invoke 把目标方法抛出的异常
                // 【原样抛出】（实测抛 IllegalStateException、cause 为 null），而不是像
                // java.lang.reflect.Method.invoke 那样包一层 InvocationTargetException。
                // 所以这里不能只捕 InvocationTargetException，改捕 Throwable 并把【原始异常本身】
                // 作为失败原因——对外可见结果（failed Future 携带真实 cause）与改造前完全一致。
                raw = method.invoke(engine, arguments);
            } catch (Throwable throwable) {
                return failed(throwable);
            }
            if (!(raw instanceof CompletableFuture<?> future)) {
                return failed(new IllegalStateException(
                    "CraftEngine reloadPlugin 返回类型不是 CompletableFuture：" + (raw == null ? "null" : raw.getClass().getName())));
            }
            return ((CompletableFuture<Object>) future).thenApply(ReloadInvoker::toResult);
        }

        private static <T> CompletableFuture<T> failed(Throwable throwable) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(throwable);
            return failed;
        }

        /**
         * 把 CE 的 ReloadResult（0.0.67 与 26.x 都是带 success() 的 Record）归一化成项目内部结果。
         *
         * <p>成功判定同样走反射：有 {@code success()} 就用它，没有则视为不可判定并明确失败——
         * 不伪造成功。26.x 新增的 {@code issues()} 不参与判定：本项目按 0.0.67 编译，
         * 静态调用 issues() 在 0.0.67 上不存在，而且 issues() 的语义是「解析告警数」而非
         * 「重载是否成功」，用于成功判定会改变既有语义。
         */
        private static HudResourcePackSync.ReloadResult toResult(Object result) {
            if (result == null) {
                return new HudResourcePackSync.ReloadResult(false, "无结果");
            }
            try {
                // success() 是 Record 的无参访问器，且 ReloadResult 的运行时实现类来自 CE 自己的
                // 类加载器。这里按【实例的运行时类】现查一次：结构分析本身按 Class 缓存（见 analyse），
                // 缓存键就是 Class，因此 CE 换了结果类型时会自动落到新结构，不会缓存到失效的旧类。
                Object value = firstMethod(result.getClass(), "success").invoke(result, new Object[0]);
                return new HudResourcePackSync.ReloadResult(
                    value instanceof Boolean flag && flag, result.toString());
            } catch (Throwable exception) {
                return new HudResourcePackSync.ReloadResult(false,
                    "无法判定重载结果（缺少 success()）：" + result);
            }
        }

        /**
         * 仅供同包测试直接驱动成功判定。
         *
         * <p>「没有 success() 的结果必须判为失败」这条分支在生产里只在 CE 换了结果类型时才走到，
         * 用假替身驱动比造一整个 CraftEngine 更直接，也避免把判定逻辑复制进测试。
         */
        static HudResourcePackSync.ReloadResult toResultForTest(Object result) {
            return toResult(result);
        }
    }

    /**
     * 直接 API 访问器。
     *
     * <p>刻意不写成 {@code private}：同包测试需要用「带不同 API 形状的假 CraftEngine 替身」
     * 驱动 {@code isReloading()} / {@code generateResourcePack()} / 路径读取，验证这些调用在
     * 26.x 上仍能通过反射命中（它们在三代版本里签名一致，但既然主入口已改反射，就一并锁住）。
     */
    static final class DirectEngineAccess implements EngineAccess {
        /**
         * 与 {@link CraftEngineReflection} 分开的构造入口：直接接一个引擎实例，不做 Bukkit 查询。
         * 生产路径由 {@link #resolveEngineAccess()} 用 {@code CraftEngine.instance()} 的结果调用。
         */
        static DirectEngineAccess of(Object engine) {
            return new DirectEngineAccess(engine);
        }

        private final Object engine;
        private final Class<?> engineType;
        private final ClassMethod isReloadingMethod;
        private final ClassMethod packManagerMethod;
        private final ClassMethod generateResourcePackMethod;
        private final ClassMethod resourcePackPathMethod;

        /**
         * 当前实例的 CE 版本号，仅用于把「不支持的 API」错误写成可排查的文本。
         *
         * <p>取不到时写成「版本未知」，不抛异常：版本号只是诊断信息，缺了它仍然要给出缺失的方法名。
         */
        private final String engineVersion;

        /**
         * PackManager 形状不受支持时的原因；为 null 表示形状可用。
         *
         * <p>在构造期就判出来，是为了让「不支持的 CE API」只 warning 一次（见
         * {@link #warnUnsupportedApiOnce(String)}），而不是每次保存/恢复都刷一条。
         */
        private final String unsupportedPackManagerReason;

        private DirectEngineAccess(Object engine) {
            this.engine = engine;
            this.engineType = engine.getClass();
            this.engineVersion = CraftEngineReflection.detectedVersion();
            this.isReloadingMethod = findInHierarchy(engineType, "isReloading");
            this.packManagerMethod = findInHierarchy(engineType, "packManager");
            ClassMethod generate = null;
            ClassMethod path = null;
            Object manager = null;
            if (packManagerMethod != null) {
                manager = invokeQuietly(packManagerMethod, engine);
            }
            if (manager != null) {
                generate = findInHierarchy(manager.getClass(), "generateResourcePack");
                path = findInHierarchy(manager.getClass(), "resourcePackPath");
            }
            this.generateResourcePackMethod = generate;
            this.resourcePackPathMethod = path;
            // 【为什么要在这里就把「不支持」判出来】CraftEngine 26.9.1 的 PackManager 没有
            // generateResourcePack() / resourcePackPath()（0.0.67 与 26.8.x 有）。旧实现让它们
            // 保持 null、由上层报「路径为空」，排查时看不出是版本形状不同还是配置写错；
            // 现在把原因一次算清，错误信息里带上 CE 版本号与缺失的方法名。
            if (packManagerMethod == null) {
                this.unsupportedPackManagerReason = "CraftEngine " + engineVersion
                    + " 未提供 CraftEngine#packManager()，当前只支持 0.0.67 / 26.8.x 的 PackManager 形状"
                    + "（26.9.1 需另行适配），无法生成或读取资源包路径。";
            } else if (generate == null || path == null) {
                this.unsupportedPackManagerReason = "CraftEngine " + engineVersion
                    + " 的 PackManager 未提供 "
                    + (generate == null ? "generateResourcePack()" : "")
                    + (generate == null && path == null ? " 与 " : "")
                    + (path == null ? "resourcePackPath()" : "")
                    + "，当前只支持 0.0.67 / 26.8.x 的 PackManager 形状（26.9.1 需另行适配）。";
            } else {
                this.unsupportedPackManagerReason = null;
            }
        }

        /** 形状不受支持时的原因（带 CE 版本号）；形状可用时为 null。 */
        String unsupportedReason() {
            return unsupportedPackManagerReason;
        }

        @Override
        public boolean isReloading() {
            Object value = invokeQuietly(isReloadingMethod, engine);
            return value instanceof Boolean flag && flag;
        }

        @Override
        public CompletableFuture<HudResourcePackSync.ReloadResult> reload(Executor ioExecutor,
                                                                            Executor mainExecutor) {
            // 每次 reload 都重新按【当前实例的运行时类】解析一次重载：CraftEngine 自身 reload 可能
            // 重建实例或换掉实现类，缓存到字段会让我们拿着过期方法反复失败。分析结果本身按 Class
            // 缓存（见 analyse），所以这里的「重新解析」只是一次结构查找，不是重新扫一遍类。
            final ReloadInvoker invoker;
            try {
                invoker = ReloadInvoker.resolve(engine, engineType);
            } catch (RuntimeException exception) {
                return failedFuture(exception);
            }
            return invoker.invoke(ioExecutor, mainExecutor);
        }

        @Override
        public void generateResourcePack() throws Exception {
            if (generateResourcePackMethod == null) {
                throw new UnsupportedOperationException(unsupportedPackManagerReason
                    == null ? "CraftEngine PackManager 未提供 generateResourcePack()，无法生成资源包。"
                    : unsupportedPackManagerReason);
            }
            Object manager = invokeChecked(packManagerMethod, engine);
            invokeChecked(generateResourcePackMethod, manager);
        }

        @Override
        public Path generatedPackPath() {
            // 【勘误：这里从「返回 null」改成「抛异常」】旧实现返回 null，上层只会报一句
            // 「路径为空」，无法区分「CE 版本形状不同」与「配置写错」。按本轮要求，缺失时
            // 抛带 CE 版本号与缺失方法名的明确错误，让失败信息本身可排查。
            if (resourcePackPathMethod == null) {
                throw new UnsupportedOperationException(unsupportedPackManagerReason == null
                    ? "CraftEngine PackManager 未提供 resourcePackPath()，无法读取生成路径。"
                    : unsupportedPackManagerReason);
            }
            Object manager = invokeQuietly(packManagerMethod, engine);
            Object value = invokeQuietly(resourcePackPathMethod, manager);
            if (value instanceof Path path) {
                return path;
            }
            // 方法存在但没给出 Path：这不是「不支持」，而是 CE 真的没返回路径，交给上层统一报错。
            return null;
        }

        @Override
        public Path uploadPackPath() {
            // Config 与 CraftEngine 同属 compileOnly；缺失 fileToUpload() 时抛带版本号的明确错误，
            // 不静默返回 null（26.9.1 上该方法不存在，见类头部的勘误说明）。
            try {
                Class<?> config = Class.forName(CONFIG_CLASS, false, CraftEngineReflection.loader());
                Object value = firstMethod(config, "fileToUpload").invokeStatic(new Object[0]);
                return value instanceof Path path ? path : null;
            } catch (Throwable exception) {
                throw new UnsupportedOperationException(
                    "CraftEngine " + engineVersion + " 未提供 " + CONFIG_CLASS + "#fileToUpload()"
                        + "（当前只支持 0.0.67 / 26.8.x 的形状；26.9.1 需另行适配），"
                        + "无法校验上传资源包路径。", exception);
            }
        }
    }

    /**
     * 在类及其父类里查找无参方法；找不到返回 null，由各调用点明确处理。
     *
     * <p>【为什么还要自己逐层向上爬，而不是一次分析就够】：TabooLib 的 {@code getStructure()}
     * 只描述【该类自己声明】的成员，继承来的 {@code packManager} / {@code generateResourcePack}
     * 等要在父类里继续找；{@code getMethodByTypeSilently} 之类带查找语义的入口会抛异常而不是
     * 返回 null，而这里的调用契约是「找不到返回 null 由上层决定降级」，所以保留逐层查找的
     * 显式形状，只把「在某一层取无参方法」换成 TabooLib 的按类型查询。
     *
     * <p>【勘误：这里的匹配不是「精确」的】{@code getMethodByTypeSilently} 对形参用
     * {@code isAssignableFrom}，属【宽松匹配】（形参为 Object 时任何实参类型都能命中），
     * 不是签名全等。本方法只取【零参】方法，宽松与否不会改变结果；但若将来要按参数类型取
     * 有参 CE 方法，必须显式比较签名，不能依赖这里「精确」的旧措辞。
     */
    private static ClassMethod findInHierarchy(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            // 零参方法用空类型列表查；同名同 arity 的方法在一层里至多一个，结果确定。
            ClassMethod found = analyse(current).getMethodByTypeSilently(name, false, false, new Class<?>[0]);
            if (found != null) {
                return found;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object invokeQuietly(ClassMethod method, Object target) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return method.invoke(target, new Object[0]);
        } catch (Throwable exception) {
            return null;
        }
    }

    private static Object invokeChecked(ClassMethod method, Object target) throws Exception {
        if (method == null || target == null) {
            throw new UnsupportedOperationException("CraftEngine API 缺失：" + method);
        }
        try {
            return method.invoke(target, new Object[0]);
        } catch (Exception exception) {
            // TabooLib 的 ClassMethod.invoke 不包 InvocationTargetException，异常原样抛出；
            // 方法签名要求这里抛 Exception，业务异常（含 CE 自己抛的受检异常包装）继续向上抛。
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable);
        }
    }
}
