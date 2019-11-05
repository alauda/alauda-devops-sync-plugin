package io.alauda.jenkins.devops.sync.event;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.extended.workqueue.ratelimiter.BucketRateLimiter;
import io.kubernetes.client.models.V1Event;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This executor will submit events in queue and create {@link io.kubernetes.client.models.V1Event}
 * to k8s.
 */
public class EventExecutor implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(EventExecutor.class);

  private static final int DEFAULT_WORKER_COUNT = 4;

  private static volatile EventExecutor instance;

  public static EventExecutor getInstance() {
    if (instance == null) {
      synchronized (EventExecutor.class) {
        if (instance == null) {
          instance = new EventExecutor();
        }
      }
    }
    return instance;
  }

  @Initializer(after = InitMilestone.PLUGINS_STARTED)
  @SuppressWarnings("unused")
  @Restricted(DoNotUse.class)
  public static void initializeExecutor() {
    logger.info("Initializing EventExecutor...");

    EventExecutor eventExecutor = getInstance();
    ExecutorService threadPool = Executors.newSingleThreadExecutor();
    threadPool.submit(eventExecutor);

    logger.info("EventExecutor initialized :P");
  }

  private RateLimitingQueue<Event> queue;
  private ScheduledExecutorService executor;

  private EventExecutor() {
    queue =
        new DefaultRateLimitingQueue<>(
            Executors.newSingleThreadExecutor(),
            new BucketRateLimiter<>(100, 5, Duration.ofSeconds(1)));
    executor =
        Executors.newScheduledThreadPool(DEFAULT_WORKER_COUNT, namedEventWorkerThreadFactory());
  }

  /**
   * Add event to queue. Same events will be fold into one.
   *
   * @param event event will be submitted.
   */
  public void submit(Event event) {
    queue.addRateLimited(event);
  }

  @Override
  public void run() {
    logger.info(
        "Initializing EventExecutor {} workers, worker count {}", this, DEFAULT_WORKER_COUNT);
    for (int i = 0; i < DEFAULT_WORKER_COUNT; i++) {
      int finalIndex = i;
      // We use scheduleWIthFixedDelay to start worker,
      // so that even when task failed, it will resume automatically.
      executor.scheduleWithFixedDelay(
          () -> {
            logger.info("Starting EventWorker {}", finalIndex);
            worker();
            logger.info("Resuming EventWorker {}", finalIndex);
          },
          0,
          1,
          TimeUnit.SECONDS);
    }
  }

  private void worker() {
    while (!queue.isShuttingDown()) {
      Event event = null;
      try {
        event = queue.get();
      } catch (InterruptedException e) {
        logger.error("EventExecutor worker interrupted.", e);
      }

      if (event == null) {
        logger.info("EventExecutor worker exiting because work queue has shutdown..");
        return;
      }

      try {
        createEvent(event.getEvent());
      } finally {
        queue.forget(event);
        queue.done(event);
      }
    }
  }

  public static void createEvent(V1Event event) {
    CoreV1Api api = new CoreV1Api();
    try {
      V1Event e =
          api.createNamespacedEvent(
              event.getInvolvedObject().getNamespace(), event, null, null, null);
      if (e == null) {
        logger.debug(
            "Failed to create event, source {}, reason {}, message {}",
            event.getSource(),
            event.getReason(),
            event.getMessage());
      } else {
        logger.debug(
            "Created event successfully, source {}, reason {}, message {}",
            event.getSource(),
            event.getReason(),
            event.getMessage());
      }
    } catch (ApiException e) {
      // we omit any exception
      logger.debug(
          "Failed to create event, source {}, reason {}, message {}. error {}",
          event.getSource(),
          event.getReason(),
          event.getMessage(),
          e);
    }
  }

  private static ThreadFactory namedEventWorkerThreadFactory() {
    return new ThreadFactoryBuilder().setNameFormat("EventWorker" + "-%d").build();
  }
}
