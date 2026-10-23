package io.github.anyoung1.servercore.music.client;

import io.github.anyoung1.servercore.music.MusicPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public final class MusicScreen extends Screen {
    private final BlockPos pos;
    private EditBox endpoint, search;
    private String message = "Shift + 右键唱片机打开；32 格内共享播放";
    private String key;
    private BufferedImage qr;
    private long expires, nextPoll;
    private boolean busy, closed;
    private int generation;
    private final List<Button> results = new ArrayList<>();
    private int x, y, panel;
    public MusicScreen(BlockPos pos) { super(Component.literal("网易云共享唱片机")); this.pos = pos.immutable(); }
    private Button button(String text, int bx, int by, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(bx, by, width, 20).build());
    }
    @Override protected void init() {
        panel = Math.min(460, width - 20); x = (width - panel) / 2; y = Math.max(4, (height - 238) / 2);
        endpoint = addRenderableWidget(new EditBox(font, x, y + 26, panel - 64, 20, Component.literal("可信 API 地址")));
        endpoint.setMaxLength(500); endpoint.setValue(MusicClient.API.endpoint());
        button("保存 API", x + panel - 60, y + 26, 60, () -> {
            if (busy) return;
            try {
                MusicClient.API.setEndpoint(endpoint.getValue(), true); cancelLogin(); MusicClient.retry();
                message = "API 已保存；远程地址必须是你信任的服务";
            } catch (IOException e) { message = e.getMessage(); }
        });
        int cell = (panel - 15) / 4;
        button("扫码登录", x, y + 52, cell, this::login);
        button("退出登录", x + cell + 5, y + 52, cell, () -> {
            cancelLogin(); MusicClient.API.logout(); MusicClient.retry(); message = "已清除本次会话的网易云登录";
        });
        button("停止共享", x + (cell + 5) * 2, y + 52, cell, () -> send(MusicPacket.stop(pos)));
        button("重试收听", x + (cell + 5) * 3, y + 52, cell, MusicClient::retry);
        search = addRenderableWidget(new EditBox(font, x, y + 80, panel - 64, 20, Component.literal("搜索歌曲")));
        search.setMaxLength(100); search.setHint(Component.literal("输入歌名或歌手"));
        button("搜索", x + panel - 60, y + 80, 60, () -> {
            if (search.getValue().isBlank()) return;
            String keyword = search.getValue();
            run(() -> MusicClient.API.search(keyword), songs -> {
                for (var b : results) removeWidget(b); results.clear();
                qr = null;
                for (int i = 0; i < songs.size(); i++) {
                    var song = songs.get(i);
                    results.add(button(font.plainSubstrByWidth(song.name(), panel - 12), x, y + 104 + i * 21, panel,
                        () -> run(() -> { MusicClient.API.songUrl(song.id()); return song; }, selected -> {
                            send(new MusicPacket(pos, selected.id(), selected.name(), selected.duration(), 0, 0));
                        })));
                }
                message = songs.isEmpty() ? "没有找到歌曲" : "点击歌曲开始共享；其他玩家按自己的账号权限播放";
            });
        });
    }
    private void send(MusicPacket packet) {
        if (ClientPlayNetworking.canSend(MusicPacket.TYPE)) {
            ClientPlayNetworking.send(packet); message = "请求已发送（须已登录服务器并在唱片机 8 格内）";
        } else message = "服务器不支持共享唱片机";
    }
    private void cancelLogin() { generation++; key = null; qr = null; }
    private void login() {
        if (busy) return;
        cancelLogin();
        for (var b : results) removeWidget(b); results.clear();
        run(() -> {
            var k = MusicClient.API.call("/login/qr/key", Map.of()).getAsJsonObject("data").get("unikey").getAsString();
            var data = MusicClient.API.call("/login/qr/create", Map.of("key", k, "qrimg", "true")).getAsJsonObject("data");
            String image = data.get("qrimg").getAsString();
            if (!image.startsWith("data:image/png;base64,") || image.length() > 500000) throw new IOException("二维码图片无效");
            var decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1))));
            if (decoded == null || decoded.getWidth() > 512 || decoded.getHeight() > 512) throw new IOException("二维码尺寸无效");
            return new LoginCode(k, decoded);
        }, code -> {
            key = code.key(); qr = code.image(); expires = System.nanoTime() + 120_000_000_000L;
            nextPoll = 0; message = "请用网易云音乐 APP 扫码并确认登录（2 分钟内）";
        });
    }
    private record LoginCode(String key, BufferedImage image) {}
    @Override public void tick() {
        if (key == null || busy) return;
        if (System.nanoTime() > expires) { cancelLogin(); message = "二维码已过期，请重新登录"; return; }
        if (System.nanoTime() < nextPoll) return;
        nextPoll = System.nanoTime() + 3_000_000_000L;
        String checking = key;
        run(() -> MusicClient.API.call("/login/qr/check", Map.of("key", checking)), result -> {
            int code = result.get("code").getAsInt();
            if (code == 803) {
                MusicClient.API.login(result.get("cookie").getAsString()); cancelLogin(); MusicClient.retry();
                message = "网易云登录成功（退出游戏后需重新扫码）";
            } else if (code == 800) { cancelLogin(); message = "二维码已过期"; }
            else message = code == 802 ? "已扫码，请在手机确认" : "等待手机扫码";
        });
    }
    @FunctionalInterface private interface Work<T> { T execute() throws Exception; }
    private <T> void run(Work<T> work, Consumer<T> accept) {
        if (busy || closed) return;
        busy = true;
        int request = generation;
        Thread.startVirtualThread(() -> {
            try {
                T result = work.execute();
                minecraft.execute(() -> {
                    busy = false;
                    if (!closed && request == generation) {
                        try { accept.accept(result); } catch (RuntimeException e) { message = "API 返回不完整，请重新登录或更换 API"; }
                    }
                });
            } catch (Exception e) {
                minecraft.execute(() -> {
                    busy = false;
                    if (!closed && request == generation) message = e instanceof IOException ? e.getMessage() : "API 返回异常，请检查兼容性";
                });
            }
        });
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.text(font, title, x, y + 6, 0xFFFFFFFF);
        g.text(font, MusicClient.API.loggedIn() ? "网易云：已登录" : "网易云：未登录", x + panel - 110, y + 6, 0xFF55DDCC);
        if (qr != null) {
            int size = 104, qx = x + (panel - size) / 2, qy = y + 104;
            for (int row = 0; row < qr.getHeight(); row++) {
                int start = 0;
                while (start < qr.getWidth()) {
                    int color = qr.getRGB(start, row) | 0xFF000000, end = start + 1;
                    while (end < qr.getWidth() && (qr.getRGB(end, row) | 0xFF000000) == color) end++;
                    g.fill(qx + start * size / qr.getWidth(), qy + row * size / qr.getHeight(),
                        qx + end * size / qr.getWidth(), qy + (row + 1) * size / qr.getHeight(), color);
                    start = end;
                }
            }
        }
        g.text(font, font.plainSubstrByWidth(busy ? "请求中… " + message : message, panel), x, y + 211, 0xFFCCCCCC);
        g.text(font, font.plainSubstrByWidth(MusicClient.status, panel), x, y + 224, 0xFF55DDCC);
    }
    @Override public void removed() { closed = true; cancelLogin(); }
    @Override public boolean isPauseScreen() { return false; }
}
