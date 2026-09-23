package com.weave.lunarmods.internal;

import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives {@link Registry#tick()} on the client thread twenty times a second without depending on
 * any Weave event API, which differs between Weave versions: a daemon thread queues each tick with
 * Minecraft's own scheduler, which runs it on the client thread. Settings are also saved from a
 * JVM shutdown hook when the game closes.
 */
final class Ticker implements Runnable {
    private static final long INTERVAL_MS = 50L;
    private static final AtomicBoolean started = new AtomicBoolean();

    /** Set while a tick is queued, so a stalled client thread never piles up ticks. */
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            queued.set(false);
            Registry.tick();
        }
    };

    private Ticker() {
    }

    static void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(new Ticker(), "Lunar Mod API ticker");
        thread.setDaemon(true);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                Registry.shutdown();
            }
        }, "Lunar Mod API save"));
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                return;
            }
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null && queued.compareAndSet(false, true)) {
                minecraft.addScheduledTask(tick);
            }
        }
    }
}
