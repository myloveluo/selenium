package org.openqa.selenium.remote.server.scheduler;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.openqa.selenium.remote.server.scheduler.RetryDelays.immediately;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.NewSessionPayload;
import org.openqa.selenium.remote.server.ActiveSession;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class Scheduler {

  private final static Logger LOG = Logger.getLogger(Scheduler.class.getName());

  // Used for when a session is Delayed.
  private final ScheduledExecutorService delayedSessionExecutor;
  // Used for looking at the requests queue
  private final ExecutorService queueProcessor;
  private final BlockingDeque<SessionRequest> requests;
  private final Distributor distributor;
  private final RetryDelay retryDelay;
  private final ReentrantLock lock;

  private Scheduler(
      Distributor distributor,
      RetryDelay retryDelay) {

    this.lock = new ReentrantLock();

    this.distributor = distributor;
    this.retryDelay = retryDelay;

    this.requests = new LinkedBlockingDeque<>();
    this.queueProcessor = Executors.newCachedThreadPool(
        r -> new Thread(r, "Selenium Session Scheduler"));
    this.delayedSessionExecutor = new ScheduledThreadPoolExecutor(1);

    int coreCount = Runtime.getRuntime().availableProcessors();
    for (int i = 0; i < coreCount; i++) {
      queueProcessor.execute(() -> endlesslyConsumeQueue(requests));
    }
    LOG.info(String.format("Started new scheduler with %d threads", coreCount));
  }

  public void stop() {
    delayedSessionExecutor.shutdownNow();
    queueProcessor.shutdownNow();

    requests.forEach(request -> request.setResult(Optional.empty()));
  }

  public ActiveSession createSession(NewSessionPayload payload) {
    SessionRequest request = new SessionRequest(payload, retryDelay.iterator());

    try {
      LOG.info(String.format("Adding %s to queue", request));
      requests.putLast(request);

      Optional<ActiveSession> session = request.getResult();

      return session.orElseThrow(
          () -> new SessionNotCreatedException("Unable to create session for " + payload));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SessionNotCreatedException("Attempt to add session to creation queue failed", e);
    }
  }

  private void endlesslyConsumeQueue(BlockingDeque<SessionRequest> requests) {
    while (true) {
      try {
        consumeRequest(requests);
      } catch (InterruptedException e) {
        LOG.log(Level.SEVERE, "Queue has been interrupted. Quitting.", e);
        Thread.currentThread().interrupt();
        throw new Error("Killing queue because of interruption", e);
      }
    }
  }

  private void consumeRequest(BlockingDeque<SessionRequest> requests) throws InterruptedException {
    SessionRequest request = requests.takeFirst();
    LOG.info("Processing: " + request);

    Optional<FactoryAndCaps> sessionFactory = Optional.empty();

    try {
      // getSessionFactory marks the factory as being used. We must remember to release it
      sessionFactory = getSessionFactory(request);
      Optional<ActiveSession> session = sessionFactory.flatMap(FactoryAndCaps::apply);

      if (session.isPresent()) {
        LOG.info("Successfully createed session for " + request);
        request.setResult(session);
        return;
      }
    } catch (Throwable e) {
      LOG.log(Level.WARNING, "Unable to create session: " + e.getMessage(), e);
      // Fall through to the handling of the session not being created.
    }

    sessionFactory.ifPresent(fac -> fac.factory.setAvailable(true));

    RetryDelay strategy = request.nextStrategy();
    if (strategy == null) {
      LOG.info("No more retry strategies left for session request. Abandoning " + request);
      request.setResult(Optional.empty());
      return;
    }

    try {
      delayedSessionExecutor.schedule(() -> {
            try {
              LOG.info("Rescheduling " + request);
              requests.putFirst(request);
            } catch (InterruptedException e) {
              LOG.log(SEVERE, "Unable to complete session: " + request, e);
              request.setResult(Optional.empty());
              Thread.currentThread().interrupt();
            }
          },
          strategy.getDelay().toMillis(),
          MILLISECONDS);
    } catch (Exception e) {
      LOG.log(WARNING, "Problem creating session for " + request, e);
      request.setResult(Optional.empty());
    }
  }

  private Optional<FactoryAndCaps> getSessionFactory(SessionRequest request) {
    lock.lock();
    try {
      Optional<FactoryAndCaps> factoryAndCaps = request.stream()
          .map(caps ->
                   distributor.match(caps).map(factory -> new FactoryAndCaps(request, factory, caps)))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .findFirst();
      factoryAndCaps.ifPresent(fac -> fac.factory.setAvailable(false));

      return factoryAndCaps;
    } catch (IOException e) {
      return Optional.empty();
    } finally {
      lock.unlock();
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private Distributor distributor;
    private RetryDelay retryStrategy;

    private Builder() {
      this.retryStrategy = immediately();
    }

    public Builder distributeUsing(Distributor distributor) {
      this.distributor = Objects.requireNonNull(distributor, "Distributor cannot be null");
      return this;
    }

    public Builder retryUsing(RetryDelay strategy) {
      retryStrategy = Objects.requireNonNull(strategy, "Strategy must not be null");
      return this;
    }

    public Scheduler create() {
      if (distributor == null) {
        throw new IllegalStateException("Distributor has not be set and is required");
      }

      return new Scheduler(distributor, retryStrategy);
    }
  }

  private static class SessionRequest {

    private final NewSessionPayload payload;
    private final Iterator<RetryDelay> retry;
    private final CountDownLatch latch;
    private AtomicReference<Optional<ActiveSession>> result;

    private SessionRequest(NewSessionPayload payload, Iterator<RetryDelay> retry) {
      this.payload = payload;
      this.retry = retry;

      this.result = new AtomicReference<>(Optional.empty());
      this.latch = new CountDownLatch(1);
    }

    private Set<Dialect> getDownstreamDialects() {
      return payload.getDownstreamDialects();
    }

    private RetryDelay nextStrategy() {
      return retry.hasNext() ? retry.next() : null;
    }

    public void setResult(Optional<ActiveSession> result) {
      this.result.set(result);
      latch.countDown();
    }

    public Optional<ActiveSession> getResult() throws InterruptedException {
      latch.await();
      return result.get();
    }

    public NewSessionPayload getPayload() {
      return payload;
    }

    public Stream<Capabilities> stream() throws IOException {
      return payload.stream();
    }
  }

  private static class FactoryAndCaps {
    private final SessionRequest request;
    private final ScheduledSessionFactory factory;
    private final Capabilities capabilities;

    private FactoryAndCaps(
        SessionRequest request,
        ScheduledSessionFactory factory,
        Capabilities capabilities) {
      this.request = request;
      this.factory = factory;
      this.capabilities = capabilities;
    }

    private Optional<ActiveSession> apply() {
      LOG.info(String.format(
          "Creating session for %s using capabilities %s and factory %s",
          request,
          capabilities,
          factory));
      return factory.apply(request.getDownstreamDialects(), capabilities);
    }
  }

//  public static void main(String[] args) throws IOException {
//    Host host = Host.builder()
//        .address("localhost")
//        .add(new ServicedSession.Factory(
//            caps -> "firefox".equals(caps.getBrowserName()),
//            GeckoDriverService.class.getName()))
//        .limitSessionCount(5)
//        .create()
//        .setStatus(UP);
//
//    Distributor distributor = new Distributor().addHost(host);
//
//    Scheduler scheduler = Scheduler.builder()
//        .distributeUsing(distributor)
//        .retryUsing(
//            immediately()
//                .orElse(retryAfter(Duration.ofMillis(500)))
//                .orElse(retryAfter(Duration.ofSeconds(1))))
//        .create();
//
//    ActiveSession session;
//    try (NewSessionPayload payload = NewSessionPayload.create(new FirefoxOptions())) {
//      session = scheduler.createSession(payload);
//    }
//
//    WebDriver driver = session.getWrappedDriver();
//    driver.get("http://www.google.com");
//    session.stop();
//  }
}
