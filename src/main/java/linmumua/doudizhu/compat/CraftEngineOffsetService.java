package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import taboolib.library.reflex.AnalyseMode;
import taboolib.library.reflex.ClassAnalyser;
import taboolib.library.reflex.ClassMethod;
import taboolib.library.reflex.ReflexClass;

/**
 * 借 CraftEngine 的负空格字形做像素级水平定位。
 *
 * <p>为什么必须借：把头像和牌排进同一行文本后，头像的第二行要回到行首才能画，
 * 这需要「负宽度的空格」。Minecraft 原版靠字体的 space provider 实现，但那要求
 * 覆写 assets/minecraft/font/default.json —— 而 default.json 已经被 CraftEngine
 * 用来注册我们的牌面与方块字形了，抢着写必然冲突。CraftEngine 自己就带了一套
 * 偏移字形（内置资源包里的 font/offset/*），并暴露成 MiniMessage 的 shift 标签，
 * 直接复用它比自己造一套安全。
 *
 * <p>拿到的是一段【标准 MiniMessage】文本（形如 {@code <font:...>某些字符</font>}），
 * 所以可以交给 Paper 自带的 MiniMessage 解析，不需要碰 CraftEngine 内部那套被
 * 重定位过的 Adventure 类（{@code craftengine.libraries.adventure.*} 和插件用的
 * {@code net.kyori.adventure.*} 是两个不同的类，Component 没法直接互传）。
 *
 * <p>整个类是「取不到就降级」的：CraftEngine 缺失或换了内部结构时不抛异常，
 * 只是偏移变成空串（头像会挤成一坨而不是整个功能崩掉），并且只警告一次。
 */
/**
 * 借 CraftEngine 的负空格字形做像素级水平定位。
 *
 * <p>【为什么不再是 final】{@link #fontManagerClass(ClassLoader)} 是本类唯一无法在单测里复现的
 * 一步（替身插件给不出自定义类加载器，见该方法的说明）。放开继承只为了让测试覆写这一个入口，
 * 没有其它子类；生产代码不依赖「不可继承」这一性质。
 */
public class CraftEngineOffsetService {
    private final DoudizhuPlugin plugin;

    /**
     * 已解析的类结构缓存：{@code Class} → 结构分析结果。
     *
     * <p>渲染路径会反复调用 {@link #offset(int)}，每次都重做全类分析没有必要。键用 {@code Class}
     * 而不是类名：CraftEngine reload 换掉实现类后 {@code Class} 实例本身就不同，缓存天然不命中旧结构。
     * 用 {@code ConcurrentHashMap} 是因为 CE 的重载与渲染可能来自不同线程。
     */
    private static final ConcurrentHashMap<Class<?>, ReflexClass> ANALYSED_CLASSES = new ConcurrentHashMap<>();

    /** CraftEngine 的 FontManager 实例，反射拿到后一直复用。 */
    private Object fontManager;

    /**
     * {@code FontManager.createMiniMessageOffsets(int)}，把像素偏移量转成 MiniMessage。
     *
     * <p>【为什么用 TabooLib 的 {@link ClassMethod} 而不是裸 {@link java.lang.reflect.Method}】：
     * 项目约定 Java 反射一律走 TabooLib 的 reflex 工具。这里的调用形状很干净——单态
     * （无重载）、固定参数类型、非静态——正是 {@code ClassMethod} 的适用场景：
     * 它把 {@code MethodHandle} 的绑定与调用封装好，调用点不必再自己处理
     * {@code setAccessible} 与异常包装。
     *
     * <p>【语义差异，调用点必须照做】：{@code ClassMethod.invoke} 的参数匹配是【按装箱类型判等】的，
     * 不做 String→int 之类的宽松转换（实测 String 实参抛 ClassCastException）。本类的入参一直是
     * {@code Integer}，与 {@code int} 形参的装箱类型一致，因此行为与原来的
     * {@code Method.invoke(fontManager, pixels)} 完全等价。
     *
     * <p>【勘误：这里的方法查找不是「精确匹配」】{@code ReflexClass.getMethodByTypes} 内部对形参
     * 用的是 {@code isAssignableFrom}，属【宽松匹配】（例如形参是 Object 时任何实参类型都能命中），
     * 不是签名全等。本类只在「单态、无重载」的 CE 查询上用它，宽松与否不改变结果；但如果哪天
     * CE 给同一名字加了重载，必须改成按 arity + 类型逐个显式选择，不能指望它「精确」到唯一解。
     */
    private ClassMethod createMiniMessageOffsetsMethod;

    private boolean initialised;

    /** 只警告一次，避免每帧刷屏。 */
    private boolean warned;

    /**
     * 丢掉上一次的解析结果，下次取偏移时重新找 FontManager。
     *
     * <p>【为什么必须有这个入口】：{@code initialised} 只置一次，解析失败后永远不会
     * 再试。于是只要出现下面任一情况，整条 HUD 就永久消失、且只能重启服务器：
     * <ul>
     *   <li>CraftEngine 比 MUZ 晚就绪（首次解析必然拿不到 instance）</li>
     *   <li>资源包配置有错导致 CraftEngine 加载失败，修好配置后 reload</li>
     *   <li>CraftEngine 自身 reload 后重建了 FontManager</li>
     * </ul>
     * 偏移服务取不到时 {@code TrickHudService.render} 会整条 hide，玩家看到的就是
     * 「什么都没有」，而失败原因只写在控制台——没有任何线索指向这里。
     */
    public void invalidate() {
        initialised = false;
        warned = false;
        fontManager = null;
        createMiniMessageOffsetsMethod = null;
    }

    public CraftEngineOffsetService(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 取一段把光标水平移动 {@code pixels} 像素的 MiniMessage 文本。
     *
     * @param pixels 正数右移、负数左移；0 直接返回空串
     * @return 可以直接拼进 MiniMessage 的文本；CraftEngine 不可用时返回空串
     */
    public String offset(int pixels) {
        if (pixels == 0) {
            return "";
        }
        if (!initialised) {
            initialised = true;
            resolveFontManager();
        }
        if (fontManager == null || createMiniMessageOffsetsMethod == null) {
            return "";
        }
        try {
            // 显式装箱：ClassMethod 的参数是 Object 可变参数，类型必须与 int 形参的装箱类型精确一致。
            Object result = createMiniMessageOffsetsMethod.invoke(fontManager, new Object[] { Integer.valueOf(pixels) });
            return result == null ? "" : result.toString();
        } catch (Exception exception) {
            warnOnce("CraftEngine offset lookup failed: " + exception.getMessage());
            return "";
        }
    }

    /** CraftEngine 在线且偏移可用时为 true，调用方可以据此决定要不要画头像。 */
    public boolean isAvailable() {
        if (!initialised) {
            initialised = true;
            resolveFontManager();
        }
        return fontManager != null && createMiniMessageOffsetsMethod != null;
    }

    /**
     * 取运行期 FontManager 的 {@code Class}：默认就是按 CE 类加载器 {@code Class.forName}。
     *
     * <p>【为什么单独抽一个入口】它是本类唯一依赖「CE 在另一个类加载器里、必须按名加载」的一步，
     * 也正是单测没法直接复现的一步（{@code Proxy} 的 {@code getClass()} 不路由到调用处理器，
     * 所以替身插件提供不了自定义类加载器——实测）。把这一步收口成一个方法，
     * 测试就能覆盖它【后面】的全部诊断逻辑，而不必为了可测性去改 {@code resolveFontManager} 的流程。
     *
     * <p>生产行为不变：本方法就是原来那一行 {@code Class.forName}，没有旁路、没有缓存。
     */
    Class<?> fontManagerClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("net.momirealms.craftengine.core.font.FontManager", true, loader);
    }

    /**
     * 取运行期 CraftEngine 主类的 {@code Class}：默认就是按 CE 类加载器 {@code Class.forName}。
     *
     * <p>与 {@link #fontManagerClass(ClassLoader)} 同源同理，只是它取的是两个类里的第一个
     * ——单测要覆盖「取类之后」的诊断逻辑，就得能同时顶替这两步。
     *
     * <p>生产行为不变：本方法就是原来那一行 {@code Class.forName}，没有旁路、没有缓存。
     */
    Class<?> craftEngineClass(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine", true, loader);
    }

    private void resolveFontManager() {
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            warnOnce("CraftEngine is not enabled, avatar offsets are disabled.");
            return;
        }
        try {
            ClassLoader loader = craftEngine.getClass().getClassLoader();
            // 【为什么这里仍然是 Class.forName 而不是 TabooLib】：CraftEngine 是 compileOnly，
            // 它的类在 MUZ 自己的 ClassLoader 里【根本看不到】，必须先按 CE 插件类加载器把
            // Class 对象取回来。initialise 保持 true（与改造前一致）：CE 的 instance() 依赖
            // 类静态初始化完成，改 false 会引入行为差异。TabooLib 的 ReflexClass 接受的是一个
            // 已经拿到的 Class<?>，它不替代类加载这一步。
            Class<?> craftEngineClass = craftEngineClass(loader);
            Class<?> fontManagerClass = fontManagerClass(loader);
            // 三个调用点都是「单态、无重载、参数形状固定」的查询，用 TabooLib 的结构分析取方法，
            // 再交给 ClassMethod 调用。REFLECTION_ONLY 是按项目现状选的：CE 的类来自另一个插件
            // 类加载器，ASM 分析需要把类当资源读出来，跨加载器不保证可行；纯反射分析没有这个前提。
            // instance() / fontManager() 都是无参访问器，用会向上查找父类与接口的 firstMethod 取；
            // 不能直接用 getStructure().getMethodsMap()，那只描述【该类自己声明】的成员，
            // 而且同名重载的顺序没有语义保证。
            Object instance = firstMethod(craftEngineClass, "instance").invokeStatic(new Object[0]);
            if (instance == null) {
                warnOnce("CraftEngine instance is not ready, avatar offsets are disabled.");
                return;
            }
            Object manager = firstMethod(craftEngineClass, "fontManager").invoke(instance, new Object[0]);
            if (manager == null) {
                warnOnce("CraftEngine font manager is missing, avatar offsets are disabled.");
                return;
            }
            // 按「方法名 + 参数类型」取（见 getMethodByTypes 的宽松匹配说明：它不是签名全等）。
            // 【勘误：这里的“取不到”不是返回 null，而且 Java 侧捕不到具体类型】
            // 实测 common-reflex 6.3.0 两件事：
            //   1) 找不到方法时它【抛 NoSuchMethodException】，不是返回 null；
            //   2) ReflexClass 是 Kotlin 类，getMethodByTypes 的字节码签名【没有 throws 子句】，
            //      于是 javac 认为它不抛受检异常——直接写 catch (NoSuchMethodException e) 会得到
            //      「在相应的 try 语句主体中不能抛出异常错误」而【编译不过】（实测）。
            // 所以这里只能捕 Exception，再用异常的实际类型确认是不是「方法缺失」。
            // 不做这一步的话，异常会冒泡到本方法尾部那个既有 catch (Exception)，告警退化成通用的
            // 「offset service unavailable」；而 NoSuchMethodException 的 message 本身可以是 null，
            // 于是日志里连一句可读的原因都没有——这正是现场「HUD 整条消失却查不出原因」的来源。
            try {
                createMiniMessageOffsetsMethod =
                    analyse(fontManagerClass).getMethodByTypes("createMiniMessageOffsets", false, false, int.class);
            } catch (Exception failure) {
                // 【只处理「方法缺失」这一条路径】不是 NoSuchMethodException 时【原样上抛】，
                // 交给外层既有的通用告警与降级处理；绝不把它误报成「CE 没提供这个方法」。
                if (!(failure instanceof NoSuchMethodException)) {
                    throw failure;
                }
                // 就地诊断（而不是让它冒泡到外层 catch）：外层拿不到 fontManagerClass，且它的告警文案
                // 会把这条「形状不匹配」混进通用的「偏移服务不可用」，诊断价值归零。
                // 只读诊断，不改判定语义：createMiniMessageOffsetsMethod 保持 null，
                // isAvailable() 与 offset() 的降级路径与本改动前完全一致（仍返回空串、仍只警告一次）。
                warnOnce("CraftEngine FontManager 未提供 createMiniMessageOffsets(int)，头像偏移不可用；实际发现："
                    + describeMethodsNamed(fontManagerClass, "createMiniMessageOffsets")
                    + "；FontManager 实际声明的方法：" + describeAllMethods(fontManagerClass));
                return;
            }
            fontManager = manager;
        } catch (Exception exception) {
            // 【保留 message 为 null 的事实】NoSuchMethodException 的 message 可以为 null，
            // 直接拼接会写出「unavailable: null」——比不写更误导，因此这里显式区分。
            warnOnce("CraftEngine offset service unavailable: "
                + (exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage()));
        }
    }


    /**
     * 汇总某个类自身、父类与接口上全部同名方法的签名；没有则返回「无」。
     *
     * <p>与 {@link #firstMethod} 同源地沿继承链向上走：CE 把访问器挪到父类/接口时，
     * 只看本类声明会误报「无」，而这个诊断的全部价值就在于如实反映运行期到底有什么。
     */
    private static String describeMethodsNamed(Class<?> type, String name) {
        List<String> signatures = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (ClassMethod method : methodsDeclaredIn(current, name)) {
                signatures.add(signatureOf(method));
            }
            current = current.getSuperclass();
        }
        return signatures.isEmpty() ? "无" : String.join(", ", signatures);
    }

    /**
     * 列出类自身声明的全部方法签名，按名字与 arity 排序（诊断信息必须确定，不依赖枚举顺序）。
     *
     * <p>为什么连整张方法表都列：CE 若把方法改名（例如改成 {@code miniMessageOffsets}），
     * 只报「无同名方法」仍然查不出正确名字；把实际声明的方法表放进日志，
     * 维护者一眼就能看出它变成了什么。
     */
    private static String describeAllMethods(Class<?> type) {
        List<String> signatures = new ArrayList<>();
        for (List<ClassMethod> overloads : analyse(type).getStructure().getMethodsMap().values()) {
            for (ClassMethod method : overloads) {
                signatures.add(signatureOf(method));
            }
        }
        signatures.sort(Comparator.naturalOrder());
        return signatures.isEmpty() ? "无" : String.join(", ", signatures);
    }

    /** 方法签名文本，用于诊断（形参类型 + 返回类型）。 */
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
     * 按纯反射方式分析一个类结构（按 Class 缓存，见下）。
     *
     * <p>{@code REFLECTION_ONLY} 而不是默认的 {@code ASM_FIRST}：CraftEngine 的类来自它自己的
     * 插件类加载器，ASM 分析要把 {@code .class} 当资源读出来，跨加载器不保证拿得到；纯反射分析
     * 只需要 {@code Class} 对象本身，正是这里唯一能保证的前提。
     *
     * <p>【为什么要缓存】{@code offset()} 会在渲染路径上被反复调用，每次重新做一遍全类结构分析
     * 是纯粹的浪费。以 {@code Class} 为键即可：CraftEngine 自身 reload 若重建了类，
     * {@code Class.forName} 拿到的就是另一个 {@code Class} 实例，缓存自然不会命中旧结构，
     * 因此不需要按名字做失效逻辑。
     */
    private static ReflexClass analyse(Class<?> type) {
        return ANALYSED_CLASSES.computeIfAbsent(type, key -> new ReflexClass(
            ClassAnalyser.INSTANCE.analyse(key, AnalyseMode.REFLECTION_ONLY), AnalyseMode.REFLECTION_ONLY));
    }

    /**
     * 取指定名字的【无参】方法，并向上查找父类与接口。
     *
     * <p>【为什么不直接用 {@code getStructure().getMethodsMap()}】它只描述【该类自己声明】的成员，
     * 父类/接口声明的访问器取不到；而且它的同名重载列表顺序没有语义保证，
     * {@code get(0)} 等于依赖枚举顺序。这里改为「从目标类开始逐层向上找、按参数个数筛」，
     * 同一层里同名同 arity 只可能存在一个方法，因此结果是确定的；最派生的声明优先。
     */
    private static ClassMethod firstMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            List<ClassMethod> methods = methodsDeclaredIn(current, name);
            for (ClassMethod method : methods) {
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

    private void warnOnce(String message) {
        if (warned) {
            return;
        }
        warned = true;
        plugin.getLogger().warning(message);
    }
}
