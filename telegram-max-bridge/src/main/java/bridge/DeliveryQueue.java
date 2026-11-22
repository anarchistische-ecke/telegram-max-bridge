package bridge;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class DeliveryQueue implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(DeliveryQueue.class.getName());
    private final Store store;
    private final int concurrency;
    private final Consumer<Store.Job> deliver;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<Boolean> wakeups = new ArrayBlockingQueue<>(1);
    private final Thread dispatcher;
    private volatile boolean stopping;

    // A missing forward receipt or a busy media slot is backpressure, not a failed API attempt.
    static final class Defer extends RuntimeException {
        Defer() {
            super(null, null, false, false);
        }
    }

    DeliveryQueue(Store store, int concurrency, Consumer<Store.Job> deliver) {
        this.store = store;
        this.concurrency = concurrency;
        this.deliver = deliver;
        dispatcher = Thread.ofPlatform().name("bridge-dispatcher").unstarted(this::dispatch);
    }

    void start() {
        dispatcher.start();
    }

    boolean isAlive() {
        return dispatcher.isAlive() && !stopping;
    }

    void wake() {
        wakeups.offer(Boolean.TRUE);
    }

    private void dispatch() {
        long pruneAt = 0;
        while (!stopping) {
            try {
                if (System.currentTimeMillis() >= pruneAt) {
                    store.prune();
                    pruneAt = System.currentTimeMillis() + 3_600_000;
                }
                List<String> active = List.copyOf(running);
                int capacity = concurrency - active.size();
                if (capacity > 0) {
                    for (var job : store.ready(active, capacity)) {
                        if (stopping) {
                            break;
                        }
                        running.add(job.lane());
                        workers.execute(() -> process(job));
                    }
                }
                wakeups.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            } catch (Store.Failure error) {
                LOG.warning("Delivery queue could not access SQLite. Retrying.");
                if (!backOff()) {
                    break;
                }
            }
        }
    }

    private void process(Store.Job job) {
        try {
            try {
                deliver.accept(job);
            } catch (Defer waiting) {
                store.defer(job.seq(), 0.25);
                return;
            } catch (Transport.ApiFailure error) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (error.retryable() && job.attempts() < 9) {
                    int delay = Math.max(error.retryAfter, Math.min(300, 1 << (job.attempts() + 1)));
                    store.retry(job.seq(), delay);
                    LOG.warning(() -> "Update " + job.seq() + " will retry in " + delay + "s (" + error.getMessage() + ")");
                    return;
                }
                LOG.warning(() -> "Update " + job.seq() + " failed (" + error.getMessage() + ")");
            } catch (Store.Failure error) {
                throw error;
            } catch (RuntimeException error) {
                LOG.severe(() -> "Update " + job.seq() + " could not be processed (" + error.getClass().getSimpleName() + ")");
            }
            if (!Thread.currentThread().isInterrupted()) {
                store.finish(job.seq());
            }
        } catch (Store.Failure error) {
            LOG.warning(() -> "Could not save delivery state for update " + job.seq() + ". Retrying.");
            backOff();
        } finally {
            running.remove(job.lane());
            wake();
        }
    }

    private static boolean backOff() {
        try {
            Thread.sleep(1000);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        stopping = true;
        wake();
        try {
            dispatcher.join();
            workers.shutdown();
            if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                workers.awaitTermination(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
