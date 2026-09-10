package linmumua.doudizhu.debug;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import linmumua.doudizhu.DoudizhuPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Debug Web 调试面板（端口默认 2000）。
 *
 * <p>启用后在本机监听指定端口，提供渲染器 GUI 设置页面；
 * 浏览器访问 {@code http://localhost:<port>} 即可打开。
 * 仅供本地调试使用，禁止将此端口暴露到公网。
 *
 * <p>集成方式（{@code DoudizhuPlugin.onEnable()} 末尾）：
 * <pre>
 * if (yamlConfig().getBoolean("debug.web-ui.enabled", false)) {
 *     debugWebServer = new DebugWebServer(this,
 *         () -> syncHotbarHudRuntime(true),  // 接管可拖动 ascent 字形，仍继续推送
 *         () -> syncHotbarHudRuntime(false)); // 停止接管，恢复 bundle 固定 ascent 字形
 *     debugWebServer.start(yamlConfig().getInt("debug.web-ui.port", 2000));
 * }
 * </pre>
 * {@code onDisable()} 必须调用 {@link #close()} 防止端口占用与异步 HUD 任务泄漏。
 *
 * <p>HTTP 请求在独立守护线程池中处理；需要主线程操作时通过
 * {@code BukkitScheduler.runTask} 切回，禁止在请求线程直接调用 Bukkit API。
 */
public final class DebugWebServer {
    private static final Gson GSON = new Gson();
    private static final int MAX_BODY_BYTES = 16 * 1024;
    /**
     * HTTP 层仅保留比协调器 120 秒结果租约略长的保护等待；超时只结束本次请求，
     * 不取消 coordinator 底层任务。迟到结果由 coordinator 自己按 generation 丢弃。
     */
    private static final long HTTP_APPLY_PROTECTION_TIMEOUT_SECONDS = 125L;

    private final DoudizhuPlugin plugin;
    /** 服务器启动时回调；Debug Web 接管 hotbar 定位参数，但不停止 PLAYING 阶段推送。 */
    private final Runnable onStart;
    /** 服务器停止时回调；结束 Debug Web 接管并恢复 bundle 固定 ascent 字形。 */
    private final Runnable onStop;
    private final DebugHudConfigController controller;
    private final HudWebApplyCoordinator applyCoordinator;
    private final String token;

    private HttpServer httpServer;
    private ExecutorService executor;
    /** 当前监听端口；未运行时为 0。 */
    private int activePort;
    /** HTTP 线程只能读取这份快照，不能直接访问 MuzYamlConfig。 */
    private volatile DebugHudConfigController.Snapshot snapshot;

    /**
     * @param plugin  插件主类，用于读取配置和调度主线程任务
     * @param onStart 启动回调；传 {@code hotbarHudService::stop} 可在面板开启时停热键栏
     * @param onStop  停止回调；传 {@code hotbarHudService::start} 可在面板关闭时恢复热键栏
     */
    public DebugWebServer(DoudizhuPlugin plugin, Runnable onStart, Runnable onStop) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.onStart = onStart;
        this.onStop = onStop;
        this.controller = new DebugHudConfigController(plugin);
        this.applyCoordinator = new HudWebApplyCoordinator(plugin, controller);
        this.token = newToken();
    }

    /**
     * 启动 HTTP 服务器。调用前应确认 debug.web-ui.enabled=true。
     *
     * @param port 监听端口（来自 debug.web-ui.port）
     */
    public void start(int port) {
        if (httpServer != null) {
            return; // 已在运行，幂等
        }
        if (port < 1 || port > 65535) {
            plugin.getLogger().severe("Debug Web 调试面板端口非法：" + port + "（必须为 1..65535）");
            return;
        }
        try {
            // start 通常从 onEnable 主线程调用，此处立即刷新快照；HTTP 线程后续只读 volatile snapshot。
            snapshot = controller.snapshot();
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            httpServer.createContext("/", this::handleRoot);
            httpServer.createContext("/api/save", this::handleSave);
            httpServer.createContext("/api/reload", this::handleReload);
            // 守护线程：随 JVM 退出自动终止，不阻塞 shutdown
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "muz-debug-web");
                t.setDaemon(true);
                return t;
            });
            httpServer.setExecutor(executor);
            httpServer.start();
            activePort = port;
            plugin.getLogger().info("Debug Web 调试面板已启动，端口 " + port
                + "，访问 http://localhost:" + port + "（仅监听本机回环地址）");
            if (onStart != null) {
                onStart.run();
            }
        } catch (IOException | RuntimeException e) {
            if (httpServer != null) {
                httpServer.stop(0);
                httpServer = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            plugin.getLogger().severe("Debug Web 调试面板启动失败（端口 " + port
                + "）：" + e.getMessage() + "（端口可能被占用，修改 debug.web-ui.port 后重启）");
        }
    }

    /** 停止 HTTP 服务器。{@code onDisable} 必须调用，否则端口占用导致下次启动失败。 */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            activePort = 0;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            plugin.getLogger().info("Debug Web 调试面板已停止");
            if (onStop != null) {
                onStop.run();
            }
        }
    }

    /** 停止 HTTP 服务并释放 HUD 异步应用执行器。 */
    public void close() {
        stop();
        applyCoordinator.close();
    }

    /** 是否正在运行。 */
    public boolean isRunning() {
        return httpServer != null;
    }

    /** 当前监听端口；未运行时返回 0。 */
    public int getPort() {
        return activePort;
    }

    /** 首页：可编辑的渲染器 GUI 设置预览面板。 */
    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            sendJson(exchange, 404, Map.of("ok", false, "messages", List.of("接口不存在。")));
            return;
        }
        byte[] bytes = buildHtml(snapshot, token).getBytes(StandardCharsets.UTF_8);
        addSecurityHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** /api/save（POST）：只保存提交的白名单键，成功后轻量应用 HUD 运行态。 */
    private void handleSave(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }
        if (!hasValidToken(exchange)) {
            sendJson(exchange, 403, Map.of("ok", false, "messages", List.of("X-MUZ-Token 无效。")));
            return;
        }
        if (!isJsonContent(exchange)) {
            sendJson(exchange, 415, Map.of("ok", false, "messages", List.of("Content-Type 必须是 application/json。")));
            return;
        }

        String body;
        try {
            body = readBody(exchange);
        } catch (PayloadTooLargeException exception) {
            sendJson(exchange, 413, Map.of("ok", false, "messages", List.of(exception.getMessage())));
            return;
        }

        final DebugHudConfigController.Patch patch;
        try {
            patch = DebugHudConfigController.parsePatch(body);
        } catch (DebugHudConfigController.ValidationException exception) {
            sendJson(exchange, 400, Map.of("ok", false, "messages", List.of(exception.getMessage())));
            return;
        }
        // 空 patch 也交给 coordinator 检查 closed 状态；未关闭时直接返回快照，且不触发资源流程。
        try {
            HudWebApplyCoordinator.ApplyResult result = applyCoordinator.submitSave(patch)
                .get(HTTP_APPLY_PROTECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            snapshot = result.snapshot();
            sendJson(exchange, result.ok() ? 200 : 500,
                apiPayload(result.ok(), result.snapshot(), result.appliedKeys(), result.messages()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, apiPayload(false, snapshot, List.of(), List.of("HTTP 线程被中断。")));
        } catch (TimeoutException exception) {
            // 只结束本次 HTTP 等待，不取消 coordinator；它会继续持有自己的任务租约并防护迟到结果。
            sendJson(exchange, 504, apiPayload(false, snapshot, List.of(),
                List.of("HUD 保存流程超过 HTTP 保护等待时间，后台应用任务仍在继续。")));
        } catch (ExecutionException exception) {
            sendJson(exchange, 500, apiPayload(false, snapshot, List.of(),
                List.of("HUD 保存流程失败：" + String.valueOf(exception.getMessage()))));
        }
    }

    /**
     * /api/reload（POST）：从网页重新读取配置并刷新页面快照。
     * Bukkit API 必须在主线程，此处通过 BukkitScheduler 切回。
     */
    private void handleReload(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }
        if (!hasValidToken(exchange)) {
            sendJson(exchange, 403, Map.of("ok", false, "messages", List.of("X-MUZ-Token 无效。")));
            return;
        }
        if (!isJsonContent(exchange)) {
            sendJson(exchange, 415, Map.of("ok", false, "messages", List.of("Content-Type 必须是 application/json。")));
            return;
        }
        final String body;
        try {
            body = readBody(exchange);
        } catch (PayloadTooLargeException exception) {
            sendJson(exchange, 413, Map.of("ok", false, "messages", List.of(exception.getMessage())));
            return;
        }
        // reload 也必须先完成 JSON 对象校验，不能让任意请求体绕过 HTTP 层校验直接进入异步配置流程。
        try {
            DebugHudConfigController.parsePatch(body);
        } catch (DebugHudConfigController.ValidationException exception) {
            sendJson(exchange, 400, Map.of("ok", false, "messages", List.of(exception.getMessage())));
            return;
        }

        try {
            HudWebApplyCoordinator.ApplyResult result = applyCoordinator.submitReload()
                .get(HTTP_APPLY_PROTECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            snapshot = result.snapshot();
            sendJson(exchange, result.ok() ? 200 : 500,
                apiPayload(result.ok(), result.snapshot(), result.appliedKeys(), result.messages()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, apiPayload(false, snapshot, List.of(), List.of("HTTP 线程被中断。")));
        } catch (TimeoutException exception) {
            // 只结束本次 HTTP 等待，不取消 coordinator；它会继续持有自己的任务租约并防护迟到结果。
            sendJson(exchange, 504, apiPayload(false, snapshot, List.of(),
                List.of("HUD 重载流程超过 HTTP 保护等待时间，后台应用任务仍在继续。")));
        } catch (ExecutionException exception) {
            sendJson(exchange, 500, apiPayload(false, snapshot, List.of(),
                List.of("HUD 重载流程失败：" + String.valueOf(exception.getMessage()))));
        }
    }

    private Map<String, Object> apiPayload(
        boolean ok,
        DebugHudConfigController.Snapshot snapshot,
        List<String> appliedKeys,
        List<String> messages
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", ok);
        payload.put("snapshot", snapshot);
        payload.put("appliedKeys", appliedKeys);
        payload.put("messages", messages);
        return payload;
    }

    private static boolean isJsonContent(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
    }

    private boolean hasValidToken(HttpExchange exchange) {
        return token.equals(exchange.getRequestHeaders().getFirst("X-MUZ-Token"));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException("请求体不能超过 16KB。");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void sendMethodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendJson(exchange, 405, Map.of("ok", false, "messages", List.of("只允许 " + allowed + " 方法。")));
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        addSecurityHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void addSecurityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; "
                + "img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'");
    }

    static String buildHtml(DebugHudConfigController.Snapshot snapshot, String token) {
        DebugHudConfigController.Snapshot safeSnapshot = snapshot == null
            ? new DebugHudConfigController.Snapshot(Map.of(), List.of("配置快照尚未初始化。"), List.of())
            : snapshot;
        String stateJson = escapeJsonForScript(GSON.toJson(safeSnapshot));
        // 溢出防护靠 minmax(0,...)/min-width:0/word-break 从源头约束子元素尺寸，
        // 而不是在 body 或 .panel 上用 overflow:hidden 裁切——裁切会创建新的滚动容器，
        // 导致 .actions 的 position:sticky 失效（sticky 只在最近的滚动祖先内生效）。
        // 预览区的大尺寸 MC 像素内容在 .preview（overflow:auto）内独立横滚。
        String styles = "*{box-sizing:border-box}body{margin:0;background:#202326;color:#f4f1e8;font-family:Verdana,'Segoe UI',sans-serif;image-rendering:pixelated}"
            + "header{padding:18px 22px;background:#303438;border-bottom:4px solid #17191b;box-shadow:0 4px 0 #111}h1{margin:0;color:#f1c75b;font-size:22px;text-shadow:2px 2px #17191b}"
            // main 网格：宽屏两列、窄屏媒体查询里降为单列。minmax(0,...) 防止隐式最小宽度撑破容器。
            + "main{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(0,.8fr);gap:18px;padding:18px}"
            + ".panel{background:#303438;border:3px solid #17191b;border-right-color:#62666a;border-bottom-color:#62666a;padding:16px;box-shadow:6px 6px 0 #111;min-width:0}"
            + "h2{margin:6px 0 12px;color:#f1c75b;font-size:18px;text-shadow:1px 1px #17191b}.group{margin-bottom:18px}.field{display:grid;grid-template-columns:minmax(100px,190px) minmax(0,1fr) auto;gap:8px;align-items:center;padding:8px 0;border-bottom:2px solid #25282a}"
            // .key 是配置键名（如 trick-hud.avatar-outline.enabled），长名必须允许换行，否则撑宽标签列。
            + "label{font-size:13px;color:#eee9dc;min-width:0}.key{display:block;color:#a9abad;font-size:11px;word-break:break-all}input,select{accent-color:#d9a93a}"
            + "input[type=number],input[type=text],select{width:100%;min-width:0;background:#1c1f21;color:#fff;border:2px solid #111;border-top-color:#686d70;border-left-color:#686d70;padding:7px}input:focus-visible,select:focus-visible{outline:2px solid #f1c75b;outline-offset:1px}"
            + "input[type=range]{width:100%;min-width:0}.dirty label{color:#f1c75b}"
            + "button{background:#b77b22;color:#fff;border:2px solid #17191b;border-right-color:#e0bd68;border-bottom-color:#e0bd68;padding:9px 15px;cursor:pointer;font-weight:700;text-shadow:1px 1px #4c3210}button.secondary{background:#565b5e}button:disabled{opacity:.55;cursor:not-allowed}button:focus-visible{outline:2px solid #f1c75b;outline-offset:2px}"
            + ".actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:12px;position:sticky;bottom:0;z-index:10;background:#303438;padding:10px 0 6px;border-top:2px solid #25282a}"
            + ".msg-fade{animation:msgFade 5s ease-in forwards}@keyframes msgFade{0%,80%{opacity:1}100%{opacity:0}}"
            // .preview 用 overflow:auto 让 MC 像素预览在面板内横滚，不撑破外层布局。
            + ".preview{min-height:340px;overflow:auto;background:#17191b;position:relative;padding:12px;border:3px solid #111;border-right-color:#666;max-width:100%}"
            + ".screen{position:relative;margin:0 auto;background:repeating-linear-gradient(0deg,#202326 0,#202326 7px,#24282a 8px),repeating-linear-gradient(90deg,#202326 0,#202326 7px,#24282a 8px);overflow:hidden;outline:3px solid #080909;box-shadow:4px 4px 0 #080909}"
            + ".ref{position:absolute;pointer-events:none}.ref.boss{background:#5c3d2177;outline:2px solid #d4a943}"
            + ".ref.bottom{border-top:2px dashed #d4a943;left:0;right:0}.ref.mid{border-left:2px dashed #d4a943;top:0;bottom:0}.ref.grid{background-image:linear-gradient(#ffffff0b 1px,transparent 1px),linear-gradient(90deg,#ffffff0b 1px,transparent 1px);background-size:8px 8px;inset:0}"
            + ".layer{position:absolute;cursor:grab;outline:2px solid transparent}.layer:hover{outline-color:#f1c75b}.layer.drag{cursor:grabbing;outline-color:#d26b48}"
            + ".layer .tag{position:absolute;top:-17px;left:0;font-size:10px;color:#f1c75b;white-space:nowrap;pointer-events:none;text-shadow:1px 1px #111}"
            + ".cardbox{position:absolute;background:#f9fafb;outline:1px solid #222;color:#111;font-size:9px;font-weight:800;text-align:center;overflow:hidden}"
            + ".avslot{position:absolute}.avbox{position:absolute;background:#4e8cff;outline:1px solid #111}.avbox.crowned{background:#d7a52b}.avbox.empty{background:#25282a;opacity:.6}.cnt{position:absolute}.cnt-label{position:absolute;left:0;top:0;width:100%;color:#fff;text-align:center;font-size:10px}.cnt-frame{position:absolute;box-sizing:border-box;outline:1px solid #8bd5ff;background:#8bd5ff33}.cnt-digit{position:absolute;color:#b8b8b8;text-align:center;font-size:9px}.cnt.exhausted .cnt-label,.cnt.exhausted .cnt-digit{color:#777;opacity:.55}.cnt.exhausted .cnt-frame{outline-color:#777;background:#7773}"
            + ".hb{position:absolute;box-sizing:border-box;border:0}.scalebar{display:flex;gap:8px;align-items:center;margin:0 0 10px;font-size:12px;color:#eee9dc;flex-wrap:wrap}"
            + ".warn{color:#e6aa63}.ok{color:#a7d46f}.msg{min-height:22px;color:#eee9dc}code{color:#f1c75b}"
            // 窄屏：900px 以下降为单列堆叠，字段网格缩窄但保持三列；
            // 500px 以下字段堆叠为标签在上、输入在下的两行布局，适配手机。
            + "@media(max-width:900px){main{grid-template-columns:1fr}.field{grid-template-columns:minmax(80px,140px) minmax(0,1fr) auto;gap:6px}}"
            + "@media(max-width:500px){.field{grid-template-columns:1fr;gap:4px}.field>label{min-width:0}header{padding:12px}main{padding:10px;gap:10px}.panel{padding:10px}}";
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>MUZ Debug HUD 调试面板</title><style>" + styles + "</style></head>"
            + "<body data-token='" + htmlEscape(token) + "'><header><h1>MUZ Debug HUD 调试面板</h1>"
            + "<div>只开放 19 个 HUD 运行期字段；保存会异步写入配置与当前 hotbar 覆盖层，再在主线程重载 CraftEngine 并应用 HUD。客户端需重新下载资源包。</div></header>"
            + "<main><section class='panel'><h2>可编辑配置</h2><form id='hudForm'></form>"
            + "<div class='actions'><button type='button' id='saveBtn' title='Ctrl+S'>保存并应用</button>"
            + "<button type='button' class='secondary' id='reloadBtn' title='Ctrl+R'>重新读取</button>"
            + "<button type='button' class='secondary' id='undoBtn'>撤销</button></div><p id='message' class='msg'></p></section>"
            + "<section class='panel'><h2>像素预览（可拖动）</h2>"
            + "<div class='scalebar'><label>逻辑视口（MC px）</label><input id='viewportWidth' type='number' min='320' max='1920' step='1' value='640' aria-label='逻辑视口宽度'>"
            + "<span>×</span><input id='viewportHeight' type='number' min='240' max='1080' step='1' value='360' aria-label='逻辑视口高度'>"
            + "<span>仅页面校准，不写入 19 个 HUD 配置键</span></div>"
            + "<div class='scalebar'><label>客户端 GUI 倍率</label><select id='guiScale'>"
            + "<option value='2'>2</option><option value='3' selected>3</option><option value='4'>4</option></select>"
            + "<label>页面查看倍率</label><select id='pageScale'><option value='0.25'>1/4</option><option value='0.3333333333' selected>1/3</option><option value='0.5'>1/2</option><option value='1'>1</option></select>"
            + "<span>CSS px/MC px = GUI 倍率 × 页面查看倍率；纵向 hotbar 需保存后重载资源包。</span></div>"
            + "<div class='scalebar'><label><input type='checkbox' id='snapToggle'> Minecraft 风格吸附</label><span id='dragStatus'>吸附：开启 · 中心线：开启 · Alt 轴锁：开启</span><span>资源状态：CraftEngine 覆盖层按保存流程生成并重载</span></div>"
            + "<div class='preview'><div id='screen' class='screen'></div></div>"
            + "<p class='msg' id='dragHint'>提示：预览按 Minecraft 像素绘制，宽度取自资源包实际字形前进量。</p>"
            + "<h2>警告</h2><ul id='warnings'></ul></section></main>"
            + "<script type='application/json' id='muz-state'>" + stateJson + "</script>"
            + "<script>"
            + "const token=document.body.dataset.token;let state=JSON.parse(document.getElementById('muz-state').textContent);let dirty=new Set();let dragging=null;let busy=false;"
            + "let dragCfg=state.drag||{snapEnabled:true,snapThreshold:4,centerGuidesEnabled:true,altAxisLock:true};let pageSnapEnabled=dragCfg.snapEnabled!==false;"
            + "const form=document.getElementById('hudForm'),msg=document.getElementById('message'),warns=document.getElementById('warnings'),dragHint=document.getElementById('dragHint');"
            + "function fieldSpec(k){return state.fields.find(f=>f.key===k)}"
            + "function esc(s){return String(s??'').replace(/[&<>\\\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\\\"':'&quot;',\"'\":'&#39;'})[c])}"
            + "function messagesHtml(xs){return (xs||[]).map(x=>'<div>'+esc(x)+'</div>').join('')}"
            + "function v(k){const f=fieldSpec(k);return state.values&&Object.prototype.hasOwnProperty.call(state.values,k)?state.values[k]:f?f.fallback:null}"
            + "function normalizeValue(k,value){const f=fieldSpec(k);if(!f)return value;if(f.type==='boolean')return!!value;if(f.type==='integer')return Number(value);return String(value??'').trim().toUpperCase()}"
            + "function control(k){const q=CSS.escape(k);return form.querySelector(\"[data-key='\"+q+\"']:not([type='range'])\")||form.querySelector(\"[data-key='\"+q+\"']\")}"
            + "function readValue(k){const f=fieldSpec(k),el=control(k);if(!el)return v(k);return f.type==='boolean'?el.checked:(f.type==='integer'?Number(el.value):el.value)}"
            + "function refreshDragStatus(){dragCfg=state.drag||{snapEnabled:true,snapThreshold:4,centerGuidesEnabled:true,altAxisLock:true};dragCfg={...dragCfg,snapEnabled:pageSnapEnabled};const toggle=document.getElementById('snapToggle');if(toggle){toggle.checked=pageSnapEnabled;toggle.onchange=()=>{pageSnapEnabled=toggle.checked;refreshDragStatus();setPrompt('页面拖动吸附已'+(pageSnapEnabled?'开启':'关闭')+'（仅当前页面，不写入 HUD 配置）。')}}document.getElementById('dragStatus').textContent='吸附：'+(pageSnapEnabled?'开启':'关闭')+'（阈值 '+dragCfg.snapThreshold+'px） · 中心线：'+(dragCfg.centerGuidesEnabled?'开启':'关闭')+' · Alt 轴锁：'+(dragCfg.altAxisLock?'开启':'关闭')}"
            + "function setPrompt(text){msg.textContent=text; if(!dragging)dragHint.textContent=text}"
            + "function setDirtyPrompt(){setPrompt(dirty.size?'有未保存改动：'+dirty.size+' 项':'当前没有未保存改动。')}"
            + "function updateDirty(k){const same=normalizeValue(k,readValue(k))===normalizeValue(k,v(k));if(same)dirty.delete(k);else dirty.add(k);const el=control(k),row=el&&el.closest('.field');if(row)row.classList.toggle('dirty',dirty.has(k));}"
            + "function setBusy(on){busy=on;document.querySelectorAll('#hudForm input,#hudForm select,#saveBtn,#reloadBtn,#undoBtn,#viewportWidth,#viewportHeight,#guiScale,#pageScale,#snapToggle').forEach(el=>el.disabled=on)}"
            + "function renderForm(){form.innerHTML='';let groups={};state.fields.forEach(f=>(groups[f.group]??=[]).push(f));Object.entries(groups).forEach(([g,fs])=>{let box=document.createElement('div');box.className='group';box.innerHTML='<h2>'+esc(g)+'</h2>';fs.forEach(f=>box.appendChild(field(f)));form.appendChild(box)});refreshDragStatus();renderPreview();renderWarnings();if(!busy&&!dragging)setDirtyPrompt()}"
            + "function field(f){let row=document.createElement('div');row.className='field';let val=v(f.key);let left=document.createElement('label');left.innerHTML=esc(f.label)+'<span class=key>'+esc(f.key)+'</span>';let mid=document.createElement('div');let right=document.createElement('div');if(f.type==='boolean'){let i=document.createElement('input');i.type='checkbox';i.checked=!!val;i.dataset.key=f.key;i.onchange=changed;mid.appendChild(i);right.textContent=i.checked?'true':'false'}else if(f.type==='integer'){if(f.control==='select'){let s=document.createElement('select');s.dataset.key=f.key;(f.options||[]).forEach(o=>{let option=document.createElement('option');option.value=o;option.textContent=o;option.selected=Number(o)===Number(val);s.appendChild(option)});s.onchange=changed;mid.appendChild(s);right.textContent='资源档位'}else if(f.control==='range'){let r=document.createElement('input');r.type='range';r.min=f.min;r.max=f.max;r.step=f.step;r.value=val;r.dataset.key=f.key;let n=document.createElement('input');n.type='number';n.min=f.min;n.max=f.max;n.step=f.step;n.value=val;n.dataset.key=f.key;r.oninput=()=>{n.value=r.value;changed({target:r})};n.onchange=()=>{r.value=n.value;changed({target:n})};n.oninput=()=>{r.value=n.value;changed({target:n})};mid.append(r,n);right.textContent=f.min+'..'+f.max}else{let n=document.createElement('input');n.type='number';if(f.min!=null)n.min=f.min;if(f.max!=null)n.max=f.max;if(f.step!=null)n.step=f.step;n.value=val;n.dataset.key=f.key;n.oninput=changed;mid.appendChild(n);right.textContent=(f.min==null?'无下限':f.min)+'..'+(f.max==null?'无上限':f.max)}}else{let c=document.createElement('input');c.type='color';let color=String(val);c.value=/^#[0-9a-fA-F]{6}$/.test(color)?color:'#'+color.slice(-6);let t=document.createElement('input');t.type='text';t.value=val;t.dataset.key=f.key;c.oninput=()=>{t.value=c.value.toUpperCase();changed({target:t})};t.oninput=()=>{if(/^#[0-9a-fA-F]{6}$/.test(t.value))c.value=t.value;changed({target:t})};mid.append(c,t);right.textContent='#RGB/#ARGB'}row.append(left,mid,right);return row}"
            // row 可能取不到（拖动是从预览层触发的，不一定有对应的 .field 祖先）。
            // 这里必须判空：拖动过程中一次 TypeError 就会中断整个手势，表现成「拖不动」。
            + "function changed(e){if(busy||!e||!e.target||!e.target.dataset.key)return;const k=e.target.dataset.key;updateDirty(k);let row=e.target.closest('.field'),f=fieldSpec(k);if(f&&f.type==='boolean'&&row)row.lastElementChild.textContent=e.target.checked?'true':'false';setDirtyPrompt();renderPreview();renderWarnings()}"
            + "function currentPatch(){let p={};dirty.forEach(k=>{const f=fieldSpec(k),el=control(k);if(!el)throw new Error('找不到配置控件：'+k);p[k]=f.type==='boolean'?el.checked:(f.type==='integer'?Number(el.value):el.value)});return p}"
            + "function collectAll(){let out={...(state.values||{})};dirty.forEach(k=>{out[k]=readValue(k)});return out}"
            + "async function post(url,body){let r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json','X-MUZ-Token':token},body:JSON.stringify(body||{})});let j=await r.json();if(!r.ok||!j.ok)throw new Error((j.messages||['请求失败']).join('；'));return j}"
            + "document.getElementById('saveBtn').onclick=async()=>{if(busy)return;let patch;try{patch=currentPatch()}catch(e){setPrompt(e.message);return}if(!Object.keys(patch).length){setDirtyPrompt();return}setBusy(true);setPrompt('正在保存并应用，请稍候。');try{let j=await post('/api/save',{values:patch});state=j.snapshot;dirty.clear();renderForm();"
            // 保存成功消息：先显示 appliedKeys，再附上服务端返回的 messages（包含 CE 校验/上传/客户端状态）。
            // msg 与 dragHint 保持一致，都使用同一段文本。
            + "let text='已保存并应用：'+((j.appliedKeys||[]).join(', ')||'无改动');let srvMsgs=(j.messages||[]);if(srvMsgs.length)text+='。'+srvMsgs.join('；');"
            + "msg.innerHTML='<span class=\"ok msg-fade\">'+esc(text)+'</span>';dragHint.textContent=text}catch(e){msg.textContent=e.message;dragHint.textContent=e.message}finally{setBusy(false)}};"
            + "document.getElementById('reloadBtn').onclick=async()=>{if(busy)return;setBusy(true);setPrompt('正在重新读取配置，请稍候。');try{let j=await post('/api/reload',{});state=j.snapshot;dirty.clear();renderForm();let text='已重新读取配置。';let srvMsgs=(j.messages||[]);if(srvMsgs.length)text+=srvMsgs.join('；');msg.innerHTML='<span class=\"ok msg-fade\">'+esc(text)+'</span>';dragHint.textContent=text}catch(e){msg.textContent=e.message;dragHint.textContent=e.message}finally{setBusy(false)}};"
            + "document.getElementById('undoBtn').onclick=()=>{if(busy)return;dirty.clear();renderForm();const text='已撤销未保存改动。';msg.textContent=text;dragHint.textContent=text};"
            // Ctrl+S 保存、Ctrl+R 重载快捷键——拦截浏览器默认行为，触发对应按钮 click。
            // Ctrl+S/R 必须始终 preventDefault 阻止浏览器保存/刷新，即使 busy 期间也不放过；
            // 但按钮 click 只在非 busy 时触发（按钮 handler 自己检查 busy）。
            + "document.addEventListener('keydown',e=>{if(e.ctrlKey&&e.key==='s'){e.preventDefault();document.getElementById('saveBtn').click()}if(e.ctrlKey&&e.key==='r'){e.preventDefault();document.getElementById('reloadBtn').click()}});"
            // 【预览几何全部来自服务端 PreviewGeometry】：屏幕基准、行宽、字形前进量、
            // Hotbar 尺寸/ascent 都由 DebugHudConfigController.currentGeometry() 汇总下发，
            // 前端不复算任何字形表。旧版把屏幕基准、字符宽度与固定 cell 估算硬写在这里，
            // 档位或分层几何一改预览就静默错位，现统一由服务端 geometry 集中下发。
            + "function geo(){return state.geometry}"
            + "function guiScale(){return Number(document.getElementById('guiScale').value)}"
            + "function pageScale(){return Number(document.getElementById('pageScale').value)}"
            + "function viewport(){const g=geo();let w=Number(document.getElementById('viewportWidth').value),h=Number(document.getElementById('viewportHeight').value);if(!Number.isFinite(w))w=g.screenWidth;if(!Number.isFinite(h))h=g.screenHeight;return{width:Math.max(320,Math.round(w)),height:Math.max(240,Math.round(h))}}"
            // MC 像素 → CSS 像素：先乘客户端 GUI 倍率，再乘页面查看倍率。页面查看倍率只影响 CSS，
            // 不改变下发的 MC 几何；默认 GUI=3、页面=1/3 时 1 CSS px 对应 1 MC px。
            + "function cssScale(){return guiScale()*pageScale()}function cssPx(mc){return mc*cssScale()}"
            // 头像重叠所需空间 = 牌行 offset-down + 当前档位 avatar.rowHeight（不再是 12*scale 硬编码）
            + "function warningFor(vals){const g=geo();const scale=Number(vals['trick-hud.avatar-scale']);"
            + "const av=(g.avatars||[]).find(x=>Number(x.scale)===scale);if(!av)return null;"
            + "const down=Number(vals['trick-hud.offset-down']),avatarDown=Number(vals['trick-hud.avatar-offset-down']);"
            + "const required=down+Number(av.rowHeight);"
            + "return avatarDown<required?'头像行会与牌行重叠：建议 avatar-offset-down 至少为 '+required+'。':null}"
            // rowGeom 现在只查表，不复算：card/avatar 从 geometry 数组按档位匹配，counter 使用服务端固定 cell advance。
            + "function rowGeom(vals){const g=geo();"
            + "const cards=g.sampleCards||[],n=cards.length,step=Number(vals['trick-hud.card-step']),h=Number(vals['trick-hud.card-height']);"
            + "const card=(g.cards||[]).find(x=>Number(x.height)===h)||{width:0,advance:0,height:h};"
            + "const cardW=Number(card.width),cardAdvance=Number(card.advance),cardHeight=Number(card.height);"
            + "const cardRowWidth=n?((n-1)*step+cardAdvance):0;"
            + "const scale=Number(vals['trick-hud.avatar-scale']),avGap=Number(vals['trick-hud.avatar-gap']),outlined=!!vals['trick-hud.avatar-outline.enabled'];"
            + "const avatar=(g.avatars||[]).find(x=>Number(x.scale)===scale)||{plainAdvance:0,outlinedAdvance:0,rowHeight:0};"
            + "const layout=(g.avatarLayouts||[]).find(x=>Number(x.middleScale)===scale&&!!x.outlined===outlined);"
            + "const slots=layout?(layout.slots||[]).map(x=>({...x})):[];"
            + "const slotWidth=layout?Number(layout.slotWidth||0):0;"
            + "const avatarRowWidth=3*slotWidth+2*avGap;"
            + "const avatarHeight=layout?Number(layout.rowHeight||0):Number(avatar.rowHeight);"
            + "const counterCells=g.counters||[],counterGap=Number(vals['trick-hud.counter.gap']);"
            + "const counterCellWidth=Number(g.counterCellWidth),counterCellHeight=Number(g.counterCellHeight),counterAdvance=Number(g.counterAdvance);"
            + "const counterLabelHeight=Number(g.counterLabelHeight),counterFrameHeight=Number(g.counterFrameHeight),counterDigitHeight=Number(g.counterDigitHeight);"
            + "const counterLabelAscent=Number(g.counterLabelAscent),counterFrameTopDelta=Number(g.counterFrameTopDelta),counterDigitInset=Number(g.counterDigitInset);"
            + "let counterRowWidth=0;if(counterCells.length){counterRowWidth=counterCells.length*counterAdvance+(counterCells.length-1)*counterGap}"
            + "return{cards:cards,cardW:cardW,cardAdvance:cardAdvance,cardRowWidth:cardRowWidth,cardHeight:cardHeight,step:step,n:n,"
            + "avatarSlot:slotWidth,avatarSlots:slots,avatarRowWidth:avatarRowWidth,avatarHeight:avatarHeight,avGap:avGap,"
            + "counterCells:counterCells,counterRowWidth:counterRowWidth,counterGap:counterGap,counterCellWidth:counterCellWidth,"
            + "counterCellHeight:counterCellHeight,counterAdvance:counterAdvance,counterLabelHeight:counterLabelHeight,"
            + "counterFrameHeight:counterFrameHeight,counterDigitHeight:counterDigitHeight,counterLabelAscent:counterLabelAscent,"
            + "counterFrameTopDelta:counterFrameTopDelta,counterDigitInset:counterDigitInset}}"
            // 布局警告缓存：renderPreview 每次重算，renderWarnings 再合并进列表。
            + "let layoutWarnings=[];"
            + "function pushBoundsWarn(name,x,y,w,h){const v=viewport();"
            + "if(x<0)layoutWarnings.push(name+' 越出屏幕左边界（x='+Math.round(x)+'）');"
            + "if(y<0)layoutWarnings.push(name+' 越出屏幕上边界（y='+Math.round(y)+'）');"
            + "if(x+w>v.width)layoutWarnings.push(name+' 越出屏幕右边界（x+w='+Math.round(x+w)+' > '+v.width+'）');"
            + "if(y+h>v.height)layoutWarnings.push(name+' 越出屏幕下边界（y+h='+Math.round(y+h)+' > '+v.height+'）')}"
            + "function renderPreview(){layoutWarnings=[];const vals=collectAll(),g=geo(),v=viewport(),screen=document.getElementById('screen');"
            + "screen.style.width=cssPx(v.width)+'px';screen.style.height=cssPx(v.height)+'px';screen.innerHTML='';"
            + "let html='';"
            // 参照物：BossBar 轨道（装饰性 182x5，仅用于示意；left/top 基于 geometry）、屏幕水平中线、屏幕底边
            + "const bossW=182,bossH=5,bossLeft=Math.floor((v.width-bossW)/2),bossTop=Math.max(0,g.bossBarBaselineY-bossH);"
            + "html+='<div class=\"ref boss\" style=\"left:'+cssPx(bossLeft)+'px;top:'+cssPx(bossTop)+'px;width:'+cssPx(bossW)+'px;height:'+cssPx(bossH)+'px\"></div>';"
            + "if(dragCfg.centerGuidesEnabled){html+='<div class=\"ref grid\"></div><div class=\"ref mid\" style=\"left:'+cssPx(v.width/2)+'px\"></div><div class=\"ref bottom\" style=\"top:'+cssPx(v.height-1)+'px\"></div>';}"
            + "if(vals['trick-hud.enabled']){const r=rowGeom(vals);"
            + "const maxW=Math.max(r.cardRowWidth,r.avatarRowWidth,vals['trick-hud.counter.enabled']?r.counterRowWidth:0);"
            + "const ox=Number(vals['trick-hud.offset-x']);"
            // 整数 MC 像素居中：先按屏幕居中 max 行，再在 max 行内居中当前行。
            + "const baseLeft=Math.floor((v.width-maxW)/2),bossBaseline=g.bossBarBaselineY;"
            // 头像行：ascent = rowHeight - avatar-offset-down；top = baseline - ascent
            + "const avatarAscent=r.avatarHeight-Number(vals['trick-hud.avatar-offset-down']);"
            + "const avX=baseLeft+Math.floor((maxW-r.avatarRowWidth)/2)+ox+Number(vals['trick-hud.avatar-offset-x']);"
            + "const avY=bossBaseline-avatarAscent;"
            + "let oc=String(vals['trick-hud.avatar-outline.color']);if(!/^#[0-9a-fA-F]{6}$/.test(oc))oc='#'+oc.slice(-6);"
            + "const ob=vals['trick-hud.avatar-outline.enabled']?oc:'transparent';"
            + "html+='<div class=layer data-drag=avatar style=\"left:'+cssPx(avX)+'px;top:'+cssPx(avY)+'px;width:'+cssPx(r.avatarRowWidth)+'px;height:'+cssPx(r.avatarHeight)+'px\"><span class=tag>头像行几何示意 avatar-offset-x/down</span>';"
            + "for(let i=0;i<3;i++){const slot=r.avatarSlots[i]||{slotWidth:r.avatarSlot,contentAdvance:r.avatarSlot,rowHeight:r.avatarHeight,crowned:false,empty:true};const slotW=Number(slot.slotWidth||r.avatarSlot),contentW=Number(slot.contentAdvance||slotW),faceH=Number(slot.rowHeight||r.avatarHeight),faceTop=r.avatarHeight-faceH,faceLeft=(slotW-contentW)/2;const classes='avbox'+(slot.crowned?' crowned':'')+(slot.empty?' empty':'');html+='<div class=avslot style=\"left:'+cssPx(i*(r.avatarSlot+r.avGap))+'px;top:0;width:'+cssPx(r.avatarSlot)+'px;height:'+cssPx(r.avatarHeight)+'px\"><div class=\"'+classes+'\" data-position=\"'+esc(slot.position||'')+'\" style=\"left:'+cssPx(faceLeft)+'px;top:'+cssPx(faceTop)+'px;width:'+cssPx(contentW)+'px;height:'+cssPx(faceH)+'px;outline-color:'+ob+'\"></div></div>'}html+='</div>';"
            + "pushBoundsWarn('头像行',avX,avY,r.avatarRowWidth,r.avatarHeight);"
            // 牌行：ascent = cardHeight - offset-down；top = baseline - ascent（offset-down 增大 top 下降）
            + "const cardAscent=r.cardHeight-Number(vals['trick-hud.offset-down']);"
            + "const cdX=baseLeft+Math.floor((maxW-r.cardRowWidth)/2)+ox+Number(vals['trick-hud.card-offset-x']);"
            + "const cdY=bossBaseline-cardAscent;"
            + "html+='<div class=layer data-drag=card style=\"left:'+cssPx(cdX)+'px;top:'+cssPx(cdY)+'px;width:'+cssPx(r.cardRowWidth)+'px;height:'+cssPx(r.cardHeight)+'px\"><span class=tag>牌行 card-offset-x / offset-down</span>';"
            + "const cardLabels=r.cards.map(card=>String(card.label||card.rank||''));"
            + "for(let i=0;i<r.n;i++){html+='<div class=cardbox data-card-index=\"'+i+'\" data-card-rank=\"'+esc(cardLabels[i])+'\" style=\"left:'+cssPx(i*r.step)+'px;top:0;width:'+cssPx(r.cardW)+'px;height:'+cssPx(r.cardHeight)+'px;line-height:'+cssPx(r.cardHeight)+'px\">'+esc(cardLabels[i])+'</div>'}html+='</div>';"
            + "pushBoundsWarn('牌行',cdX,cdY,r.cardRowWidth,r.cardHeight);"
            // 记牌行：服务端固定下发分层 cell geometry，前端只按 geometry 画牌类、数字和闭合矩形。
            // cell 的水平定位使用固定 advance；不读取 label 长度，也不复算任何字体宽度。
            + "if(vals['trick-hud.counter.enabled']){"
            + "const cnX=baseLeft+Math.floor((maxW-r.counterRowWidth)/2)+ox+Number(vals['trick-hud.counter.offset-x']);"
            + "const counterAscent=r.counterLabelAscent-Number(vals['trick-hud.avatar-offset-down']);"
            + "const cnY=bossBaseline-counterAscent;const cnH=r.counterCellHeight;"
            + "html+='<div class=layer data-drag=counter style=\"left:'+cssPx(cnX)+'px;top:'+cssPx(cnY)+'px;width:'+cssPx(r.counterRowWidth)+'px;height:'+cssPx(cnH)+'px\"><span class=tag>记牌行 counter.offset-x</span>';"
            + "let cx=0;r.counterCells.forEach((cell,i)=>{"
            + "const exhausted=!!cell.exhausted,hidden=exhausted&&!!vals['trick-hud.counter.hide-exhausted'];"
            + "const label=String(cell.label),digits=String(cell.playedCount);"
            + "const frameTop=r.counterFrameTopDelta,digitTop=frameTop+r.counterDigitInset;"
            + "const digitWidth=r.counterCellWidth-2*r.counterDigitInset;"
            + "html+='<div class=\"cnt'+(exhausted?' exhausted':'')+(hidden?' hidden':'')+'\" data-label=\"'+esc(cell.label)+'\" data-played-count=\"'+cell.playedCount+'\" data-exhausted=\"'+exhausted+'\" data-hidden=\"'+hidden+'\" style=\"left:'+cssPx(cx)+'px;top:0;width:'+cssPx(r.counterCellWidth)+'px;height:'+cssPx(r.counterCellHeight)+'px\">';"
            + "if(!hidden){html+='<div class=cnt-label style=\"height:'+cssPx(r.counterLabelHeight)+'px;line-height:'+cssPx(r.counterLabelHeight)+'px\">'+esc(label)+'</div>';"
            + "html+='<div class=cnt-frame style=\"left:0;top:'+cssPx(frameTop)+'px;width:'+cssPx(r.counterCellWidth)+'px;height:'+cssPx(r.counterFrameHeight)+'px\"></div>';"
            + "html+='<div class=cnt-digit style=\"left:'+cssPx(r.counterDigitInset)+'px;top:'+cssPx(digitTop)+'px;width:'+cssPx(digitWidth)+'px;height:'+cssPx(r.counterDigitHeight)+'px;line-height:'+cssPx(r.counterDigitHeight)+'px\">'+esc(digits)+'</div>';}html+='</div>';"
            + "cx+=r.counterAdvance+(i<r.counterCells.length-1?r.counterGap:0)});"
            + "html+='</div>';"
            + "pushBoundsWarn('记牌行',cnX,cnY,r.counterRowWidth,cnH)}}"
            // hotbar 定位公式（批准版）：
            //   baseAscent = g.hotbarBaseAscent
            //   currentAscent = baseAscent - hy
            //   ascentDelta = baseAscent - currentAscent   （= hy）
            //   hbY = actionBarBottomY - hotbarHeight + ascentDelta
            //   hbX = floor((screenWidth - hotbarAdvance)/2) + hx
            // 完整 9 槽热键栏：182×22，槽块 18×20，x=2,22...162，不影响 Hotbar 外框几何。
            + "if(vals['hotbar-hud.enabled']){"
            + "const hy=Number(vals['hotbar-hud.offset-y']),hx=Number(vals['hotbar-hud.offset-x']);"
            + "const baseAscent=g.hotbarBaseAscent,currentAscent=baseAscent-hy,ascentDelta=baseAscent-currentAscent;"
            + "const hbY=v.height-g.hotbarHeight+ascentDelta;"
            + "const hbX=Math.floor((v.width-g.hotbarAdvance)/2)+hx;"
            + "html+='<div class=layer data-drag=hotbar style=\"left:'+cssPx(hbX)+'px;top:'+cssPx(hbY)+'px;width:'+cssPx(g.hotbarWidth)+'px;height:'+cssPx(g.hotbarHeight)+'px;background:#121216\"><span class=tag>Hotbar offset-x / offset-y（纵向需重载资源包）</span>';"
            + "const cols=['#E03A3A','#E06A2A','#E08A2A','#D8D030','#3CC050','#30C0A8','#3888E0','#7050D8','#C04AA0'];"
            + "const slotW=18,slotH=20,slotStep=20,slotsStart=2;"
            + "for(let i=0;i<9;i++){html+='<div class=hb style=\"left:'+cssPx(slotsStart+i*slotStep)+'px;top:'+cssPx(1)+'px;width:'+cssPx(slotW)+'px;height:'+cssPx(slotH)+'px;background:'+cols[i]+'\"></div>'}html+='</div>';"
            + "pushBoundsWarn('Hotbar',hbX,hbY,g.hotbarWidth,g.hotbarHeight)}"
            // 【拖动期间绝对不能重建 DOM】：真实鼠标按下时浏览器会做「隐式指针捕获」，
            // 把指针事件锁定到 pointerdown 的那个 target 元素上。一旦这个元素被
            // innerHTML 重建销毁，浏览器就派发 pointercancel 并【停止派发后续
            // pointermove】—— 表现正是「点一次只能拖动一下」。
            // 用合成 PointerEvent 测不出来这个问题：合成事件不走隐式捕获，
            // 所以哪怕元素被销毁，dispatchEvent 仍然照常触发。
            // 拖动时走 nudgeDraggedLayer 只改 style，松手后才做完整重建。
            + "screen.innerHTML=html;bindDrag()}"
            // 拖动：把 CSS 位移换算回 MC 像素写进表单。松手才提交（纵向重打包代价高）。
            + "const DRAG_KEYS={avatar:['trick-hud.avatar-offset-x','trick-hud.avatar-offset-down'],"
            + "card:['trick-hud.card-offset-x','trick-hud.offset-down'],"
            + "counter:['trick-hud.counter.offset-x',null],"
            + "hotbar:['hotbar-hud.offset-x','hotbar-hud.offset-y']};"
            + "function controls(k){const q=CSS.escape(k);return form.querySelectorAll(\"[data-key='\"+q+\"']\")}"
            + "function writeValue(k,value){controls(k).forEach(el=>{if(el.type==='checkbox')el.checked=!!value;else el.value=value})}"
            + "function setField(key,val){const el=control(key);if(!el)return false;const f=fieldSpec(key);let next=Math.round(val);"
            + "if(f&&f.min!=null)next=Math.max(f.min,next);if(f&&f.max!=null)next=Math.min(f.max,next);"
            // 档位型字段（select）只能取资源包实际生成的档，吸附到最近的合法选项，
            // 否则会写出一个后端必然拒绝的值。
            + "if(f&&f.control==='select'&&f.options&&f.options.length){let best=Number(f.options[0]);"
            + "f.options.forEach(o=>{if(Math.abs(Number(o)-next)<Math.abs(best-next))best=Number(o)});next=best}"
            + "writeValue(key,next);if(dragging){updateDirty(key);return true}changed({target:el});return true}"
            // 【拖动为什么不能把监听器挂在被拖的元素上】：拖动过程中要实时更新预览，
            // 而 renderPreview() 是整段重建 screen.innerHTML 的 —— 那会把正在被拖的
            // 元素本身销毁，挂在它上面的 pointermove 与 setPointerCapture 一起消失，
            // 结果拖动只在第一帧生效然后立刻断掉（表现就是「拖不动」）。
            // 所以监听器挂在 window 上：它不随预览重建而消失。
            + "function bindDrag(){document.querySelectorAll('.layer').forEach(el=>{el.onpointerdown=e=>{"
            + "if(busy)return;e.preventDefault();const kind=el.dataset.drag,keys=DRAG_KEYS[kind];if(!keys)return;"
            + "const vals=collectAll(),s=cssScale(),baseLeft=parseFloat(el.style.left)/s,baseTop=parseFloat(el.style.top)/s;"
            + "if(el.setPointerCapture)el.setPointerCapture(e.pointerId);"
            + "dragging={kind:kind,keys:keys,sx:e.clientX,sy:e.clientY,axis:null,hasMoved:false,"
            + "bx:Number(vals[keys[0]]||0),by:keys[1]?Number(vals[keys[1]]||0):0,el:el,"
            + "baseLeft:baseLeft,baseTop:baseTop,width:parseFloat(el.style.width)/s,height:parseFloat(el.style.height)/s,pointerId:e.pointerId};"
            + "el.style.transform='';el.classList.add('drag')}})}"
            + "function clampDelta(base,size,extent,delta){const min=extent-size-base,max=-base;return min<=max?Math.max(min,Math.min(max,delta)):(extent-size)/2-base}"
            + "window.addEventListener('pointermove',ev=>{if(!dragging||busy)return;ev.preventDefault();"
            + "const s=cssScale(),v=viewport(),k=dragging.keys;let dx=(ev.clientX-dragging.sx)/s,dy=(ev.clientY-dragging.sy)/s;"
            // 零位移 pointermove（同坐标或亚像素抖动）不触发 snap，避免点击时意外吸附到中心线。
            + "if(Math.abs(dx)<0.5&&Math.abs(dy)<0.5&&!dragging.hasMoved)return;"
            + "dragging.hasMoved=true;"
            + "if(!k[1])dy=0;if(dragCfg.altAxisLock&&ev.altKey&&dragging.axis===null&&(Math.abs(dx)>=2||Math.abs(dy)>=2))dragging.axis=Math.abs(dx)>=Math.abs(dy)?'x':'y';"
            + "if(dragging.axis==='x')dy=0;if(dragging.axis==='y')dx=0;"
            + "dx=clampDelta(dragging.baseLeft,dragging.width,v.width,dx);if(k[1])dy=clampDelta(dragging.baseTop,dragging.height,v.height,dy);"
            // snap 只在轴锁之后生效：先用 axis 过滤，再 snap，最后 clamp；
            // 这样 Alt 锁 Y 轴后 snap 只作用于 X，不会反向给 Y 施加力。
            + "if(dragCfg.snapEnabled){const tx=(v.width-dragging.width)/2-dragging.baseLeft,ty=(v.height-dragging.height)/2-dragging.baseTop;"
            + "if(dragging.axis!=='y'&&Math.abs(dx-tx)<=dragCfg.snapThreshold)dx=tx;if(k[1]&&dragging.axis!=='x'&&Math.abs(dy-ty)<=dragCfg.snapThreshold)dy=ty;"
            + "dx=clampDelta(dragging.baseLeft,dragging.width,v.width,dx);if(k[1])dy=clampDelta(dragging.baseTop,dragging.height,v.height,dy)}"
            + "setField(k[0],dragging.bx+dx);if(k[1])setField(k[1],dragging.by+dy);"
            // transform 必须使用限幅、吸附和资源档位吸附后的实际值，而不是原始指针 dx/dy。
            + "const actualX=Number(readValue(k[0])),actualY=k[1]?Number(readValue(k[1])):dragging.by;"
            + "const effectiveDx=Number.isFinite(actualX)?actualX-dragging.bx:dx,effectiveDy=k[1]&&Number.isFinite(actualY)?actualY-dragging.by:0;"
            // 拖动期间不重建 DOM，只让当前层视觉上跟着指针走；松手后才完整重建。
            + "dragging.el.classList.add('drag');dragging.el.style.transform='translate('+cssPx(effectiveDx)+'px,'+cssPx(effectiveDy)+'px)';"
            + "dragHint.textContent='拖动中：'+k[0]+'='+Math.round(actualX)+(k[1]?('，'+k[1]+'='+Math.round(actualY)):'')+(dragging.axis?'，Alt 锁 '+dragging.axis:'')});"
            + "function finishDrag(){if(!dragging)return;const active=dragging;dragging=null;"
            + "if(active.el.releasePointerCapture&&active.el.hasPointerCapture&&active.el.hasPointerCapture(active.pointerId))active.el.releasePointerCapture(active.pointerId);"
            // 松手后才完整重建一次，让吸附后的档位值和所有行的 max-width 居中重新计算。
            + "renderPreview();renderWarnings();setDirtyPrompt();"
            + "dragHint.textContent=dirty.size?'已停止拖动，改动尚未保存。点「保存并应用」写回 config.yml。':'当前没有未保存改动。'}"
            + "window.addEventListener('pointerup',finishDrag);window.addEventListener('pointercancel',finishDrag);"
            + "window.addEventListener('lostpointercapture',finishDrag);"
            + "document.getElementById('guiScale').onchange=()=>renderPreview();"
            + "document.getElementById('pageScale').onchange=()=>renderPreview();"
            + "document.getElementById('viewportWidth').onchange=()=>{renderPreview();renderWarnings()};"
            + "document.getElementById('viewportHeight').onchange=()=>{renderPreview();renderWarnings()};"
            // 警告合并：服务端快照警告 + 头像/牌行重叠 + 布局越界（renderPreview 期间收集）
            + "function renderWarnings(){warns.innerHTML='';let list=[...(state.warnings||[])];"
            + "let current=warningFor(collectAll());if(current&&!list.some(x=>x.includes('头像行会与牌行重叠')))list.push(current);"
            + "layoutWarnings.forEach(w=>{if(!list.includes(w))list.push(w)});"
            + "list.forEach(w=>{let li=document.createElement('li');li.className='warn';li.textContent=w;warns.appendChild(li)});"
            + "if(!warns.children.length){let li=document.createElement('li');li.className='ok';li.textContent='当前快照无重叠警告。';warns.appendChild(li)}}"
            + "renderForm();"
            + "</script></body></html>";
    }

    private static String escapeJsonForScript(String raw) {
        return raw.replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026");
    }

    static String htmlEscape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
