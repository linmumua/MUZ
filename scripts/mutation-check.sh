#!/usr/bin/env bash
# 变异验证：逐条把实现改坏，确认对应测试真的 FAILED，然后恢复。
# 用法：bash scripts/mutation-check.sh
# 只做临时改动，每条结束后立刻用 git 恢复被改的那个文件。
set -u

GRADLE="./gradlew test -PmuzTarget=paper-26.1.2 --quiet"
PACK=src/main/java/linmumua/doudizhu/assets/PackAssets.java
VIEW=src/main/java/linmumua/doudizhu/game/TrickHudView.java
SVC=src/main/java/linmumua/doudizhu/game/TrickHudService.java
TABLE=src/main/java/linmumua/doudizhu/game/GameTable.java
BUILD=build.gradle.kts
HEAD=src/main/java/linmumua/doudizhu/assets/PlayerHeadRenderer.java

pass=0
fail=0

# 当前正在变异的文件与它的字节快照。中途被打断时靠这两个变量还原。
ACTIVE_FILE=""
ACTIVE_SNAPSHOT=""

# 【必须有这个 trap】：脚本被打断时，被改坏的那个文件会原样留在工作区。
# 真实事故：把本脚本输出接到 `head -12` 上，head 读够行数就退出、脚本收到 SIGPIPE 当场死掉，
# 恰好死在「已改坏、还没还原」之间，于是 TrickHudView.java 带着变异载荷留了下来，
# 后面一次全量 test 因此莫名变红，排查方向被带偏。
# EXIT 覆盖正常结束与 set -e 退出，PIPE/INT/TERM 覆盖被打断。
restore_active() {
  if [ -n "$ACTIVE_FILE" ] && [ -n "$ACTIVE_SNAPSHOT" ] && [ -f "$ACTIVE_SNAPSHOT" ]; then
    cp "$ACTIVE_SNAPSHOT" "$ACTIVE_FILE"
    echo "INTERRUPTED  已按字节快照还原 $ACTIVE_FILE"
    rm -f "$ACTIVE_SNAPSHOT"
    ACTIVE_FILE=""
    ACTIVE_SNAPSHOT=""
  fi
}
trap restore_active EXIT PIPE INT TERM

# $1 说明  $2 被改文件  $3 测试过滤  $4 python 改写脚本
#
# 【还原必须走字节快照，绝不能用 git checkout --】
# 这里曾经写的是 `git checkout -- "$file"`，造成过一次真实的工作丢失：
#   1. git checkout -- 不是「还原到一秒前」，而是「还原到 HEAD」。本仓库有大量未提交改动，
#      于是它把用户尚未提交的工作连同变异一起抹掉了（build.gradle.kts 掉了 300 多行）。
#   2. 对未跟踪文件它又是彻底的 no-op（文件不在索引里），于是那些文件的变异根本没被还原，
#      一路累积下去，后面每条变异测出来的「FAILED」都不可信。
# 一个错误的还原机制同时制造了这两种相反的破坏。字节快照对两类文件都正确。
mutate() {
  local label="$1" file="$2" filter="$3" script="$4"
  local snapshot
  snapshot=$(mktemp)
  cp "$file" "$snapshot"
  # 登记给 trap：从这一刻起，任何异常退出都能把这个文件还原回去。
  ACTIVE_FILE="$file"
  ACTIVE_SNAPSHOT="$snapshot"

  if ! python -c "$script"; then
    echo "SETUP-ERROR  $label   (锚点没找到，实现已改动，这条变异需要更新)"
    fail=$((fail+1))
    cp "$snapshot" "$file"
    rm -f "$snapshot"
    ACTIVE_FILE=""
    ACTIVE_SNAPSHOT=""
    return
  fi

  if $GRADLE --tests "$filter" >/dev/null 2>&1; then
    echo "NOT-CAUGHT   $label   (测试仍然全绿 —— 这条测试守不住这个错误)"
    fail=$((fail+1))
  else
    echo "CAUGHT       $label"
    pass=$((pass+1))
  fi

  # 无条件按快照还原，并校验真的还原干净了 —— 还原失败必须立刻喊出来，
  # 否则后面所有变异都跑在被污染的代码上，结论全部失效。
  #
  # 【校验必须比对「变异后的样子」而不是快照自己】：原先写的是
  #   cp "$snapshot" "$file"; cmp -s "$snapshot" "$file"
  # 那个 cmp 恒真 —— 刚把 A 拷成 B 再问「A 和 B 一样吗」，永远一样。
  # 它看着像在校验，实际上一个失败都测不出来。现在改成：先记下变异后的内容，
  # 还原之后确认文件【确实不再等于变异后的内容】，同时确认它等于快照。
  local mutated
  mutated=$(mktemp)
  cp "$file" "$mutated"
  cp "$snapshot" "$file"
  if ! cmp -s "$file" "$snapshot"; then
    echo "RESTORE-FAIL $label   $file 没能还原成快照，请立即手工检查，后续结论不可信"
    fail=$((fail+1))
  elif cmp -s "$file" "$mutated"; then
    echo "RESTORE-FAIL $label   $file 还原后仍等于变异后的内容，变异或还原有一方没生效"
    fail=$((fail+1))
  fi
  rm -f "$mutated" "$snapshot"
  ACTIVE_FILE=""
  ACTIVE_SNAPSHOT=""
}

echo "== 变异验证开始 =="

# 统一的改写脚本：二进制读写，保住 CRLF 行尾，只替换第一处。
py() {
  echo "p='$1';d=open(p,'rb').read().decode('utf-8');o='''$2''';assert o in d,'anchor not found';open(p,'wb').write(d.replace(o,'''$3''',1).encode('utf-8'))"
}

# ---- 1 头像字形盒按 8*scale 算（这次踩的坑，那句错注释诱导的写法）----
mutate "头像盒高改按 8*scale 算" "$PACK" "*CraftEngineBundleResourcesTest" \
  "$(py "$PACK" "return cardDownOffset + AVATAR_OUTLINED_PIXELS * avatarScale;" "return cardDownOffset + AVATAR_HEAD_PIXELS * avatarScale;")"

# ---- 2 构建侧头像字形按 8 行生成 ----
mutate "构建侧头像字形改按 8 行生成" "$BUILD" "*CraftEngineBundleResourcesTest" \
  "$(py "$BUILD" "val size = (avatarOutlinedPixels - row) * scale" "val size = (avatarHeadPixels - row) * scale")"

# ---- 3 构建侧头像档位表漏掉默认那一档（旧包渲染错位的真实场景）----
# 拆表后 110 归头像表，牌表里已经没有它了，所以这条锚点从牌表挪到了头像表。
mutate "构建侧头像档位表漏掉 110" "$BUILD" "*CraftEngineBundleResourcesTest" \
  "$(py "$BUILD" "100, 110, 120" "100, 120")"

# ---- 4 插件侧牌表多一档（牌面码位整体平移）----
# 锚点只取「52」那一行：档位表是多行带注释的，整表当锚点会因为注释改动而失配。
mutate "插件侧牌表多加一档 108" "$PACK" "*CraftEngineBundleResourcesTest" \
  "$(py "$PACK" "40, 44, 48, 50, 52
    };" "40, 44, 48, 50, 52, 108
    };")"

# ---- 4b 插件侧头像表多一档（头像与 bot 码位整体平移）----
mutate "插件侧头像表多加一档 45" "$PACK" "*CraftEngineBundleResourcesTest" \
  "$(py "$PACK" "0, 40, 50, 60" "0, 40, 45, 50, 60")"

# ---- 4c 两张表被合回一张（废条目回归 + 牌行放行 110）----
mutate "头像表合回牌表" "$PACK" "*CraftEngineBundleResourcesTest" \
  "$(py "$PACK" "        0, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150" "        0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 50, 52, 110")"

# ---- 4d bot 图标跟错表（bot 座位图标与真人头像错开一整行）----
mutate "bot 图标改跟牌表" "$BUILD" "*CraftEngineBundleResourcesTest" \
  "$(py "$BUILD" "        val botAvatarDownImages = buildString {
            avatarDownOffsetTiers.forEachIndexed" "        val botAvatarDownImages = buildString {
            cardGlyphDownOffsetTiers.forEachIndexed")"

# ---- 4e 头像字形跟错表 ----
mutate "头像字形改跟牌表" "$BUILD" "*CraftEngineBundleResourcesTest" \
  "$(py "$BUILD" "        val avatarPixelImages = buildString {
            avatarDownOffsetTiers.forEachIndexed" "        val avatarPixelImages = buildString {
            cardGlyphDownOffsetTiers.forEachIndexed")"

# ---- 4f bot 码位的 off-by-one（换表时最容易踩的那个 -1）----
mutate "bot 码位公式去掉 -1" "$BUILD" "*CraftEngineBundleResourcesTest" \
  "$(py "$BUILD" "(downTier - 1) * botAvatarGlyphs.size + roleIndex" "downTier * botAvatarGlyphs.size + roleIndex")"

# ---- 5 牌行不补居中偏移 ----
mutate "牌行去掉居中补偿" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "int pad = (containerAdvance - cardRowAdvance) / 2;" "int pad = 0;")"

# ---- 6 头像行不补居中偏移 ----
mutate "头像行去掉居中补偿" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "int pad = (containerAdvance - avatarRowAdvance) / 2;" "int pad = 0;")"

# ---- 7 补整份而不是一半（最容易写错的那种）----
mutate "居中补偿补整份而非一半" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "int pad = (containerAdvance - avatarRowAdvance) / 2;" "int pad = containerAdvance - avatarRowAdvance;")"

# ---- 8 首尾偏移不配对 ----
mutate "行尾不补到容器宽（首尾不配对）" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "appendOffset(builder, offsetProvider, containerAdvance - pad - avatarRowAdvance);" "")"

# ---- 9 恢复「没牌就返回空串」（常显回归）----
mutate "没牌时又返回空串（HUD 闪烁回归）" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "if (!hasAvatarRow && !hasCardRow) {" "if (!hasCardRow) {")"

# ---- 10 空槽不占宽度（人数不足时布局塌）----
mutate "空槽不再占一整槽宽度" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "int trail = slotPixels - lead - used;" "int trail = slot.isEmpty() ? -lead : slotPixels - lead - used;")"

# ---- 11 槽内不居中（左对齐）----
mutate "头像在槽内改成左对齐" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "int lead = (slotPixels - used) / 2;" "int lead = 0;")"

# ---- 12 牌不再叠放（20 张超屏）----
mutate "牌改成全展开（20 张超屏）" "$VIEW" "*TrickHudViewTest" \
  "$(py "$VIEW" "int cardRow = cardCount <= 0
            ? 0
            : (cardCount - 1) * cardStepPixels + PackAssets.cardGlyphAdvance(heightTier);" "int cardRow = cardCount <= 0
            ? 0
            : cardCount * PackAssets.cardGlyphAdvance(heightTier);")"

# ---- 13 两行重叠时静默放过（「完全自由」方案唯一的防线被拆掉）----
mutate "两行重叠时不留警告" "$SVC" "*TrickHudSettingsTest" \
  "$(py "$SVC" "        if (avatarDown >= required) {
            return;
        }" "        if (avatarDown >= required || true) {
            return;
        }")"

# ---- 13b 重叠判据按 8*scale 算（那句错注释诱导的写法，会漏报 2*scale 的重叠）----
mutate "重叠判据改按 8*scale 算" "$SVC" "*TrickHudSettingsTest" \
  "$(py "$SVC" "        int required = PackAssets.avatarRowDownOffset(cardDown, avatarScale);" "        int required = cardDown + PackAssets.AVATAR_HEAD_PIXELS * avatarScale;")"

# ---- 13c 头像行偏移改回由牌行推导（拆表等于没做）----
mutate "头像行偏移改回联动牌行" "$SVC" "*TrickHudSettingsTest" \
  "$(py "$SVC" "        int avatarOffsetDown = config.getInt(\"trick-hud.avatar-offset-down\", DEFAULT_AVATAR_OFFSET_DOWN);" "        int avatarOffsetDown = PackAssets.avatarRowDownOffset(
            PackAssets.cardGlyphDownOffsetAt(downOffsetTier), avatarScale);")"

# ---- 13d 新键的非法值静默照用 ----
mutate "avatar-offset-down 非法值不回退" "$SVC" "*TrickHudSettingsTest" \
  "$(py "$SVC" "        if (avatarDownOffsetTier < 0) {
            warn.accept(\"trick-hud.avatar-offset-down=\"" "        if (false) {
            warn.accept(\"trick-hud.avatar-offset-down=\"")"

# ---- 13e 默认值写死成字面量（改了牌行默认值就会错开）----
mutate "头像行默认值写死 100" "$SVC" "*TrickHudSettingsTest" \
  "$(py "$SVC" "        PackAssets.avatarRowDownOffset(DEFAULT_OFFSET_DOWN, DEFAULT_AVATAR_SCALE);" "        100;")"

# ---- 14 中间头像取回 leadPlayer（语义回归）----
mutate "中间大头像取回 leadPlayer" "$TABLE" "*GameTableTrickHudSeatsTest" \
  "$(py "$TABLE" "            trickHudSeat(currentTurn),
            trickHudSeat(neighbourSeat(currentTurn, 1))" "            trickHudSeat(leadPlayer),
            trickHudSeat(neighbourSeat(currentTurn, 1))")"

# ---- 15 负数取模没回卷 ----
mutate "上一位算式漏掉 + size（负数取模）" "$TABLE" "*GameTableTrickHudSeatsTest" \
  "$(py "$TABLE" "return seats.get((index + step + size) % size);" "return seats.get((index + step) % size);")"

# ---- 16 人数不足时绕回自己 ----
mutate "人数不足时绕回自己而不是留空" "$TABLE" "*GameTableTrickHudSeatsTest" \
  "$(py "$TABLE" "if (index < 0 || size < PLAYER_COUNT) {" "if (index < 0) {")"

# ---- 17 机器人图标宽度算错 ----
mutate "机器人图标宽度漏掉字间距" "$PACK" "*CraftEngineBundleResourcesTest" \
  "$(py "$PACK" "        int height = role == null ? BOT_AVATAR_HEIGHT : BOT_AVATAR_OUTLINED_HEIGHT;
        return height + 1;" "        int height = role == null ? BOT_AVATAR_HEIGHT : BOT_AVATAR_OUTLINED_HEIGHT;
        return height;")"

# ---- 18 头像宽度算式按 8 行（描边开着时算窄）----
mutate "头像宽度算式忽略描边行数" "$HEAD" "*TrickHudViewTest" \
  "$(py "$HEAD" "int rows = outlined ? PackAssets.AVATAR_OUTLINED_PIXELS : PackAssets.AVATAR_HEAD_PIXELS;" "int rows = PackAssets.AVATAR_HEAD_PIXELS;")"

# ---- 19 头像字形的越界校验借回牌表（串表：同一下标在两表是不同像素值）----
# 过滤器必须指向 PlayerHeadRendererTest：守这条的是那里的「越界校验查的是头像表」测试。
# 两张表在下标 0..12 上都合法，所以只有表尾越界那一格能观察到差异。
mutate "头像字形越界校验借回牌表" "$PACK" "*PlayerHeadRendererTest" \
  "$(py "$PACK" "        // 同时承担偏移档的越界校验，查的是头像自己那张表。
        avatarDownOffsetAt(downOffsetTier);" "        cardGlyphDownOffsetAt(downOffsetTier);")"

# ---- 20 头像条目名借回牌表（构建侧与插件侧名字对不上）----
mutate "头像条目名借回牌表" "$PACK" "*CraftEngineBundleResourcesTest" \
  "$(py "$PACK" "return \"avatar_px_\" + scale + \"_\" + row + \"_d\" + avatarDownOffsetAt(downOffsetTier);" "return \"avatar_px_\" + scale + \"_\" + row + \"_d\" + cardGlyphDownOffsetAt(downOffsetTier);")"

# ---- 21 bot 图标的越界校验借回牌表 ----
mutate "bot 图标越界校验借回牌表" "$PACK" "*PlayerHeadRendererTest" \
  "$(py "$PACK" "        // 先校验再分支：越界的档位不能因为「刚好是 0」就悄悄放过去。
        avatarDownOffsetAt(downOffsetTier);" "        cardGlyphDownOffsetAt(downOffsetTier);")"

# ---- 22 牌面码位公式改用头像表档数（牌面码位整体平移）----
mutate "牌面码位公式改用头像表档数" "$PACK" "*CraftEngineBundleResourcesTest" \
  "$(py "$PACK" "int tier = heightTier * cardGlyphDownOffsetTierCount() + downOffsetTier;" "int tier = heightTier * avatarDownOffsetTierCount() + downOffsetTier;")"

# ---- 23 两行独立性被破坏：头像档位无视 config，恒取默认 ----
mutate "头像档位恒取默认（avatar-offset-down 失效）" "$SVC" "*TrickHudSettingsTest" \
  "$(py "$SVC" "        int avatarDownOffsetTier = PackAssets.avatarDownOffsetTierOf(avatarOffsetDown);" "        int avatarDownOffsetTier = PackAssets.avatarDownOffsetTierOf(DEFAULT_AVATAR_OFFSET_DOWN);")"

# ---- 24 牌行档位跟着头像键走（改 avatar-offset-down 会带动牌行）----
mutate "牌行档位改读头像键" "$SVC" "*TrickHudSettingsTest" \
  "$(py "$SVC" "        int offsetDown = config.getInt(\"trick-hud.offset-down\", DEFAULT_OFFSET_DOWN);" "        int offsetDown = config.getInt(\"trick-hud.avatar-offset-down\", DEFAULT_OFFSET_DOWN);")"

# ---- 25 列间缝：不抵消位图字形那 1 像素字间距（用户截图里的百叶窗）----
mutate "不抵消字形字间距（列间露 1 像素缝）" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "                pending -= GLYPH_TRAILING_SPACING;" "")"

# ---- 26 列距写回 scale + 1（advanceWidth 与渲染同时改错，视觉宽度算大）----
mutate "advanceWidth 写回 rows*(scale+1)" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "        return rows * scale;" "        return rows * (scale + 1);")"

# ---- 27 透明像素的占位偏移多走 1 像素（同行右边的列逐个右移）----
mutate "透明像素占位偏移用 scale+1" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "                    pending += scale;" "                    pending += scale + 1;")"

# ---- 28 收尾不吐净剩余偏移（净前进量比 advanceWidth 多 1，槽内居中逐槽累积偏移）----
mutate "收尾漏掉最后一段偏移" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "        if (pending != 0) {
            builder.append(offsetProvider.apply(pending));
        }
        builder.append(\"</font>\");" "        builder.append(\"</font>\");")"

# ---- 29 皮肤分配丢掉排序（座位轮转时机器人每轮换脸）----
mutate "皮肤分配名单不排序" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "            .distinct()
            .sorted()
            .toList();" "            .distinct()
            .toList();")"

# ---- 30 皮肤分配丢掉顺位探测（同桌两个 bot 有概率重脸）----
mutate "皮肤分配不做去重探测" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "            for (int step = 1; step < skins && taken[chosen]; step++) {
                chosen = (preferred + step) % skins;
            }" "")"

# ---- 31 皮肤分配退化成「按排名取第 rank 张」（后面几张皮肤永远见不到）----
mutate "皮肤分配退化成按排名取" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "            int preferred = Math.floorMod(stableHash(candidate), skins);" "            int preferred = roster.indexOf(candidate) % skins;")"

# ---- 32 顺位探测没有上界（bot 多于皮肤数时死循环，主线程刷 HUD 会卡服）----
mutate "顺位探测去掉上界（死循环）" "$HEAD" "*PlayerHeadRendererTest" \
  "$(py "$HEAD" "            for (int step = 1; step < skins && taken[chosen]; step++) {" "            for (int step = 1; taken[chosen]; step++) {")"

# ---- 33 取到皮肤后仍报位图图标的宽度（机器人头像压到隔壁槽）----
mutate "取到皮肤仍报图标宽度" "$SVC" "*TrickHudBotAvatarTest" \
  "$(py "$SVC" "            return new TrickHudView.Avatar(rendered, PlayerHeadRenderer.advanceWidth(scale, outlined));" "            return new TrickHudView.Avatar(rendered, PackAssets.botAvatarAdvanceWidth(seat.role()));")"

# ---- 34 皮肤取不到时直接留空槽（删掉位图兜底）----
mutate "皮肤取不到时留空槽" "$SVC" "*TrickHudBotAvatarTest" \
  "$(py "$SVC" "        if (rendered != null) {
            // 【宽度必须和渲染那边同源】" "        if (rendered == null) {
            return TrickHudView.Avatar.EMPTY;
        }
        if (rendered != null) {
            // 【宽度必须和渲染那边同源】")"

# ---- 35 皮肤名单混进真人（真人占掉皮肤下标，还让名单随在线状态漂移）----
mutate "皮肤名单混进真人" "$SVC" "*TrickHudBotAvatarTest" \
  "$(py "$SVC" "            if (seat != null && seat.isBot() && seat.playerId() != null) {" "            if (seat != null && seat.playerId() != null) {")"

echo "== 变异验证结束：CAUGHT $pass 条，问题 $fail 条 =="
git status --short | grep -E "^ M" | head
