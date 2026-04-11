package dev.codex.doudizhu.world;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.assets.PackAssets;
import dev.codex.doudizhu.game.SimpleBotBrain;
import dev.codex.doudizhu.game.GamePhase;
import dev.codex.doudizhu.game.GameTable;
import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.DoudizhuCard;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class PhysicalTableManager {
    // 桌椅与按钮属于世界里的公共实体；手牌和个人按钮则是按玩家隐藏/显示的私有实体
    private static final float TABLE_SCALE = 2.25f;
    private static final float CHAIR_SCALE = 1.35f;
    private static final float CARD_LABEL_Y = 0.34f;
    private static final float SMALL_TEXT_SCALE = 0.46f;
    private static final float STATUS_TEXT_SCALE = 0.72f;
    private static final float LABEL_TEXT_SCALE = 0.40f;

    private final DoudizhuPlugin plugin;
    private final Map<String, PlacedTable> placedTables = new LinkedHashMap<>();
    private final Map<UUID, ActionBinding> actionBindings = new LinkedHashMap<>();
    private final Map<UUID, CardBinding> cardBindings = new LinkedHashMap<>();
    private final Map<UUID, SeatBinding> seatBindings = new LinkedHashMap<>();
    private final Map<UUID, Integer> hintIndices = new LinkedHashMap<>();

    public PhysicalTableManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public GameTable placeNewTable(Player owner, String name) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这个牌桌已经有实体桌面了。");
        }
        if (plugin.getTableManager().getTableOf(owner) != null) {
            throw new IllegalArgumentException("你已经在另一个牌桌里了。");
        }
        GameTable table = plugin.getTableManager().getTable(name);
        if (table == null) {
            table = plugin.getTableManager().createTable(name);
        }
        Location anchor = owner.getLocation().getBlock().getLocation().add(0.5, plugin.getTableSpawnOffsetY(), 0.5);
        placedTables.put(key, spawnTable(table, anchor, snappedYaw(owner.getLocation().getYaw())));
        refresh(table);
        return table;
    }

    public boolean isPlaced(String tableName) {
        return placedTables.containsKey(normalize(tableName));
    }

    public void rebuildAllTables() {
        Map<String, PlacedTable> snapshot = new LinkedHashMap<>(placedTables);
        placedTables.clear();
        for (Map.Entry<String, PlacedTable> entry : snapshot.entrySet()) {
            PlacedTable previous = entry.getValue();
            cleanupPlacedTable(previous);
            GameTable table = plugin.getTableManager().getTable(previous.tableName());
            if (table == null) {
                continue;
            }
            PlacedTable rebuilt = spawnTable(table, previous.anchor().clone(), previous.yaw());
            rebuilt.seatAssignments().putAll(previous.seatAssignments());
            placedTables.put(entry.getKey(), rebuilt);
            refresh(table);
        }
    }

    public void refresh(GameTable table) {
        // 统一刷新入口：桌面状态、座位信息、按钮、公共出牌区、私人手牌都在这里协同更新
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTables.get(normalize(table.getName()));
        if (placed == null) {
            return;
        }
        reconcileSeatAssignments(table, placed);
        refreshStatus(table, placed);
        refreshPlayDetail(table, placed);
        refreshSeatInfos(table, placed);
        refreshActionButtons(table, placed);
        refreshPublicTrick(table, placed);
        refreshPrivateHands(table, placed);
    }

    public void refreshPrivateHand(GameTable table, UUID playerId) {
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTables.get(normalize(table.getName()));
        if (placed == null) {
            return;
        }
        renderPrivateHand(table, placed, playerId);
    }

    public void hidePrivateEntitiesFrom(Player viewer) {
        for (PlacedTable placed : placedTables.values()) {
            for (Map.Entry<UUID, List<UUID>> entry : placed.privateEntitiesByPlayer().entrySet()) {
                if (entry.getKey().equals(viewer.getUniqueId())) {
                    continue;
                }
                for (UUID entityId : entry.getValue()) {
                    Entity entity = Bukkit.getEntity(entityId);
                    if (entity != null) {
                        viewer.hideEntity(plugin, entity);
                    }
                }
            }
        }
    }

    public boolean handleInteraction(Player player, Entity entity) {
        CardBinding cardBinding = cardBindings.get(entity.getUniqueId());
        if (cardBinding != null) {
            if (!cardBinding.ownerId().equals(player.getUniqueId())) {
                return true;
            }
            GameTable table = plugin.getTableManager().getTable(cardBinding.tableName());
            if (table != null) {
                boolean wasSelected = table.getSelection(player.getUniqueId()).contains(cardBinding.cardId());
                table.toggleSelection(player.getUniqueId(), cardBinding.cardId());
                PlacedTable placed = placedTables.get(normalize(table.getName()));
                if (placed != null) {
                    updatePrivateSelection(table, placed, player.getUniqueId());
                    updateBacksideSelection(table, placed, player.getUniqueId());
                    refreshPublicTrick(table, placed);
                }
                if (plugin.isSelectionSoundEnabledFor(player.getUniqueId())) {
                    playSelectionSound(player, !wasSelected);
                }
            }
            return true;
        }

        ActionBinding binding = actionBindings.get(entity.getUniqueId());
        if (binding != null) {
            GameTable table = plugin.getTableManager().getTable(binding.tableName());
            if (table == null) {
                return true;
            }

            try {
                switch (binding.action()) {
                    case JOIN -> joinSeat(table, placedTables.get(normalize(table.getName())), player, binding.seatIndex());
                    case READY -> table.toggleReady(player);
                    case START -> table.startRound(player);
                    case STATUS -> hint(player, "查看桌面上方状态面板。", NamedTextColor.YELLOW);
                    case LEAVE -> plugin.getTableManager().leaveTable(player);
                    case PLAY_SELECTED -> table.playSelected(player);
                    case PASS_TURN -> table.pass(player);
                    case HINT_PLAY -> applyHint(table, player);
                    case CLEAR_SELECTION -> {
                        table.clearSelection(player.getUniqueId());
                        refreshPrivateHand(table, player.getUniqueId());
                        hint(player, "已清除已选牌。", NamedTextColor.GRAY);
                    }
                    case OPEN_SETTINGS -> {
                        plugin.getHandGuiService().openSettings(player);
                        hint(player, "已打开你的个人微调菜单。", NamedTextColor.GREEN);
                    }
                    case BID_0 -> table.bid(player, 0);
                    case BID_1 -> table.bid(player, 1);
                    case BID_2 -> table.bid(player, 2);
                    case BID_3 -> table.bid(player, 3);
                }
                refresh(table);
            } catch (RuntimeException exception) {
                hint(player, exception.getMessage(), NamedTextColor.RED);
            }
            return true;
        }

        SeatBinding seatBinding = seatBindings.get(entity.getUniqueId());
        if (seatBinding == null) {
            return false;
        }
        Entity mountEntity = Bukkit.getEntity(seatBinding.mountId());
        if (!(mountEntity instanceof ArmorStand seatMount)) {
            return true;
        }
        if (player.getVehicle() != null && player.getVehicle().getUniqueId().equals(seatMount.getUniqueId())) {
            player.leaveVehicle();
            hint(player, "你起身了。", NamedTextColor.YELLOW);
            return true;
        }
        if (!seatMount.getPassengers().isEmpty()) {
            hint(player, "这个椅子已经有人坐了。", NamedTextColor.RED);
            return true;
        }
        seatMount.addPassenger(player);
        hint(player, "你坐下了。", NamedTextColor.GREEN);
        return true;
    }

    public boolean handleAttack(Player player, Entity entity) {
        CardBinding cardBinding = cardBindings.get(entity.getUniqueId());
        if (cardBinding == null) {
            return false;
        }
        if (!cardBinding.ownerId().equals(player.getUniqueId())) {
            return true;
        }
        GameTable table = plugin.getTableManager().getTable(cardBinding.tableName());
        if (table == null) {
            return true;
        }
        try {
            Set<Integer> selection = table.getSelection(player.getUniqueId());
            if (selection.isEmpty()) {
                hint(player, "请先右键选择要出的牌。", NamedTextColor.YELLOW);
                return true;
            }
            if (!selection.contains(cardBinding.cardId())) {
                hint(player, "请左键点击已选中的牌来出牌。", NamedTextColor.YELLOW);
                return true;
            }
            table.playSelected(player);
            refresh(table);
        } catch (RuntimeException exception) {
            hint(player, exception.getMessage(), NamedTextColor.RED);
            refresh(table);
        }
        return true;
    }

    public void removeTable(String tableName) {
        GameTable table = plugin.getTableManager().getTable(tableName);
        if (table != null && table.getSeats().stream().anyMatch(playerId -> !table.isBot(playerId))) {
            throw new IllegalStateException("请先让玩家离桌，再移除实体牌桌。");
        }
        PlacedTable placed = placedTables.remove(normalize(tableName));
        if (placed == null) {
            throw new IllegalArgumentException("这个牌桌没有实体桌面。");
        }
        cleanupPlacedTable(placed);
        if (table != null) {
            plugin.getTableManager().unregisterTable(table.getName());
        }
    }

    public void shutdown() {
        for (PlacedTable placed : new ArrayList<>(placedTables.values())) {
            cleanupPlacedTable(placed);
        }
        placedTables.clear();
        actionBindings.clear();
        cardBindings.clear();
    }

    public void tick() {
        // Intentionally left blank. Public trick display stays anchored above the table.
    }

    private void reconcileSeatAssignments(GameTable table, PlacedTable placed) {
        // 座位分配与 GameTable 的玩家列表保持同步，并去掉失效/重复绑定
        placed.seatAssignments().entrySet().removeIf(entry -> !table.contains(entry.getValue()));
        List<UUID> seen = new ArrayList<>();
        placed.seatAssignments().entrySet().removeIf(entry -> !seen.add(entry.getValue()));
        for (UUID playerId : table.getSeats()) {
            if (placed.seatAssignments().containsValue(playerId)) {
                continue;
            }
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                if (!placed.seatAssignments().containsKey(seatIndex)) {
                    placed.seatAssignments().put(seatIndex, playerId);
                    break;
                }
            }
        }
    }

    private void joinSeat(GameTable table, PlacedTable placed, Player player, Integer seatIndex) {
        // 点击哪张座位的“加入”按钮，就强制把该玩家绑定到哪个座位
        if (placed == null || seatIndex == null) {
            throw new IllegalStateException("座位信息异常。");
        }
        if (placed.seatAssignments().containsKey(seatIndex)) {
            throw new IllegalStateException("这个座位已经有人了。");
        }
        if (plugin.getTableManager().getTableOf(player) != null) {
            throw new IllegalStateException("你已经在别的牌桌里了。");
        }
        placed.seatAssignments().put(seatIndex, player.getUniqueId());
        try {
            plugin.getTableManager().joinTable(player, table.getName());
        } catch (RuntimeException exception) {
            placed.seatAssignments().remove(seatIndex);
            throw exception;
        }
        placed.seatAssignments().entrySet().removeIf(entry -> !entry.getKey().equals(seatIndex) && entry.getValue().equals(player.getUniqueId()));
    }

    private void hint(Player player, String text, NamedTextColor color) {
        player.sendActionBar(message(text, color));
    }

    private void applyJoinVisibility(GameTable table, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            GameTable current = plugin.getTableManager().getTableOf(viewer);
            if (current != null && current.getName().equalsIgnoreCase(table.getName())) {
                viewer.hideEntity(plugin, entity);
            } else {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    private PlacedTable spawnTable(GameTable table, Location anchor, float yaw) {
        // 这里只生成“永久桌面层”：桌子、椅子、桌顶状态文字
        List<UUID> staticEntities = new ArrayList<>();
        PlacedTable placed = new PlacedTable(
            table.getName(),
            anchor.clone(),
            yaw,
            staticEntities,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new ArrayList<>(),
            null,
            null,
            new ArrayList<>(),
            new ArrayList<>()
        );

        Location tableLocation = anchor.clone().add(0.0, 0.55, 0.0);
        staticEntities.add(spawnFurnitureDisplay(tableLocation, tableItem(), TABLE_SCALE).getUniqueId());
        staticEntities.add(spawnCollider(anchor.clone().add(0.0, 0.72, 0.0), 2).getUniqueId());

        for (int index = 0; index < 3; index++) {
            Vector chairAdjustment = chairVisualAdjustment(index);
            Location chairLocation = rotate(
                anchor,
                yaw,
                chairOffsets(index)[0] + chairAdjustment.x(),
                0.20 + chairAdjustment.y(),
                chairOffsets(index)[1] + chairAdjustment.z()
            );
            ItemDisplay chair = spawnFurnitureDisplay(chairLocation, chairItem(), CHAIR_SCALE);
            chair.setRotation(yaw + chairYawOffset(index), 0.0f);
            staticEntities.add(chair.getUniqueId());
            staticEntities.add(spawnCollider(chairLocation.clone().add(0.0, 0.18, 0.0), 1).getUniqueId());

            ArmorStand seatMount = spawnSeatMount(chairLocation.clone().add(0.0, 0.18, 0.0));
            staticEntities.add(seatMount.getUniqueId());

            Vector chairHitboxAdjustment = chairHitboxAdjustment(index);
            Interaction seatInteraction = spawnInteraction(
                chairLocation.clone().add(chairHitboxAdjustment.x(), 0.38 + chairHitboxAdjustment.y(), chairHitboxAdjustment.z()),
                (float) plugin.getChairHitboxWidth(),
                (float) plugin.getChairHitboxHeight()
            );
            staticEntities.add(seatInteraction.getUniqueId());
            seatBindings.put(seatInteraction.getUniqueId(), new SeatBinding(table.getName(), index, seatMount.getUniqueId()));

            TextDisplay seatLabel = spawnText(
                chairLocation.clone().add(0.0, 1.35, 0.0),
                seatInfo(table, index),
                Display.Billboard.CENTER,
                false,
                SMALL_TEXT_SCALE
            );
            staticEntities.add(seatLabel.getUniqueId());
            placed.seatInfoDisplayIds().add(seatLabel.getUniqueId());
        }

        TextDisplay status = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getStatusHeight(), 0.0),
            buildStatus(table),
            Display.Billboard.CENTER,
            true,
            STATUS_TEXT_SCALE
        );
        status.setLineWidth(250);
        placed = placed.withStatusDisplayId(status.getUniqueId());
        staticEntities.add(status.getUniqueId());

        TextDisplay playDetail = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getPlayDetailHeight(), 0.0),
            buildPlayDetail(table),
            Display.Billboard.CENTER,
            false,
            SMALL_TEXT_SCALE
        );
        placed = placed.withPlayDetailDisplayId(playDetail.getUniqueId());
        staticEntities.add(playDetail.getUniqueId());

        return placed;
    }

    private void refreshActionButtons(GameTable table, PlacedTable placed) {
        // 每次都重建座位按钮，保证大厅/叫分/出牌三个阶段的按钮集完全一致
        clearEntities(placed.actionEntities(), false);
        placed.actionEntities().clear();

        List<ActionButtonState> activeStates = switch (table.getPhase()) {
            case BIDDING -> List.of(
                new ActionButtonState("bid", "不叫", ButtonAction.BID_0, -0.96),
                new ActionButtonState("bid", "叫1分", ButtonAction.BID_1, -0.32),
                new ActionButtonState("bid", "叫2分", ButtonAction.BID_2, 0.32),
                new ActionButtonState("bid", "叫3分", ButtonAction.BID_3, 0.96)
            );
            case PLAYING -> List.of(
                new ActionButtonState("inspect", "提示", ButtonAction.HINT_PLAY, -0.72),
                new ActionButtonState("pass", "不要", ButtonAction.PASS_TURN, -0.24),
                new ActionButtonState("refresh", "清选", ButtonAction.CLEAR_SELECTION, 0.24),
                new ActionButtonState("status", "设置", ButtonAction.OPEN_SETTINGS, 0.72)
            );
            case LOBBY -> List.of(
                new ActionButtonState("ready", "准备", ButtonAction.READY, -0.64),
                new ActionButtonState("start", "开始", ButtonAction.START, 0.00),
                new ActionButtonState("leave", "离开", ButtonAction.LEAVE, 0.64)
            );
        };

        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Location base = actionBase(placed.anchor(), placed.yaw(), seatIndex);
            UUID owner = placed.seatAssignments().get(seatIndex);
            float rowYaw = handCardYaw(placed.yaw(), seatIndex);
            Vector actionStep = actionStep(seatIndex);

            if (owner == null) {
                Location joinLocation = base.clone();
                ItemDisplay icon = spawnFlatButtonItem(joinLocation, uiItem("join"), plugin.getButtonScale(), rowYaw);
                placed.actionEntities().add(icon.getUniqueId());
                actionBindings.put(icon.getUniqueId(), new ActionBinding(table.getName(), ButtonAction.JOIN, seatIndex));

                TextDisplay label = spawnText(
                    joinLocation.clone().add(0.0, 0.18, 0.0),
                    message("加入座位" + (seatIndex + 1), NamedTextColor.WHITE),
                    Display.Billboard.CENTER,
                    false,
                    LABEL_TEXT_SCALE
                );
                placed.actionEntities().add(label.getUniqueId());
                applyJoinVisibility(table, label);

                Vector buttonHitbox = buttonHitboxAdjustment(seatIndex);
                Interaction interaction = spawnInteraction(
                    joinLocation.clone().add(buttonHitbox.x(), buttonHitbox.y(), buttonHitbox.z()),
                    (float) plugin.getButtonHitboxWidth(),
                    (float) plugin.getButtonHitboxHeight()
                );
                placed.actionEntities().add(interaction.getUniqueId());
                actionBindings.put(interaction.getUniqueId(), new ActionBinding(table.getName(), ButtonAction.JOIN, seatIndex));
                applyJoinVisibility(table, icon);
                applyJoinVisibility(table, interaction);
                continue;
            }

            if (table.isBot(owner)) {
                continue;
            }

            for (ActionButtonState state : activeStates) {
                Location buttonLocation = base.clone().add(actionStep.x() * state.offsetX(), 0.0, actionStep.z() * state.offsetX());
                ItemDisplay icon = spawnFlatButtonItem(buttonLocation, uiItem(state.modelId()), plugin.getButtonScale(), rowYaw);
                placed.actionEntities().add(icon.getUniqueId());
                actionBindings.put(icon.getUniqueId(), new ActionBinding(table.getName(), state.action(), seatIndex));
                applyPrivateVisibility(owner, icon);

                TextDisplay label = spawnText(
                    buttonLocation.clone().add(0.0, 0.18, 0.0),
                    message(state.label(), NamedTextColor.WHITE),
                    Display.Billboard.CENTER,
                    false,
                    LABEL_TEXT_SCALE
                );
                placed.actionEntities().add(label.getUniqueId());
                applyPrivateVisibility(owner, label);

                Vector buttonHitbox = buttonHitboxAdjustment(seatIndex);
                Interaction interaction = spawnInteraction(
                    buttonLocation.clone().add(buttonHitbox.x(), buttonHitbox.y(), buttonHitbox.z()),
                    (float) plugin.getButtonHitboxWidth(),
                    (float) plugin.getButtonHitboxHeight()
                );
                placed.actionEntities().add(interaction.getUniqueId());
                actionBindings.put(interaction.getUniqueId(), new ActionBinding(table.getName(), state.action(), seatIndex));
                applyPrivateVisibility(owner, interaction);
            }

        }
    }

    private void refreshStatus(GameTable table, PlacedTable placed) {
        if (placed.statusDisplayId() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(placed.statusDisplayId());
        if (entity instanceof TextDisplay display) {
            display.text(buildStatus(table));
        }
    }

    private void refreshPlayDetail(GameTable table, PlacedTable placed) {
        if (placed.playDetailDisplayId() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(placed.playDetailDisplayId());
        if (entity instanceof TextDisplay display) {
            display.text(buildPlayDetail(table));
        }
    }

    private void refreshSeatInfos(GameTable table, PlacedTable placed) {
        for (int index = 0; index < placed.seatInfoDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatInfoDisplayIds().get(index));
            if (entity instanceof TextDisplay display) {
                display.text(seatInfo(table, index));
            }
        }
        updateSeatInfoVisibility(table, placed);
    }

    private void updateSeatInfoVisibility(GameTable table, PlacedTable placed) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (int index = 0; index < placed.seatInfoDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatInfoDisplayIds().get(index));
            if (entity == null) {
                continue;
            }
            UUID owner = placed.seatAssignments().get(index);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (owner != null && viewer.getUniqueId().equals(owner) && !table.isBot(owner)) {
                    viewer.hideEntity(plugin, entity);
                } else {
                    viewer.showEntity(plugin, entity);
                }
            }
        }
    }

    private void refreshPrivateHands(GameTable table, PlacedTable placed) {
        // 每位玩家都有两层牌：
        // 1. 只有自己能看到的正面牌
        // 2. 其他人能看到的背面牌
        Set<UUID> currentPlayers = Set.copyOf(table.getSeats());
        for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
            if (!currentPlayers.contains(playerId)) {
                clearBacksideEntities(placed, playerId);
            }
        }
        for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
            if (!currentPlayers.contains(playerId)) {
                clearPrivateEntities(placed, playerId);
            }
        }
        for (UUID playerId : table.getSeats()) {
            renderBacksideHand(table, placed, playerId);
            renderPrivateHand(table, placed, playerId);
        }
    }

    private void renderBacksideHand(GameTable table, PlacedTable placed, UUID playerId) {
        // 背面牌是给“其他人”看的，所以会对牌主人隐藏
        clearBacksideEntities(placed, playerId);
        if (table.getPhase() == GamePhase.LOBBY) {
            return;
        }
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty()) {
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        List<UUID> spawned = new ArrayList<>();
        Map<Integer, UUID> visuals = new LinkedHashMap<>();
        Set<Integer> selected = table.getSelection(playerId);
        Vector step = handStep(seatIndex);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + step.x() * delta + depth.x() * delta,
                center.y() + (isSelected ? 0.12 : 0.0),
                center.z() + step.z() * delta + depth.z() * delta
            );
            ItemDisplay cardDisplay = spawnPlacedCard(cardLocation, backCardItem(), plugin.getPrivateCardScale(), cardYaw);
            cardDisplay.setGlowing(isSelected);
            cardDisplay.setGlowColorOverride(isSelected ? Color.fromRGB(96, 180, 255) : null);
            spawned.add(cardDisplay.getUniqueId());
            visuals.put(card.id(), cardDisplay.getUniqueId());
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(playerId) && !table.isBot(playerId)) {
                    viewer.hideEntity(plugin, cardDisplay);
                } else {
                    viewer.showEntity(plugin, cardDisplay);
                }
            }
        }
        placed.backsideEntitiesByPlayer().put(playerId, spawned);
        placed.backsideVisualsByPlayer().put(playerId, visuals);
    }

    private void renderPrivateHand(GameTable table, PlacedTable placed, UUID playerId) {
        // 正面手牌只给主人自己看，右键切换选择、左键对已选牌执行出牌
        clearPrivateEntities(placed, playerId);

        Player owner = Bukkit.getPlayer(playerId);
        if (owner == null) {
            return;
        }
        if (table.getPhase() == GamePhase.LOBBY) {
            return;
        }

        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty()) {
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        Set<Integer> selected = table.getSelection(playerId);
        List<UUID> spawned = new ArrayList<>();
        Map<Integer, HandCardVisual> visuals = new LinkedHashMap<>();
        Vector step = privateHandStep(seatIndex, playerId);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = privateHandAdjustment(seatIndex, playerId);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        Map<CardRank, Integer> rankCounts = countRanks(hand);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y() + (isSelected ? 0.18 : 0.0),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );

            ItemDisplay cardDisplay = spawnPlacedCard(cardLocation, cardItem(card), plugin.getPrivateCardScale(), cardYaw);
            cardDisplay.setGlowing(isSelected);
            cardDisplay.setGlowColorOverride(Color.fromRGB(255, 226, 92));
            spawned.add(cardDisplay.getUniqueId());
            cardBindings.put(cardDisplay.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));

            Vector cardHitbox = cardHitboxAdjustment(seatIndex);
            Interaction interaction = spawnInteraction(
                cardLocation.clone().add(cardHitbox.x(), cardHitbox.y(), cardHitbox.z()),
                (float) plugin.getCardHitboxWidth(),
                (float) plugin.getCardHitboxHeight()
            );
            spawned.add(interaction.getUniqueId());
            cardBindings.put(interaction.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));

            if (shouldShowPrivateLabel(playerId, card, rankCounts)) {
                TextDisplay label = spawnText(
                    cardLocation.clone().add(0.0, CARD_LABEL_Y, 0.0),
                    message(card.rank().label(), NamedTextColor.WHITE),
                    Display.Billboard.CENTER,
                    false,
                    LABEL_TEXT_SCALE
                );
                spawned.add(label.getUniqueId());
                applyPrivateVisibility(playerId, label);
                visuals.put(card.id(), new HandCardVisual(cardDisplay.getUniqueId(), interaction.getUniqueId(), label.getUniqueId()));
            } else {
                visuals.put(card.id(), new HandCardVisual(cardDisplay.getUniqueId(), interaction.getUniqueId(), null));
            }

            applyPrivateVisibility(playerId, cardDisplay);
            applyPrivateVisibility(playerId, interaction);
        }

        placed.privateEntitiesByPlayer().put(playerId, spawned);
        placed.privateVisualsByPlayer().put(playerId, visuals);
    }

    private void updatePrivateSelection(GameTable table, PlacedTable placed, UUID playerId) {
        // 为了避免右键选牌整排闪烁，这里只移动/发光已存在的实体，不整手重建
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (visuals == null || visuals.size() != hand.size()) {
            renderPrivateHand(table, placed, playerId);
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }
        Set<Integer> selected = table.getSelection(playerId);
        Vector step = privateHandStep(seatIndex, playerId);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = privateHandAdjustment(seatIndex, playerId);
        Vector cardHitbox = cardHitboxAdjustment(seatIndex);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            HandCardVisual visual = visuals.get(card.id());
            if (visual == null) {
                renderPrivateHand(table, placed, playerId);
                return;
            }
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y() + (isSelected ? 0.18 : 0.0),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );

            Entity cardEntity = Bukkit.getEntity(visual.cardDisplayId());
            Entity interactionEntity = Bukkit.getEntity(visual.interactionId());
            Entity labelEntity = visual.labelId() == null ? null : Bukkit.getEntity(visual.labelId());
            if (!(cardEntity instanceof ItemDisplay cardDisplay) || interactionEntity == null) {
                renderPrivateHand(table, placed, playerId);
                return;
            }

            cardEntity.teleport(cardLocation);
            interactionEntity.teleport(cardLocation.clone().add(cardHitbox.x(), cardHitbox.y(), cardHitbox.z()));
            cardDisplay.setRotation(cardYaw, 0.0f);
            cardDisplay.setGlowing(isSelected);
            cardDisplay.setGlowColorOverride(isSelected ? Color.fromRGB(255, 226, 92) : null);
            if (labelEntity != null) {
                labelEntity.teleport(cardLocation.clone().add(0.0, CARD_LABEL_Y, 0.0));
            }
        }
    }

    private void updateBacksideSelection(GameTable table, PlacedTable placed, UUID playerId) {
        Map<Integer, UUID> visuals = placed.backsideVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (visuals == null || visuals.size() != hand.size()) {
            renderBacksideHand(table, placed, playerId);
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        Set<Integer> selected = table.getSelection(playerId);
        Vector step = handStep(seatIndex);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            UUID entityId = visuals.get(card.id());
            Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
            if (!(entity instanceof ItemDisplay cardDisplay)) {
                renderBacksideHand(table, placed, playerId);
                return;
            }
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + step.x() * delta + depth.x() * delta,
                center.y() + (isSelected ? 0.12 : 0.0),
                center.z() + step.z() * delta + depth.z() * delta
            );
            cardDisplay.teleport(cardLocation);
            cardDisplay.setRotation(cardYaw, 0.0f);
            cardDisplay.setGlowing(isSelected);
            cardDisplay.setGlowColorOverride(isSelected ? Color.fromRGB(96, 180, 255) : null);
        }
    }

    private void refreshPublicTrick(GameTable table, PlacedTable placed) {
        // 公共出牌区固定显示在桌子上方：详情文字在上，牌组在下
        clearEntities(placed.publicEntities(), true);
        placed.publicEntities().clear();
        for (UUID playerId : new ArrayList<>(placed.viewerTrickEntitiesByPlayer().keySet())) {
            clearViewerTrickEntities(placed, playerId);
        }

        if (!table.getCurrentTrickCards().isEmpty()) {
            renderPreviewCards(
                table,
                placed,
                null,
                placed.publicEntities(),
                table.getCurrentTrickCards(),
                plugin.getPublicCardScale(),
                plugin.getPublicTrickHeight(),
                NamedTextColor.YELLOW
            );
        }
        for (UUID playerId : table.getSeats()) {
            if (table.isBot(playerId)) {
                continue;
            }
            List<DoudizhuCard> selectedCards = selectedCards(table, playerId);
            boolean hasCurrentTrick = !table.getCurrentTrickCards().isEmpty();
            boolean showOpponentRow = hasCurrentTrick && selectedCards.isEmpty();
            if (!selectedCards.isEmpty() && plugin.isOpponentPreviewEnabledFor(playerId)) {
                showOpponentRow = true;
            }
            if (!showOpponentRow && selectedCards.isEmpty()) {
                continue;
            }
            List<UUID> viewerEntities = new ArrayList<>();
            float scale = plugin.getPublicCardScale();
            if (showOpponentRow) {
                double y = selectedCards.isEmpty() ? plugin.getPublicTrickHeight() : plugin.getPublicTrickHeight() + 0.28;
                renderPreviewCards(
                    table,
                    placed,
                    playerId,
                    viewerEntities,
                    table.getCurrentTrickCards(),
                    scale,
                    y,
                    NamedTextColor.YELLOW
                );
            }
            if (!selectedCards.isEmpty()) {
                double y = showOpponentRow ? plugin.getPublicTrickHeight() - 0.24 : plugin.getPublicTrickHeight();
                renderPreviewCards(
                    table,
                    placed,
                    playerId,
                    viewerEntities,
                    selectedCards,
                    scale,
                    y,
                    NamedTextColor.AQUA
                );
            }
            placed.viewerTrickEntitiesByPlayer().put(playerId, viewerEntities);
        }
    }

    private void renderPreviewCards(
        GameTable table,
        PlacedTable placed,
        UUID ownerId,
        List<UUID> target,
        List<DoudizhuCard> cards,
        float scale,
        double height,
        NamedTextColor labelColor
    ) {
        if (cards.isEmpty()) {
            return;
        }
        Map<CardRank, Integer> rankCounts = countRanks(cards);
        int perRow = 6;
        int rowCount = Math.max(1, (cards.size() + perRow - 1) / perRow);
        for (int index = 0; index < cards.size(); index++) {
            int row = index / perRow;
            int col = index % perRow;
            int rowSize = Math.min(perRow, cards.size() - row * perRow);
            double centered = col - ((rowSize - 1) * 0.5);
            double rowOffset = row - ((rowCount - 1) * 0.5);
            Location location = rotate(
                placed.anchor(),
                placed.yaw(),
                centered * plugin.getPublicTrickSpacing(),
                height,
                rowOffset * plugin.getPublicTrickSpacing()
            );
            DoudizhuCard card = cards.get(index);
            ItemDisplay display;
            if (ownerId == null) {
                display = spawnBillboardItem(location, cardItem(card), scale);
            } else {
                int seatIndex = placedSeatIndex(placed, ownerId);
                float yaw = seatIndex < 0 ? publicCardYaw(placed.yaw()) : handCardYaw(placed.yaw(), seatIndex);
                display = spawnPlacedCard(location, cardItem(card), scale, yaw);
            }
            target.add(display.getUniqueId());
            applyTrickVisibility(table, ownerId, display);
            if (shouldShowLabel(card, rankCounts)) {
                TextDisplay label = spawnText(
                    location.clone().add(0.0, 0.22, 0.0),
                    message(card.rank().label(), labelColor),
                    Display.Billboard.CENTER,
                    false,
                    LABEL_TEXT_SCALE
                );
                target.add(label.getUniqueId());
                applyTrickVisibility(table, ownerId, label);
            }
        }
    }

    private List<DoudizhuCard> selectedCards(GameTable table, UUID playerId) {
        Set<Integer> selected = table.getSelection(playerId);
        if (selected.isEmpty()) {
            return List.of();
        }
        return table.getHand(playerId).stream()
            .filter(card -> selected.contains(card.id()))
            .toList();
    }

    private void clearPrivateEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.privateEntitiesByPlayer().remove(playerId);
        placed.privateVisualsByPlayer().remove(playerId);
        if (entities != null) {
            clearEntities(entities, false);
        }
    }

    private void clearBacksideEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.backsideEntitiesByPlayer().remove(playerId);
        placed.backsideVisualsByPlayer().remove(playerId);
        if (entities != null) {
            clearEntities(entities, false);
        }
    }

    private void clearViewerTrickEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.viewerTrickEntitiesByPlayer().remove(playerId);
        if (entities != null) {
            clearEntities(entities, false);
        }
    }

    private void clearEntities(List<UUID> entityIds, boolean publicCards) {
        for (UUID entityId : entityIds) {
            actionBindings.remove(entityId);
            cardBindings.remove(entityId);
            seatBindings.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        if (publicCards) {
            entityIds.clear();
        }
    }

    private void cleanupPlacedTable(PlacedTable placed) {
        clearEntities(placed.actionEntities(), false);
        clearEntities(placed.publicEntities(), false);
        for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
            clearBacksideEntities(placed, playerId);
        }
        for (UUID playerId : new ArrayList<>(placed.viewerTrickEntitiesByPlayer().keySet())) {
            clearViewerTrickEntities(placed, playerId);
        }
        for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
            clearPrivateEntities(placed, playerId);
        }
        clearEntities(placed.staticEntities(), false);
    }

    private void applyPrivateVisibility(UUID ownerId, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(ownerId)) {
                viewer.showEntity(plugin, entity);
            } else {
                viewer.hideEntity(plugin, entity);
            }
        }
    }

    private void applyTrickVisibility(GameTable table, UUID ownerId, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (ownerId != null) {
                if (viewer.getUniqueId().equals(ownerId)) {
                    viewer.showEntity(plugin, entity);
                } else {
                    viewer.hideEntity(plugin, entity);
                }
                continue;
            }
            GameTable current = plugin.getTableManager().getTableOf(viewer);
            if (current != null && current.getName().equalsIgnoreCase(table.getName())) {
                viewer.hideEntity(plugin, entity);
            } else {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    private ItemDisplay spawnFurnitureDisplay(Location location, ItemStack item, float scale) {
        return location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned);
        });
    }

    private ItemDisplay spawnBillboardItem(Location location, ItemStack item, float scale) {
        return location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned);
        });
    }

    private ItemDisplay spawnFlatButtonItem(Location location, ItemStack item, float scale, float yaw) {
        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f((float) Math.toRadians(plugin.getButtonRollDegrees()), 0.0f, 0.0f, 1.0f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned);
        });
        display.setRotation(yaw, 0.0f);
        return display;
    }

    private ItemDisplay spawnPlacedCard(Location location, ItemStack item, float scale, float yaw) {
        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned);
        });
        display.setRotation(yaw, 0.0f);
        return display;
    }

    private Slime spawnCollider(Location location, int size) {
        return location.getWorld().spawn(location, Slime.class, spawned -> {
            spawned.setSize(size);
            spawned.setAI(false);
            spawned.setInvisible(true);
            spawned.setSilent(true);
            spawned.setCollidable(true);
            protectEntity(spawned);
        });
    }

    private ArmorStand spawnSeatMount(Location location) {
        return location.getWorld().spawn(location, ArmorStand.class, spawned -> {
            spawned.setVisible(false);
            spawned.setMarker(true);
            spawned.setSmall(true);
            spawned.setBasePlate(false);
            spawned.setArms(false);
            spawned.setCollidable(false);
            protectEntity(spawned);
        });
    }

    private TextDisplay spawnText(Location location, Component text, Display.Billboard billboard, boolean background) {
        return spawnText(location, text, billboard, background, 1.0f);
    }

    private TextDisplay spawnText(Location location, Component text, Display.Billboard billboard, boolean background, float scale) {
        return location.getWorld().spawn(location, TextDisplay.class, spawned -> {
            spawned.text(text);
            spawned.setBillboard(billboard);
            spawned.setDefaultBackground(false);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned);
        });
    }

    private Interaction spawnInteraction(Location location, float width, float height) {
        return location.getWorld().spawn(location, Interaction.class, spawned -> {
            spawned.setInteractionWidth(width);
            spawned.setInteractionHeight(height);
            spawned.setResponsive(true);
            protectEntity(spawned);
        });
    }

    private void protectEntity(Entity entity) {
        entity.setInvulnerable(true);
        entity.setPersistent(false);
        entity.setGravity(false);
    }

    private ItemStack tableItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        if (configured != null) {
            configured.setAmount(1);
            return configured;
        }
        NamespacedKey model = configuredModelKey(
            plugin.getTableItemModelId(),
            PackAssets.furnitureModel(plugin, "table_visual")
        );
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model);
        meta.displayName(message(plugin.getTableDisplayName(), NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack chairItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.CHAIR);
        if (configured != null) {
            configured.setAmount(1);
            return configured;
        }
        NamespacedKey model = configuredModelKey(
            plugin.getChairItemModelId(),
            PackAssets.furnitureModel(plugin, "seat_chair")
        );
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model);
        meta.displayName(message(plugin.getChairDisplayName(), NamedTextColor.RED));
        item.setItemMeta(meta);
        return item;
    }

    private NamespacedKey configuredModelKey(String itemModelId, NamespacedKey fallback) {
        if (itemModelId == null || itemModelId.isBlank()) {
            return fallback;
        }
        NamespacedKey configured = NamespacedKey.fromString(itemModelId.trim());
        return configured == null ? fallback : configured;
    }

    private ItemStack uiItem(String modelId) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(PackAssets.uiModel(plugin, modelId));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack cardItem(DoudizhuCard card) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(PackAssets.cardModel(plugin, card));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backCardItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(PackAssets.backModel(plugin));
        item.setItemMeta(meta);
        return item;
    }

    private Vector privateHandAdjustment(int seatIndex, UUID playerId) {
        double lateral = plugin.getGlobalPrivateHandLateralOffset();
        double vertical = plugin.getGlobalPrivateHandVerticalOffset();
        double depth = plugin.getGlobalPrivateHandDepthOffset();
        Vector lateralAxis = normalizeHorizontal(handStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return new Vector(
            lateralAxis.x() * lateral + depthAxis.x() * depth,
            vertical,
            lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    private Vector privateHandStep(int seatIndex, UUID playerId) {
        double spacing = Math.max(0.02, plugin.getHandSpacing());
        return switch (seatIndex) {
            case 0 -> new Vector(spacing, 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, -spacing);
            default -> new Vector(0.0, 0.0, spacing);
        };
    }

    private Vector chairVisualAdjustment(int seatIndex) {
        Vector lateralAxis = normalizeHorizontal(actionStep(seatIndex));
        return new Vector(
            lateralAxis.x() * plugin.getChairVisualLateralOffset(),
            plugin.getChairVisualVerticalOffset(),
            lateralAxis.z() * plugin.getChairVisualLateralOffset()
        );
    }

    private Vector chairHitboxAdjustment(int seatIndex) {
        Vector lateralAxis = normalizeHorizontal(actionStep(seatIndex));
        return new Vector(
            lateralAxis.x() * plugin.getChairHitboxLateralOffset(),
            plugin.getChairHitboxVerticalOffset(),
            lateralAxis.z() * plugin.getChairHitboxLateralOffset()
        );
    }

    private Vector buttonHitboxAdjustment(int seatIndex) {
        Vector lateralAxis = normalizeHorizontal(actionStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return new Vector(
            lateralAxis.x() * plugin.getButtonHitboxLateralOffset() + depthAxis.x() * plugin.getButtonHitboxDepthOffset(),
            plugin.getButtonHitboxVerticalOffset(),
            lateralAxis.z() * plugin.getButtonHitboxLateralOffset() + depthAxis.z() * plugin.getButtonHitboxDepthOffset()
        );
    }

    private Vector cardHitboxAdjustment(int seatIndex) {
        Vector lateralAxis = normalizeHorizontal(actionStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return new Vector(
            lateralAxis.x() * plugin.getCardHitboxLateralOffset() + depthAxis.x() * plugin.getCardHitboxDepthOffset(),
            plugin.getCardHitboxVerticalOffset(),
            lateralAxis.z() * plugin.getCardHitboxLateralOffset() + depthAxis.z() * plugin.getCardHitboxDepthOffset()
        );
    }

    private Component buildStatus(GameTable table) {
        StringBuilder builder = new StringBuilder();
        builder.append(table.getName()).append(" | ").append(table.getPhase().name());
        if (table.getCurrentTurn() != null) {
            builder.append("\n当前: ").append(table.displayName(table.getCurrentTurn()));
        }
        if (table.getLandlord() != null) {
            builder.append(" | 地主: ").append(table.displayName(table.getLandlord()));
        }
        if (!table.getBottomCards().isEmpty()) {
            builder.append("\n底牌数: ").append(table.getBottomCards().size());
        }
        return message(builder.toString(), NamedTextColor.GREEN);
    }

    private Component buildPlayDetail(GameTable table) {
        return message(table.currentTrickPreviewText(), table.getCurrentPattern() == null ? NamedTextColor.GRAY : NamedTextColor.YELLOW);
    }

    private void applyHint(GameTable table, Player player) {
        // “提示”按钮每按一次就切到下一组建议牌
        List<List<DoudizhuCard>> hints = buildHints(table, player.getUniqueId());
        if (hints.isEmpty()) {
            hint(player, "没有可出的建议牌组。", NamedTextColor.GRAY);
            return;
        }
        int index = hintIndices.getOrDefault(player.getUniqueId(), 0) % hints.size();
        table.replaceSelection(player.getUniqueId(), hints.get(index));
        hintIndices.put(player.getUniqueId(), index + 1);
        refreshPrivateHand(table, player.getUniqueId());
        hint(player, "提示第 " + (index + 1) + " 组，可再次点击切换。", NamedTextColor.YELLOW);
    }

    private List<List<DoudizhuCard>> buildHints(GameTable table, UUID playerId) {
        List<DoudizhuCard> hand = new ArrayList<>(table.getHand(playerId));
        hand.sort(DoudizhuCard.ORDER);
        List<List<DoudizhuCard>> hints = new ArrayList<>();
        List<DoudizhuCard> best = SimpleBotBrain.choosePlay(
            hand,
            table.getLeadPlayer() != null && !table.getLeadPlayer().equals(playerId) ? table.getCurrentPattern() : null
        );
        if (!best.isEmpty()) {
            hints.add(best);
        }
        for (DoudizhuCard card : hand) {
            List<DoudizhuCard> single = List.of(card);
            if (table.getCurrentPattern() != null && table.getLeadPlayer() != null && !table.getLeadPlayer().equals(playerId)) {
                boolean legal = dev.codex.doudizhu.model.PatternAnalyzer.analyze(single)
                    .map(pattern -> pattern.canBeat(table.getCurrentPattern()))
                    .orElse(false);
                if (!legal) {
                    continue;
                }
            }
            if (hints.stream().noneMatch(existing -> sameCards(existing, single))) {
                hints.add(single);
            }
            if (hints.size() >= 6) {
                break;
            }
        }
        return hints;
    }

    private boolean sameCards(List<DoudizhuCard> left, List<DoudizhuCard> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index).id() != right.get(index).id()) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldShowLabel(DoudizhuCard card, Map<CardRank, Integer> rankCounts) {
        return plugin.isCardHologramLabelsEnabled();
    }

    private boolean shouldShowPrivateLabel(UUID playerId, DoudizhuCard card, Map<CardRank, Integer> rankCounts) {
        if (!plugin.isCardLabelsEnabledFor(playerId)) {
            return false;
        }
        return shouldShowLabel(card, rankCounts);
    }

    private Map<CardRank, Integer> countRanks(List<DoudizhuCard> cards) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : cards) {
            counts.merge(card.rank(), 1, Integer::sum);
        }
        return counts;
    }

    private Component seatInfo(GameTable table, int seatIndex) {
        UUID seat = placedSeat(table, seatIndex);
        if (seat == null) {
            return message("座位" + (seatIndex + 1) + "\n空位", NamedTextColor.GRAY);
        }
        StringBuilder builder = new StringBuilder("座位").append(seatIndex + 1).append("\n").append(table.displayName(seat));
        builder.append(table.isBot(seat) ? "\n机器人" : "\n玩家");
        if (table.isReady(seat)) {
            builder.append("\n已准备");
        } else if (table.getPhase() == GamePhase.LOBBY) {
            builder.append("\n未准备");
        }
        if (table.getRole(seat) != null) {
            builder.append("\n").append(table.getRole(seat).displayName());
        }
        if (table.getPhase() == GamePhase.PLAYING) {
            builder.append("\n剩余 ").append(table.getHand(seat).size()).append(" 张");
        }
        builder.append("\n分数 ").append(table.getScore(seat));
        if (seat.equals(table.getCurrentTurn())) {
            builder.append("\n当前操作");
        }
        return message(builder.toString(), table.isBot(seat) ? NamedTextColor.AQUA : NamedTextColor.GOLD);
    }

    private UUID placedSeat(GameTable table, int seatIndex) {
        PlacedTable placed = placedTables.get(normalize(table.getName()));
        return placed == null ? null : placed.seatAssignments().get(seatIndex);
    }

    private int placedSeatIndex(PlacedTable placed, UUID playerId) {
        return placed.seatAssignments().entrySet().stream()
            .filter(entry -> entry.getValue().equals(playerId))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(-1);
    }

    private Location actionBase(Location anchor, float tableYaw, int seatIndex) {
        double baseDistance = plugin.getButtonDistance();
        double frontDistance = 1.40 + Math.max(0.0, (baseDistance - 1.10) * 0.45);
        double sideDistance = 1.72 + Math.max(0.0, (baseDistance - 1.10) * 0.45);
        double height = plugin.getButtonHeight();
        return switch (seatIndex) {
            case 0 -> rotate(anchor, tableYaw, 0.0, height, -frontDistance);
            case 1 -> rotate(anchor, tableYaw, -sideDistance, height, 0.0);
            default -> rotate(anchor, tableYaw, sideDistance, height, 0.0);
        };
    }

    private void playSelectionSound(Player player, boolean selected) {
        DoudizhuPlugin.SelectionSound sound = plugin.selectionSoundFor(player.getUniqueId());
        if (sound.volume() <= 0.0f) {
            return;
        }
        float pitch = selected ? sound.selectedPitch() : sound.deselectedPitch();
        player.playSound(player.getLocation(), sound.key(), sound.volume(), pitch);
    }

    private Component message(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static float snappedYaw(float yaw) {
        return Math.round(yaw / 90.0f) * 90.0f;
    }

    private static Location rotate(Location anchor, float yaw, double x, double y, double z) {
        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double rx = x * cos - z * sin;
        double rz = x * sin + z * cos;
        return anchor.clone().add(rx, y, rz);
    }

    private static Vector handCenter(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(0.0, 1.23, -1.34);
            case 1 -> new Vector(-1.88, 1.23, 0.0);
            default -> new Vector(1.88, 1.23, 0.0);
        };
    }

    private Vector handStep(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(plugin.getHandSpacing(), 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, -plugin.getHandSpacing());
            default -> new Vector(0.0, 0.0, plugin.getHandSpacing());
        };
    }

    private Vector handDepth(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(0.0, 0.0, plugin.getCardDepthOffset());
            case 1 -> new Vector(plugin.getCardDepthOffset(), 0.0, 0.0);
            default -> new Vector(-plugin.getCardDepthOffset(), 0.0, 0.0);
        };
    }

    private static Vector towardTableAxis(int seatIndex) {
        Vector center = handCenter(seatIndex);
        return normalizeHorizontal(new Vector(-center.x(), 0.0, -center.z()));
    }

    private static Vector normalizeHorizontal(Vector vector) {
        double length = Math.sqrt(vector.x() * vector.x() + vector.z() * vector.z());
        if (length < 0.0001) {
            return new Vector(0.0, 0.0, 0.0);
        }
        return new Vector(vector.x() / length, 0.0, vector.z() / length);
    }

    private static Vector actionStep(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(1.0, 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, 1.0);
            default -> new Vector(0.0, 0.0, -1.0);
        };
    }

    private static float handCardYaw(float tableYaw, int seatIndex) {
        return switch (seatIndex) {
            case 0 -> tableYaw;
            case 1 -> tableYaw - 90.0f;
            default -> tableYaw + 90.0f;
        };
    }

    private static float publicCardYaw(float tableYaw) {
        return tableYaw + 180.0f;
    }

    private static double[] chairOffsets(int index) {
        return switch (index) {
            case 0 -> new double[] {0.0, -2.15};
            case 1 -> new double[] {-2.55, 0.0};
            default -> new double[] {2.55, 0.0};
        };
    }

    private static float chairYawOffset(int index) {
        return switch (index) {
            case 0 -> 0.0f;
            case 1 -> -90.0f;
            default -> 90.0f;
        };
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record Vector(double x, double y, double z) {
    }

    private record ButtonDefinition(String modelId, String label, ButtonAction action, double offsetX, double offsetY, double offsetZ) {
    }

    private record ActionBinding(String tableName, ButtonAction action, Integer seatIndex) {
    }

    private record CardBinding(String tableName, UUID ownerId, int cardId) {
    }

    private record SeatBinding(String tableName, int seatIndex, UUID mountId) {
    }

    private record ActionButtonState(String modelId, String label, ButtonAction action, double offsetX) {
    }

    private record HandCardVisual(UUID cardDisplayId, UUID interactionId, UUID labelId) {
    }

    private record PlacedTable(
        String tableName,
        Location anchor,
        float yaw,
        List<UUID> staticEntities,
        Map<Integer, UUID> seatAssignments,
        Map<UUID, List<UUID>> privateEntitiesByPlayer,
        Map<UUID, Map<Integer, HandCardVisual>> privateVisualsByPlayer,
        Map<UUID, List<UUID>> backsideEntitiesByPlayer,
        Map<UUID, Map<Integer, UUID>> backsideVisualsByPlayer,
        Map<UUID, List<UUID>> viewerTrickEntitiesByPlayer,
        List<UUID> publicEntities,
        UUID statusDisplayId,
        UUID playDetailDisplayId,
        List<UUID> seatInfoDisplayIds,
        List<UUID> actionEntities
    ) {
        private PlacedTable withStatusDisplayId(UUID newStatusDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                staticEntities,
                seatAssignments,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                viewerTrickEntitiesByPlayer,
                publicEntities,
                newStatusDisplayId,
                playDetailDisplayId,
                seatInfoDisplayIds,
                actionEntities
            );
        }

        private PlacedTable withPlayDetailDisplayId(UUID newPlayDetailDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                staticEntities,
                seatAssignments,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                viewerTrickEntitiesByPlayer,
                publicEntities,
                statusDisplayId,
                newPlayDetailDisplayId,
                seatInfoDisplayIds,
                actionEntities
            );
        }
    }

    private enum ButtonAction {
        JOIN,
        READY,
        START,
        STATUS,
        LEAVE,
        PLAY_SELECTED,
        PASS_TURN,
        HINT_PLAY,
        CLEAR_SELECTION,
        OPEN_SETTINGS,
        BID_0,
        BID_1,
        BID_2,
        BID_3
    }
}
