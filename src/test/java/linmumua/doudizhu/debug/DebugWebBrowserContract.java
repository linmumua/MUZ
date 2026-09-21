package linmumua.doudizhu.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 Chromium 执行正式 src/main/resources/debug-hud-preview.html 的浏览器契约。
 *
 * <p>此类故意不以 *Test 命名，避免普通 JUnit 全量回归在没有浏览器依赖时误启动；
 * 由独立 launcher 显式传入本类运行。Node/Puppeteer 依赖与浏览器路径由环境提供。
 */
public class DebugWebBrowserContract {
    private static final String TOKEN = "muz-browser-contract-token";

    @Test
    void formalPageRunsRealBrowserContracts() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path page = root.resolve("src/main/resources/debug-hud-preview.html");
        Path rootPage = root.resolve("debug-hud-preview.html");
        assertTrue(Files.isRegularFile(page), "正式 Debug Web 页面不存在：" + page);
        assertTrue(Files.isRegularFile(rootPage), "根目录 Debug Web 页面不存在：" + rootPage);
        assertArrayEquals(Files.readAllBytes(rootPage), Files.readAllBytes(page),
            "根目录与正式资源目录 Debug Web 页面必须字节一致");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("trick-hud.enabled", true);
        values.put("trick-hud.avatar-scale", 6);
        values.put("trick-hud.avatar-gap", 6);
        values.put("trick-hud.card-step", 22);
        values.put("trick-hud.card-height", 53);
        values.put("trick-hud.offset-down", 50);
        values.put("trick-hud.avatar-offset-down", 122);
        values.put("trick-hud.offset-x", 0);
        values.put("trick-hud.card-offset-x", 0);
        values.put("trick-hud.avatar-offset-x", 0);
        values.put("trick-hud.avatar-outline.enabled", true);
        values.put("trick-hud.avatar-outline.color", "#000000");
        values.put("trick-hud.counter.enabled", true);
        values.put("trick-hud.counter.scale", 100);
        values.put("trick-hud.counter.offset-down", 122);
        values.put("trick-hud.counter.gap", 2);
        values.put("trick-hud.counter.hide-exhausted", false);
        values.put("trick-hud.counter.offset-x", 0);

        List<DebugHudConfigController.FieldDto> fields = DebugHudConfigController.fields().values().stream()
            .map(spec -> new DebugHudConfigController.FieldDto(
                spec.key(), spec.type().name().toLowerCase(java.util.Locale.ROOT), spec.fallback(),
                spec.min(), spec.max(), spec.step(), spec.label(), spec.group(), spec.control(), spec.options()))
            .toList();
        DebugHudConfigController.Snapshot snapshot = new DebugHudConfigController.Snapshot(values, List.of(), fields);
        String built = DebugWebServer.buildHtml(snapshot, TOKEN);
        assertTrue(built.contains("id=\"gadgetCollapse\""), "正式页面必须提供可收起道具面板入口");
        assertTrue(built.contains("new AbortController()"), "道具预览请求必须支持取消");
        assertTrue(built.contains("window.addEventListener('pagehide'"), "页面离开时必须清理道具轮询和图片资源");
        assertTrue(built.contains("item.slot>=0&&item.slot<=7"), "道具预览必须限制为 0..7 槽位");
        assertTrue(built.contains("fetch('/api/gadget-preview',{cache:'no-store',headers:{'X-MUZ-Token':token}"),
            "正式页面道具 GET 必须携带 X-MUZ-Token");
        assertTrue(Files.readString(page).contains("fetch(url,{cache:'force-cache',headers:{'X-MUZ-Token':token}"),
            "正式页面图标 GET 必须携带 X-MUZ-Token");
        String marker = "<script type='application/json' id='muz-state'>";
        int stateStart = built.indexOf(marker);
        int stateEnd = built.indexOf("</script>", stateStart);
        assertTrue(stateStart >= 0 && stateEnd > stateStart, "无法从真实几何快照导出浏览器 fixture 状态");
        JsonObject snapshotJson = new Gson().fromJson(
            built.substring(stateStart + marker.length(), stateEnd), JsonObject.class);
        JsonObject apiState = new JsonObject();
        apiState.addProperty("ok", true);
        apiState.add("snapshot", snapshotJson);
        apiState.add("previewResources", new Gson().toJsonTree(previewResourceManifest()));
        // 使用生产方法导出响应头，不在 Node 中复制 CSP，避免夹具漏掉真实浏览器限制。
        com.sun.net.httpserver.Headers headers = new com.sun.net.httpserver.Headers();
        try {
            java.lang.invoke.MethodHandles.privateLookupIn(DebugWebServer.class, java.lang.invoke.MethodHandles.lookup())
                .findStatic(DebugWebServer.class, "addSecurityHeaders",
                    java.lang.invoke.MethodType.methodType(void.class, com.sun.net.httpserver.Headers.class))
                .invoke(headers);
        } catch (Throwable failure) {
            throw new AssertionError("无法导出正式安全响应头", failure);
        }
        Map<String, String> securityHeaders = new LinkedHashMap<>();
        headers.forEach((key, entries) -> securityHeaders.put(key, String.join(", ", entries)));
        assertEquals("no-referrer", headers.getFirst("Referrer-Policy"),
            "正式页面 fixture 必须使用生产 Referrer-Policy");
        assertTrue(headers.getFirst("Content-Security-Policy").contains("img-src 'self' data: blob:"),
            "正式页面 fixture 的生产 CSP 必须允许 blob 图片");
        apiState.add("securityHeaders", new Gson().toJsonTree(securityHeaders));

        Path tempDir = Files.createTempDirectory("muz-debug-web-browser-");
        Process process = null;
        try {
            Path state = tempDir.resolve("state.json");
            Path screenshots = Files.createDirectories(tempDir.resolve("screenshots"));
            Files.writeString(state, new Gson().toJson(apiState), StandardCharsets.UTF_8);
            Path script = resourceScript();
            Path processLog = tempDir.resolve("browser-contract.log");
            process = new ProcessBuilder(
                nodeExecutable(), script.toString(), page.toString(), state.toString(), TOKEN,
                screenshots.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(processLog.toFile())
                .start();
            boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                terminateProcessTree(process);
            }
            String output = Files.exists(processLog)
                ? Files.readString(processLog, StandardCharsets.UTF_8)
                : "";
            assertTrue(finished, "真实浏览器契约超时：" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("BROWSER_CONTRACT=PASS"), output);
        } finally {
            // 即使等待被中断也清理本次启动的进程树，不触碰其他会话或浏览器。
            terminateProcessTree(process);
            Files.deleteIfExists(tempDir.resolve("state.json"));
            System.out.println("BROWSER_CONTRACT_SCREENSHOTS=" + tempDir.resolve("screenshots"));
        }
    }

    /** 先捕获并终止后代，再终止根进程，避免 Node 退出后 Chromium 变为孤立进程。 */
    private static void terminateProcessTree(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void hangingNodeAndItsChildAreTerminatedAfterTimeout() throws Exception {
        Path log = Files.createTempFile("muz-browser-timeout-", ".log");
        Process process = null;
        ProcessHandle child = null;
        try {
            String script = "const child=require('node:child_process').spawn(process.execPath,"
                + "['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});"
                + "process.stdout.write(String(child.pid)+'\\n');setInterval(()=>{},1000);";
            process = new ProcessBuilder(nodeExecutable(), "-e", script)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            String output = "";
            while (System.nanoTime() < deadline) {
                output = Files.readString(log, StandardCharsets.UTF_8);
                if (output.endsWith("\n")) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(output.strip().matches("[0-9]+"), "Node 子进程未就绪：" + output);
            child = ProcessHandle.of(Long.parseLong(output.strip())).orElseThrow();
            assertTrue(child.isAlive(), "测试必须实际创建活动子进程");
            assertTrue(!process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS),
                "挂起进程必须在输出流尚未结束时返回超时，而不是阻塞读取 EOF");
            terminateProcessTree(process);
            child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(!process.isAlive() && !child.isAlive(), "超时后根进程与后代均须结束");
        } finally {
            terminateProcessTree(process);
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
            Files.deleteIfExists(log);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> previewResourceManifest() throws Exception {
        Field field = DebugWebServer.class.getDeclaredField("PREVIEW_RESOURCE_WHITELIST");
        field.setAccessible(true);
        Map<?, ?> whitelist = (Map<?, ?>) field.get(null);
        Method manifest = null;
        for (Method candidate : whitelist.values().iterator().next().getClass().getDeclaredMethods()) {
            if (candidate.getName().equals("manifest") && candidate.getParameterCount() == 0) {
                manifest = candidate;
                break;
            }
        }
        assertTrue(manifest != null, "正式预览资源必须提供 manifest 方法");
        manifest.setAccessible(true);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object resource : whitelist.values()) {
            result.add((Map<String, Object>) manifest.invoke(resource));
        }
        return result;
    }

    private static Path resourceScript() throws Exception {
        Path output = Files.createTempFile("muz-debug-web-browser-contract-", ".cjs");
        String source;
        try (var input = DebugWebBrowserContract.class.getResourceAsStream(
            "/linmumua/doudizhu/debug/debug-web-browser-contract.cjs")) {
            assertTrue(input != null, "缺少浏览器契约 Node 脚本资源");
            // Git/Windows 可能将资源转为 CRLF；先统一换行，避免固定 LF 注入点误报缺失。
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");
        }
        String harness = """

            async function installLogicalBoundsMonitor(page) {
              await page.evaluate(() => {
                const failures = [];
                const seen = new Set();
                const visible = element => {
                  const style = getComputedStyle(element);
                  const rect = element.getBoundingClientRect();
                  return style.display !== 'none' && style.visibility !== 'hidden'
                    && Number(style.opacity) > 0 && rect.width > 0 && rect.height > 0;
                };
                const outside = (rect, screen) => rect.left < screen.left - 1.5
                  || rect.top < screen.top - 1.5 || rect.right > screen.right + 1.5
                  || rect.bottom > screen.bottom + 1.5;
                const remember = (kind, element, rect, screen, label) => {
                  const key = kind + ':' + element + ':' + [rect.left, rect.top, rect.right, rect.bottom]
                    .map(value => Math.round(value * 10) / 10).join(',');
                  if (seen.has(key)) return;
                  seen.add(key);
                  failures.push({label, kind, element, rect: {
                    left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom
                  }, screen: {
                    left: screen.left, top: screen.top, right: screen.right, bottom: screen.bottom
                  }});
                };
                window.__muzBoundsFailures = failures;
                window.__muzCheckBounds = label => {
                  const screenElement = document.querySelector('#screen');
                  if (!screenElement) return;
                  const screen = screenElement.getBoundingClientRect();
                  if (!(screen.width > 0 && screen.height > 0)) return;
                  for (const kind of ['card', 'avatar', 'counter']) {
                    const layer = document.querySelector('.layer[data-layer="' + kind + '"]');
                    if (!layer) continue;
                    const layerRect = layer.getBoundingClientRect();
                    if (!(layerRect.width > 0 && layerRect.height > 0)) continue;
                    if (outside(layerRect, screen)) remember(kind, 'layer', layerRect, screen, label);
                    const selector = kind === 'card' ? '.card'
                      : kind === 'avatar' ? '.avatar-face'
                      : kind === 'counter' ? '.counter-cell'
                      : 'img';
                    for (const element of layer.querySelectorAll(selector)) {
                      if (!visible(element)) continue;
                      const rect = element.getBoundingClientRect();
                      if (outside(rect, screen)) remember(kind, element.className || element.tagName, rect, screen, label);
                    }
                  }
                };
                const tick = () => {
                  window.__muzCheckBounds('requestAnimationFrame');
                  requestAnimationFrame(tick);
                };
                requestAnimationFrame(tick);
              });
            }

            async function assertLogicalBounds(page, label) {
              const failures = await page.evaluate(label => {
                window.__muzCheckBounds?.(label);
                return (window.__muzBoundsFailures || []).slice(0, 20);
              }, label);
              assert.equal(failures.length, 0,
                label + ' 三层真实 DOM 内容必须完整位于 #screen 逻辑 viewport 内：' + JSON.stringify(failures));
            }

            async function assertViewportBounds(page, label) {
              const rect = await page.$eval('#screen', element => {
                const r = element.getBoundingClientRect();
                return {left: r.left, top: r.top, right: r.right, bottom: r.bottom,
                  width: r.width, height: r.height};
              });
              assert(rect.width > 0 && rect.height > 0, label + ' screen 逻辑舞台必须可见：' + JSON.stringify(rect));
              assert(rect.left >= -1.5 && rect.top >= -1.5
                && rect.right <= page.viewport().width + 1.5
                && rect.bottom <= page.viewport().height + 1.5,
                label + ' screen 逻辑舞台不得被浏览器窗口裁切：' + JSON.stringify(rect));
            }

            async function setCoordinateField(page, id, value) {
              await page.$eval('#' + id, (element, next) => {
                element.value = String(next);
                element.dispatchEvent(new Event('input', {bubbles: true}));
              }, value);
              await frame(page);
            }

            async function exerciseDirectionalDrags(page) {
              const cases = [
                ['card', 'trick-hud.card-offset-x', 'trick-hud.offset-down'],
                ['avatar', 'trick-hud.avatar-offset-x', 'trick-hud.avatar-offset-down'],
                ['counter', 'trick-hud.counter.offset-x', 'trick-hud.counter.offset-down'],
              ];
              for (const [kind, keyX, keyY] of cases) {
                for (const [dx, dy, direction] of [[-24, 0, '左'], [24, 0, '右'],
                  [0, -24, '上'], [0, 24, '下']]) {
                  await dragLayer(page, kind, dx, dy, keyX, keyY);
                  await assertLogicalBounds(page, kind + ' ' + direction + '侧拖动');
                }
              }
            }

            async function exerciseStableResize(page) {
              await setRawField(page, 'trick-hud.offset-x', 0);
              await setRawField(page, 'trick-hud.avatar-offset-x', 0);
              await setRawField(page, 'trick-hud.avatar-offset-down', 100);
              await page.$eval('[data-select="avatar"]', element => element.click());
              await page.$eval('#zoomMode', element => element.click());
              await frame(page);
              await page.evaluate(() => {
                const content = document.querySelector('.layer[data-layer="avatar"] .layer-content');
                window.__muzResizeMutations = 0;
                window.__muzResizeObserver = new MutationObserver(records => {
                  window.__muzResizeMutations += records.reduce((sum, record) => sum + record.addedNodes.length + record.removedNodes.length, 0);
                });
                window.__muzResizeObserver.observe(content, {childList: true, subtree: true});
              });
              const handle = await page.$eval('.layer[data-layer="avatar"] .resize-handle.se', element => {
                const rect = element.getBoundingClientRect();
                const x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
                return {x, y, width: rect.width, height: rect.height,
                  hit: document.elementFromPoint(x, y) === element};
              });
              assert(handle.width > 0 && handle.height > 0 && handle.hit,
                '头像缩放手柄必须可见且实际命中，不能点击隐藏坐标：' + JSON.stringify(handle));
              await page.mouse.move(handle.x, handle.y);
              await page.mouse.down();
              const started = await page.evaluate(({x, y}) => ({
                dragging: document.querySelector('.layer[data-layer="avatar"]')?.classList.contains('dragging'),
                selected: document.querySelector('.layer[data-layer="avatar"]')?.classList.contains('selected'),
                mode: document.querySelector('#screen')?.className,
                hit: document.elementFromPoint(x, y)?.className
              }), handle);
              assert(started.dragging, 'resize pointerdown 必须命中活动头像句柄：' + JSON.stringify(started));
              await page.mouse.move(handle.x - 120, handle.y - 120, {steps: 3});
              await frame(page);
              const during = await page.evaluate(() => ({
                mutations: window.__muzResizeMutations,
                dragging: document.querySelector('.layer[data-layer="avatar"]')?.classList.contains('dragging'),
                scale: Number(document.querySelector('[data-key="trick-hud.avatar-scale"]')?.value)
              }));
              assert(during.dragging, 'resize pointermove 后必须保持 dragging 状态');
              assert.equal(during.mutations, 0,
                'resize pointermove 期间不得 replaceChildren 重建头像 DOM：' + JSON.stringify(during));
              assert([4, 6].includes(during.scale), 'resize 只能选择真实头像资源档位：' + JSON.stringify(during));
              await assertLogicalBounds(page, '头像 resize 期间');
              await page.mouse.up();
              await frame(page);
              const after = await page.evaluate(() => {
                const result = {mutations: window.__muzResizeMutations};
                window.__muzResizeObserver?.disconnect();
                delete window.__muzResizeObserver;
                return result;
              });
              assert(after.mutations > 0, 'resize 松手后必须执行一次正式 DOM 刷新：' + JSON.stringify(after));
              await assertLogicalBounds(page, '头像 resize 完成');
              await page.$eval('#moveMode', element => element.click());
            }

            async function exerciseViewPan(page) {
              const before = await page.$eval('#screen', element => {
                const r = element.getBoundingClientRect();
                return {left: r.left, top: r.top, width: r.width, height: r.height};
              });
              const zoom = before.width / 640;
              const x = before.left + 6 * zoom;
              const y = before.top + 200 * zoom;
              await page.keyboard.down('Shift');
              try {
                await page.mouse.move(x, y);
                await page.mouse.down();
                await page.mouse.move(x + 40, y + 24, {steps: 2});
                await page.mouse.up();
              } finally {
                await page.keyboard.up('Shift');
              }
              await frame(page);
              const after = await page.$eval('#screen', element => {
                const r = element.getBoundingClientRect();
                return {left: r.left, top: r.top, width: r.width, height: r.height};
              });
              assert(Math.abs(after.left - before.left) > 1 || Math.abs(after.top - before.top) > 1,
                'Shift+空白画布拖动必须平移逻辑舞台：' + JSON.stringify({before, after}));
              await assertLogicalBounds(page, '视图平移');
              await assertViewportBounds(page, '视图平移');
            }
            """;
        int marker = source.indexOf("(async () => {");
        assertTrue(marker >= 0, "浏览器 fixture 缺少可注入的异步入口");
        source = source.substring(0, marker) + harness + "\n" + source.substring(marker);
        source = replaceRequired(source,
            "    }, {timeout: 10000});\n    assert.equal(errors.length, 0, '页面启动 JS 错误：' + errors.join(' | '));",
            "    }, {timeout: 10000});\n    await installLogicalBoundsMonitor(page);\n    await assertLogicalBounds(page, '初载');\n    await assertViewportBounds(page, '初载');\n    assert.equal(errors.length, 0, '页面启动 JS 错误：' + errors.join(' | '));",
            "初载断言注入点");
        source = replaceRequired(source,
            "    assert.equal(drags.length, 3, '牌行、头像、记牌三层必须全部完成最终帧拖动');",
            "    assert.equal(drags.length, 3, '牌行、头像、记牌三层必须全部完成最终帧拖动');\n"
                + "    await exerciseDirectionalDrags(page);\n"
                + "    await exerciseStableResize(page);\n"
                + "    await exerciseViewPan(page);\n"
                + "    await assertLogicalBounds(page, '拖动、缩放与平移完成');",
            "拖动与三层拖动、缩放及平移断言注入点");
        source = replaceRequired(source,
            "    const scale2 = await screenScale(page);",
            "    const scale2 = await screenScale(page);\n"
                + "    await assertLogicalBounds(page, 'GUI 2');\n"
                + "    await assertViewportBounds(page, 'GUI 2');",
            "GUI 2 断言注入点");
        source = replaceRequired(source,
            "    const scale4 = await screenScale(page);",
            "    const scale4 = await screenScale(page);\n"
                + "    await assertLogicalBounds(page, 'GUI 4');\n"
                + "    await assertViewportBounds(page, 'GUI 4');",
            "GUI 4 断言注入点");
        source = replaceRequired(source,
            "      await frame(page);\n      const responsive = await page.evaluate(() => {",
            "      await frame(page);\n      await assertLogicalBounds(page, '窄屏 ' + width);\n      await assertViewportBounds(page, '窄屏 ' + width);\n      const responsive = await page.evaluate(() => {",
            "窄屏断言注入点");
        source = replaceRequired(source,
            "    assert.equal(errors.length, 0, '浏览器契约期间出现 JS/console error：' + errors.join(' | '));",
            "    await assertLogicalBounds(page, '最终');\n"
                + "    await assertViewportBounds(page, '最终');\n"
                + "    assert.equal(errors.length, 0, '浏览器契约期间出现 JS/console error：' + errors.join(' | '));",
            "最终断言注入点");
        Files.writeString(output, source, StandardCharsets.UTF_8);
        output.toFile().deleteOnExit();
        return output;
    }

    private static String replaceRequired(String source, String marker, String replacement, String description) {
        assertTrue(source.contains(marker), "浏览器 fixture 缺少" + description);
        return source.replace(marker, replacement);
    }

    private static String nodeExecutable() {
        String configured = System.getenv("NODE");
        return configured == null || configured.isBlank() ? "node" : configured;
    }
}
