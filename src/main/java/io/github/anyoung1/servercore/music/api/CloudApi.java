package io.github.anyoung1.servercore.music.api;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 网易云接口封装：无 Cookie 时使用公开音源，有 Cookie 时按共享账号权限解析音源。 */
public class CloudApi {
    private static final String ORIGIN = "https://music.163.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.6";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1).build();
    private final URI searchEndpoint;
    private final String cookie;

    public CloudApi() { this(""); }
    public CloudApi(String cookie) { this(URI.create(ORIGIN + "/api/search/get/web"), cookie); }
    CloudApi(URI searchEndpoint) { this(searchEndpoint, ""); }
    CloudApi(URI searchEndpoint, String cookie) { this.searchEndpoint = searchEndpoint; this.cookie = cookie; }

    public record Song(long id, String name, int duration) {}
    public static final int PAGE_SIZE = 5;
    public record SearchPage(List<Song> songs, boolean hasNext) {}
    public record Playlist(long id, String name, int trackCount) {}
    public record PlaylistPage(String account, List<Playlist> playlists, boolean hasNext) {}

    public PlaylistPage playlists(int page) throws IOException {
        if (cookie.isBlank()) throw new IOException("服务器尚未配置网易云 Cookie，请联系管理员");
        int offset = pageOffset(page);
        try {
            var account = getJson(searchEndpoint.resolve("/api/nuser/account/get"));
            if (!account.has("profile") || account.get("profile").isJsonNull())
                throw new IOException("网易云登录已失效，请管理员更新 Cookie");
            var profile = account.getAsJsonObject("profile");
            if (profile == null || profile.get("userId").getAsLong() <= 0)
                throw new IOException("网易云登录已失效，请管理员更新 Cookie");
            long uid = profile.get("userId").getAsLong();
            var response = getJson(searchEndpoint.resolve("/api/user/playlist/?uid=" + uid
                + "&offset=" + offset + "&limit=" + PAGE_SIZE));
            List<Playlist> lists = new ArrayList<>();
            var entries = response.getAsJsonArray("playlist");
            // 旧 GET 接口可能忽略 limit/offset，返回全部歌单，此时在本地切片分页。
            boolean fullCollection = entries != null && entries.size() > PAGE_SIZE;
            int start = fullCollection ? offset : 0;
            if (entries != null) for (int i = start; i < entries.size() && lists.size() < PAGE_SIZE; i++) {
                var p = entries.get(i).getAsJsonObject();
                lists.add(new Playlist(p.get("id").getAsLong(), shorten(p.get("name").getAsString()), p.get("trackCount").getAsInt()));
            }
            boolean more = fullCollection ? offset + lists.size() < entries.size()
                : response.has("more") ? response.get("more").getAsBoolean() : lists.size() == PAGE_SIZE;
            return new PlaylistPage(shorten(profile.get("nickname").getAsString()), lists, !lists.isEmpty() && more);
        } catch (RuntimeException e) { throw new IOException("网易云账号或歌单数据格式错误", e); }
    }

    public SearchPage playlistSongs(long id, int page) throws IOException {
        if (id <= 0) throw new IOException("歌单 ID 无效");
        int offset = pageOffset(page);
        try {
            var response = getJson(searchEndpoint.resolve("/api/v3/playlist/detail?id=" + id + "&n=0&s=0"));
            var playlist = response.getAsJsonObject("playlist");
            if (playlist == null) throw new IOException("无法读取歌单，请检查账号权限");
            var ids = playlist.getAsJsonArray("trackIds");
            if (ids == null) throw new IOException("网易云未返回歌单歌曲列表");
            if (offset >= ids.size()) return new SearchPage(List.of(), false);
            int end = Math.min(offset + PAGE_SIZE, ids.size());
            List<Long> requested = new ArrayList<>();
            for (int i = offset; i < end; i++) requested.add(ids.get(i).getAsJsonObject().get("id").getAsLong());
            String encoded = URLEncoder.encode(requested.toString(), StandardCharsets.UTF_8);
            var details = getJson(searchEndpoint.resolve("/api/song/detail/?ids=" + encoded)).getAsJsonArray("songs");
            Map<Long, Song> found = new HashMap<>();
            if (details != null) for (var entry : details) {
                Song song = parseSong(entry.getAsJsonObject()); found.put(song.id(), song);
            }
            List<Song> songs = new ArrayList<>();
            for (long songId : requested) if (found.containsKey(songId)) songs.add(found.get(songId));
            return new SearchPage(songs, end < ids.size());
        } catch (RuntimeException e) { throw new IOException("网易云歌单歌曲格式错误", e); }
    }

    private static int pageOffset(int page) throws IOException {
        if (page < 0 || page > 100000) throw new IOException("页码无效");
        return page * PAGE_SIZE;
    }
    private static String shorten(String text) { return text.substring(0, Math.min(120, text.length())); }

    public List<Song> search(String keyword) throws IOException {
        return search(keyword, 0).songs();
    }

    public SearchPage search(String keyword, int page) throws IOException {
        if (page < 0 || page > Integer.MAX_VALUE / PAGE_SIZE) throw new IOException("搜索页码无效");
        int offset = page * PAGE_SIZE;
        String query = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        var json = getJson(URI.create(searchEndpoint + "?s=" + query + "&type=1&limit=" + PAGE_SIZE + "&offset=" + offset));
        try {
            List<Song> songs = new ArrayList<>();
            var result = json.getAsJsonObject("result");
            if (result == null || !result.has("songs") || result.get("songs").isJsonNull())
                return new SearchPage(List.of(), false);
            for (var entry : result.getAsJsonArray("songs")) {
                songs.add(parseSong(entry.getAsJsonObject()));
                if (songs.size() == PAGE_SIZE) break;
            }
            boolean hasNext = songs.size() == PAGE_SIZE;
            if (result.has("songCount") && !result.get("songCount").isJsonNull())
                hasNext = !songs.isEmpty() && (long) offset + songs.size() < result.get("songCount").getAsLong();
            return new SearchPage(List.copyOf(songs), hasNext);
        } catch (RuntimeException e) { throw new IOException("网易云返回格式错误", e); }
    }

    public Song songFromLink(String link) throws IOException {
        return song(songId(link));
    }

    public List<Long> playlistIds(long id) throws IOException {
        if (id <= 0) throw new IOException("歌单 ID 无效");
        try {
            var response = getJson(searchEndpoint.resolve("/api/v3/playlist/detail?id=" + id + "&n=0&s=0"));
            var entries = response.getAsJsonObject("playlist").getAsJsonArray("trackIds");
            if (entries == null || entries.isEmpty()) throw new IOException("歌单中没有歌曲");
            if (entries.size() > 10000) throw new IOException("歌单超过 10000 首限制");
            List<Long> ids = new ArrayList<>();
            for (var entry : entries) ids.add(entry.getAsJsonObject().get("id").getAsLong());
            return List.copyOf(ids);
        } catch (RuntimeException e) { throw new IOException("网易云歌单数据格式错误", e); }
    }

    public Song song(long id) throws IOException {
        if (id <= 0) throw new IOException("歌曲 ID 无效");
        var json = getJson(searchEndpoint.resolve("/api/song/detail/?id=" + id + "&ids=%5B" + id + "%5D"));
        try {
            var songs = json.getAsJsonArray("songs");
            if (songs != null) for (var entry : songs) {
                Song song = parseSong(entry.getAsJsonObject());
                if (song.id() == id) return song;
            }
            throw new IOException("未找到该歌曲，请检查歌曲链接");
        } catch (RuntimeException e) { throw new IOException("网易云歌曲详情格式错误", e); }
    }

    static long songId(String link) throws IOException {
        try {
            URI uri = URI.create(link.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !"music.163.com".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80))
                throw new IllegalArgumentException();
            if (uri.getRawFragment() != null && uri.getRawFragment().startsWith("/"))
                uri = URI.create(uri.getRawFragment());
            String path = uri.getPath();
            if (!("/song".equals(path) || "/song/".equals(path) || "/m/song".equals(path)
                || "/song/media/outer/url".equals(path))) throw new IllegalArgumentException();
            if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && parts[0].equals("id")) {
                    String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    if ("/song/media/outer/url".equals(path)) value = value.replaceFirst("\\.mp3$", "");
                    if (!value.matches("[0-9]+")) throw new IllegalArgumentException();
                    long id = Long.parseLong(value);
                    if (id > 0) return id;
                }
            }
        } catch (RuntimeException e) { throw new IOException("请粘贴有效的网易云歌曲链接（包含歌曲 id）", e); }
        throw new IOException("请粘贴有效的网易云歌曲链接（包含歌曲 id）");
    }

    private static Song parseSong(JsonObject song) {
        String name = song.get("name").getAsString().trim();
        var artists = song.getAsJsonArray(song.has("artists") ? "artists" : "ar");
        if (artists != null && !artists.isEmpty()) {
            StringJoiner names = new StringJoiner(", ");
            for (int i = 0; i < Math.min(3, artists.size()); i++)
                names.add(artists.get(i).getAsJsonObject().get("name").getAsString().trim());
            if (artists.size() > 3) names.add("...");
            name += " - " + names;
        }
        return new Song(song.get("id").getAsLong(), name.substring(0, Math.min(120, name.length())),
            song.get(song.has("duration") ? "duration" : "dt").getAsInt());
    }

    private JsonObject getJson(URI uri) throws IOException {
        var response = request(uri);
        var json = parseJson(response.body());
        if (json.has("code") && json.get("code").getAsInt() != 200)
            throw new IOException("网易云 API 错误 " + json.get("code").getAsInt());
        return json;
    }

    public record QrStatus(int code, String cookie) {}
    public String qrKey() throws IOException {
        try {
            String key = getJson(searchEndpoint.resolve("/api/login/qrcode/unikey?type=3")).get("unikey").getAsString();
            if (key.isBlank() || key.length() > 256) throw new IOException("二维码登录凭据无效");
            return key;
        } catch (RuntimeException e) { throw new IOException("二维码登录数据格式错误", e); }
    }
    public QrStatus qrStatus(String key) throws IOException {
        var response = request(searchEndpoint.resolve("/api/login/qrcode/client/login?key="
            + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&type=3"));
        try {
            int code = parseJson(response.body()).get("code").getAsInt();
            if (code < 800 || code > 803) throw new IOException("网易云扫码登录失败，请重新执行登录命令");
            if (code != 803) return new QrStatus(code, "");
            Map<String, String> values = new LinkedHashMap<>();
            for (String header : response.headers().allValues("Set-Cookie")) {
                String[] pair = header.split(";", 2)[0].split("=", 2);
                if (pair.length == 2) values.put(pair[0].trim(), pair[1].trim());
            }
            if (!values.containsKey("MUSIC_U") || values.get("MUSIC_U").isBlank())
                throw new IOException("扫码成功但未收到登录 Cookie，请重试");
            values.put("os", "pc"); values.put("appver", "3.1.6");
            StringJoiner joined = new StringJoiner("; ");
            values.forEach((name, value) -> joined.add(name + "=" + value));
            return new QrStatus(code, joined.toString());
        } catch (RuntimeException e) { throw new IOException("扫码登录响应格式错误", e); }
    }

    private static JsonObject parseJson(String body) throws IOException {
        try { return JsonParser.parseString(body).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("网易云返回格式错误", e); }
    }

    private HttpResponse<String> request(URI uri) throws IOException {
        var builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30)).header("Referer", ORIGIN + "/")
            .header("Origin", ORIGIN).header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded").GET();
        if (!cookie.isBlank()) builder.header("Cookie", cookie);
        var request = builder.build();
        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("网易云 HTTP " + response.statusCode());
            if (response.body().length() > 2_000_000) throw new IOException("网易云响应过大");
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("网易云请求已中断", e);
        } catch (JsonParseException | IllegalStateException | NullPointerException | NumberFormatException e) {
            throw new IOException("网易云返回格式错误", e);
        }
    }

    public URI songUrl(long id) throws IOException {
        if (id <= 0) throw new IOException("歌曲 ID 无效");
        if (cookie.isBlank()) return URI.create(ORIGIN + "/song/media/outer/url?id=" + id + ".mp3");
        try {
            var response = getJson(searchEndpoint.resolve("/api/song/enhance/player/url/v1?encodeType=mp3&ids=%5B"
                + id + "%5D&level=standard"));
            var data = response.getAsJsonArray("data");
            if (data == null || data.isEmpty()) throw new IOException("未获取到歌曲音源");
            var song = data.get(0).getAsJsonObject();
            if (song.has("code") && song.get("code").getAsInt() != 200
                || !song.has("url") || song.get("url").isJsonNull())
                throw new IOException("账号无播放权限或 Cookie 已失效，请联系管理员");
            if (song.has("freeTrialInfo") && !song.get("freeTrialInfo").isJsonNull())
                throw new IOException("账号仅能试听此歌曲，无法同步完整播放");
            if (song.has("type") && !"mp3".equalsIgnoreCase(song.get("type").getAsString()))
                throw new IOException("当前播放器仅支持 MP3");
            return mediaUri(song.get("url").getAsString());
        } catch (RuntimeException e) { throw new IOException("网易云播放地址格式错误", e); }
    }

    /** 逐次解析音源重定向，每次连接前校验网易云域名，避免任意地址下载。 */
    public static HttpURLConnection openAudio(URI uri) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            uri = mediaUri(uri.toString());
            if ("music.163.com".equalsIgnoreCase(uri.getHost()) && "/404".equals(uri.getPath()))
                throw new IOException("歌曲没有可用的公开音源，请更换歌曲");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Referer", ORIGIN + "/");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            try {
                int code = connection.getResponseCode();
                if (code == 200) {
                    String type = connection.getContentType();
                    if (type != null && (type.startsWith("text/") || type.contains("json")))
                        throw new IOException("歌曲没有可用的公开音源，请更换歌曲");
                    return connection;
                }
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308)
                    throw new IOException("音源下载失败：HTTP " + code);
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("音源重定向缺少地址");
                try { uri = uri.resolve(location); }
                catch (IllegalArgumentException e) { throw new IOException("音源重定向地址无效", e); }
            } catch (IOException | RuntimeException e) {
                connection.disconnect();
                throw e;
            }
            connection.disconnect();
        }
        throw new IOException("音源重定向次数过多");
    }

    public static URI mediaUri(String value) throws IOException {
        URI uri;
        try { uri = URI.create(value); } catch (RuntimeException e) { throw new IOException("音源地址无效", e); }
        String host = uri.getHost();
        if (host != null) host = host.toLowerCase(Locale.ROOT);
        if (host == null || !(host.equals("music.163.com") || host.endsWith(".music.126.net") || host.endsWith(".music.163.com"))
            || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
            || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
            throw new IOException("音源不是受支持的网易云地址");
        return URI.create(uri.toString().replaceFirst("^http:", "https:"));
    }
}
