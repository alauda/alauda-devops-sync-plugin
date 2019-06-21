package io.alauda.jenkins.devops.sync.controller.util;

import java.util.concurrent.*;
import java.util.function.Predicate;

public final class Wait {

    private Wait() {
    }

    public static <T> void waitUntil(T t, Predicate<T> predicate, long period, long timeout, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<Void> completedFuture = new CompletableFuture<>();

        ScheduledFuture scheduledFuture = scheduledExecutor.scheduleAtFixedRate(() -> {
            if (predicate.test(t)) {
                completedFuture.complete(null);
            }
        }, period, period, timeUnit);


        // When all controllers synced, we cancel the schedule task
        completedFuture.whenComplete((v, throwable) -> scheduledFuture.cancel(true));

        completedFuture.get(timeout, timeUnit);
    }

}
