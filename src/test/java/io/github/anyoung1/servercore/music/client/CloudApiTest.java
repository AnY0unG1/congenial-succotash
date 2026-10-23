package io.github.anyoung1.servercore.music.client;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Local compatibility and security checks; no real account or internet required. */
public final class CloudApiTest {
    private static int n;
    private static void check(boolean ok, String why) { n++; if (!ok) throw new AssertionError(why); }
    private static void rejects(Runnable r) { boolean yes = false; try { r.run(); } catch (RuntimeException e) { yes = true; } check(yes, "expected rejection"); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("music-api-test-"); Path cfg = dir.resolve("music.json");
        var body = new AtomicReference<>("{\"code\":200}"); var request = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { request.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); byte[] b = body.get().getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200,b.length); try(var o=ex.getResponseBody()){o.write(b);} }); server.start();
        try {
            CloudApi api = new CloudApi(cfg); api.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort(), true); api.login("MUSIC_U=private; other=value"); api.call("/status", Map.of());
            check(request.get().contains("cookie=MUSIC_U%3Dprivate%3B+other%3Dvalue"), "cookie encoding"); check(!Files.readString(cfg).contains("private"), "cookie persisted"); check(!new CloudApi(cfg).loggedIn(), "session persisted");
            rejects(() -> { try { api.setEndpoint("http://example.com", false); } catch(Exception e){ throw new RuntimeException(e); }});
            rejects(() -> { try { CloudApi.mediaUri("https://music.126.net.evil.example/a.mp3"); } catch(Exception e){ throw new RuntimeException(e); }});
            rejects(() -> { try { CloudApi.mediaUri("file:///etc/passwd"); } catch(Exception e){ throw new RuntimeException(e); }});
            check(CloudApi.mediaUri("http://m801.music.126.net/a.mp3").getScheme().equals("https"), "CDN upgrade");
            body.set("{\"code\":200,\"data\":[{\"url\":null}]}"); rejects(() -> { try { api.songUrl(1); } catch(Exception e){ throw new RuntimeException(e); }});
            body.set("{\"code\":200,\"result\":{\"songs\":[{\"id\":1,\"name\":\"测试\",\"dt\":90000,\"ar\":[{\"name\":\"歌手\"}]}]}}"); check(api.search("测试").getFirst().name().equals("测试 - 歌手"), "search parse");
            System.out.println("Music API: " + n + " checks passed");
        } finally { server.stop(0); Files.deleteIfExists(cfg); Files.deleteIfExists(dir); }
    }
}
