package io.github.anyoung1.servercore.music;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** 服务器专用凭据：仅写入配置文件，不进入音乐数据包或命令回显。 */
public final class MusicConfig {
    private final Path path;
    private String cookie = "";

    public MusicConfig(Path path) { this.path = path; }
    public synchronized String cookie() { return cookie; }
    public synchronized void load() throws IOException {
        if (!Files.exists(path)) { setCookie(""); return; }
        try {
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String next = json.has("cookie") ? json.get("cookie").getAsString() : "";
            validate(next); cookie = next.trim();
        } catch (RuntimeException e) { throw new IOException("音乐配置文件格式错误"); }
    }
    public synchronized void setCookie(String value) throws IOException {
        validate(value);
        String next = value.trim();
        var json = new JsonObject(); json.addProperty("cookie", next);
        Files.createDirectories(path.toAbsolutePath().getParent());
        // 先写临时文件再替换，避免写入失败破坏原配置；落盘成功后才更新内存。
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "music-config-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            cookie = next;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void validate(String value) throws IOException {
        if (value == null || value.length() > 16384 || value.chars().anyMatch(c -> c < 32 || c > 126)
            || (!value.isBlank() && !value.contains("=")))
            throw new IOException("Cookie 格式无效，请使用 name=value; name=value 格式");
    }
}
