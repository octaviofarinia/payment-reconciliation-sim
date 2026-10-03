package org.octavio.paymentreconciliationsim.acceptance.support;

import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled wait outcomes around real executing tasks, Mongo fixtures, and S3 fixture data. */
class WorkerInvocationLifecycleIT {
    private static final String COLLECTION = "worker_lifecycle_probe";
    private static final byte[] BYTES = {1, 2, 3};

    @Test
    void timeoutCannotForgetActiveInvocationOrPermitScenarioReset() throws Exception {
        try (var environment = new LocalEnvironment()) {
            environment.start();
            retainFixtures(environment);
            try (var invocation = new ControlledInvocation(environment, true)) {
                var first = assertThrows(IllegalStateException.class, environment::awaitWorker);
                assertInstanceOf(TimeoutException.class, first.getCause());
                assertTrue(invocation.active());
                var reset = assertThrows(IllegalStateException.class, environment::resetScenario,
                        "Reset must keep waiting for the invocation that timed out");
                assertInstanceOf(TimeoutException.class, reset.getCause());
                assertFixturesRetained(environment);
                assertTrue(invocation.active());
                assertEquals(TimeUnit.SECONDS.toNanos(330), invocation.waitBudget.get());

                invocation.finish();
                environment.awaitWorker();
                environment.resetScenario();
                assertEquals(0, environment.mongoDatabase().getCollection(COLLECTION).countDocuments());
                assertNull(environment.s3().get(LocalEnvironment.BUCKET, "worker-lifecycle", null));
            }
        }
    }

    @Test
    void interruptedAwaitAndResetRetainInvocationAndPreserveInterruptSemantics() throws Exception {
        try (var environment = new LocalEnvironment()) {
            environment.start();
            retainFixtures(environment);
            try (var invocation = new ControlledInvocation(environment, false)) {
                assertInterruptedWait(environment::awaitWorker, invocation);
                assertTrue(invocation.active());
                assertInterruptedWait(environment::resetScenario, invocation);
                assertFixturesRetained(environment);
                assertTrue(invocation.active());

                invocation.finish();
                environment.awaitWorker();
                environment.resetScenario();
                assertEquals(0, environment.mongoDatabase().getCollection(COLLECTION).countDocuments());
                assertNull(environment.s3().get(LocalEnvironment.BUCKET, "worker-lifecycle", null));
            }
        }
    }

    @Test
    void cancelledFutureDoesNotProveExecutingTaskTerminatedOrPermitResourceCleanup() throws Exception {
        var environment = new LocalEnvironment();
        environment.start();
        retainFixtures(environment);
        try (var invocation = new ControlledInvocation(environment, false)) {
            assertTrue(invocation.cancel(true));
            assertTrue(invocation.isDone(), "Cancellation marks the future done while the task still runs");
            assertTrue(invocation.interruptions.tryAcquire(5, TimeUnit.SECONDS));
            assertTrue(invocation.active());
            assertThrows(IllegalStateException.class, environment::awaitWorker);
            assertThrows(IllegalStateException.class, environment::resetScenario);
            assertFixturesRetained(environment);

            var closeFailure = new AtomicReference<Throwable>();
            var closer = new Thread(() -> {
                try { environment.close(); }
                catch (Throwable failure) { closeFailure.set(failure); }
            }, "worker-close-probe");
            closer.start();
            try {
                assertTrue(invocation.interruptions.tryAcquire(5, TimeUnit.SECONDS),
                        "Close must request executor shutdown");
                assertTrue(closer.isAlive(), "Close must wait for actual executor termination");
                assertTrue(environment.mongoRunning());
                assertTrue(environment.springRunning());
                assertFixturesRetained(environment);
                var reset = assertThrows(IllegalStateException.class, environment::resetScenario);
                assertEquals("The local environment is already closed", reset.getMessage());
            } finally {
                invocation.finish();
                closer.join(10000);
            }
            assertFalse(closer.isAlive());
            assertInstanceOf(IllegalStateException.class, closeFailure.get());
            assertFalse(environment.mongoRunning());
            assertFalse(environment.springRunning());
            assertFalse(environment.s3().isRunning());
            environment.close();
        } finally {
            environment.close();
        }
    }

    private static void assertInterruptedWait(Runnable wait, ControlledInvocation invocation) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var interruptPreserved = new AtomicBoolean();
        var waiter = new Thread(() -> {
            try { wait.run(); }
            catch (Throwable caught) { failure.set(caught); }
            finally { interruptPreserved.set(Thread.currentThread().isInterrupted()); }
        }, "worker-interruption-probe");
        waiter.start();
        try {
            assertTrue(invocation.waitEntered.tryAcquire(5, TimeUnit.SECONDS),
                    "Reset/await must still wait for the active invocation");
        } finally {
            waiter.interrupt();
            waiter.join(10000);
        }
        assertFalse(waiter.isAlive());
        var caught = assertInstanceOf(IllegalStateException.class, failure.get());
        assertInstanceOf(InterruptedException.class, caught.getCause());
        assertTrue(interruptPreserved.get());
    }

    private static void retainFixtures(LocalEnvironment environment) {
        environment.mongoDatabase().getCollection(COLLECTION).insertOne(new Document("_id", "retained"));
        environment.s3().store(LocalEnvironment.BUCKET, "worker-lifecycle", BYTES);
    }

    private static void assertFixturesRetained(LocalEnvironment environment) {
        assertEquals(1, environment.mongoDatabase().getCollection(COLLECTION).countDocuments());
        assertArrayEquals(BYTES, environment.s3().get(LocalEnvironment.BUCKET, "worker-lifecycle", null));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(LocalEnvironment environment, String name) throws Exception {
        Field field = LocalEnvironment.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(environment);
    }

    /** A real executor task with deterministic simulated timeout instead of a 330-second sleep. */
    private static final class ControlledInvocation implements Future<Object>, AutoCloseable {
        private final Future<?> delegate;
        private final boolean forceTimeout;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final Semaphore waitEntered = new Semaphore(0);
        private final Semaphore interruptions = new Semaphore(0);
        private final AtomicLong waitBudget = new AtomicLong();

        ControlledInvocation(LocalEnvironment environment, boolean forceTimeout) throws Exception {
            this.forceTimeout = forceTimeout;
            var started = new CountDownLatch(1);
            ExecutorService executor = field(environment, "workerExecutor");
            delegate = executor.submit(() -> {
                started.countDown();
                boolean interrupted = false;
                try {
                    while (true) {
                        try { release.await(); break; }
                        catch (InterruptedException ignored) {
                            interrupted = true;
                            interruptions.release();
                        }
                    }
                } finally {
                    stopped.countDown();
                    if (interrupted) Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            ConcurrentLinkedQueue<Future<?>> invocations = field(environment, "workerInvocations");
            invocations.add(this);
        }

        boolean active() { return stopped.getCount() != 0; }
        void finish() throws InterruptedException {
            release.countDown();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        }
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            waitBudget.set(unit.toNanos(timeout));
            waitEntered.release();
            if (forceTimeout && active()) throw new TimeoutException("Controlled invocation deadline");
            return delegate.get(timeout, unit);
        }
        public Object get() throws InterruptedException, ExecutionException { return delegate.get(); }
        public boolean cancel(boolean interrupt) { return delegate.cancel(interrupt); }
        public boolean isCancelled() { return delegate.isCancelled(); }
        public boolean isDone() { return delegate.isDone(); }
        public void close() throws InterruptedException { finish(); }
    }
}
