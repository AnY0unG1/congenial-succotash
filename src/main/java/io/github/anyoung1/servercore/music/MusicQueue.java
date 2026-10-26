package io.github.anyoung1.servercore.music;

import java.util.*;

/** 服务端播放队列：完整歌单与界面分页独立，随机模式另外保存返回历史。 */
public final class MusicQueue {
    public static final int ORDER = 0, RANDOM = 1, REPEAT_ONE = 2, REPEAT_ALL = 3;
    private final List<Long> songs;
    private final Deque<Integer> history = new ArrayDeque<>();
    private final Random random;
    private int index, mode;

    public MusicQueue(List<Long> songs, long selected, int mode) { this(songs, selected, mode, new Random()); }
    MusicQueue(List<Long> songs, long selected, int mode, Random random) {
        if (songs.isEmpty() || songs.size() > 10000 || songs.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("播放列表无效");
        this.songs = List.copyOf(songs); index = songs.indexOf(selected);
        if (index < 0) throw new IllegalArgumentException("歌曲不在播放列表中");
        this.random = random; mode(mode);
    }
    public long current() { return songs.get(index); }
    public int size() { return songs.size(); }
    public int mode() { return mode; }
    public void mode(int mode) {
        if (mode < ORDER || mode > REPEAT_ALL) throw new IllegalArgumentException("播放模式无效");
        this.mode = mode;
    }
    // 自动结束遵守循环模式；手动切歌可离开单曲循环，并在列表边界回绕。
    public boolean next(boolean manual) {
        if (!manual && mode == REPEAT_ONE) return true;
        int next;
        if (mode == RANDOM && songs.size() > 1) {
            next = random.nextInt(songs.size() - 1);
            if (next >= index) next++;
        } else {
            next = index + 1;
            if (next >= songs.size()) {
                if (!manual && mode == ORDER) return false;
                next = 0;
            }
        }
        history.push(index);
        if (history.size() > 1000) history.removeLast();
        index = next; return true;
    }
    public void previous() {
        index = mode == RANDOM && !history.isEmpty() ? history.pop() : Math.floorMod(index - 1, songs.size());
    }
}
