package io.github.anyoung1.servercore.music.client;

import io.github.anyoung1.servercore.music.MusicPacket;
import io.github.anyoung1.servercore.music.api.CloudApi;
import javazoom.jl.decoder.*;
import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.function.Consumer;

/** 音源下载和 MP3 解码均在后台执行，渲染线程仅更新播放状态和音量。 */
public final class MusicAudio {
    private volatile Session current;
    private volatile float volume;
    private static final class Session {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile SourceDataLine line;
        Thread thread;
    }
    public void volume(float value) { volume = Math.max(0, Math.min(1, value)); }
    public void stop() {
        Session s = current; current = null;
        if (s == null) return;
        s.cancelled = true;
        if (s.thread != null) s.thread.interrupt();
        // 关闭网络流也可能阻塞，因此音频清理同样放到后台线程。
        Thread.startVirtualThread(() -> {
            if (s.line != null) { s.line.stop(); s.line.flush(); s.line.close(); }
            if (s.connection != null) s.connection.disconnect();
        });
    }
    public void play(CloudApi api, MusicPacket packet, Consumer<String> status) {
        stop();
        Session s = new Session(); current = s;
        long received = System.nanoTime();
        s.thread = Thread.ofVirtual().start(() -> {
            try {
                URI url = api.songUrl(packet.song());
                if (s.cancelled) return;
                HttpURLConnection connection = CloudApi.openAudio(url);
                s.connection = connection;
                byte[] data;
                try {
                    if (connection.getResponseCode() != 200) throw new IOException("音源下载失败");
                    try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[16384];
                        int count;
                        while (!s.cancelled && (count = input.read(buffer)) != -1) {
                            if (output.size() + count > 40_000_000) throw new IOException("音源超过 40 MB 限制");
                            output.write(buffer, 0, count);
                        }
                        data = output.toByteArray();
                    }
                } finally { connection.disconnect(); s.connection = null; }
                if (s.cancelled) return;
                Bitstream bits = new Bitstream(new ByteArrayInputStream(data));
                Decoder decoder = new Decoder();
                double decodedMs = 0;
                boolean caughtUp = false;
                try {
                    Header header;
                    while (!s.cancelled && (header = bits.readFrame()) != null) {
                        SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bits);
                        bits.closeFrame();
                        int count = samples.getBufferLength();
                        double frameMs = 1000.0 * count / samples.getChannelCount() / samples.getSampleFrequency();
                        decodedMs += frameMs;
                        // 解码到服务器当前进度，补偿下载耗时，供新加入和暂停恢复时对齐。
                        if (!caughtUp && decodedMs < packet.elapsedMs() + (System.nanoTime() - received) / 1_000_000.0) continue;
                        if (!caughtUp) {
                            AudioFormat format = new AudioFormat(samples.getSampleFrequency(), 16, samples.getChannelCount(), true, false);
                            SourceDataLine line = AudioSystem.getSourceDataLine(format);
                            s.line = line;
                            line.open(format, 8192);
                            if (s.cancelled) break;
                            line.start(); caughtUp = true;
                            if (current == s) status.accept("正在播放：" + packet.title());
                        }
                        byte[] pcm = new byte[count * 2];
                        float gain = volume;
                        for (int i = 0; i < count; i++) {
                            short sample = (short) (samples.getBuffer()[i] * gain);
                            pcm[i * 2] = (byte) sample; pcm[i * 2 + 1] = (byte) (sample >> 8);
                        }
                        if (!s.cancelled) s.line.write(pcm, 0, pcm.length);
                    }
                    if (!s.cancelled && s.line != null) s.line.drain();
                } finally { bits.close(); }
            } catch (Exception e) {
                if (!s.cancelled && current == s) status.accept(e instanceof IOException ? e.getMessage() : "播放失败：解码器或音频设备不可用");
            } finally {
                if (s.line != null) s.line.close();
            }
        });
    }
}
