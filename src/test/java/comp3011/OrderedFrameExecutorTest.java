package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OrderedFrameExecutorTest {
    @Test
    void deliversSubmissionOrderWhenSecondTaskFinishesFirst() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        try (var executor = new OrderedFrameExecutor<Integer>(2, 2)) {
            executor.submit(() -> {
                releaseFirst.await();
                return 1;
            }, () -> {});
            executor.submit(() -> {
                secondFinished.countDown();
                return 2;
            }, () -> {});

            assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
            assertNull(executor.pollCompleted());
            releaseFirst.countDown();

            var first = awaitCompleted(executor);
            var second = awaitCompleted(executor);
            assertEquals(0, first.sequence());
            assertEquals(1, first.value());
            assertEquals(1, second.sequence());
            assertEquals(2, second.value());
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void rejectsWorkAtTheInFlightLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = new OrderedFrameExecutor<Integer>(1, 2)) {
            executor.submit(() -> {
                release.await();
                return 1;
            }, () -> {});
            executor.submit(() -> 2, () -> {});
            assertEquals(2, executor.outstanding());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.submit(() -> 3, () -> {}));
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidationCancelsPendingWorkAndAdvancesGeneration() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        try (var executor = new OrderedFrameExecutor<Integer>(1, 2)) {
            long oldGeneration = executor.generation();
            executor.submit(() -> {
                started.countDown();
                release.await();
                return 1;
            }, cancellations::incrementAndGet);
            executor.submit(() -> 2, cancellations::incrementAndGet);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            executor.invalidate();
            assertEquals(oldGeneration + 1, executor.generation());
            assertEquals(2, cancellations.get());
            assertEquals(0, executor.outstanding());
            assertNull(executor.pollCompleted());

            executor.submit(() -> 3, () -> {});
            var fresh = awaitCompleted(executor);
            assertEquals(executor.generation(), fresh.generation());
            assertEquals(3, fresh.value());
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownTerminatesWorkersAndRejectsNewWork() throws Exception {
        var executor = new OrderedFrameExecutor<Integer>(2, 2);
        executor.submit(() -> 1, () -> {});
        executor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class,
                () -> executor.submit(() -> 2, () -> {}));
    }

    @Test
    void reportsWorkerFailureToTheCaller() throws Exception {
        try (var executor = new OrderedFrameExecutor<Integer>(1, 1)) {
            executor.submit(() -> {
                throw new IllegalStateException("processor failed");
            }, () -> {});
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> awaitCompleted(executor));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("processor failed", failure.getCause().getMessage());
            assertFalse(executor.isShutdown());
        }
    }

    private static <T> OrderedFrameExecutor.Completed<T> awaitCompleted(OrderedFrameExecutor<T> executor)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var completed = executor.pollCompleted();
            if (completed != null) {
                return completed;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Timed out waiting for a completed frame");
    }
}
