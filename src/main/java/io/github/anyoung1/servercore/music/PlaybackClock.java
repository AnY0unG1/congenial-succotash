package io.github.anyoung1.servercore.music;

/** 使用服务端单调时钟累计播放进度，暂停期间不计时，避免系统时间变化导致跳播。 */
public final class PlaybackClock {
    private long started, offset;
    private boolean paused;
    public PlaybackClock(long now) { started = now; }
    public long elapsed(long now) { return offset + (paused ? 0 : Math.max(0, now - started) / 1_000_000); }
    public boolean paused() { return paused; }
    public void toggle(long now) {
        if (paused) started = now;
        else offset = elapsed(now);
        paused = !paused;
    }
}
