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
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 5L;

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
                .get(MAIN_THREAD_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
            snapshot = result.snapshot();
            sendJson(exchange, result.ok() ? 200 : 500,
                apiPayload(result.ok(), result.snapshot(), result.appliedKeys(), result.messages()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, apiPayload(false, snapshot, List.of(), List.of("HTTP 线程被中断。")));
        } catch (ExecutionException | TimeoutException exception) {
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
                .get(MAIN_THREAD_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
            snapshot = result.snapshot();
            sendJson(exchange, result.ok() ? 200 : 500,
                apiPayload(result.ok(), result.snapshot(), result.appliedKeys(), result.messages()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, apiPayload(false, snapshot, List.of(), List.of("HTTP 线程被中断。")));
        } catch (ExecutionException | TimeoutException exception) {
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
        String styles = "*{box-sizing:border-box}body{margin:0;background:#202326;color:#f4f1e8;font-family:Verdana,'Segoe UI',sans-serif;image-rendering:pixelated}"
            + "header{padding:18px 22px;background:#303438;border-bottom:4px solid #17191b;box-shadow:0 4px 0 #111}h1{margin:0;color:#f1c75b;font-size:22px;text-shadow:2px 2px #17191b}"
            + "main{display:grid;grid-template-columns:minmax(460px,1.2fr)minmax(360px,.8fr);gap:18px;padding:18px}"
            + ".panel{background:#303438;border:3px solid #17191b;border-right-color:#62666a;border-bottom-color:#62666a;padding:16px;box-shadow:6px 6px 0 #111}"
            + "h2{margin:6px 0 12px;color:#f1c75b;font-size:18px;text-shadow:1px 1px #17191b}.group{margin-bottom:18px}.field{display:grid;grid-template-columns:190px 1fr 86px;gap:10px;align-items:center;padding:8px 0;border-bottom:2px solid #25282a}"
            + "label{font-size:13px;color:#eee9dc}.key{display:block;color:#a9abad;font-size:11px}input,select{accent-color:#d9a93a}"
            + "input[type=number],input[type=text],select{width:100%;background:#1c1f21;color:#fff;border:2px solid #111;border-top-color:#686d70;border-left-color:#686d70;padding:7px}"
            + "input[type=range]{width:100%}.dirty label{color:#f1c75b}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:12px}"
            + "button{background:#b77b22;color:#fff;border:2px solid #17191b;border-right-color:#e0bd68;border-bottom-color:#e0bd68;padding:9px 15px;cursor:pointer;font-weight:700;text-shadow:1px 1px #4c3210}button.secondary{background:#565b5e}button:disabled{opacity:.55;cursor:not-allowed}"
            + ".preview{min-height:340px;overflow:auto;background:#17191b;position:relative;padding:12px;border:3px solid #111;border-right-color:#666}"
            + ".screen{position:relative;margin:0 auto;background:repeating-linear-gradient(0deg,#202326 0,#202326 7px,#24282a 8px),repeating-linear-gradient(90deg,#202326 0,#202326 7px,#24282a 8px);overflow:hidden;border:3px solid #080909;box-shadow:4px 4px 0 #080909}"
            + ".ref{position:absolute;pointer-events:none}.ref.boss{background:#5c3d2177;border:2px solid #d4a943}"
            + ".ref.bottom{border-top:2px dashed #d4a943;left:0;right:0}.ref.mid{border-left:2px dashed #d4a943;top:0;bottom:0}.ref.grid{background-image:linear-gradient(#ffffff0b 1px,transparent 1px),linear-gradient(90deg,#ffffff0b 1px,transparent 1px);background-size:8px 8px;inset:0}"
            + ".layer{position:absolute;cursor:grab;border:2px solid transparent}.layer:hover{border-color:#f1c75b}.layer.drag{cursor:grabbing;border-color:#d26b48}"
            + ".layer .tag{position:absolute;top:-17px;left:0;font-size:10px;color:#f1c75b;white-space:nowrap;pointer-events:none;text-shadow:1px 1px #111}"
            + ".cardbox{position:absolute;background:#f9fafb;border:1px solid #222;color:#111;font-size:9px;font-weight:800;text-align:center;overflow:hidden}"
            + ".avbox{position:absolute;background:#4e8cff;border:1px solid #111}.cnt{position:absolute;background:#8bd5ff33;border:1px solid #8bd5ff88}"
            + ".hb{position:absolute;border:1px solid #fff8}.scalebar{display:flex;gap:8px;align-items:center;margin:0 0 10px;font-size:12px;color:#eee9dc}"
            + ".warn{color:#e6aa63}.ok{color:#a7d46f}.msg{min-height:22px;color:#eee9dc}code{color:#f1c75b}"
            + "@media(max-width:900px){main{grid-template-columns:1fr}.field{grid-template-columns:1fr}}";
        return "<!DOCTYPE html><html lang='zh'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>MUZ Debug HUD 调试面板</title><style>" + styles + "</style></head>"
            + "<body data-token='" + htmlEscape(token) + "'><header><h1>MUZ Debug HUD 调试面板</h1>"
            + "<div>只开放 19 个 HUD 运行期字段；保存会异步写入配置与当前 hotbar 覆盖层，再在主线程重载 CraftEngine 并应用 HUD。客户端需重新下载资源包。</div></header>"
            + "<main><section class='panel'><h2>可编辑配置</h2><form id='hudForm'></form>"
            + "<div class='actions'><button type='button' id='saveBtn'>保存并应用</button>"
            + "<button type='button' class='secondary' id='reloadBtn'>重新读取</button>"
            + "<button type='button' class='secondary' id='undoBtn'>撤销</button></div><p id='message' class='msg'></p></section>"
            + "<section class='panel'><h2>像素预览（可拖动）</h2>"
            + "<div class='scalebar'><label>GUI 缩放</label><select id='guiScale'>"
            + "<option value='2'>2</option><option value='3' selected>3</option><option value='4'>4</option></select>"
            + "<span>拖动各层可改对应偏移；纵向 hotbar 需保存后重载资源包才生效。</span></div>"
            + "<div class='scalebar'><label><input type='checkbox' id='snapToggle'> Minecraft 风格吸附</label><span id='dragStatus'>吸附：开启 · 中心线：开启 · Alt 轴锁：开启</span><span>资源状态：CraftEngine 覆盖层按保存流程生成并重载</span></div>"
            + "<div class='preview'><div id='screen' class='screen'></div></div>"
            + "<p class='msg' id='dragHint'>提示：预览按 Minecraft 像素绘制，宽度取自资源包实际字形前进量。</p>"
            + "<h2>警告</h2><ul id='warnings'></ul></section></main>"
            + "<script type='application/json' id='muz-state'>" + stateJson + "</script>"
            + "<script>"
            + "const token=document.body.dataset.token;let state=JSON.parse(document.getElementById('muz-state').textContent);let dirty=new Set();"
            + "let dragCfg=state.drag||{snapEnabled:true,snapThreshold:4,centerGuidesEnabled:true,altAxisLock:true};let pageSnapEnabled=dragCfg.snapEnabled!==false;"
            + "const form=document.getElementById('hudForm'),msg=document.getElementById('message'),warns=document.getElementById('warnings');"
            + "function refreshDragStatus(){dragCfg=state.drag||{snapEnabled:true,snapThreshold:4,centerGuidesEnabled:true,altAxisLock:true};dragCfg={...dragCfg,snapEnabled:pageSnapEnabled};const toggle=document.getElementById('snapToggle');if(toggle){toggle.checked=pageSnapEnabled;toggle.onchange=()=>{pageSnapEnabled=toggle.checked;refreshDragStatus();msg.textContent='页面拖动吸附已'+(pageSnapEnabled?'开启':'关闭')+'（仅当前页面，不写入 HUD 配置）。'}}document.getElementById('dragStatus').textContent='吸附：'+(pageSnapEnabled?'开启':'关闭')+'（阈值 '+dragCfg.snapThreshold+'px） · 中心线：'+(dragCfg.centerGuidesEnabled?'开启':'关闭')+' · Alt 轴锁：'+(dragCfg.altAxisLock?'开启':'关闭')}refreshDragStatus();"
            + "function v(k){return state.values&&Object.prototype.hasOwnProperty.call(state.values,k)?state.values[k]:state.fields.find(f=>f.key===k).fallback}"
            + "function esc(s){return String(s).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]))}"
            + "function renderForm(){form.innerHTML='';let groups={};state.fields.forEach(f=>(groups[f.group]??=[]).push(f));Object.entries(groups).forEach(([g,fs])=>{let box=document.createElement('div');box.className='group';box.innerHTML='<h2>'+esc(g)+'</h2>';fs.forEach(f=>box.appendChild(field(f)));form.appendChild(box)});refreshDragStatus();renderPreview();renderWarnings()}"
            + "function field(f){let row=document.createElement('div');row.className='field';let val=v(f.key);let left=document.createElement('label');left.innerHTML=esc(f.label)+'<span class=key>'+esc(f.key)+'</span>';let mid=document.createElement('div');let right=document.createElement('div');if(f.type==='boolean'){let i=document.createElement('input');i.type='checkbox';i.checked=!!val;i.dataset.key=f.key;i.onchange=changed;mid.appendChild(i);right.textContent=i.checked?'true':'false'}else if(f.type==='integer'){if(f.control==='select'){let s=document.createElement('select');s.dataset.key=f.key;(f.options||[]).forEach(o=>{let option=document.createElement('option');option.value=o;option.textContent=o;option.selected=Number(o)===Number(val);s.appendChild(option)});s.onchange=changed;mid.appendChild(s);right.textContent='资源档位'}else if(f.control==='range'){let r=document.createElement('input');r.type='range';r.min=f.min;r.max=f.max;r.step=f.step;r.value=val;r.dataset.key=f.key;let n=document.createElement('input');n.type='number';n.min=f.min;n.max=f.max;n.step=f.step;n.value=val;n.dataset.key=f.key;r.oninput=()=>{n.value=r.value;changed({target:r})};n.oninput=()=>{r.value=n.value;changed({target:n})};mid.append(r,n);right.textContent=f.min+'..'+f.max}else{let n=document.createElement('input');n.type='number';if(f.min!=null)n.min=f.min;if(f.max!=null)n.max=f.max;if(f.step!=null)n.step=f.step;n.value=val;n.dataset.key=f.key;n.oninput=changed;mid.appendChild(n);right.textContent=(f.min==null?'无下限':f.min)+'..'+(f.max==null?'无上限':f.max)}}else{let c=document.createElement('input');c.type='color';let color=String(val);c.value=/^#[0-9a-fA-F]{6}$/.test(color)?color:'#'+color.slice(-6);let t=document.createElement('input');t.type='text';t.value=val;t.dataset.key=f.key;c.oninput=()=>{t.value=c.value.toUpperCase();changed({target:t})};t.oninput=()=>{if(/^#[0-9a-fA-F]{6}$/.test(t.value))c.value=t.value;changed({target:t})};mid.append(c,t);right.textContent='#RGB/#ARGB'}row.append(left,mid,right);return row}"
            // row 可能取不到（拖动是从预览层触发的，不一定有对应的 .field 祖先）。
            // 这里必须判空：拖动过程中一次 TypeError 就会中断整个手势，表现成「拖不动」。
            + "function changed(e){let row=e.target.closest('.field');dirty.add(e.target.dataset.key);if(row)row.classList.add('dirty');let f=state.fields.find(x=>x.key===e.target.dataset.key);if(f&&f.type==='boolean'&&row)row.lastElementChild.textContent=e.target.checked?'true':'false';msg.textContent='有未保存改动：'+dirty.size+' 项';renderPreview();renderWarnings()}"
            + "function currentPatch(){let p={};dirty.forEach(k=>{let f=state.fields.find(x=>x.key===k),el=form.querySelector(\"input[data-key='\"+CSS.escape(k)+\"'],select[data-key='\"+CSS.escape(k)+\"']\");if(!el)throw new Error('找不到配置控件：'+k);p[k]=f.type==='boolean'?el.checked:(f.type==='integer'?Number(el.value):el.value)});return p}"
            + "function collectAll(){let old=state.values;state.values={...old,...currentPatch()};let out={...state.values};state.values=old;return out}"
            + "async function post(url,body){let r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json','X-MUZ-Token':token},body:JSON.stringify(body||{})});let j=await r.json();if(!r.ok||!j.ok)throw new Error((j.messages||['请求失败']).join('；'));return j}"
            + "document.getElementById('saveBtn').onclick=async()=>{try{let j=await post('/api/save',{values:currentPatch()});state=j.snapshot;dirty.clear();renderForm();msg.innerHTML='<span class=ok>已保存并应用：'+esc((j.appliedKeys||[]).join(', ')||'无改动')+'</span>'}catch(e){msg.innerHTML='<span class=warn>'+esc(e.message)+'</span>'}};"
            + "document.getElementById('reloadBtn').onclick=async()=>{try{let j=await post('/api/reload',{});state=j.snapshot;dirty.clear();renderForm();msg.innerHTML='<span class=ok>已重新读取配置。</span>'}catch(e){msg.innerHTML='<span class=warn>'+esc(e.message)+'</span>'}};"
            + "document.getElementById('undoBtn').onclick=()=>{dirty.clear();renderForm();msg.textContent='已撤销未保存改动。'};"
            // 【预览几何全部来自服务端 PreviewGeometry】：屏幕基准、行宽、字形前进量、
            // Hotbar 尺寸/ascent 都由 DebugHudConfigController.currentGeometry() 汇总下发，
            // 前端不复算任何字形表。旧版把 SCREEN_W/SCREEN_H/baseY/abBase/cellW=12/6*scale 这些
            // 常量硬写在这里，档位一改预览就静默错位，历史课就此终结。
            + "function geo(){return state.geometry}"
            + "function guiScale(){return Number(document.getElementById('guiScale').value)}"
            // MC 像素 → CSS 像素。预览缩放只整体放大，相对位置不变（与游戏内 GUI 缩放同理）。
            + "function cssPx(mc){return mc*guiScale()/3}"
            // 头像重叠所需空间 = 牌行 offset-down + 当前档位 avatar.rowHeight（不再是 12*scale 硬编码）
            + "function warningFor(vals){const g=geo();const scale=Number(vals['trick-hud.avatar-scale']);"
            + "const av=(g.avatars||[]).find(x=>Number(x.scale)===scale);if(!av)return null;"
            + "const down=Number(vals['trick-hud.offset-down']),avatarDown=Number(vals['trick-hud.avatar-offset-down']);"
            + "const required=down+Number(av.rowHeight);"
            + "return avatarDown<required?'头像行会与牌行重叠：建议 avatar-offset-down 至少为 '+required+'。':null}"
            // rowGeom 现在只查表，不复算：card/avatar 从 geometry 数组按档位匹配，counter 按每格 advance 累加
            + "function rowGeom(vals){const g=geo();"
            + "const n=7,step=Number(vals['trick-hud.card-step']),h=Number(vals['trick-hud.card-height']);"
            + "const card=(g.cards||[]).find(x=>Number(x.height)===h)||{width:0,advance:0,height:h};"
            + "const cardW=Number(card.width),cardAdvance=Number(card.advance),cardHeight=Number(card.height);"
            + "const cardRowWidth=(n-1)*step+cardAdvance;"
            + "const scale=Number(vals['trick-hud.avatar-scale']),avGap=Number(vals['trick-hud.avatar-gap']);"
            + "const avatar=(g.avatars||[]).find(x=>Number(x.scale)===scale)||{plainAdvance:0,outlinedAdvance:0,rowHeight:0};"
            + "const outlined=!!vals['trick-hud.avatar-outline.enabled'];"
            + "const avatarSlot=Number(outlined?avatar.outlinedAdvance:avatar.plainAdvance);"
            + "const avatarRowWidth=3*avatarSlot+2*avGap,avatarHeight=Number(avatar.rowHeight);"
            + "const counterCells=g.counters||[],counterGap=Number(vals['trick-hud.counter.gap']);"
            + "let counterRowWidth=0;if(counterCells.length){counterCells.forEach(c=>{counterRowWidth+=Number(c.advance)});counterRowWidth+=(counterCells.length-1)*counterGap}"
            + "return{cardW:cardW,cardAdvance:cardAdvance,cardRowWidth:cardRowWidth,cardHeight:cardHeight,step:step,n:n,"
            + "avatarSlot:avatarSlot,avatarRowWidth:avatarRowWidth,avatarHeight:avatarHeight,avGap:avGap,"
            + "counterCells:counterCells,counterRowWidth:counterRowWidth,counterGap:counterGap}}"
            // 布局警告缓存：renderPreview 每次重算，renderWarnings 再合并进列表。
            + "let layoutWarnings=[];"
            + "function pushBoundsWarn(name,x,y,w,h){const g=geo();"
            + "if(x<0)layoutWarnings.push(name+' 越出屏幕左边界（x='+Math.round(x)+'）');"
            + "if(y<0)layoutWarnings.push(name+' 越出屏幕上边界（y='+Math.round(y)+'）');"
            + "if(x+w>g.screenWidth)layoutWarnings.push(name+' 越出屏幕右边界（x+w='+Math.round(x+w)+' > '+g.screenWidth+'）');"
            + "if(y+h>g.screenHeight)layoutWarnings.push(name+' 越出屏幕下边界（y+h='+Math.round(y+h)+' > '+g.screenHeight+'）')}"
            + "function renderPreview(){layoutWarnings=[];const vals=collectAll(),g=geo(),screen=document.getElementById('screen');"
            + "screen.style.width=cssPx(g.screenWidth)+'px';screen.style.height=cssPx(g.screenHeight)+'px';screen.innerHTML='';"
            + "let html='';"
            // 参照物：BossBar 轨道（装饰性 182x5，仅用于示意；left/top 基于 geometry）、屏幕水平中线、屏幕底边
            + "const bossW=182,bossH=5,bossLeft=Math.floor((g.screenWidth-bossW)/2),bossTop=Math.max(0,g.bossBarBaselineY-bossH);"
            + "html+='<div class=\"ref boss\" style=\"left:'+cssPx(bossLeft)+'px;top:'+cssPx(bossTop)+'px;width:'+cssPx(bossW)+'px;height:'+cssPx(bossH)+'px\"></div>';"
            + "if(dragCfg.centerGuidesEnabled){html+='<div class=\"ref grid\"></div><div class=\"ref mid\" style=\"left:'+cssPx(g.screenWidth/2)+'px\"></div><div class=\"ref bottom\" style=\"top:'+cssPx(g.screenHeight-1)+'px\"></div>';}"
            + "if(vals['trick-hud.enabled']){const r=rowGeom(vals);"
            + "const maxW=Math.max(r.cardRowWidth,r.avatarRowWidth,vals['trick-hud.counter.enabled']?r.counterRowWidth:0);"
            + "const ox=Number(vals['trick-hud.offset-x']);"
            // 整数 MC 像素居中：先按屏幕居中 max 行，再在 max 行内居中当前行。
            + "const baseLeft=Math.floor((g.screenWidth-maxW)/2),bossBaseline=g.bossBarBaselineY;"
            // 头像行：ascent = rowHeight - avatar-offset-down；top = baseline - ascent
            + "const avatarAscent=r.avatarHeight-Number(vals['trick-hud.avatar-offset-down']);"
            + "const avX=baseLeft+Math.floor((maxW-r.avatarRowWidth)/2)+ox+Number(vals['trick-hud.avatar-offset-x']);"
            + "const avY=bossBaseline-avatarAscent;"
            + "let oc=String(vals['trick-hud.avatar-outline.color']);if(!/^#[0-9a-fA-F]{6}$/.test(oc))oc='#'+oc.slice(-6);"
            + "const ob=vals['trick-hud.avatar-outline.enabled']?oc:'transparent';"
            + "html+='<div class=layer data-drag=avatar style=\"left:'+cssPx(avX)+'px;top:'+cssPx(avY)+'px;width:'+cssPx(r.avatarRowWidth)+'px;height:'+cssPx(r.avatarHeight)+'px\"><span class=tag>头像行 avatar-offset-x/down</span>';"
            + "for(let i=0;i<3;i++){html+='<div class=avbox style=\"left:'+cssPx(i*(r.avatarSlot+r.avGap))+'px;top:0;width:'+cssPx(r.avatarSlot)+'px;height:'+cssPx(r.avatarHeight)+'px;border-color:'+ob+'\"></div>'}html+='</div>';"
            + "pushBoundsWarn('头像行',avX,avY,r.avatarRowWidth,r.avatarHeight);"
            // 牌行：ascent = cardHeight - offset-down；top = baseline - ascent（offset-down 增大 top 下降）
            + "const cardAscent=r.cardHeight-Number(vals['trick-hud.offset-down']);"
            + "const cdX=baseLeft+Math.floor((maxW-r.cardRowWidth)/2)+ox+Number(vals['trick-hud.card-offset-x']);"
            + "const cdY=bossBaseline-cardAscent;"
            + "html+='<div class=layer data-drag=card style=\"left:'+cssPx(cdX)+'px;top:'+cssPx(cdY)+'px;width:'+cssPx(r.cardRowWidth)+'px;height:'+cssPx(r.cardHeight)+'px\"><span class=tag>牌行 card-offset-x / offset-down</span>';"
            + "const cardLabels=['3','4','5','6','7','8','9'];"
            + "for(let i=0;i<r.n;i++){html+='<div class=cardbox style=\"left:'+cssPx(i*r.step)+'px;top:0;width:'+cssPx(r.cardW)+'px;height:'+cssPx(r.cardHeight)+'px;line-height:'+cssPx(r.cardHeight)+'px\">'+cardLabels[i]+'</div>'}html+='</div>';"
            + "pushBoundsWarn('牌行',cdX,cdY,r.cardRowWidth,r.cardHeight);"
            // 记牌行：每格逐格累加 x += advance + gap；视觉高度 14 只是现有预览盒，
            // 服务端不下发 counterCellHeight/counterLineHeight/counterBaselineY，运行期 counter 垂直语义未动。
            + "if(vals['trick-hud.counter.enabled']){"
            + "const cnX=baseLeft+Math.floor((maxW-r.counterRowWidth)/2)+ox+Number(vals['trick-hud.counter.offset-x']);"
            + "const cnY=cdY+r.cardHeight+r.counterGap;const cnH=14;"
            + "html+='<div class=layer data-drag=counter style=\"left:'+cssPx(cnX)+'px;top:'+cssPx(cnY)+'px;width:'+cssPx(r.counterRowWidth)+'px;height:'+cssPx(cnH)+'px\"><span class=tag>记牌行 counter.offset-x</span>';"
            + "let cx=0;r.counterCells.forEach((cell,i)=>{"
            + "const adv=Number(cell.advance);"
            + "html+='<div class=cnt data-label=\"'+esc(cell.label)+'\" data-remaining=\"'+cell.remaining+'\" style=\"left:'+cssPx(cx)+'px;top:0;width:'+cssPx(adv)+'px;height:'+cssPx(cnH)+'px;font-size:8px;line-height:'+cssPx(cnH)+'px;text-align:center;color:#eef\">'+esc(cell.label)+':'+cell.remaining+'</div>';"
            + "cx+=adv+(i<r.counterCells.length-1?r.counterGap:0)});"
            + "html+='</div>';"
            + "pushBoundsWarn('记牌行',cnX,cnY,r.counterRowWidth,cnH)}}"
            // hotbar 定位公式（批准版）：
            //   baseAscent = g.hotbarBaseAscent
            //   currentAscent = baseAscent - hy
            //   ascentDelta = baseAscent - currentAscent   （= hy）
            //   hbY = actionBarBottomY - hotbarHeight + ascentDelta
            //   hbX = floor((screenWidth - hotbarAdvance)/2) + hx
            // 中央 5 槽的 20/22/彩色只是视觉装饰，不影响 Hotbar 外框几何。
            + "if(vals['hotbar-hud.enabled']){"
            + "const hy=Number(vals['hotbar-hud.offset-y']),hx=Number(vals['hotbar-hud.offset-x']);"
            + "const baseAscent=g.hotbarBaseAscent,currentAscent=baseAscent-hy,ascentDelta=baseAscent-currentAscent;"
            + "const hbY=g.actionBarBottomY-g.hotbarHeight+ascentDelta;"
            + "const hbX=Math.floor((g.screenWidth-g.hotbarAdvance)/2)+hx;"
            + "html+='<div class=layer data-drag=hotbar style=\"left:'+cssPx(hbX)+'px;top:'+cssPx(hbY)+'px;width:'+cssPx(g.hotbarWidth)+'px;height:'+cssPx(g.hotbarHeight)+'px;background:#121216\"><span class=tag>Hotbar offset-x / offset-y（纵向需重载资源包）</span>';"
            + "const cols=['#e03a3a','#e08a2a','#d8d030','#3cc050','#3888e0'];"
            + "const slotW=20,slotStep=22,slotsW=5*slotW+4*(slotStep-slotW),slotsStart=Math.floor((g.hotbarWidth-slotsW)/2);"
            + "for(let i=0;i<5;i++){html+='<div class=hb style=\"left:'+cssPx(slotsStart+i*slotStep)+'px;top:'+cssPx(1)+'px;width:'+cssPx(slotW)+'px;height:'+cssPx(slotW)+'px;background:'+cols[i]+'\"></div>'}html+='</div>';"
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
            + "function setField(key,val){const el=form.querySelector(\"input[data-key='\"+CSS.escape(key)+\"'],select[data-key='\"+CSS.escape(key)+\"']\");if(!el)return false;"
            + "const f=state.fields.find(x=>x.key===key);let v=Math.round(val);"
            + "if(f&&f.min!=null)v=Math.max(f.min,v);if(f&&f.max!=null)v=Math.min(f.max,v);"
            // 档位型字段（select）只能取资源包实际生成的档，吸附到最近的合法选项，
            // 否则会写出一个后端必然拒绝的值。
            + "if(f&&f.control==='select'&&f.options&&f.options.length){let best=f.options[0];"
            + "f.options.forEach(o=>{if(Math.abs(Number(o)-v)<Math.abs(Number(best)-v))best=Number(o)});v=Number(best)}"
            // dragging 非空时走轻量路径：只更新表单值与 dirty 标记，不触发 renderPreview，
            // 避免把正在被拖的元素连同它的隐式指针捕获一起销毁。
            + "el.value=v;if(dragging){markDirty(el)}else{changed({target:el})}return true}"
            // 从 changed() 里抽出来的「只更新状态、不重建预览」部分
            + "function markDirty(el){const row=el.closest('.field');dirty.add(el.dataset.key);"
            + "if(row)row.classList.add('dirty');msg.textContent='有未保存改动：'+dirty.size+' 项'}"
            // 【拖动为什么不能把监听器挂在被拖的元素上】：拖动过程中要实时更新预览，
            // 而 renderPreview() 是整段重建 screen.innerHTML 的 —— 那会把正在被拖的
            // 元素本身销毁，挂在它上面的 pointermove 与 setPointerCapture 一起消失，
            // 结果拖动只在第一帧生效然后立刻断掉（表现就是「拖不动」）。
            // 所以监听器挂在 window 上：它不随预览重建而消失。
            + "let dragging=null;"
            + "function bindDrag(){document.querySelectorAll('.layer').forEach(el=>{el.onpointerdown=e=>{"
            + "e.preventDefault();const kind=el.dataset.drag,keys=DRAG_KEYS[kind];if(!keys)return;"
            + "const vals=collectAll();if(el.setPointerCapture)el.setPointerCapture(e.pointerId);"
            + "dragging={kind:kind,keys:keys,sx:e.clientX,sy:e.clientY,axis:null,"
            + "bx:Number(vals[keys[0]]||0),by:keys[1]?Number(vals[keys[1]]||0):0,el:el};"
            + "el.classList.add('drag')}});}"
            + "window.addEventListener('pointermove',ev=>{if(!dragging)return;ev.preventDefault();"
            + "const s=guiScale()/3;let dx=(ev.clientX-dragging.sx)/s,dy=(ev.clientY-dragging.sy)/s;"
            + "if(dragCfg.altAxisLock&&ev.altKey&&dragging.axis===null&&(Math.abs(dx)>=2||Math.abs(dy)>=2))dragging.axis=Math.abs(dx)>=Math.abs(dy)?'x':'y';"
            + "if(dragging.axis==='x')dy=0;if(dragging.axis==='y')dx=0;"
            + "const cur=document.querySelector('.layer[data-drag=\"'+dragging.kind+'\"]');"
            + "if(cur&&dragCfg.snapEnabled){const g=geo(),lw=parseFloat(cur.style.width)/s,lh=parseFloat(cur.style.height)/s;"
            + "const left=parseFloat(cur.style.left)/s,top=parseFloat(cur.style.top)/s;"
            + "const tx=Math.floor((g.screenWidth-lw)/2),ty=Math.floor((g.screenHeight-lh)/2);"
            + "if(Math.abs(left+dx-tx)<=dragCfg.snapThreshold)dx=tx-left;"
            + "if(Math.abs(top+dy-ty)<=dragCfg.snapThreshold&&dragging.keys[1])dy=ty-top;}"
            + "const k=dragging.keys;setField(k[0],dragging.bx+dx);if(k[1])setField(k[1],dragging.by+dy);"
            // 拖动期间不重建 DOM，只让当前层视觉上跟着指针走；否则真实鼠标的隐式指针捕获
            // 会因 target 元素被销毁而触发 pointercancel，导致「点一次只能拖一下」。
            + "if(cur){cur.classList.add('drag');cur.style.transform='translate('+cssPx(dx)+'px,'+cssPx(dy)+'px)'}"
            + "document.getElementById('dragHint').textContent='拖动中：'+k[0]+'='+Math.round(dragging.bx+dx)+(k[1]?('，'+k[1]+'='+Math.round(dragging.by+dy)):'')+(dragging.axis?'，Alt 锁 '+dragging.axis:'')});"
            + "function finishDrag(){if(!dragging)return;dragging=null;"
            // 松手后才完整重建一次，让吸附后的档位值和所有行的 max-width 居中重新计算。
            + "renderPreview();renderWarnings();"
            + "document.getElementById('dragHint').textContent='已停止拖动，改动尚未保存。点「保存并应用」写回 config.yml。'}"
            + "window.addEventListener('pointerup',finishDrag);window.addEventListener('pointercancel',finishDrag);"
            + "window.addEventListener('lostpointercapture',finishDrag);"
            + "document.getElementById('guiScale').onchange=()=>renderPreview();"
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
