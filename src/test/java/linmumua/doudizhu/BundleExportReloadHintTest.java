package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 材质导出成功时必须提示还要重载 CraftEngine。
 *
 * <p>为什么这句话不能省：材质落进 CraftEngine 的 resources 目录不等于客户端能拿到。
 * CraftEngine 是在自己启动时打 {@code resource_pack.zip} 的，而它按 paper-plugin.yml 的
 * {@code load: BEFORE} 先于 MUZ 加载，所以 MUZ 这一批文件进的是「已经打完包」的目录。
 * 不重载 CraftEngine，客户端下载到的仍是旧 zip。
 *
 * <p>缺了这句提示的后果特别难查：启动摘要是绿色的「材质已同步 131/437 个」，看着完全成功，
 * 客户端却是一片豆腐块，控制台没有任何错误。管理员会去翻贴图、翻字形注册、翻命名空间，
 * 而真正要做的只是重载一次。
 *
 * <p>只在 EXPORTED 这一档提示。UP_TO_DATE 说明本来就没拷东西，加提示只是噪音。
 */
class BundleExportReloadHintTest {
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");

    /**
     * 导出成功那一档必须同时说明重载与重新下载两件事。
     *
     * <p>失败条件：把提示删掉，或只说重载不说客户端重新下载。
     * 后者同样会卡住——CraftEngine 重载后 zip 更新了，但客户端缓存着旧包，
     * 不重新下载照旧是豆腐块。
     */
    @Test
    void exportedStateTellsUserToReloadAndRedownload() throws IOException {
        String source = Files.readString(PLUGIN);
        int exported = source.indexOf("case EXPORTED -> \"材质已同步 \"");
        assertTrue(exported >= 0, "找不到 EXPORTED 那一档的摘要文案，这条测试的锚点已失效");
        String tail = source.substring(exported, Math.min(source.length(), exported + 400));
        assertTrue(tail.contains("重载 CraftEngine"),
            "导出成功没提示重载 CraftEngine：客户端拿到的仍是旧 zip，字形是豆腐块，"
                + "而摘要却是绿色的已同步，会把排查带向错误方向");
        assertTrue(tail.contains("重新下载"),
            "没提示客户端重新下载资源包：只重载 CraftEngine 不够，客户端缓存着旧包");
    }

    /**
     * 手动 reload 必须强制重拷，不能被指纹挡掉。
     *
     * <p>这条决定了「要不要重启第二次」。{@code /muz reload} 走
     * {@code ensureBundleReady(reason, force)}，force 传 true 时绕过指纹比对全量重拷，
     * 效果和重启时的导出等价，管理员就不必为了推一批材质去重启整台服。
     *
     * <p>失败条件：把 force 改成 false。那时指纹一致就直接跳过，
     * 管理员跑了 reload、看到「材质已是最新」，却发现客户端还是豆腐块——
     * 因为该拷的文件一个都没动。这种失败没有任何报错，只能靠重启撞开。
     */
    @Test
    void manualReloadForcesAFullRecopyInsteadOfTrustingTheFingerprint() throws IOException {
        String source = Files.readString(PLUGIN);
        int call = source.indexOf("craftEngineBundleExporter.ensureBundleReady(");
        assertTrue(call >= 0, "找不到手动 reload 的导出调用，这条测试的锚点已失效");
        String args = source.substring(call, Math.min(source.length(), call + 200));
        assertTrue(args.contains("\"manual-reload\""),
            "手动 reload 的 reason 变了，确认这里仍是管理员触发的那条路径");
        assertTrue(args.contains("true"),
            "手动 reload 没有强制重拷：指纹一致时会跳过，管理员会看到「已是最新」"
                + "却仍然一片豆腐块，且没有任何报错指向成因");
    }

    /**
     * UP_TO_DATE 不该跟着加提示。
     *
     * <p>那一档意味着指纹一致、一个文件都没拷，此时喊重载纯属噪音，
     * 每次正常启动都刷一遍反而会让真正需要重载的那次被忽略。
     */
    @Test
    void upToDateStateStaysQuiet() throws IOException {
        String source = Files.readString(PLUGIN);
        int upToDate = source.indexOf("case UP_TO_DATE -> \"材质已是最新\"");
        assertTrue(upToDate >= 0, "找不到 UP_TO_DATE 那一档的摘要文案");
        String line = source.substring(upToDate, Math.min(source.length(), upToDate + 120));
        assertTrue(!line.contains("重载 CraftEngine"),
            "没拷任何文件也喊重载：每次启动都刷这句，真正需要重载的那次会被当成噪音忽略");
    }
}
