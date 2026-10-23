package io.github.anyoung1.servercore.music.client;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** A personal, trusted NeteaseCloudMusicApi-compatible gateway. Cookie stays in RAM. */
public final class CloudApi {
    private final Path config;
    private volatile String endpoint = "http://127.0.0.1:3000";
    private volatile String cookie = "";
    public CloudApi() {
        this(FabricLoader.getInstance().getConfigDir().resolve("server-core-music.json"));
    }
    CloudApi(Path config) {
        this.config = config;
        try {
            if (Files.exists(config)) setEndpoint(JsonParser.parseString(Files.readString(config))
                .getAsJsonObject().get("apiBaseUrl").getAsString(), false);
        } catch (Exception ignored) { /* Invalid local config falls back to loopback. */ }
    }
    public String endpoint() { return endpoint; }
    public boolean loggedIn() { return !cookie.isBlank(); }
    public void login(String value) {
        if (value == null || value.isBlank() || value.length() > 32768) throw new IllegalArgumentException("登录未返回有效 Cookie");
        cookie = value;
    }
    public void logout() { cookie = ""; }
    public void setEndpoint(String value, boolean save) throws IOException {
        URI uri;
        try { uri = URI.create(value.trim()); } catch (RuntimeException e) { throw new IOException("API 地址格式错误"); }
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback)))
            throw new IOException("远程 API 必须使用 HTTPS；本机可用 HTTP");
        String next = uri.toString().replaceAll("/+$", "");
        if (save) {
            JsonObject json = new JsonObject(); json.addProperty("apiBaseUrl", next);
            Files.createDirectories(config.getParent()); Files.writeString(config, json.toString());
        }
        if (!next.equals(endpoint)) logout();
        endpoint = next;
    }
    public JsonObject call(String route, Map<String, String> values) throws IOException {
        var params = new LinkedHashMap<>(values);
        params.put("timestamp", Long.toString(System.currentTimeMillis()));
        if (!cookie.isEmpty()) params.put("cookie", cookie);
        StringJoiner body = new StringJoiner("&");
        params.forEach((k,v) -> body.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpURLConnection con = (HttpURLConnection) URI.create(endpoint + route).toURL().openConnection();
        con.setConnectTimeout(10000); con.setReadTimeout(15000); con.setInstanceFollowRedirects(false);
        con.setRequestMethod("POST"); con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try {
            try (var out = con.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            if (con.getResponseCode() != 200) throw new IOException("API HTTP " + con.getResponseCode());
            byte[] bytes;
            try (var in = con.getInputStream()) { bytes = in.readNBytes(2_000_001); }
            if (bytes.length > 2_000_000) throw new IOException("API 响应过大");
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("code")) {
                int code = json.get("code").getAsInt();
                if (code != 200 && (code < 800 || code > 803)) throw new IOException("API 错误 " + code + "，请检查登录状态或稍后重试");
            }
            return json;
        } catch (JsonParseException | IllegalStateException e) { throw new IOException("API 返回格式错误"); }
        finally { con.disconnect(); }
    }
    public record Song(long id, String name, int duration) {}
    public List<Song> search(String keyword) throws IOException {
        var response = call("/cloudsearch", Map.of("keywords", keyword, "type", "1", "limit", "5"));
        List<Song> songs = new ArrayList<>();
        var result = response.getAsJsonObject("result");
        if (result == null || !result.has("songs")) return songs;
        for (var entry : result.getAsJsonArray("songs")) {
            var s = entry.getAsJsonObject();
            String name = s.get("name").getAsString();
            if (s.has("ar") && !s.getAsJsonArray("ar").isEmpty())
                name += " - " + s.getAsJsonArray("ar").get(0).getAsJsonObject().get("name").getAsString();
            songs.add(new Song(s.get("id").getAsLong(), name.substring(0, Math.min(120, name.length())), s.get("dt").getAsInt()));
        }
        return songs;
    }
    public URI songUrl(long id) throws IOException {
        var result = call("/song/url", Map.of("id", Long.toString(id), "br", "128000"));
        var data = result.getAsJsonArray("data");
        if (data == null || data.isEmpty()) throw new IOException("没有可播放的音源");
        var song = data.get(0).getAsJsonObject();
        if (!song.has("url") || song.get("url").isJsonNull()) throw new IOException("歌曲无播放权限，请登录或更换歌曲");
        if (song.has("type") && !"mp3".equalsIgnoreCase(song.get("type").getAsString()))
            throw new IOException("当前仅支持 MP3 音源");
        if (song.has("freeTrialInfo") && !song.get("freeTrialInfo").isJsonNull())
            throw new IOException("此账号仅可试听，不能同步完整歌曲");
        return mediaUri(song.get("url").getAsString());
    }
    static URI mediaUri(String value) throws IOException {
        URI uri;
        try { uri = URI.create(value); } catch (RuntimeException e) { throw new IOException("音源地址无效"); }
        String host = uri.getHost();
        if (host == null || !(host.endsWith(".music.126.net") || host.endsWith(".music.163.com"))
            || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
            || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
            throw new IOException("音源不是受支持的网易云 CDN");
        return URI.create(uri.toString().replaceFirst("^http:", "https:"));
    }
}
