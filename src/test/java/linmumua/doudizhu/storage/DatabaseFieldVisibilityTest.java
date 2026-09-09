package linmumua.doudizhu.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * DatabaseManager 的跨线程字段必须是 volatile。
 *
 * <p>【为什么这是真问题而不是洁癖】：写库操作已经下沉到异步线程
 * （见 DatabaseWriteIsAsyncTest）。异步体会读 {@code initialized} 判断能不能写，
 * 又会经 openConnection() 读 {@code config} 取连接参数。
 * 这两个字段都由主线程在 initialize() 里写。
 *
 * <p>没有 volatile 时，JMM 不保证异步线程看得到主线程的写入，可能出现：
 * <ul>
 *   <li>看到 {@code initialized == true} 但 {@code config} 仍是 null → 写库线程 NPE</li>
 *   <li>关服后仍看到旧的 {@code true} → 去开一条本该关闭的连接</li>
 * </ul>
 *
 * <p>用反射断言而不是只读源码文本：反射查的是编译后的真实修饰符，
 * 源码里写没写、有没有被格式化工具挪走，都骗不过它。
 */
class DatabaseFieldVisibilityTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/storage/DatabaseManager.java");

    /** 这三个字段主线程写、其他线程读。 */
    private static final String[] CROSS_THREAD_FIELDS = {"config", "initialized", "status"};

    @Test
    void crossThreadFieldsAreVolatile() throws NoSuchFieldException {
        for (String name : CROSS_THREAD_FIELDS) {
            Field field = DatabaseManager.class.getDeclaredField(name);
            assertTrue(
                Modifier.isVolatile(field.getModifiers()),
                "字段 " + name + " 不是 volatile。异步写库线程读它时，"
                    + "JMM 不保证能看到主线程 initialize() 里的写入"
            );
        }
    }

    /**
     * config 的赋值必须早于 initialized = true。
     *
     * <p>这条比 volatile 本身更关键：两个字段都 volatile 时，
     * 「先写 config，后写 initialized」配合「先读 initialized，后读 config」
     * 才构成 happens-before —— 任何看到 initialized == true 的线程
     * 都保证看到已经发布完整的 config。
     *
     * <p>失败条件：有人把 initialized = true 挪到 config 赋值之前，
     * 那样即便都是 volatile，异步线程仍可能读到 null config。
     */
    @Test
    void configIsPublishedBeforeInitializedFlag() throws IOException {
        String body = methodBody(Files.readString(MANAGER), "public boolean initialize()");

        int configAssign = body.indexOf("config = SqlConfig.fromConfig");
        int flagAssign = body.indexOf("initialized = true");

        assertTrue(configAssign >= 0, "找不到 config 赋值，这条测试的锚点已失效");
        assertTrue(flagAssign >= 0, "找不到 initialized = true，锚点已失效");
        assertTrue(
            configAssign < flagAssign,
            "initialized 被置为 true 的时候 config 还没赋值。"
                + "异步写库线程会读到 null config 直接 NPE"
        );
    }

    /**
     * 失败路径必须把 initialized 置回 false。
     *
     * <p>连接失败却留着 true，异步写库会拿着不可用的配置反复尝试，
     * 每次结算都在异步线程刷一条异常日志。
     */
    @Test
    void initializationFailureResetsTheFlag() throws IOException {
        String body = methodBody(Files.readString(MANAGER), "public boolean initialize()");
        int catchAt = body.indexOf("catch (Exception");

        assertTrue(catchAt >= 0, "initialize 没有异常分支");
        assertTrue(
            body.substring(catchAt).contains("initialized = false"),
            "初始化失败后没有把 initialized 置回 false，"
                + "异步写库会拿着不可用配置反复失败"
        );
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
