package io.github.anyoung1.servercore.music;

import java.util.List;
import java.util.Random;

public final class PlaybackTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    public static void run() {
        var ids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        var queue = new MusicQueue(ids, 5, MusicQueue.ORDER);
        check(queue.next(false) && queue.current() == 6, "queue crosses UI page boundary");
        check(queue.next(false) && queue.current() == 7, "playlist order");
        check(!queue.next(false) && queue.current() == 7, "order stops at end");
        check(queue.next(true) && queue.current() == 1, "manual next wraps");
        queue.previous(); check(queue.current() == 7, "manual previous wraps");
        queue.mode(MusicQueue.REPEAT_ONE);
        check(queue.next(false) && queue.current() == 7, "repeat one restarts same song");
        check(queue.next(true) && queue.current() == 1, "manual next escapes repeat one");
        queue.mode(MusicQueue.REPEAT_ALL); queue.previous();
        check(queue.next(false) && queue.current() == 1, "repeat list wraps automatically");
        var random = new MusicQueue(ids, 1, MusicQueue.RANDOM, new Random(42));
        check(random.next(false) && random.current() != 1, "random avoids immediate repeat");
        long second = random.current();
        random.next(false); random.previous(); check(random.current() == second, "random previous follows history");
        random.previous(); check(random.current() == 1, "random history returns to original");
        var single = new MusicQueue(List.of(1L), 1, MusicQueue.REPEAT_ONE);
        check(single.next(false) && single.current() == 1, "single song repeats");
        single.mode(MusicQueue.ORDER); check(!single.next(false), "single order stops");
        single.mode(MusicQueue.RANDOM); check(single.next(false) && single.current() == 1, "single random remains playable");
        boolean rejected = false;
        try { new MusicQueue(ids, 100, 0); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "selected song must belong to queue");
        var clock = new PlaybackClock(1_000_000_000L);
        check(clock.elapsed(3_000_000_000L) == 2000, "elapsed playback");
        clock.toggle(3_000_000_000L);
        check(clock.paused() && clock.elapsed(60_000_000_000L) == 2000, "paused time does not advance");
        clock.toggle(60_000_000_000L);
        check(!clock.paused() && clock.elapsed(61_000_000_000L) == 3000, "resume preserves position");
        clock.toggle(61_000_000_000L); clock.toggle(90_000_000_000L);
        check(clock.elapsed(92_000_000_000L) == 5000, "repeated pause resume");
        System.out.println("Music playback: " + checks + " checks passed");
    }
}
