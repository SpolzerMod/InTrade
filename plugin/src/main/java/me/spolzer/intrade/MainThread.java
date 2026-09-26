package me.spolzer.intrade;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs database callbacks on the main server thread.
 *
 * <p>Unlike the Bukkit scheduler, it still accepts tasks while the plugin is disabling, so the results of the
 * last database operations, such as mail that was already taken from the database, are not lost.
 */
public final class MainThread implements Executor {
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Logger logger;

    MainThread(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void execute(Runnable task) {
        queue.add(task);
    }

    /** Runs all queued tasks. Main thread only. */
    void drain() {
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Error in a scheduled InTrade task", e);
            }
        }
    }
}
