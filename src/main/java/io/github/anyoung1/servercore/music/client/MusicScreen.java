package io.github.anyoung1.servercore.music.client;

import io.github.anyoung1.servercore.music.MusicPacket;
import io.github.anyoung1.servercore.music.MusicControlPacket;
import io.github.anyoung1.servercore.music.api.CloudApi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 唱片机界面：分页只影响展示；歌单播放将歌单 ID 交给服务端加载完整队列。 */
public final class MusicScreen extends Screen {
    private final BlockPos pos;
    private EditBox search, link;
    private String message = "输入歌名或歌手，点击搜索结果共享播放";
    private boolean busy, closed;
    private Button previous, next, playlistsButton, pauseButton, modeButton;
    private int playbackMode;
    private static final String[] MODES = {"顺序播放", "随机播放", "单曲循环", "列表循环"};
    private static final net.minecraft.resources.Identifier BACKGROUND = net.minecraft.resources.Identifier.fromNamespaceAndPath(
        "server-core", "textures/gui/music_background.png");
    private boolean hasBackground;
    private String keyword = "";
    private int page;
    private boolean hasNext;
    private List<CloudApi.Song> songs = List.of();
    private List<CloudApi.Playlist> playlists = List.of();
    private enum View { SEARCH, PLAYLISTS, TRACKS }
    private View view = View.SEARCH;
    private long playlistId;
    private String playlistName = "", account = "";
    private final List<Button> results = new ArrayList<>();
    private int x, y, panel, panelHeight, rowStep, rowHeight;
    public MusicScreen(BlockPos pos) { super(Component.literal("网易云共享唱片机")); this.pos = pos.immutable(); }
    private Button button(String text, int bx, int by, int width, Runnable action) {
        return addRenderableWidget(new MusicWidgets.TransparentButton(font, text, bx, by, width, 20, action));
    }
    @Override protected void init() {
        results.clear();
        panelHeight = Math.min(288, height - 8);
        rowStep = Math.min(21, Math.max(10, (panelHeight - 180) / 5)); rowHeight = rowStep - 1;
        hasBackground = minecraft.getResourceManager().getResource(BACKGROUND).isPresent();
        panel = Math.min(460, width - 20); x = (width - panel) / 2; y = Math.max(4, (height - panelHeight) / 2);
        int cell = (panel - 10) / 3;
        button("停止共享", x, y + 52, cell, () -> control("stop"));
        button("重试收听", x + cell + 5, y + 52, cell, MusicClient::retry);
        playlistsButton = button("账号歌单", x + (cell + 5) * 2, y + 52, cell, () -> loadPlaylists(0));
        link = addRenderableWidget(new MusicWidgets.TransparentEditBox(font, x, y + 26, panel - 64, 20, Component.literal("歌曲链接")));
        link.setMaxLength(2048); link.setHint(Component.literal("粘贴网易云歌曲链接"));
        button("链接播放", x + panel - 60, y + 26, 60, () -> {
            if (link.getValue().isBlank()) return;
            String value = link.getValue();
            run(() -> MusicClient.API.songFromLink(value), song ->
                play(song, false));
        });
        search = addRenderableWidget(new MusicWidgets.TransparentEditBox(font, x, y + 80, panel - 64, 20, Component.literal("搜索歌曲")));
        search.setMaxLength(100); search.setHint(Component.literal("输入歌名或歌手"));
        search.setValue(keyword);
        button("搜索", x + panel - 60, y + 80, 60, () -> {
            if (search.getValue().isBlank()) return;
            loadPage(search.getValue().trim(), 0);
        });
        int controlWidth = (panel - 15) / 4;
        button("上一首", x, y + 104, controlWidth, () -> control("previous"));
        pauseButton = button("暂停", x + controlWidth + 5, y + 104, controlWidth, () -> control("pause"));
        button("下一首", x + (controlWidth + 5) * 2, y + 104, controlWidth, () -> control("next"));
        modeButton = button(MODES[playbackMode], x + (controlWidth + 5) * 3, y + 104, controlWidth, () -> {
            playbackMode = (playbackMode + 1) % MODES.length;
            control("mode"); modeButton.setMessage(Component.literal(MODES[playbackMode]));
        });
        previous = button("上一页", x, y + panelHeight - 54, 60, () -> turnPage(page - 1));
        next = button("下一页", x + panel - 60, y + panelHeight - 54, 60, () -> turnPage(page + 1));
        showResults();
    }
    private void loadPage(String query, int targetPage) {
        run(() -> MusicClient.API.search(query, targetPage), result -> {
            if (targetPage > 0 && result.songs().isEmpty()) {
                hasNext = false;
                message = "已经是最后一页";
            } else {
                view = View.SEARCH;
                keyword = query; page = targetPage; songs = result.songs(); hasNext = result.hasNext();
                message = songs.isEmpty() ? "没有找到歌曲，请更换关键词" : "点击歌曲开始共享；使用服务器网易云账号";
                showResults();
            }
        });
    }
    private void turnPage(int target) {
        switch (view) {
            case SEARCH -> loadPage(keyword, target);
            case PLAYLISTS -> loadPlaylists(target);
            case TRACKS -> loadTracks(playlistId, playlistName, target);
        }
    }
    private void loadPlaylists(int target) {
        if (!busy && !closed) message = "正在读取服务器账号歌单…";
        run(() -> MusicClient.API.playlists(target), result -> {
            if (target > 0 && result.playlists().isEmpty()) { hasNext = false; message = "已经是最后一页"; return; }
            view = View.PLAYLISTS; page = target; playlists = result.playlists();
            account = result.account(); hasNext = result.hasNext();
            message = "账号：" + account + (playlists.isEmpty() ? " · 暂无歌单" : " · 点击歌单查看歌曲");
            showResults();
        });
    }
    private void loadTracks(long id, String name, int target) {
        run(() -> MusicClient.API.playlistSongs(id, target), result -> {
            view = View.TRACKS; page = target; playlistId = id; playlistName = name;
            songs = result.songs(); hasNext = result.hasNext();
            message = "歌单：" + name + (songs.isEmpty() ? " · 本页无可用歌曲" : " · 点击歌曲播放，账号歌单返回列表");
            showResults();
        });
    }
    private void showResults() {
        for (var b : results) removeWidget(b);
        results.clear();
        if (view == View.PLAYLISTS) {
            for (int i = 0; i < playlists.size(); i++) {
                var playlist = playlists.get(i);
                String label = playlist.name() + "（" + playlist.trackCount() + " 首）";
                results.add(addRenderableWidget(new MusicWidgets.TransparentButton(font, label,
                    x, y + 128 + i * rowStep, panel, rowHeight, () -> loadTracks(playlist.id(), playlist.name(), 0))));
            }
            updatePaging(); return;
        }
        for (int i = 0; i < songs.size(); i++) {
            var song = songs.get(i);
            results.add(addRenderableWidget(new MusicWidgets.TransparentButton(font, song.name(),
                x, y + 128 + i * rowStep, panel, rowHeight, () -> play(song, true))));
        }
        updatePaging();
    }
    private void updatePaging() {
        if (previous != null) previous.active = !busy && page > 0;
        if (next != null) next.active = !busy && hasNext;
        if (playlistsButton != null) {
            playlistsButton.active = !busy;
            playlistsButton.setMessage(Component.literal(busy ? "加载中…" : "账号歌单"));
        }
    }
    private void play(CloudApi.Song song, boolean fromResults) {
        List<Long> queue = fromResults ? songs.stream().map(CloudApi.Song::id).toList() : List.of(song.id());
        send(new MusicControlPacket(pos, "play", song.id(), fromResults && view == View.TRACKS ? playlistId : 0, queue, playbackMode));
    }
    private void control(String action) {
        send(new MusicControlPacket(pos, action, 0, 0, List.of(), playbackMode));
    }
    private void send(MusicControlPacket packet) {
        if (ClientPlayNetworking.canSend(MusicControlPacket.TYPE)) {
            ClientPlayNetworking.send(packet); message = "请求已发送（须已登录服务器并在唱片机 8 格内）";
        } else message = "服务器不支持共享唱片机";
    }
    @Override public void tick() {
        MusicPacket state = MusicClient.state(pos);
        if (state != null) {
            playbackMode = Math.max(0, Math.min(MODES.length - 1, state.mode()));
            modeButton.setMessage(Component.literal(MODES[playbackMode]));
        }
        pauseButton.active = state != null;
        pauseButton.setMessage(Component.literal(state != null && state.paused() ? "继续" : "暂停"));
    }
    @FunctionalInterface private interface Work<T> { T execute() throws Exception; }
    private <T> void run(Work<T> work, Consumer<T> accept) {
        if (busy || closed) return;
        busy = true;
        updatePaging();
        Thread.startVirtualThread(() -> {
            try {
                T result = work.execute();
                minecraft.execute(() -> {
                    busy = false;
                    try { if (!closed) accept.accept(result); }
                    catch (RuntimeException e) { message = "服务器返回的数据不完整，请重试"; }
                    finally { updatePaging(); }
                });
            } catch (Exception e) {
                minecraft.execute(() -> {
                    busy = false;
                    if (!closed) message = e instanceof IOException ? e.getMessage() : "网易云请求失败，请稍后重试";
                    updatePaging();
                });
            }
        });
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // 通过裁剪纹理坐标适配面板比例，避免缩小窗口时拉伸背景人物。
        if (hasBackground) {
            float aspect = (float) panel / panelHeight, imageAspect = 460f / 288f;
            float uSpan = Math.min(1f, aspect / imageAspect), vSpan = Math.min(1f, imageAspect / aspect);
            g.blit(BACKGROUND, x, y, x + panel, y + panelHeight,
                (1 - uSpan) / 2, (1 + uSpan) / 2, (1 - vSpan) / 2, (1 + vSpan) / 2);
            g.fill(x, y, x + panel, y + panelHeight, 0x55000000);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.text(font, title, x, y + 6, 0xFFFFFFFF);
        String pageText = "第 " + (page + 1) + " 页";
        g.text(font, pageText, x + (panel - font.width(pageText)) / 2, y + panelHeight - 48, 0xFFCCCCCC);
        g.text(font, font.plainSubstrByWidth(busy ? "请求中… " + message : message, panel), x, y + panelHeight - 27, 0xFFCCCCCC);
        g.text(font, font.plainSubstrByWidth(MusicClient.status, panel), x, y + panelHeight - 14, 0xFF55DDCC);
    }
    @Override public void removed() { closed = true; }
    @Override public boolean isPauseScreen() { return false; }
}
