package linmumua.doudizhu.debug;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import linmumua.doudizhu.DoudizhuPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
     * 同源只读资源白名单：只允许前端请求构建期生成的 hotbar 相关 PNG。
     *
     * <p>键是后端 {@code hotbars[].texture / selectTexture} 下发的资源名（如
     * {@code "muz:font/hotbar_slots.png"}、{@code "muz:font/scale_75/hotbar_select.png"}），
     * 前端用 {@code /api/resource/<资源名>} 请求。值是 JAR classpath 内嵌路径。
     *
     * <p>三档（75/100/125）× 两个文件（底图 + 选中框）= 固定 6 条。
     * 不要添加非 HUD 调试用途的资源条目，不要开放任意路径。
     */
    private static final Map<String, String> RESOURCE_WHITELIST = buildResourceWhitelist();

    private static Map<String, String> buildResourceWhitelist() {
        // 构建期产物的 classpath 根路径；与 build.gradle.kts 的 outputAssetsRoot 对应
        final String classpathBase = "craftengine/muz/resourcepack/assets/muz/textures/font/";
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        // 100% 默认档：muz:font/hotbar_slots.png → classpath .../font/hotbar_slots.png
        map.put("muz:font/hotbar_slots.png", classpathBase + "hotbar_slots.png");
        map.put("muz:font/hotbar_select.png", classpathBase + "hotbar_select.png");
        // 75% 档：muz:font/scale_75/hotbar_slots.png → classpath .../font/scale_75/hotbar_slots.png
        map.put("muz:font/scale_75/hotbar_slots.png", classpathBase + "scale_75/hotbar_slots.png");
        map.put("muz:font/scale_75/hotbar_select.png", classpathBase + "scale_75/hotbar_select.png");
        // 125% 档：muz:font/scale_125/hotbar_slots.png → classpath .../font/scale_125/hotbar_slots.png
        map.put("muz:font/scale_125/hotbar_slots.png", classpathBase + "scale_125/hotbar_slots.png");
        map.put("muz:font/scale_125/hotbar_select.png", classpathBase + "scale_125/hotbar_select.png");
        return Map.copyOf(map);
    }
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
     * @param onStart 启动回调；用于进入 Debug Web 接管状态并切换可调定位资源
     * @param onStop  停止回调；用于退出 Debug Web 接管状态并恢复 bundle 固定字形
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
            httpServer.createContext("/api/resource/", this::handleResource);
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

    /**
     * /api/resource/{resourceName}（GET）：只读白名单内的构建期 PNG 资源。
     *
     * <p>URL 路径格式为 {@code /api/resource/muz:font/hotbar_slots.png}，
     * 前缀 {@code /api/resource/} 之后的整段作为白名单键查找。
     * 仅允许 {@link #RESOURCE_WHITELIST} 中列出的 6 个固定条目；
     * 用于前端 hotbar 各缩放档的真实图片预览。
     * 不需要 Token——资源不含敏感数据，且服务器仅监听回环地址。
     */
    private void handleResource(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        // 提取 /api/resource/ 之后的完整相对路径作为白名单键
        // 例如 /api/resource/muz:font/scale_75/hotbar_slots.png → muz:font/scale_75/hotbar_slots.png
        final String prefix = "/api/resource/";
        if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
            sendJson(exchange, 404, Map.of("ok", false, "messages", List.of("资源路径无效。")));
            return;
        }
        String name = path.substring(prefix.length());
        // 安全校验：拒绝路径遍历和空名
        if (name.isEmpty() || name.contains("..") || name.startsWith("/")) {
            sendJson(exchange, 400, Map.of("ok", false, "messages", List.of("资源路径包含非法字符。")));
            return;
        }
        String classpathResource = RESOURCE_WHITELIST.get(name);
        if (classpathResource == null) {
            sendJson(exchange, 404, Map.of("ok", false, "messages", List.of("资源不在白名单内：" + name)));
            return;
        }
        byte[] data;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (stream == null) {
                sendJson(exchange, 404, Map.of("ok", false, "messages",
                    List.of("资源包尚未构建或 JAR 中不包含该文件：" + classpathResource)));
                return;
            }
            data = stream.readAllBytes();
        }
        addSecurityHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        // 构建期产物不变，强缓存减少重复读取
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400, immutable");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
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

    /** 测试用：返回白名单键集合（同源资源路由允许的文件名）。 */
    static java.util.Set<String> resourceWhitelistNames() {
        return RESOURCE_WHITELIST.keySet();
    }

    static String buildHtml(DebugHudConfigController.Snapshot snapshot, String token) {
        DebugHudConfigController.Snapshot safeSnapshot = snapshot == null
            ? new DebugHudConfigController.Snapshot(Map.of(), List.of("配置快照尚未初始化。"), List.of())
            : snapshot;
        String stateJson = escapeJsonForScript(GSON.toJson(safeSnapshot));
        // 溢出防护靠 minmax(0,...)/min-width:0/word-break 从源头约束子元素尺寸，
        // 不在 body 或 .panel 上用 overflow:hidden 裁切——裁切会创建新的滚动容器导致 .actions sticky 失效。
        String styles = "*{box-sizing:border-box}html,body{width:100%;height:100%;margin:0;overflow:hidden;background:#101216;color:#f4f1e8;font-family:Verdana,'Segoe UI',sans-serif;image-rendering:pixelated}"
            + "body{min-height:100dvh}header{position:fixed;left:0;right:0;top:0;z-index:30;display:flex;align-items:center;gap:12px;padding:10px 16px;background:linear-gradient(#20251fdd,#151914cc);border-bottom:3px solid #111;box-shadow:0 3px 0 #080909;pointer-events:none}header h1{margin:0;color:#f1c75b;font-size:18px;text-shadow:2px 2px #17191b}header>div{flex:1;min-width:0;color:#d8d7ce;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}header button{pointer-events:auto}h1{margin:0;color:#f1c75b;font-size:22px;text-shadow:2px 2px #17191b}"
            // 全屏 MC 编辑器：画布固定覆盖整个 viewport，编辑表单改为右侧浮动面板。
            + "main.mc-editor{position:relative;width:100vw;height:100dvh;min-height:0;padding:0;display:block}"
            + ".panel{background:#303438;border:3px solid #17191b;border-right-color:#62666a;border-bottom-color:#62666a;padding:16px;box-shadow:6px 6px 0 #111;min-width:0}"
            + ".editor-panel{position:fixed;right:18px;top:64px;z-index:25;width:min(440px,calc(100vw - 36px));max-height:calc(100dvh - 82px);overflow:auto;transition:transform .18s ease,opacity .18s ease}.editor-panel.collapsed{transform:translateX(calc(100% + 30px));opacity:.1;pointer-events:none}"
            + ".preview-panel{position:absolute;inset:0;z-index:1;padding:0;border:0;background:transparent;box-shadow:none;overflow:hidden}.preview-panel>#dragHint{position:fixed;left:14px;bottom:12px;z-index:25;margin:0;padding:7px 10px;background:#111a;color:#f1c75b;border:2px solid #454b45;box-shadow:3px 3px 0 #080909;font-size:12px;pointer-events:none;max-width:min(80vw,620px);white-space:normal}"
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
            + ".layer.selected{outline-color:#f1c75b;outline-width:2px;outline-style:solid}.layer:not(.selected) .resize-handle{display:none}"
            + ".layer .tag{position:absolute;top:-17px;left:0;font-size:10px;color:#f1c75b;white-space:nowrap;pointer-events:none;text-shadow:1px 1px #111}"
            + ".cardbox{position:absolute;background:#f9fafb;outline:1px solid #222;color:#111;font-size:9px;font-weight:800;text-align:center;overflow:hidden}"
            + ".avslot{position:absolute}.avbox{position:absolute;background:#4e8cff;outline:1px solid #111}.avbox.crowned{background:#d7a52b}.avbox.empty{background:#25282a;opacity:.6}.cnt{position:absolute}.cnt-label{position:absolute;left:0;top:0;width:100%;color:#fff;text-align:center;font-size:10px}.cnt-frame{position:absolute;box-sizing:border-box;outline:1px solid #8bd5ff;background:#8bd5ff33}.cnt-digit{position:absolute;color:#b8b8b8;text-align:center;font-size:9px}.cnt.exhausted .cnt-label,.cnt.exhausted .cnt-digit{color:#777;opacity:.55}.cnt.exhausted .cnt-frame{outline-color:#777;background:#7773}"
            // hotbar 层样式：支持 IMG 真实贴图预览，选中框为绝对定位叠加。
            + ".hb{position:absolute;box-sizing:border-box;border:0;background:transparent!important}.hb-img{position:absolute;left:0;top:0;width:100%;height:100%;image-rendering:pixelated;pointer-events:none}"
            + ".hb-select{position:absolute;image-rendering:pixelated;pointer-events:none;z-index:2}"
            + ".hb-slot-indicator{position:absolute;bottom:-14px;left:50%;transform:translateX(-50%);font-size:9px;color:#f1c75b;white-space:nowrap;pointer-events:none}"
            // 资源加载失败可见提示：红色边框 + 叠加文字，不静默隐藏
            + ".hb-img-error{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:9px;color:#e05050;background:#121216;border:1px dashed #e05050;pointer-events:none;text-align:center}.hb-select-error{position:absolute;inset:auto 2px 2px auto;padding:2px;display:flex;align-items:center;justify-content:center;font-size:9px;color:#e05050;background:#121216;border:1px dashed #e05050;pointer-events:none;text-align:center;z-index:3}"
            + ".scalebar{display:flex;gap:8px;align-items:center;margin:0 0 10px;font-size:12px;color:#eee9dc;flex-wrap:wrap}"
            // 层选择面板：标签页切换活动层，高亮当前选中层
            + ".layer-tabs{display:flex;gap:0;margin:0 0 12px;border-bottom:3px solid #17191b}"
            + ".layer-tab{padding:6px 12px;cursor:pointer;font-size:12px;color:#a9abad;background:#25282a;border:2px solid #17191b;border-bottom:none;position:relative;top:3px;font-weight:600}"
            + ".layer-tab:hover{color:#f1c75b}.layer-tab.active{background:#303438;color:#f1c75b;border-bottom-color:#303438}"
            // 层坐标面板：当前层的精确偏移输入与尺寸读数
            + ".layer-coords{display:grid;grid-template-columns:auto 1fr auto 1fr;gap:6px 8px;align-items:center;margin:0 0 10px;padding:8px;background:#25282a;border:2px solid #17191b;font-size:12px}"
            + ".layer-coords label{color:#a9abad;font-size:11px;text-align:right}.layer-coords input{width:100%;padding:4px 6px;font-size:12px}"
            + ".layer-coords .coord-ro{color:#686d70;font-size:11px;padding:4px 0}"
            // 缩放手柄：四角 + 四边中点的 8 个小方块，仅在选中层上显示
            + ".resize-handle{position:absolute;width:8px;height:8px;background:#f1c75b;border:1px solid #17191b;z-index:5;pointer-events:auto}"
            + ".resize-handle.nw{top:-4px;left:-4px;cursor:nw-resize}.resize-handle.ne{top:-4px;right:-4px;cursor:ne-resize}"
            + ".resize-handle.sw{bottom:-4px;left:-4px;cursor:sw-resize}.resize-handle.se{bottom:-4px;right:-4px;cursor:se-resize}"
            + ".resize-handle.n{top:-4px;left:50%;transform:translateX(-50%);cursor:n-resize}.resize-handle.s{bottom:-4px;left:50%;transform:translateX(-50%);cursor:s-resize}"
            + ".resize-handle.w{top:50%;left:-4px;transform:translateY(-50%);cursor:w-resize}.resize-handle.e{top:50%;right:-4px;transform:translateY(-50%);cursor:e-resize}"
            + ".warn{color:#e6aa63}.ok{color:#a7d46f}.msg{min-height:22px;color:#eee9dc}code{color:#f1c75b}"
            // 全屏 Minecraft 画布视觉：低饱和天空、方块网格、暗角、准星、BossBar 和 ActionBar。
            + ".header-btn{padding:6px 10px;background:#3e473d;color:#f4f1e8;border:2px solid #111;border-right-color:#87906f;border-bottom-color:#87906f;cursor:pointer;font-size:11px;font-weight:700}.header-btn:hover{background:#56624f}.header-btn:focus-visible{outline:2px solid #f1c75b;outline-offset:2px}"
            + ".preview-panel .preview{min-height:0;position:absolute;inset:0;padding:0;border:0;max-width:none;background:radial-gradient(ellipse at 50% 30%,#7898a1 0,#41545e 42%,#202b31 75%,#101419 100%);overflow:hidden}"
            + ".preview-panel .screen{left:50%;top:50%;margin:0;transform:translate(-50%,-50%) translate(var(--view-pan-x,0px),var(--view-pan-y,0px)) scale(var(--screen-zoom,1));transform-origin:center center;--screen-zoom:1;--view-pan-x:0px;--view-pan-y:0px;background:linear-gradient(#7ea4a9 0 46%,#506b69 46% 52%,#35453f 52% 100%);overflow:hidden;outline:4px solid #080909;box-shadow:0 0 0 2px #67736a,8px 8px 0 #080909;touch-action:none}"
            + ".preview-panel .screen:before{content:'';position:absolute;inset:0;pointer-events:none;background-image:linear-gradient(#ffffff12 1px,transparent 1px),linear-gradient(90deg,#ffffff12 1px,transparent 1px);background-size:16px 16px;mix-blend-mode:screen}.preview-panel .screen:after{content:'';position:absolute;inset:0;pointer-events:none;background:radial-gradient(ellipse at center,transparent 48%,#0008 100%);z-index:18}"
            + ".mc-crosshair{position:absolute;left:50%;top:50%;width:14px;height:14px;transform:translate(-50%,-50%);z-index:19;pointer-events:none}.mc-crosshair:before,.mc-crosshair:after{content:'';position:absolute;background:#fff;box-shadow:1px 1px #111}.mc-crosshair:before{left:6px;top:0;width:2px;height:14px}.mc-crosshair:after{left:0;top:6px;width:14px;height:2px}"
            + ".mc-bossbar{position:absolute;left:50%;top:12px;transform:translateX(-50%);width:52%;min-width:220px;z-index:17;color:#fff;text-align:center;font-size:11px;text-shadow:1px 1px #111;pointer-events:none}.mc-bossbar .boss-track{height:8px;margin-top:4px;background:#17191bcc;border:2px solid #080909;box-shadow:inset 0 0 0 1px #515651}.mc-bossbar .boss-fill{height:100%;width:76%;background:linear-gradient(#d96262,#8b2727);box-shadow:inset 0 1px #ffb0a0}.mc-actionbar{position:absolute;left:50%;bottom:42px;transform:translateX(-50%);z-index:17;padding:4px 10px;background:#1119;color:#fff;font-size:11px;text-shadow:1px 1px #111;white-space:nowrap;pointer-events:none}.mc-coordinate{position:fixed;z-index:40;display:none;min-width:150px;padding:7px 9px;background:#111e;color:#fff;border:2px solid #d5a63b;box-shadow:3px 3px #080909;font-size:11px;line-height:1.45;pointer-events:none}.mc-coordinate.show{display:block}.mc-world-label{position:absolute;left:12px;bottom:12px;z-index:17;color:#f1c75b;font-size:10px;text-shadow:1px 1px #111;pointer-events:none}.mc-screen-legend{position:absolute;left:50%;top:calc(50% + 190px);transform:translateX(-50%);z-index:17;color:#d8d7ce;font-size:10px;text-shadow:1px 1px #111;white-space:nowrap;pointer-events:none}"
            // 窄屏：900px 以下降为单列堆叠，字段网格缩窄但保持三列；
            // 500px 以下字段堆叠为标签在上、输入在下的两行布局，适配手机。
            + "@media(max-width:900px){.field{grid-template-columns:minmax(80px,140px) minmax(0,1fr) auto;gap:6px}}"
            + "@media(max-width:500px){.field{grid-template-columns:1fr;gap:4px}.field>label{min-width:0}header{padding:12px}main{padding:10px;gap:10px}.panel{padding:10px}}";
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>MUZ Debug HUD · Minecraft HUD 编辑器</title><style>" + styles + "</style></head>"
            + "<body id='mcEditor' data-token='" + htmlEscape(token) + "'><header><h1>MUZ Debug HUD</h1><span id='muzVersion' class='key'>版本 1.10.16</span>"
            + "<div>只开放 22 个 HUD 运行期字段；Minecraft 风格全屏编辑器 · 左键拖动 · 右键查看边界 · Shift+方向键微调 · Shift+空白拖动平移</div>"
            + "<button type='button' class='header-btn' id='topSaveBtn'>保存</button><button type='button' class='header-btn' id='topReloadBtn'>重载</button>"
            + "<button type='button' class='header-btn' id='panelToggle' aria-expanded='false'>配置</button><button type='button' class='header-btn' id='fullscreenBtn'>全屏</button>"
            + "<button type='button' class='header-btn' id='resetViewBtn'>重置视图</button></header>"
            + "<main class='mc-editor'><section class='panel preview-panel' id='previewPanel'><div class='mc-coordinate' id='mcCoordinate'></div>"
            + "<div class='preview'><div id='screen' class='screen'></div></div>"
            + "<p class='msg' id='dragHint'>提示：预览按 Minecraft 像素绘制，宽度取自服务端 geometry；左键拖动，Shift+空白拖动平移视图，右键查看坐标。</p></section>"
            + "<section class='panel editor-panel collapsed' id='editorPanel'><h2>HUD 配置</h2>"
            + "<details class='tools-panel' id='toolsPanel'><summary>场景工具与层定位</summary>"
            + "<div class='layer-tabs' id='layerTabs' role='tablist' aria-label='HUD 图层'><button type='button' role='tab' aria-selected='true' aria-controls='layer-card' class='layer-tab active' data-layer='card'>牌行</button>"
            + "<button type='button' role='tab' aria-selected='false' aria-controls='layer-avatar' class='layer-tab' data-layer='avatar'>头像</button><button type='button' role='tab' aria-selected='false' aria-controls='layer-counter' class='layer-tab' data-layer='counter'>记牌</button>"
            + "<button type='button' role='tab' aria-selected='false' aria-controls='layer-hotbar' class='layer-tab' data-layer='hotbar'>Hotbar</button></div>"
            + "<div class='layer-coords' id='layerCoords'><label>X 偏移</label><input type='number' id='coordX' step='1' aria-label='层水平偏移（配置值）'>"
            + "<label>Y 偏移</label><input type='number' id='coordY' step='1' aria-label='层纵向偏移（配置值）'><label>渲染宽</label><span class='coord-ro' id='coordW'>-</span>"
            + "<label>渲染高</label><span class='coord-ro' id='coordH'>-</span></div>"
            + "<div class='scalebar'><label>逻辑视口（MC px）</label><input id='viewportWidth' type='number' min='320' max='1920' step='1' value='640' aria-label='逻辑视口宽度'><span>×</span>"
            + "<input id='viewportHeight' type='number' min='240' max='1080' step='1' value='360' aria-label='逻辑视口高度'><span>仅页面校准，不写入 HUD 配置</span></div>"
            + "<div class='scalebar'><label>客户端 GUI 倍率</label><select id='guiScale' aria-label='客户端 GUI 倍率'><option value='2'>2</option><option value='3' selected>3</option><option value='4'>4</option></select>"
            + "<label>页面查看倍率</label><select id='pageScale'><option value='0.25'>1/4</option><option value='0.3333333333' selected>1/3</option><option value='0.5'>1/2</option><option value='1'>1</option></select><span>倍率只改变 CSS 显示，逻辑位置仍为 MC px。</span></div>"
            + "<div class='scalebar'><label><input type='checkbox' id='snapToggle' aria-label='Minecraft 风格吸附' checked> Minecraft 风格吸附</label><span id='dragStatus'>吸附：开启 · 中心线：开启 · Alt 轴锁：开启</span><span>纵向 hotbar 需保存后重载资源包。</span></div>"
            + "<h2>警告</h2><ul id='warnings'></ul></details><form id='hudForm'></form>"
            + "<div class='actions'><button type='button' id='saveBtn' title='Ctrl+S'>保存并应用</button><button type='button' class='secondary' id='reloadBtn' title='Ctrl+R'>重新读取</button>"
            + "<button type='button' class='secondary' id='undoBtn'>撤销</button></div><p id='message' class='msg'></p></section></main>"
            + "<script type='application/json' id='muz-state'>" + stateJson + "</script>"
            + "<script>"
            + "const token=document.body.dataset.token;let state=JSON.parse(document.getElementById('muz-state').textContent);let dirty=new Set();let dragging=null;let panning=null;let resizing=null;let busy=false;let renderQueued=false;let viewPanX=0,viewPanY=0;"
            + "let dragCfg=state.drag||{snapEnabled:true,snapThreshold:4,centerGuidesEnabled:true,altAxisLock:true};let pageSnapEnabled=true;let renderFrame=0;"
            // 层选择状态：activeLayer 决定高亮哪层、坐标面板显示哪层。hotbar 选中槽仅页面演示，不写入 patch。
            + "let activeLayer='card';let hotbarSelectedSlot=0;"
            + "const form=document.getElementById('hudForm'),msg=document.getElementById('message'),warns=document.getElementById('warnings'),dragHint=document.getElementById('dragHint'),screen=document.getElementById('screen'),previewPanel=document.getElementById('previewPanel');"
            + "const coordX=document.getElementById('coordX'),coordY=document.getElementById('coordY'),coordW=document.getElementById('coordW'),coordH=document.getElementById('coordH');"
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
            + "function setBusy(on){busy=on;document.querySelectorAll('#hudForm input,#hudForm select,#saveBtn,#reloadBtn,#undoBtn,#topSaveBtn,#topReloadBtn,#panelToggle,#fullscreenBtn,#resetViewBtn,#viewportWidth,#viewportHeight,#guiScale,#pageScale,#snapToggle,#coordX,#coordY').forEach(el=>el.disabled=on)}"
            // 坐标面板的稳定 DOM 事件绑定与更新逻辑位于下方静态渲染器。

            + "function renderForm(){form.replaceChildren();let groups={};state.fields.forEach(f=>(groups[f.group]??=[]).push(f));Object.entries(groups).forEach(([g,fs])=>{let box=document.createElement('div');box.className='group';let title=document.createElement('h2');title.textContent=g;box.appendChild(title);fs.forEach(f=>box.appendChild(field(f)));form.appendChild(box)});refreshDragStatus();scheduleRender();renderWarnings();selectLayer(activeLayer);if(!busy&&!dragging)setDirtyPrompt()}"
            + "function field(f){let row=document.createElement('div');row.className='field';let val=v(f.key);let left=document.createElement('label');left.innerHTML=esc(f.label)+'<span class=key>'+esc(f.key)+'</span>';let mid=document.createElement('div');let right=document.createElement('div');if(f.type==='boolean'){let i=document.createElement('input');i.type='checkbox';i.checked=!!val;i.dataset.key=f.key;i.onchange=changed;mid.appendChild(i);right.textContent=i.checked?'true':'false'}else if(f.type==='integer'){if(f.control==='select'){let s=document.createElement('select');s.dataset.key=f.key;(f.options||[]).forEach(o=>{let option=document.createElement('option');option.value=o;option.textContent=o;option.selected=Number(o)===Number(val);s.appendChild(option)});s.onchange=changed;mid.appendChild(s);right.textContent='资源档位'}else if(f.control==='range'){let r=document.createElement('input');r.type='range';r.min=f.min;r.max=f.max;r.step=f.step;r.value=val;r.dataset.key=f.key;let n=document.createElement('input');n.type='number';n.min=f.min;n.max=f.max;n.step=f.step;n.value=val;n.dataset.key=f.key;r.oninput=()=>{n.value=r.value;changed({target:r})};n.onchange=()=>{r.value=n.value;changed({target:n})};n.oninput=()=>{r.value=n.value;changed({target:n})};mid.append(r,n);right.textContent=f.min+'..'+f.max}else{let n=document.createElement('input');n.type='number';if(f.min!=null)n.min=f.min;if(f.max!=null)n.max=f.max;if(f.step!=null)n.step=f.step;n.value=val;n.dataset.key=f.key;n.oninput=changed;mid.appendChild(n);right.textContent=(f.min==null?'无下限':f.min)+'..'+(f.max==null?'无上限':f.max)}}else{let c=document.createElement('input');c.type='color';let color=String(val);c.value=/^#[0-9a-fA-F]{6}$/.test(color)?color:'#'+color.slice(-6);let t=document.createElement('input');t.type='text';t.value=val;t.dataset.key=f.key;c.oninput=()=>{t.value=c.value.toUpperCase();changed({target:t})};t.oninput=()=>{if(/^#[0-9a-fA-F]{6}$/.test(t.value))c.value=t.value;changed({target:t})};mid.append(c,t);right.textContent='#RGB/#ARGB'}row.append(left,mid,right);return row}"
            // row 可能取不到（拖动是从预览层触发的，不一定有对应的 .field 祖先）。
            // 这里必须判空：拖动过程中一次 TypeError 就会中断整个手势，表现成「拖不动」。
            + "function changed(e){if(busy||!e||!e.target||!e.target.dataset.key)return;const k=e.target.dataset.key;updateDirty(k);let row=e.target.closest('.field'),f=fieldSpec(k);if(f&&f.type==='boolean'&&row)row.lastElementChild.textContent=e.target.checked?'true':'false';setDirtyPrompt();scheduleRender()}"
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
            + "document.getElementById('topSaveBtn').onclick=()=>document.getElementById('saveBtn').click();document.getElementById('topReloadBtn').onclick=()=>document.getElementById('reloadBtn').click();"
            + "document.getElementById('panelToggle').onclick=()=>{const panel=document.getElementById('editorPanel'),open=panel.classList.toggle('collapsed');document.getElementById('panelToggle').setAttribute('aria-expanded',open?'false':'true')};"
            + "document.getElementById('fullscreenBtn').onclick=async()=>{try{if(document.fullscreenElement){await document.exitFullscreen();return}if(document.documentElement.requestFullscreen)await document.documentElement.requestFullscreen();else setPrompt('当前浏览器不支持全屏，保留普通编辑模式。')}catch(e){setPrompt('全屏失败，保留普通编辑模式。')}};"
            + "document.getElementById('resetViewBtn').onclick=()=>{if(busy)return;viewPanX=0;viewPanY=0;applyScreenTransform();setPrompt('已重置视图平移，HUD 配置未改变。')};"
            + "document.getElementById('layerTabs').addEventListener('click',e=>{const tab=e.target.closest('[data-layer]');if(tab&&!busy)selectLayer(tab.dataset.layer)});"
            + "coordX.addEventListener('input',e=>{const keys=layerXYKeys(activeLayer);if(keys)setField(keys[0],Number(e.target.value||0))});coordY.addEventListener('input',e=>{const keys=layerXYKeys(activeLayer);if(keys&&keys[1])setField(keys[1],Number(e.target.value||0))});"
            + "['viewportWidth','viewportHeight','guiScale','pageScale'].forEach(id=>document.getElementById(id).addEventListener('input',scheduleRender));['guiScale','pageScale'].forEach(id=>document.getElementById(id).addEventListener('change',scheduleRender));"
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
            + "function screenZoom(){const v=viewport(),panel=previewPanel.getBoundingClientRect(),s=cssScale(),availableW=Math.max(1,panel.width-28),availableH=Math.max(1,panel.height-28);return Math.max(.25,Math.min(1,availableW/(v.width*s),availableH/(v.height*s)))}"
            // 头像重叠所需空间 = 牌行 offset-down + 当前档位 avatar.rowHeight（不再是 12*scale 硬编码）
            + "function warningFor(vals){const g=geo();const scale=Number(vals['trick-hud.avatar-scale']);"
            + "const av=(g.avatars||[]).find(x=>Number(x.scale)===scale);if(!av)return null;"
            + "const down=Number(vals['trick-hud.offset-down']),avatarDown=Number(vals['trick-hud.avatar-offset-down']);"
            + "const required=down+Number(av.rowHeight);"
            + "return avatarDown<required?'头像行会与牌行重叠：建议 avatar-offset-down 至少为 '+required+'。':null}"
            // rowGeom 只查表，不复算：card/avatar 从 geometry 数组按档位匹配；
            // counter 优先从 counterTiers[] 按 trick-hud.counter.scale 查表，找不到则降级到顶层默认字段（兼容旧快照）。
            // hotbar 同理优先从 hotbars[] 按 hotbar-hud.scale 查表。
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
            // counter 几何按 scale 从 counterTiers 数组查表，降级到顶层字段
            + "const cntScale=Number(vals['trick-hud.counter.scale'])||100;"
            + "const cntTier=(g.counterTiers||[]).find(x=>Number(x.scale)===cntScale);"
            + "const counterCells=g.counters||[],counterGap=Number(vals['trick-hud.counter.gap']);"
            + "const counterCellWidth=Number(cntTier?cntTier.cellWidth:g.counterCellWidth);"
            + "const counterCellHeight=Number(cntTier?cntTier.cellHeight:g.counterCellHeight);"
            + "const counterAdvance=Number(cntTier?cntTier.advance:g.counterAdvance);"
            + "const counterLabelHeight=Number(cntTier?cntTier.labelHeight:g.counterLabelHeight);"
            + "const counterFrameHeight=Number(cntTier?cntTier.frameHeight:g.counterFrameHeight);"
            + "const counterDigitHeight=Number(cntTier?cntTier.digitHeight:g.counterDigitHeight);"
            + "const counterLabelAscent=Number(cntTier?cntTier.labelAscent:g.counterLabelAscent);"
            + "const counterFrameTopDelta=Number(cntTier?cntTier.frameTopDelta:g.counterFrameTopDelta);"
            + "const counterDigitInset=Number(cntTier?cntTier.digitInset:g.counterDigitInset);"
            + "let counterRowWidth=0;if(counterCells.length){counterRowWidth=counterCells.length*counterAdvance+(counterCells.length-1)*counterGap}"
            // hotbar 几何按 scale 从 hotbars 数组查表，降级到顶层字段
            + "const hbScale=Number(vals['hotbar-hud.scale'])||100;"
            + "const hb=(g.hotbars||[]).find(x=>Number(x.scale)===hbScale)||{};"
            + "const hbW=Number(hb.width||g.hotbarWidth),hbH=Number(hb.height||g.hotbarHeight),hbAdv=Number(hb.advance||g.hotbarAdvance);"
            + "const hbBaseAscent=Number(hb.baseAscent||g.hotbarBaseAscent);"
            + "const hbSlotW=Number(hb.slotWidth||18),hbSlotH=Number(hb.slotHeight||20),hbSlotStep=Number(hb.slotStep||20);"
            + "const hbSlotsStartX=Number(hb.slotsStartX||2),hbSlotsStartY=Number(hb.slotsStartY||1);"
            + "const hbSelW=Number(hb.selectWidth||20),hbSelH=Number(hb.selectHeight||22);"
            + "const hbSelStartX=Number(hb.selectStartX||1),hbSelStartY=Number(hb.selectStartY||0);"
            + "const hbSlotCount=Number(hb.slotCount||9);"
            + "const hbTexture=hb.texture||'hotbar_slots.png',hbSelectTexture=hb.selectTexture||'hotbar_select.png';"
            + "return{cards:cards,cardW:cardW,cardAdvance:cardAdvance,cardRowWidth:cardRowWidth,cardHeight:cardHeight,step:step,n:n,"
            + "avatarSlot:slotWidth,avatarSlots:slots,avatarRowWidth:avatarRowWidth,avatarHeight:avatarHeight,avGap:avGap,"
            + "counterCells:counterCells,counterRowWidth:counterRowWidth,counterGap:counterGap,counterCellWidth:counterCellWidth,"
            + "counterCellHeight:counterCellHeight,counterAdvance:counterAdvance,counterLabelHeight:counterLabelHeight,"
            + "counterFrameHeight:counterFrameHeight,counterDigitHeight:counterDigitHeight,counterLabelAscent:counterLabelAscent,"
            + "counterFrameTopDelta:counterFrameTopDelta,counterDigitInset:counterDigitInset,"
            + "hbW:hbW,hbH:hbH,hbAdv:hbAdv,hbBaseAscent:hbBaseAscent,hbSlotW:hbSlotW,hbSlotH:hbSlotH,hbSlotStep:hbSlotStep,"
            + "hbSlotsStartX:hbSlotsStartX,hbSlotsStartY:hbSlotsStartY,hbSelW:hbSelW,hbSelH:hbSelH,"
            + "hbSelStartX:hbSelStartX,hbSelStartY:hbSelStartY,hbSlotCount:hbSlotCount,"
            + "hbTexture:hbTexture,hbSelectTexture:hbSelectTexture}}"
            // 布局警告缓存：renderPreview 每次重算，renderWarnings 再合并进列表。
            + "let layoutWarnings=[];"
            + "function pushBoundsWarn(name,x,y,w,h){const v=viewport();"
            + "if(x<0)layoutWarnings.push(name+' 越出屏幕左边界（x='+Math.round(x)+'）');"
            + "if(y<0)layoutWarnings.push(name+' 越出屏幕上边界（y='+Math.round(y)+'）');"
            + "if(x+w>v.width)layoutWarnings.push(name+' 越出屏幕右边界（x+w='+Math.round(x+w)+' > '+v.width+'）');"
            + "if(y+h>v.height)layoutWarnings.push(name+' 越出屏幕下边界（y+h='+Math.round(y+h)+' > '+v.height+'）')}"
            + "const SCALE_KEYS={card:'trick-hud.card-height',avatar:'trick-hud.avatar-scale',counter:'trick-hud.counter.scale',hotbar:'hotbar-hud.scale'};"
            // 视口适配：逻辑坐标仍是 640×360 MC px，舞台按浏览器大小放大；指针换算使用同一缩放值。
            // 滚轮切换 hotbar 选中槽（0..8）：仅在 hotbar 层上方才拦截滚轮，不劫持页面其他位置。
            // 这只是页面演示持槽效果，不混入保存 patch。
            + "/* Hotbar wheel、右键与空白平移统一由稳定 DOM 事件委托处理。 */"
            // 拖动：把 CSS 位移换算回 MC 像素写进表单。松手才提交（纵向重打包代价高）。
            + "const DRAG_KEYS={avatar:['trick-hud.avatar-offset-x','trick-hud.avatar-offset-down'],"
            + "card:['trick-hud.card-offset-x','trick-hud.offset-down'],"
            + "counter:['trick-hud.counter.offset-x','trick-hud.counter.offset-down'],"
            + "hotbar:['hotbar-hud.offset-x','hotbar-hud.offset-y']};"
            + "function layerXYKeys(kind){return DRAG_KEYS[kind]||null}"
            + "function selectLayer(kind){if(!DRAG_KEYS[kind])kind='card';activeLayer=kind;document.querySelectorAll('#layerTabs [data-layer]').forEach(t=>{const active=t.dataset.layer===kind;t.classList.toggle('active',active);t.setAttribute('aria-selected',active?'true':'false')});screen.querySelectorAll('.layer[data-drag]').forEach(el=>el.classList.toggle('selected',el.dataset.drag===kind));updateCoordPanel()}"
            + "function updateCoordPanel(){const keys=layerXYKeys(activeLayer),vals=collectAll(),box=staticBoxes(vals).boxes[activeLayer];if(coordX)coordX.value=keys?Number(vals[keys[0]]||0):'';if(coordY)coordY.value=keys&&keys[1]?Number(vals[keys[1]]||0):'';if(coordW)coordW.textContent=box?Math.round(box.w):'-';if(coordH)coordH.textContent=box?Math.round(box.h):'-'}"
            + "function controls(k){const q=CSS.escape(k);return form.querySelectorAll(\"[data-key='\"+q+\"']\")}"
            + "function writeValue(k,value){controls(k).forEach(el=>{if(el.type==='checkbox')el.checked=!!value;else el.value=value})}"
            + "function setField(key,val){const el=control(key);if(!el)return false;const f=fieldSpec(key);let next=Math.round(val);"
            + "if(f&&f.min!=null)next=Math.max(f.min,next);if(f&&f.max!=null)next=Math.min(f.max,next);"
            // 档位型字段（select）只能取资源包实际生成的档，吸附到最近的合法选项，
            // 否则会写出一个后端必然拒绝的值。
            + "if(f&&f.control==='select'&&f.options&&f.options.length){let best=Number(f.options[0]);"
            + "f.options.forEach(o=>{if(Math.abs(Number(o)-next)<Math.abs(best-next))best=Number(o)});next=best}"
            + "writeValue(key,next);if(dragging||resizing){updateDirty(key);return true}changed({target:el});return true}"
            // 【拖动为什么不能把监听器挂在被拖的元素上】：拖动过程中要实时更新预览，
            // 而旧版 renderPreview() 会整段重建预览节点 —— 那会把正在被拖的
            // 元素本身销毁，挂在它上面的 pointermove 与 setPointerCapture 一起消失，
            // 结果拖动只在第一帧生效然后立刻断掉（表现就是「拖不动」）。
            // 所以监听器挂在 window 上：它不随预览重建而消失。
            + "/* 旧版每层绑定与全局手势已移除；稳定 DOM 委托负责拖拽、缩放、平移、滚轮和右键。 */"
            + "function renderWarnings(){warns.replaceChildren();let list=[...(state.warnings||[])];"
            + "let current=warningFor(collectAll());if(current&&!list.some(x=>x.includes('头像行会与牌行重叠')))list.push(current);"
            + "layoutWarnings.forEach(w=>{if(!list.includes(w))list.push(w)});"
            + "list.forEach(w=>{let li=document.createElement('li');li.className='warn';li.textContent=w;warns.appendChild(li)});"
            + "if(!warns.children.length){let li=document.createElement('li');li.className='ok';li.textContent='当前快照无重叠警告。';warns.appendChild(li)}}"
            + """
            // 后续运行期只更新稳定节点的几何与内容，禁止在手势期间拆除 layer/img 节点。
            let staticDomReady=false, staticEventsReady=false, pendingStaticRender=false;
            function staticLayer(kind){return screen.querySelector('.layer[data-drag="'+kind+'"]')}
            function staticContent(el){return el.querySelector('.layer-content')}
            function createStaticScene(){
                if(staticDomReady)return;
                screen.replaceChildren();
                const boss=document.createElement('div');boss.className='mc-bossbar';boss.innerHTML='<span>MUZ · 斗地主调试 HUD</span><div class="boss-track"><div class="boss-fill"></div></div>';
                const action=document.createElement('div');action.className='mc-actionbar';action.textContent='拖动 HUD 层调整位置 · 右键查看边界 · Shift+方向键微调';
                const cross=document.createElement('div');cross.className='mc-crosshair';const world=document.createElement('div');world.className='mc-world-label';
                const legend=document.createElement('div');legend.className='mc-screen-legend';const bossRef=document.createElement('div');bossRef.className='ref boss';
                const grid=document.createElement('div');grid.className='ref grid';const mid=document.createElement('div');mid.className='ref mid';const bottom=document.createElement('div');bottom.className='ref bottom';
                screen.append(boss,action,cross,world,legend,bossRef,grid,mid,bottom);
                ['avatar','card','counter','hotbar'].forEach(kind=>{const layer=document.createElement('div');layer.className='layer';layer.dataset.drag=kind;layer.id='layer-'+kind;const tag=document.createElement('span');tag.className='tag';const content=document.createElement('div');content.className='layer-content';layer.append(tag,content);['nw','ne','sw','se','n','s','w','e'].forEach(dir=>{const h=document.createElement('div');h.className='resize-handle '+dir;h.dataset.resize=dir;layer.append(h)});screen.append(layer)});
                staticDomReady=true;
            }
            function setStaticBox(el,b){el.hidden=!b;el.style.transform='';if(!b)return;el.style.left=cssPx(b.x)+'px';el.style.top=cssPx(b.y)+'px';el.style.width=cssPx(b.w)+'px';el.style.height=cssPx(b.h)+'px';el.dataset.mcLeft=Math.round(b.x);el.dataset.mcTop=Math.round(b.y);el.dataset.mcWidth=Math.round(b.w);el.dataset.mcHeight=Math.round(b.h)}
            function staticBoxes(vals){const g=geo(),v=viewport(),r=rowGeom(vals),out={};if(vals['trick-hud.enabled']){const maxW=Math.max(r.cardRowWidth,r.avatarRowWidth,vals['trick-hud.counter.enabled']?r.counterRowWidth:0),baseLeft=Math.floor((v.width-maxW)/2),ox=Number(vals['trick-hud.offset-x']),base=Number(g.bossBarBaselineY);out.avatar={x:baseLeft+Math.floor((maxW-r.avatarRowWidth)/2)+ox+Number(vals['trick-hud.avatar-offset-x']),y:base-(r.avatarHeight-Number(vals['trick-hud.avatar-offset-down'])),w:r.avatarRowWidth,h:r.avatarHeight};out.card={x:baseLeft+Math.floor((maxW-r.cardRowWidth)/2)+ox+Number(vals['trick-hud.card-offset-x']),y:base-(r.cardHeight-Number(vals['trick-hud.offset-down'])),w:r.cardRowWidth,h:r.cardHeight};if(vals['trick-hud.counter.enabled'])out.counter={x:baseLeft+Math.floor((maxW-r.counterRowWidth)/2)+ox+Number(vals['trick-hud.counter.offset-x']),y:base-(r.counterLabelAscent-Number(vals['trick-hud.counter.offset-down'])),w:r.counterRowWidth,h:r.counterCellHeight}}if(vals['hotbar-hud.enabled']){const h=Number(vals['hotbar-hud.offset-y']),x=Number(vals['hotbar-hud.offset-x']);out.hotbar={x:Math.floor((v.width-r.hbAdv)/2)+x,y:v.height-r.hbH+h,w:r.hbW,h:r.hbH}}return{geometry:r,boxes:out,viewport:v}}
            function staticCards(layer,r){const c=staticContent(layer);for(let i=0;i<r.n;i++){let el=c.querySelector('.cardbox[data-card-index="'+i+'"]');if(!el){el=document.createElement('div');el.className='cardbox';el.dataset.cardIndex=i;c.append(el)}const label=String(r.cards[i].label||r.cards[i].rank||'');el.hidden=false;el.dataset.cardRank=label;el.textContent=label;el.style.left=cssPx(i*r.step)+'px';el.style.top='0';el.style.width=cssPx(r.cardW)+'px';el.style.height=cssPx(r.cardHeight)+'px';el.style.lineHeight=cssPx(r.cardHeight)+'px'}c.querySelectorAll('.cardbox').forEach(el=>{el.hidden=Number(el.dataset.cardIndex)>=r.n})}
            function staticAvatars(layer,r,vals){const c=staticContent(layer),outline=vals['trick-hud.avatar-outline.enabled']?String(vals['trick-hud.avatar-outline.color']):'transparent';for(let i=0;i<3;i++){let slot=c.querySelector('.avslot[data-index="'+i+'"]');if(!slot){slot=document.createElement('div');slot.className='avslot';slot.dataset.index=i;slot.append(document.createElement('div'));c.append(slot)}const data=r.avatarSlots[i]||{slotWidth:r.avatarSlot,contentAdvance:r.avatarSlot,rowHeight:r.avatarHeight,crowned:false,empty:true},face=slot.firstElementChild,sw=Number(data.slotWidth||r.avatarSlot),fw=Number(data.contentAdvance||sw),fh=Number(data.rowHeight||r.avatarHeight);slot.style.left=cssPx(i*(r.avatarSlot+r.avGap))+'px';slot.style.top='0';slot.style.width=cssPx(r.avatarSlot)+'px';slot.style.height=cssPx(r.avatarHeight)+'px';face.className='avbox'+(data.crowned?' crowned':'')+(data.empty?' empty':'');face.style.left=cssPx((sw-fw)/2)+'px';face.style.top=cssPx(r.avatarHeight-fh)+'px';face.style.width=cssPx(fw)+'px';face.style.height=cssPx(fh)+'px';face.style.outlineColor=outline}}
            function staticCounter(layer,r,vals){const c=staticContent(layer);r.counterCells.forEach((cell,i)=>{let el=c.querySelector('.cnt[data-index="'+i+'"]');if(!el){el=document.createElement('div');el.className='cnt';el.dataset.index=i;el.append(document.createElement('div'),document.createElement('div'),document.createElement('div'));el.children[0].className='cnt-label';el.children[1].className='cnt-frame';el.children[2].className='cnt-digit';c.append(el)}const hidden=!!cell.exhausted&&!!vals['trick-hud.counter.hide-exhausted'],x=i*(r.counterAdvance+r.counterGap),digitWidth=r.counterCellWidth-2*r.counterDigitInset;el.hidden=false;el.className='cnt'+(cell.exhausted?' exhausted':'');el.style.left=cssPx(x)+'px';el.style.top='0';el.style.width=cssPx(r.counterCellWidth)+'px';el.style.height=cssPx(r.counterCellHeight)+'px';el.children[0].textContent=String(cell.label);el.children[0].style.display=hidden?'none':'';el.children[1].style.display=hidden?'none':'';el.children[2].textContent=String(cell.playedCount);el.children[2].style.display=hidden?'none':'';el.children[0].style.height=cssPx(r.counterLabelHeight)+'px';el.children[0].style.lineHeight=cssPx(r.counterLabelHeight)+'px';el.children[1].style.left='0';el.children[1].style.top=cssPx(r.counterFrameTopDelta)+'px';el.children[1].style.width=cssPx(r.counterCellWidth)+'px';el.children[1].style.height=cssPx(r.counterFrameHeight)+'px';el.children[2].style.left=cssPx(r.counterDigitInset)+'px';el.children[2].style.top=cssPx(r.counterFrameTopDelta+r.counterDigitInset)+'px';el.children[2].style.width=cssPx(digitWidth)+'px';el.children[2].style.height=cssPx(r.counterDigitHeight)+'px';el.children[2].style.lineHeight=cssPx(r.counterDigitHeight)+'px'});c.querySelectorAll('.cnt').forEach(el=>{el.hidden=Number(el.dataset.index)>=r.counterCells.length})}
            function staticHotbar(layer,r){const c=staticContent(layer);let img=c.querySelector('.hb-img');if(!img){img=document.createElement('img');img.className='hb-img';c.append(img)}let err=c.querySelector('.hb-img-error');if(!err){err=document.createElement('div');err.className='hb-img-error';err.textContent='底图缺失：请构建后重启';c.append(err)}let selected=c.querySelector('.hb-select');if(!selected){selected=document.createElement('img');selected.className='hb-select';c.append(selected)}let selectErr=c.querySelector('.hb-select-error');if(!selectErr){selectErr=document.createElement('span');selectErr.className='hb-select-error';selectErr.textContent='选中框缺失';c.append(selectErr)}const setImage=(node,error,url,alt)=>{if(node.dataset.src===url)return;node.dataset.src=url;node.alt=alt;node.onerror=()=>{node.style.display='none';error.style.display='flex'};node.onload=()=>{node.style.display='block';error.style.display='none'};node.src=url};setImage(img,err,'/api/resource/'+esc(r.hbTexture),'hotbar 构建期真实 PNG');setImage(selected,selectErr,'/api/resource/'+esc(r.hbSelectTexture),'hotbar 选中框');selected.style.left=cssPx(r.hbSelStartX+hotbarSelectedSlot*r.hbSlotStep)+'px';selected.style.top=cssPx(r.hbSelStartY)+'px';selected.style.width=cssPx(r.hbSelW)+'px';selected.style.height=cssPx(r.hbSelH)+'px';let note=c.querySelector('.hb-slot-indicator');if(!note){note=document.createElement('span');note.className='hb-slot-indicator';c.append(note)}note.textContent='持槽 '+hotbarSelectedSlot;for(let i=0;i<r.hbSlotCount;i++){let slot=c.querySelector('.hb[data-slot="'+i+'"]');if(!slot){slot=document.createElement('div');slot.className='hb';slot.dataset.slot=i;c.append(slot)}slot.hidden=false;slot.style.left=cssPx(r.hbSlotsStartX+i*r.hbSlotStep)+'px';slot.style.top=cssPx(r.hbSlotsStartY)+'px';slot.style.width=cssPx(r.hbSlotW)+'px';slot.style.height=cssPx(r.hbSlotH)+'px'}c.querySelectorAll('.hb').forEach(slot=>slot.hidden=Number(slot.dataset.slot)>=r.hbSlotCount)}
            function hideCoordinate(){const box=document.getElementById('mcCoordinate');if(box)box.classList.remove('show')}
            function showCoordinate(ev,layer){const box=document.getElementById('mcCoordinate');if(!box)return;const el=layer||staticLayer(activeLayer),kind=el&&el.dataset.drag||activeLayer,b=el&&!el.hidden?{x:Number(el.dataset.mcLeft||0),y:Number(el.dataset.mcTop||0),w:Number(el.dataset.mcWidth||0),h:Number(el.dataset.mcHeight||0)}:null,rect=screen.getBoundingClientRect(),s=cssScale(),z=screenZoom(),px=Math.round((ev.clientX-rect.left)/(z*s)),py=Math.round((ev.clientY-rect.top)/(z*s)),keys=DRAG_KEYS[kind],vals=collectAll(),name=el&&el.dataset.drag||'画布';box.innerHTML='<b>'+esc(name)+'</b><br>左 '+(b?b.x:'-')+' · 右 '+(b?b.x+b.w:'-')+'<br>上 '+(b?b.y:'-')+' · 下 '+(b?b.y+b.h:'-')+'<br>宽 '+(b?b.w:'-')+' · 高 '+(b?b.h:'-')+'<br>配置 offset：'+(keys?Math.round(Number(vals[keys[0]]||0)):'-')+'，'+(keys?Math.round(Number(vals[keys[1]]||0)):'-')+'<br>指针 '+px+', '+py;box.style.left=Math.min(window.innerWidth-box.offsetWidth-8,Math.max(8,ev.clientX+12))+'px';box.style.top=Math.min(window.innerHeight-box.offsetHeight-8,Math.max(8,ev.clientY+12))+'px';box.classList.add('show')}
            function bindStaticEvents(){if(staticEventsReady)return;staticEventsReady=true;screen.addEventListener('pointerdown',e=>{const handle=e.target.closest('.resize-handle'),layer=e.target.closest('.layer[data-drag]');if(handle){startStaticResize(e,handle);return}if(layer){startStaticDrag(e,layer);return}if(!busy&&e.button===0&&e.shiftKey){e.preventDefault();panning={sx:e.clientX,sy:e.clientY,x:viewPanX,y:viewPanY,pointerId:e.pointerId,__static:true};try{screen.setPointerCapture(e.pointerId)}catch(_){}dragHint.textContent='平移视图中：Shift+拖动'}});screen.addEventListener('pointermove',moveStaticPointer);screen.addEventListener('pointerup',finishStaticPointer);screen.addEventListener('pointercancel',finishStaticPointer);screen.addEventListener('lostpointercapture',finishStaticPointer);window.addEventListener('pointerup',finishStaticPointer);window.addEventListener('pointercancel',finishStaticPointer);window.addEventListener('blur',finishStaticPointer);screen.addEventListener('contextmenu',e=>{e.preventDefault();showCoordinate(e,e.target.closest('.layer[data-drag]'))});screen.addEventListener('wheel',e=>{if(!e.target.closest('.layer[data-drag="hotbar"]'))return;e.preventDefault();hotbarSelectedSlot=Math.max(0,Math.min(8,hotbarSelectedSlot+(e.deltaY>0?1:-1)));scheduleRender()},{passive:false})}
            function startStaticDrag(e,el){if(busy||dragging||resizing||panning||e.button!==0||e.pointerType==='mouse'&&!(e.buttons&1)||e.target.closest('.resize-handle')||['INPUT','SELECT','TEXTAREA','BUTTON'].includes(e.target.tagName)||e.target.isContentEditable)return;const kind=el.dataset.drag,keys=DRAG_KEYS[kind],b=staticBoxes(collectAll()).boxes[kind];if(!keys||!b)return;e.preventDefault();selectLayer(kind);dragging={kind,keys,sx:e.clientX,sy:e.clientY,bx:Number(readValue(keys[0])||0),by:keys[1]?Number(readValue(keys[1])||0):0,baseLeft:b.x,baseTop:b.y,width:b.w,height:b.h,pointerId:e.pointerId,axis:null,hasMoved:false,el,__static:true};el.classList.add('drag');try{el.setPointerCapture(e.pointerId)}catch(_){} }
            function moveStaticPointer(e){const active=dragging||resizing||panning;if(!active||busy||e.pointerId!==active.pointerId)return;e.preventDefault();if(dragging){if(e.pointerType==='mouse'&&!(e.buttons&1)){finishStaticPointer();return}const d=dragging,s=cssScale()*screenZoom(),v=viewport();let dx=(e.clientX-d.sx)/s,dy=(e.clientY-d.sy)/s;if(Math.abs(dx)<.5&&Math.abs(dy)<.5&&!d.hasMoved)return;d.hasMoved=true;if(!d.keys[1])dy=0;if(dragCfg.altAxisLock&&e.altKey&&d.axis===null&&(Math.abs(dx)>=2||Math.abs(dy)>=2))d.axis=Math.abs(dx)>=Math.abs(dy)?'x':'y';if(d.axis==='x')dy=0;if(d.axis==='y')dx=0;dx=clampDelta(d.baseLeft,d.width,v.width,dx);if(d.keys[1])dy=clampDelta(d.baseTop,d.height,v.height,dy);if(dragCfg.snapEnabled){const tx=(v.width-d.width)/2-d.baseLeft,ty=(v.height-d.height)/2-d.baseTop;if(d.axis!=='y'&&Math.abs(dx-tx)<=dragCfg.snapThreshold)dx=tx;if(d.keys[1]&&d.axis!=='x'&&Math.abs(dy-ty)<=dragCfg.snapThreshold)dy=ty;dx=clampDelta(d.baseLeft,d.width,v.width,dx);if(d.keys[1])dy=clampDelta(d.baseTop,d.height,v.height,dy)}setField(d.keys[0],d.bx+dx);if(d.keys[1])setField(d.keys[1],d.by+dy);d.el.style.transform='translate('+cssPx(Number(readValue(d.keys[0]))-d.bx)+'px,'+cssPx((d.keys[1]?Number(readValue(d.keys[1])):d.by)-d.by)+'px)';dragHint.textContent='拖动中：'+d.keys[0]+'='+Math.round(Number(readValue(d.keys[0])))+(d.keys[1]?('，'+d.keys[1]+'='+Math.round(Number(readValue(d.keys[1])))):'');updateCoordPanel()}else if(resizing){moveStaticResize(e)}else{viewPanX=panning.x+(e.clientX-panning.sx)/(cssScale()*screenZoom());viewPanY=panning.y+(e.clientY-panning.sy)/(cssScale()*screenZoom());applyScreenTransform()}}
            function finishStaticPointer(){if(dragging){const d=dragging,el=d.el;dragging=null;try{if(el.hasPointerCapture(d.pointerId))el.releasePointerCapture(d.pointerId)}catch(_){}el.classList.remove('drag');flushStaticRender()}if(resizing){const d=resizing;resizing=null;try{if(d.el.hasPointerCapture(d.pointerId))d.el.releasePointerCapture(d.pointerId)}catch(_){}d.el.classList.remove('drag');flushStaticRender()}if(panning){const p=panning;panning=null;try{if(screen.hasPointerCapture(p.pointerId))screen.releasePointerCapture(p.pointerId)}catch(_){}applyScreenTransform();if(pendingStaticRender)flushStaticRender()}}
            function renderPreview(){layoutWarnings=[];createStaticScene();bindStaticEvents();const vals=collectAll(),g=geo(),v=viewport(),r=staticBoxes(vals).geometry,boxes=staticBoxes(vals).boxes;screen.style.width=cssPx(v.width)+'px';screen.style.height=cssPx(v.height)+'px';applyScreenTransform();screen.querySelector('.mc-world-label').textContent='世界：HUD_DEBUG · 视口 '+v.width+'×'+v.height;const ref=screen.querySelector('.ref.boss');ref.style.left=cssPx(Math.floor((v.width-182)/2))+'px';ref.style.top=cssPx(Math.max(0,Number(g.bossBarBaselineY)-5))+'px';ref.style.width=cssPx(182)+'px';ref.style.height=cssPx(5)+'px';screen.querySelector('.ref.grid').style.display=dragCfg.centerGuidesEnabled?'block':'none';screen.querySelector('.ref.mid').style.left=cssPx(v.width/2)+'px';screen.querySelector('.ref.bottom').style.top=cssPx(v.height-1)+'px';['avatar','card','counter','hotbar'].forEach(kind=>{const el=staticLayer(kind),b=boxes[kind];setStaticBox(el,b);el.classList.toggle('selected',activeLayer===kind);el.querySelector('.tag').textContent=kind==='hotbar'?'Hotbar offset-x / offset-y（纵向需重载资源包）':kind==='card'?'牌行 card-offset-x / offset-down':kind==='avatar'?'头像行 avatar-offset-x/down':'记牌行 counter.offset-x / offset-down'});Object.entries(boxes).forEach(([kind,b])=>{if(!b)return;const names={avatar:'头像行',card:'牌行',counter:'记牌行',hotbar:'Hotbar'},name=names[kind];if(b.x<0)layoutWarnings.push(name+' 越出屏幕左边界（x='+Math.round(b.x)+'）');if(b.y<0)layoutWarnings.push(name+' 越出屏幕上边界（y='+Math.round(b.y)+'）');if(b.x+b.w>v.width)layoutWarnings.push(name+' 越出屏幕右边界（x+w='+Math.round(b.x+b.w)+' > '+v.width+'）');if(b.y+b.h>v.height)layoutWarnings.push(name+' 越出屏幕下边界（y+h='+Math.round(b.y+b.h)+' > '+v.height+'）')});staticCards(staticLayer('card'),r);staticAvatars(staticLayer('avatar'),r,vals);staticCounter(staticLayer('counter'),r,vals);staticHotbar(staticLayer('hotbar'),r);updateCoordPanel()}
            function scheduleRender(){if(renderQueued)return;renderQueued=true;renderFrame=requestAnimationFrame(()=>{renderFrame=0;renderQueued=false;if(dragging||resizing||panning){pendingStaticRender=true;return}renderPreview();renderWarnings()})}
            function flushStaticRender(){if(renderFrame){cancelAnimationFrame(renderFrame);renderFrame=0}renderQueued=false;pendingStaticRender=false;renderPreview();renderWarnings();setDirtyPrompt()}
            function applyScreenTransform(){const z=screenZoom();screen.style.setProperty('--screen-zoom',z);screen.style.setProperty('--view-pan-x',cssPx(viewPanX)+'px');screen.style.setProperty('--view-pan-y',cssPx(viewPanY)+'px');screen.style.left='50%';screen.style.top='50%'}
            function clampDelta(base,size,extent,delta){const min=-base,max=extent-size-base;return min<=max?Math.max(min,Math.min(max,delta)):(extent-size)/2-base}
            function moveStaticResize(e){const d=resizing;if(!d||busy||e.pointerId!==d.pointerId)return;e.preventDefault();const dy=(e.clientY-d.sy)/(cssScale()*screenZoom()),dx=(e.clientX-d.sx)/(cssScale()*screenZoom()),dir=d.dir;let next=d.baseVal+(dir.includes('s')?dy:dir.includes('n')?-dy:dir.includes('e')?dx:-dx);setField(d.scaleKey,next);let vals=collectAll(),b=staticBoxes(vals).boxes[d.kind];if(!b)return;const vp=viewport(),targetX=dir.includes('w')?d.baseBox.x+d.baseBox.w-b.w:dir.includes('e')?d.baseBox.x:d.baseBox.x+(d.baseBox.w-b.w)/2,targetY=dir.includes('n')?d.baseBox.y+d.baseBox.h-b.h:dir.includes('s')?d.baseBox.y:d.baseBox.y+(d.baseBox.h-b.h)/2,keys=DRAG_KEYS[d.kind];if(keys[0])setField(keys[0],Number(vals[keys[0]]||0)+Math.max(0,Math.min(vp.width-b.w,targetX))-b.x);if(keys[1])setField(keys[1],Number(vals[keys[1]]||0)+Math.max(0,Math.min(vp.height-b.h,targetY))-b.y);b=staticBoxes(collectAll()).boxes[d.kind];if(!b)return;d.el.style.left=cssPx(b.x)+'px';d.el.style.top=cssPx(b.y)+'px';d.el.style.width=cssPx(b.w)+'px';d.el.style.height=cssPx(b.h)+'px';dragHint.textContent='缩放中：'+d.scaleKey+'='+Math.round(next)+'（服务端档位真实几何）'}
            function startStaticResize(e,h){if(busy||dragging||resizing||panning||e.button!==0)return;const el=h.closest('.layer[data-drag]'),kind=el&&el.dataset.drag,key=SCALE_KEYS[kind],b=staticBoxes(collectAll()).boxes[kind];if(!el||kind!==activeLayer||!el.classList.contains('selected')||!key||!b)return;e.preventDefault();e.stopPropagation();resizing={kind,scaleKey:key,dir:h.dataset.resize,sx:e.clientX,sy:e.clientY,baseVal:Number(readValue(key)),baseBox:b,el,pointerId:e.pointerId,__static:true};el.classList.add('drag');try{el.setPointerCapture(e.pointerId)}catch(_){} }
            function nudgeActive(dx,dy){if(busy||dragging||resizing||panning||document.activeElement&&['INPUT','SELECT','TEXTAREA','BUTTON'].includes(document.activeElement.tagName))return;const keys=DRAG_KEYS[activeLayer],vals=collectAll(),b=staticBoxes(vals).boxes[activeLayer],v=viewport();if(!keys||!b)return;setField(keys[0],Number(vals[keys[0]]||0)+clampDelta(b.x,b.w,v.width,dx));if(keys[1])setField(keys[1],Number(vals[keys[1]]||0)+clampDelta(b.y,b.h,v.height,dy));scheduleRender()}
            window.addEventListener('keydown',e=>{if(e.key==='Escape'){hideCoordinate();finishStaticPointer();return}if(e.shiftKey&&/^Arrow/.test(e.key)&&!e.target.closest('input,select,textarea,button,[contenteditable=true]')){const d={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]}[e.key];if(d){e.preventDefault();nudgeActive(d[0],d[1])}}});
            """
            + "function showFatal(e){const text='Debug Web 前端错误：'+(e&&e.message?e.message:String(e));msg.className='msg error';msg.textContent=text;dragHint.textContent=text;screen.textContent=text;screen.style.color='#ff8a8a';screen.style.padding='16px';screen.style.whiteSpace='pre-wrap'}window.addEventListener('error',e=>showFatal(e.error||e.message));window.addEventListener('unhandledrejection',e=>showFatal(e.reason));try{renderForm();selectLayer(activeLayer);renderWarnings()}catch(e){showFatal(e)}"
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
