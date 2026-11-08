/*
 * COMP3011 Assignment 2 Part 2.
 * Author: <student name and student number>
 */
package comp3011;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded platform-thread work with results delivered in submission order.
 * Calls to this class come from the JavaFX thread; workers only run submitted tasks.
 */
final class OrderedFrameExecutor<T> implements AutoCloseable {
    record Completed<T>(long sequence, long generation, T value) {
    }

    private record Pending<T>(long sequence, long generation, Future<T> future, Runnable onCancel) {
    }

    private final int capacity;
    private final ThreadPoolExecutor executor;
    private final Queue<Pending<T>> pending = new ArrayDeque<>();
    private long generation;
    private long nextSequence;
    private boolean closed;

    OrderedFrameExecutor(int workers, int capacity) {
        if (workers < 1 || capacity < 1) {
            throw new IllegalArgumentException("Worker and capacity counts must be positive");
        }
        this.capacity = capacity;
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name("frame-effect-", 0).factory());
    }

    long generation() {
        return generation;
    }

    int outstanding() {
        return pending.size();
    }

    long submit(Callable<T> task, Runnable onCancel) {
        if (closed || pending.size() >= capacity) {
            throw new RejectedExecutionException("Frame processing queue is full or closed");
        }
        long sequence = nextSequence++;
        Future<T> future = executor.submit(task);
        pending.add(new Pending<>(sequence, generation, future, onCancel));
        return sequence;
    }

    Completed<T> pollCompleted() throws InterruptedException, ExecutionException {
        Pending<T> head = pending.peek();
        if (head == null || !head.future().isDone()) {
            return null;
        }
        pending.remove();
        return new Completed<>(head.sequence(), head.generation(), head.future().get());
    }

    void invalidate() {
        generation++;
        for (Pending<T> item : pending) {
            item.onCancel().run();
            item.future().cancel(true);
        }
        pending.clear();
        executor.purge();
    }

    boolean isShutdown() {
        return executor.isShutdown();
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            invalidate();
            executor.shutdownNow();
        }
    }
}
