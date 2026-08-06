package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.junit.jupiter.api.Test;

/**
 * 行动栏头像必须使用客户端原生 player_head 对象，而不是把玩家名伪装成头像。
 */
class VersionCompatPlayerHeadTest {
    @Test
    void playerProfileBecomesNativeHeadObjectWithHatLayer() {
        UUID playerId = UUID.fromString("c2b73fa8-6703-3d89-943d-7cc09d51fc9a");

        Component component = VersionCompat.createPlayerHeadComponent(playerId, "NativeProbe");
        ObjectComponent object = assertInstanceOf(ObjectComponent.class, component);
        PlayerHeadObjectContents contents = assertInstanceOf(PlayerHeadObjectContents.class, object.contents());

        assertEquals(playerId, contents.id());
        assertEquals("NativeProbe", contents.name());
        assertTrue(contents.hat(), "截图里的头发、帽子和耳朵依赖第二皮肤层");
        assertEquals(TextDecoration.State.FALSE, component.decoration(TextDecoration.ITALIC));
    }

    @Test
    void authenticatedSkinPropertiesAreEmbeddedWithoutLosingUuidIdentity() {
        UUID playerId = UUID.fromString("c2b73fa8-6703-3d89-943d-7cc09d51fc9a");
        PlayerHeadObjectContents.ProfileProperty texture = PlayerHeadObjectContents.property(
            "textures",
            "signed-texture-value",
            "texture-signature"
        );

        Component component = VersionCompat.createPlayerHeadComponent(
            playerId,
            "NativeProbe",
            builder -> builder.profileProperty(texture)
        );
        ObjectComponent object = assertInstanceOf(ObjectComponent.class, component);
        PlayerHeadObjectContents contents = assertInstanceOf(PlayerHeadObjectContents.class, object.contents());

        assertEquals(playerId, contents.id());
        assertEquals("NativeProbe", contents.name());
        assertEquals(java.util.List.of(texture), contents.profileProperties());
        assertTrue(contents.hat());
    }

    @Test
    void missingProfileFallsBackToVisibleQuestionMark() {
        Component component = VersionCompat.createPlayerHeadComponent(null, " ");
        TextComponent text = assertInstanceOf(TextComponent.class, component);

        assertEquals("?", text.content());
    }

    /**
     * 真人头像必须显式设为白色，避免被父节点颜色乘法染色。
     *
     * player_head 对象组件与位图字形一样，白色像素会被完全染成文本颜色。
     * 当头像作为子节点嵌入带颜色的父 Component（例如行动栏的 GRAY、叫分阶段的
     * AQUA）时，如果头像自身没有显式颜色，就会继承父节点颜色，导致皮肤被单色覆盖。
     * 显式设 WHITE（乘以 1.0 = 不染色）是唯一正确的保护方式。
     */
    @Test
    void playerHeadIsExplicitlyWhiteSoItIsNotTintedByParentColor() {
        UUID playerId = UUID.fromString("c2b73fa8-6703-3d89-943d-7cc09d51fc9a");

        Component component = VersionCompat.createPlayerHeadComponent(playerId, "TintProbe");

        assertEquals(
            NamedTextColor.WHITE,
            component.color(),
            "真人头像没有显式白色，嵌入 GRAY/AQUA 等父节点时会被乘法染色"
        );
    }

    /**
     * 真人头像必须关掉粗体和斜体，避免客户端拉伸变形。
     *
     * 与位图字形图标一样，粗体会横向拉伸一像素，斜体会切歪；
     * 对齐 PackAssets.botAvatarIcon 的 !BOLD, !ITALIC 处理方式。
     */
    @Test
    void playerHeadDisablesBoldAndItalicToPreventDistortion() {
        UUID playerId = UUID.fromString("c2b73fa8-6703-3d89-943d-7cc09d51fc9a");

        Component component = VersionCompat.createPlayerHeadComponent(playerId, "StyleProbe");

        assertEquals(
            TextDecoration.State.FALSE,
            component.decoration(TextDecoration.BOLD),
            "真人头像没有关掉粗体，客户端会横向拉伸头像"
        );
        assertEquals(
            TextDecoration.State.FALSE,
            component.decoration(TextDecoration.ITALIC),
            "真人头像没有关掉斜体，客户端会把头像切歪"
        );
    }

    /**
     * 带皮肤属性的头像也必须有颜色保护。
     *
     * createPlayerHeadComponent 的三参数重载用于已登录玩家，携带已缓存的皮肤属性，
     * 它和两参数重载共用同一个构造路径，但仍需显式验证，防止未来重构时遗漏。
     */
    @Test
    void authenticatedHeadAlsoCarriesWhiteColorProtection() {
        UUID playerId = UUID.fromString("c2b73fa8-6703-3d89-943d-7cc09d51fc9a");
        PlayerHeadObjectContents.ProfileProperty texture = PlayerHeadObjectContents.property(
            "textures",
            "signed-texture-value",
            "texture-signature"
        );

        Component component = VersionCompat.createPlayerHeadComponent(
            playerId,
            "AuthTintProbe",
            builder -> builder.profileProperty(texture)
        );

        assertEquals(
            NamedTextColor.WHITE,
            component.color(),
            "带皮肤属性的头像也必须显式白色，否则同样会被父节点染色"
        );
        assertEquals(
            TextDecoration.State.FALSE,
            component.decoration(TextDecoration.BOLD),
            "带皮肤属性的头像也必须关掉粗体"
        );
    }
}
