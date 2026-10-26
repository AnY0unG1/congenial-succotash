package io.github.anyoung1.servercore.music.api;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import io.github.anyoung1.servercore.music.MusicConfig;

/** Local direct-web API contract checks; no real account or internet required. */
public final class CloudApiTest {
    private static int n;
    private static void check(boolean ok, String why) { n++; if (!ok) throw new AssertionError(why); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (java.io.IOException e) { rejected = true; }
        check(rejected, "expected IOException");
    }
    public static void main(String[] args) throws Exception {
        io.github.anyoung1.servercore.music.PlaybackTest.run();
        var body = new AtomicReference<>("{\"code\":200}");
        var method = new AtomicReference<>("");
        var query = new AtomicReference<>("");
        var referer = new AtomicReference<>("");
        var cookie = new AtomicReference<>("");
        var routes = new ConcurrentHashMap<String, String>();
        var loginCookies = new AtomicReference<java.util.List<String>>(java.util.List.of());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", ex -> {
            method.set(ex.getRequestMethod()); query.set(ex.getRequestURI().getRawQuery());
            referer.set(ex.getRequestHeaders().getFirst("Referer"));
            cookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            if (ex.getRequestURI().getPath().equals("/api/login/qrcode/client/login"))
                loginCookies.get().forEach(value -> ex.getResponseHeaders().add("Set-Cookie", value));
            byte[] bytes = routes.getOrDefault(ex.getRequestURI().getPath(), body.get()).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            CloudApi api = new CloudApi(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/search/get/web"));
            check(api.search("测试 &+#").isEmpty(), "empty result");
            check(method.get().equals("GET"), "direct web search uses GET");
            check(URLDecoder.decode(query.get(), StandardCharsets.UTF_8).equals("s=测试 &+#&type=1&limit=5&offset=0"), "encoded keyword and search parameters");
            check(referer.get().equals("https://music.163.com/"), "NetEase referer");
            body.set("""
                {"code":200,"result":{"songs":[
                  {"id":1,"name":" 测试 ","duration":90000,"fee":0,"artists":[{"name":"歌手"},{"name":"歌手2"}]},
                  {"id":2,"name":"VIP","duration":90000,"fee":1},
                  {"id":3,"name":"纯音乐","duration":120000,"artists":[]}
                ]}}
                """);
            var songs = api.search("测试");
            check(songs.size() == 3 && songs.get(1).id() == 2, "VIP songs retained");
            check(songs.getFirst().name().equals("测试 - 歌手, 歌手2"), "artists parsed");
            check(songs.getFirst().duration() == 90000, "duration stays in milliseconds");
            check(songs.get(2).name().equals("纯音乐"), "empty artist list");
            check(!api.search("测试", 0).hasNext(), "short page without total is final");
            String item = "{\"id\":1,\"name\":\"歌曲\",\"duration\":90000}";
            String five = String.join(",", java.util.Collections.nCopies(5, item));
            body.set("{\"code\":200,\"result\":{\"songCount\":10,\"songs\":[" + five + "]}}");
            check(api.search("测试", 0).hasNext(), "first page has next");
            var last = api.search("测试", 1);
            check(last.songs().size() == 5 && !last.hasNext(), "full final page respects total");
            check(query.get().endsWith("&limit=5&offset=5"), "second page offset");
            body.set("{\"code\":200,\"result\":{\"songs\":[" + five + "]}}");
            check(api.search("测试", 1).hasNext(), "missing total permits next page");
            body.set("{\"code\":200,\"result\":{\"songCount\":10,\"songs\":[]}}");
            check(!api.search("测试", 2).hasNext(), "empty page is final");
            rejects(() -> api.search("测试", -1));
            rejects(() -> api.search("测试", Integer.MAX_VALUE));
            check(CloudApi.songId("https://music.163.com/song?id=123&uct2=abc") == 123, "song share URL");
            check(CloudApi.songId("https://music.163.com/#/song?id=123") == 123, "fragment song URL");
            check(CloudApi.songId("https://music.163.com/m/song?id=123") == 123, "mobile song URL");
            check(CloudApi.songId("https://music.163.com/song/media/outer/url?id=123.mp3") == 123, "outer song URL");
            rejects(() -> CloudApi.songId("https://music.163.com/playlist?id=123"));
            rejects(() -> CloudApi.songId("https://music.163.com.evil.example/song?id=123"));
            rejects(() -> CloudApi.songId("https://music.163.com/song?id=0"));
            rejects(() -> CloudApi.songId("https://music.163.com/song?id=99999999999999999999999"));
            rejects(() -> CloudApi.songId("https://music.163.com/song"));
            body.set("""
                {"code":200,"songs":[{"id":123,"name":"VIP详情","fee":1,"duration":180000,"artists":[{"name":"歌手"}]}]}
                """);
            var linked = api.songFromLink("https://music.163.com/#/song?id=123");
            check(linked.id() == 123 && linked.duration() == 180000 && linked.name().equals("VIP详情 - 歌手"), "link fetches song metadata including VIP");
            check(query.get().equals("id=123&ids=%5B123%5D"), "detail query");
            rejects(() -> api.songFromLink("https://music.163.com/song?id=456"));
            body.set("{\"code\":200,\"songs\":[]}");
            rejects(() -> api.songFromLink("https://music.163.com/song?id=123"));
            check(api.songUrl(123).toString().equals("https://music.163.com/song/media/outer/url?id=123.mp3"), "NetMusic public song URL");
            rejects(() -> api.songUrl(0));
            check(CloudApi.mediaUri("http://m801.music.126.net/a.mp3").getScheme().equals("https"), "CDN HTTPS upgrade");
            check(CloudApi.mediaUri(api.songUrl(123).toString()).equals(api.songUrl(123)), "outer link allowed");
            rejects(() -> CloudApi.mediaUri("https://music.126.net.evil.example/a.mp3"));
            rejects(() -> CloudApi.mediaUri("https://music.163.com.evil.example/a.mp3"));
            rejects(() -> CloudApi.mediaUri("file:///etc/passwd"));
            rejects(() -> CloudApi.mediaUri("https://user@music.163.com/a.mp3"));
            rejects(() -> CloudApi.openAudio(URI.create("https://music.163.com/404")));
            body.set("{\"code\":500}"); rejects(() -> api.search("测试"));
            body.set("not json"); rejects(() -> api.search("测试"));
            body.set("{\"code\":200,\"result\":{\"songs\":[{\"id\":1}]}}"); rejects(() -> api.search("测试"));
            body.set("{\"code\":200,\"result\":{\"songs\":null}}");
            check(api.search("测试").isEmpty(), "null songs");
            CloudApi loggedIn = new CloudApi(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/search/get/web"), "MUSIC_U=test-session; __csrf=test-csrf");
            loggedIn.search("测试");
            check(cookie.get().equals("MUSIC_U=test-session; __csrf=test-csrf"), "server Cookie sent as header");
            check(!query.get().contains("test-session"), "Cookie not in URL");
            api.search("测试");
            check(cookie.get() == null, "anonymous API does not inherit shared Cookie");
            routes.put("/api/nuser/account/get", "{\"code\":200,\"profile\":{\"userId\":42,\"nickname\":\"测试账号\"}}");
            routes.put("/api/user/playlist/", """
                {"code":200,"more":true,"playlist":[{"id":11,"name":"收藏歌单","trackCount":6}]}
                """);
            var lists = loggedIn.playlists(1);
            check(lists.account().equals("测试账号") && lists.playlists().getFirst().id() == 11 && lists.hasNext(), "account playlists");
            check(query.get().contains("uid=42&offset=5&limit=5"), "playlist uses logged-in UID and page offset");
            String allLists = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(i -> "{\"id\":" + i + ",\"name\":\"歌单\",\"trackCount\":1}")
                .collect(java.util.stream.Collectors.joining(","));
            routes.put("/api/user/playlist/", "{\"code\":200,\"more\":false,\"playlist\":[" + allLists + "]}");
            var firstLists = loggedIn.playlists(0);
            var secondLists = loggedIn.playlists(1);
            check(firstLists.playlists().size() == 5 && firstLists.hasNext(), "full collection is paged locally");
            check(secondLists.playlists().size() == 2 && secondLists.playlists().getFirst().id() == 6
                && !secondLists.hasNext(), "full collection second page does not repeat first page");
            check(loggedIn.playlists(2).playlists().isEmpty(), "full collection past end");
            rejects(() -> api.playlists(0));
            routes.put("/api/nuser/account/get", "{\"code\":200,\"profile\":null}");
            rejects(() -> loggedIn.playlists(0));
            routes.put("/api/v3/playlist/detail", """
                {"code":200,"playlist":{"trackIds":[{"id":1},{"id":2},{"id":3},{"id":4},{"id":5},{"id":6}]}}
                """);
            routes.put("/api/song/detail/", """
                {"code":200,"songs":[{"id":6,"name":"会员歌曲","fee":1,"dt":180000,"ar":[{"name":"歌手"}]}]}
                """);
            var tracks = loggedIn.playlistSongs(11, 1);
            check(tracks.songs().getFirst().id() == 6 && tracks.songs().getFirst().duration() == 180000 && !tracks.hasNext(), "playlist final page and new-style metadata");
            check(URLDecoder.decode(query.get(), StandardCharsets.UTF_8).equals("ids=[6]"), "fetch only current page details");
            check(loggedIn.playlistSongs(11, 2).songs().isEmpty(), "playlist past end");
            check(loggedIn.playlistIds(11).equals(java.util.List.of(1L, 2L, 3L, 4L, 5L, 6L)), "playback loads entire playlist, not one page");
            rejects(() -> loggedIn.playlistSongs(0, 0));
            routes.put("/api/song/enhance/player/url/v1", """
                {"code":200,"data":[{"id":6,"code":200,"url":"http://m801.music.126.net/vip.mp3","type":"mp3","freeTrialInfo":null}]}
                """);
            check(loggedIn.songUrl(6).toString().equals("https://m801.music.126.net/vip.mp3"), "authenticated playback URL");
            check(query.get().equals("encodeType=mp3&ids=%5B6%5D&level=standard"), "BetterLogin player endpoint with MP3 quality");
            routes.put("/api/song/enhance/player/url/v1", "{\"code\":200,\"data\":[{\"code\":200,\"url\":null}]}");
            rejects(() -> loggedIn.songUrl(6));
            routes.put("/api/song/enhance/player/url/v1", """
                {"code":200,"data":[{"code":200,"url":"https://m801.music.126.net/trial.mp3","freeTrialInfo":{"start":0,"end":30}}]}
                """);
            rejects(() -> loggedIn.songUrl(6));
            routes.put("/api/song/enhance/player/url/v1", """
                {"code":200,"data":[{"code":200,"url":"https://evil.example/song.mp3"}]}
                """);
            rejects(() -> loggedIn.songUrl(6));

            routes.put("/api/login/qrcode/unikey", "{\"code\":200,\"unikey\":\"test-key\"}");
            check(api.qrKey().equals("test-key"), "BetterLogin QR key endpoint");
            routes.put("/api/login/qrcode/client/login", "{\"code\":801}");
            check(api.qrStatus("test-key").code() == 801 && api.qrStatus("test-key").cookie().isEmpty(), "QR waiting state");
            routes.put("/api/login/qrcode/client/login", "{\"code\":802}");
            check(api.qrStatus("test-key").code() == 802, "QR awaiting confirmation");
            routes.put("/api/login/qrcode/client/login", "{\"code\":800}");
            check(api.qrStatus("test-key").code() == 800, "QR expired state");
            routes.put("/api/login/qrcode/client/login", "{\"code\":803}");
            rejects(() -> api.qrStatus("test-key"));
            loginCookies.set(java.util.List.of("MUSIC_U=test-session=abc; Path=/; HttpOnly", "__csrf=test-csrf; Path=/; Secure"));
            var logged = api.qrStatus("test-key &+");
            check(logged.code() == 803 && logged.cookie().contains("MUSIC_U=test-session=abc"), "QR Cookie preserves equals signs");
            check(logged.cookie().contains("__csrf=test-csrf") && logged.cookie().contains("os=pc") && logged.cookie().contains("appver=3.1.6"), "QR desktop cookies match BetterLogin");
            check(!logged.cookie().contains("HttpOnly") && !logged.cookie().contains("Path="), "Cookie attributes excluded");
            check(URLDecoder.decode(query.get(), StandardCharsets.UTF_8).equals("key=test-key &+&type=3"), "QR key encoded");
            routes.put("/api/login/qrcode/client/login", "{\"code\":500}");
            rejects(() -> api.qrStatus("test-key"));
            String qrUrl = "https://music.163.com/login?codekey=test-key";
            var matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(qrUrl, com.google.zxing.BarcodeFormat.QR_CODE, 180, 180);
            int[] pixels = new int[180 * 180];
            for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++) pixels[y * 180 + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            var bitmap = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(180, 180, pixels)));
            check(new com.google.zxing.qrcode.QRCodeReader().decode(bitmap).getText().equals(qrUrl), "generated QR scans to NetEase login URL");

            var directory = Files.createTempDirectory("music-config-test-");
            var path = directory.resolve("server-core-music.json");
            try {
                var config = new MusicConfig(path); config.load();
                check(Files.exists(path) && config.cookie().isEmpty(), "initial server config");
                config.setCookie("MUSIC_U=test-session; os=pc");
                var restored = new MusicConfig(path); restored.load();
                check(restored.cookie().equals(config.cookie()), "shared Cookie persists across restart");
                rejects(() -> config.setCookie("MUSIC_U=test\r\nX-Test: injected"));
                check(config.cookie().equals(restored.cookie()), "invalid update preserves session");
                Files.writeString(path, "not-json");
                rejects(config::load);
                check(config.cookie().equals(restored.cookie()), "invalid reload preserves session");
                config.setCookie(""); restored.load();
                check(restored.cookie().isEmpty(), "clear persisted Cookie");
            } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory); }
            System.out.println("Music API: " + n + " checks passed");
        } finally { server.stop(0); }
    }
}
