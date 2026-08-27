package com.github.squi2rel.vp.video;

import java.util.function.Consumer;

public interface IVideoListener {
    long getProgress();

    boolean isPlaying();

    void playing(Consumer<Boolean> playing);

    void stopped(Runnable stopped);

    void errored(Runnable errored);

    void timeout(Runnable timeout);

    void listen();

    void cancel();

    default boolean canPause() {
        return false;
    }

    default void pause(boolean paused) {
    }

    default boolean isPaused() {
        return false;
    }

    default boolean canSetProgress() {
        return false;
    }

    default void setProgress(long progress) {
    }
}
