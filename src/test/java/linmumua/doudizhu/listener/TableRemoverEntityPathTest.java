package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 拆桌棍必须在"右键实体"这条路径上也能用。
 *
 * 真实症状：拆桌棍完全没反应，连动作条提示都不弹。
 *
 * 因果链：准星落在桌子家具上时，客户端只发实体交互包，
 * {@code PlayerInteractEvent} 根本不触发，所以 {@code onUseTablePlacer} 那条
 * 认拆桌棍的分发进不去；而 {@code onInteract} 里的保护实体判定
 * （桌子家具经 {@code matchesExpectedFurnitureEntity} 判定为保护实体）
 * 会直接 setCancelled，于是右键被吃掉、没有任何反馈。
 *
 * 桌椅几何修正（补上 SUPPORT_SURFACE_LIFT）之前，家具是陷在地板里的，
 * 准星多半打在方块上、走方块路径，所以这个缺陷被掩盖了；
 * 家具浮到地面之上后，实体路径成为常态，缺陷才暴露。
 *
 * 用源码扫描而不是调用方法：这段逻辑依赖 Bukkit 的事件与 Player，
 * 这个项目跑不起 Bukkit。写法沿用 OccupiedChairHitboxVisibilityTest。
 */
class TableRemoverEntityPathTest {
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/WorldTableInteractionListener.java");

    /**
     * 实体交互路径必须先认拆桌棍，再走保护判定。
     * 失败条件：把 handleTableRemoverOnEntity 的调用删掉，或挪到
     * shouldCancelProtectedInteract 之后——那样保护判定会先把事件吃掉，
     * 拆桌棍重新变成完全没反应。
     */
    @Test
    void entityInteractChecksRemoverBeforeProtectionCancels() throws IOException {
        String body = methodBody("public void onInteract(PlayerInteractEntityEvent event)");

        int removerAt = body.indexOf("handleTableRemoverOnEntity");
        int protectionAt = body.indexOf("shouldCancelProtectedInteract");

        assertTrue(removerAt >= 0, "实体交互路径没有认拆桌棍，准星对着桌子右键会完全没反应");
        assertTrue(protectionAt >= 0, "保护判定不见了，这条测试的锚点已失效");
        assertTrue(
            removerAt < protectionAt,
            "拆桌棍的判定必须排在保护判定之前，否则事件会先被保护逻辑取消掉"
        );
    }

    /**
     * 拆桌棍只能在纯 INTERACT 这一侧处理。
     * 客户端一次右键会先发 INTERACT_AT 再发 INTERACT，两处都接就会把
     * "再次右键确认"的两步流程压成一步，第一次右键就直接把桌子拆了。
     * 失败条件：在 onInteractAt 里也接上拆桌棍处理。
     */
    @Test
    void removerIsHandledOnlyOnPlainInteractSoTwoStepConfirmSurvives() throws IOException {
        String atBody = methodBody("public void onInteractAt(PlayerInteractAtEntityEvent event)");

        assertTrue(
            !atBody.contains("handleTableRemoverOnEntity"),
            "onInteractAt 也接了拆桌棍，一次右键会被算成两次，两步确认失效"
        );
    }

    /**
     * 纯 INTERACT 的 guard 必须保留：它是"只处理一次"的前提。
     * 失败条件：删掉 onInteract 开头那条 PlayerInteractAtEntityEvent 提前 return。
     */
    @Test
    void plainInteractGuardKeepsTheHandlerFromRunningTwice() throws IOException {
        String body = methodBody("public void onInteract(PlayerInteractEntityEvent event)");

        assertTrue(
            body.contains("instanceof PlayerInteractAtEntityEvent"),
            "onInteract 少了 PlayerInteractAtEntityEvent 的提前 return，一次右键会被处理两次"
        );
    }

    /**
     * 失败必须给玩家反馈，不能静默。
     * 这个 bug 最难查的地方就是"没有任何提示"，所以异常必须落到动作条上。
     * 失败条件：把 handleTableRemoverOnEntity 里的 catch 去掉或改成空实现。
     */
    @Test
    void removerFailuresAreReportedInsteadOfSwallowed() throws IOException {
        String body = methodBody("private boolean handleTableRemoverOnEntity(Player player)");

        assertTrue(body.contains("catch (RuntimeException"), "没有捕获异常，拆桌失败会变成服务端报错");
        assertTrue(body.contains("sendActionBar"), "拆桌失败没有给玩家任何提示，又会退回静默失效");
    }

    private static String methodBody(String signature) throws IOException {
        String source = Files.readString(LISTENER);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
