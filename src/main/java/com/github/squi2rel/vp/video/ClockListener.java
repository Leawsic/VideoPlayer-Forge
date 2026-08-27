package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.VideoInfo;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Server-side, VLC-free playback clock. Instead of decoding the media, it tracks
 * elapsed wall-clock time and, when the media has a known duration, schedules the
 * stopped callback so the playlist advances automatically. Live streams and
 * unknown-duration sources (duration &lt;= 0) never auto-finish, matching the old
 * StreamListener behaviour for live content.
 */
public class ClockListener implements IVideoListener {
    private final VideoInfo info;
    private Runnable stopped = () -> {};

    private long baseProgress;
    private long baseTime;
    private boolean paused;
    private boolean cancelled;
    private ScheduledFuture<?> finishTask;

    public ClockListener(VideoInfo info) {
        this.info = info;
    }

    @Override
    public synchronized long getProgress() {
        if (cancelled) return -1;
        if (paused) return baseProgress;
        return baseProgress + (System.currentTimeMillis() - baseTime);
    }

    @Override
    public synchronized boolean isPlaying() {
        return !cancelled;
    }

    @Override
    public void playing(Consumer<Boolean> playing) {
        playing.accept(info.seekable());
    }

    @Override
    public synchronized void stopped(Runnable stopped) {
        this.stopped = stopped;
    }

    @Override
    public void errored(Runnable errored) {
    }

    @Override
    public void timeout(Runnable timeout) {
    }

    @Override
    public synchronized void listen() {
        baseProgress = 0;
        baseTime = System.currentTimeMillis();
        paused = false;
        cancelled = false;
        scheduleFinish();
    }

    @Override
    public synchronized void cancel() {
        cancelled = true;
        if (finishTask != null) {
            finishTask.cancel(false);
            finishTask = null;
        }
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public synchronized void pause(boolean paused) {
        if (cancelled || this.paused == paused) return;
        if (paused) {
            baseProgress += System.currentTimeMillis() - baseTime;
            this.paused = true;
            if (finishTask != null) {
                finishTask.cancel(false);
                finishTask = null;
            }
        } else {
            baseTime = System.currentTimeMillis();
            this.paused = false;
            scheduleFinish();
        }
    }

    @Override
    public synchronized boolean isPaused() {
        return paused;
    }

    @Override
    public boolean canSetProgress() {
        return info.seekable();
    }

    @Override
    public synchronized void setProgress(long progress) {
        if (cancelled || progress < 0) return;
        baseProgress = progress;
        baseTime = System.currentTimeMillis();
        if (!paused) scheduleFinish();
    }

    private void scheduleFinish() {
        if (finishTask != null) {
            finishTask.cancel(false);
            finishTask = null;
        }
        long duration = info.duration();
        if (duration <= 0) return;
        long remaining = Math.max(0, duration - baseProgress);
        finishTask = VideoPlayerMain.scheduler.schedule(() -> {
            Runnable callback;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                finishTask = null;
                callback = stopped;
            }
            callback.run();
        }, remaining, TimeUnit.MILLISECONDS);
    }
}
