package com.bazaarvoice.curator.recipes.leader;

import com.bazaarvoice.curator.test.ZooKeeperTest;
import com.google.common.base.Objects;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.SettableFuture;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.Participant;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LeaderServiceTest extends ZooKeeperTest {
    private static final String PATH = "/path/leader";

    private CuratorFramework _curator;

    @Before
    @Override
    public void setup() throws Exception {
        super.setup();
        _curator = newCurator();
    }

    private <T extends Service> T register(final T service) {
        closer().register(new Closeable() {
            @Override
            public void close() {
                service.stop();
            }
        });
        return service;
    }

    private LeaderService newLeaderService(int reacquireDelay, TimeUnit reacquireDelayUnit, Supplier<Service> services) {
        return register(new LeaderService(_curator, PATH, "test-id", reacquireDelay, reacquireDelayUnit, services));
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Tests
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /** Test starting and stopping a single instance of LeaderService. */
    @Test
    public void testLifeCycle() throws Exception {
        ServiceTriggers triggers = new ServiceTriggers();
        LeaderService leader = newLeaderService(1, TimeUnit.HOURS, supply(triggers.listenTo(new NopService())));
        assertEquals("test-id", leader.getId());
        assertFalse(leader.hasLeadership());

        // Start trying to obtain leadership
        leader.start();
        assertTrue(triggers.getRunning().firedWithin(1, TimeUnit.MINUTES));
        assertTrue(leader.isRunning());
        assertTrue(leader.hasLeadership());
        assertEquals(new Participant("test-id", true), leader.getLeader());
        assertEquals(Collections.singletonList(new Participant("test-id", true)), leader.getParticipants());
        assertFalse(triggers.getTerminated().hasFired());
        assertTrue(leader.getDelegateService().get().isRunning());

        // Start watching ZooKeeper directly for changes
        WatchTrigger childrenTrigger = WatchTrigger.childrenTrigger();
        _curator.getChildren().usingWatcher(childrenTrigger).forPath(PATH);

        // Stop trying to obtain leadership
        leader.stop();
        assertTrue(triggers.getTerminated().firedWithin(1, TimeUnit.SECONDS));
        assertFalse(leader.isRunning());
        assertFalse(leader.getDelegateService().isPresent());

        // Wait for stopped state to reflect in ZooKeeper then poll ZooKeeper for leadership participants state
        assertTrue(childrenTrigger.firedWithin(1, TimeUnit.SECONDS));
        assertFalse(leader.hasLeadership());
        assertTrue(_curator.getChildren().forPath(PATH).isEmpty());
        assertEquals(new Participant("", false), leader.getLeader());
        assertEquals(Collections.<Participant>emptyList(), leader.getParticipants());
    }

    /** Test starting multiple instances that compete for leadership. */
    @Test
    public void testMultipleLeaders() throws Exception {
        final Trigger started = new Trigger();
        final AtomicInteger startCount = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            newLeaderService(1, TimeUnit.HOURS, new Supplier<Service>() {
                @Override
                public Service get() {
                    return new AbstractIdleService() {
                        @Override
                        protected void startUp() throws Exception {
                            started.fire();
                            startCount.incrementAndGet();
                        }

                        @Override
                        protected void shutDown() throws Exception {
                            // Do nothing
                        }
                    };
                }
            }).start();
        }
        assertTrue(started.firedWithin(1, TimeUnit.MINUTES));
        // We know one service has started.  Wait a little while and verify no more services are started.
        Thread.sleep(250);
        assertTrue(startCount.get() == 1);
    }

    @Test
    public void testSelfStoppingService() {
        // If the delegate service stops itself then it gets restarted after reacquireDelay.
        int reacquireDelayMillis = 1500;
        ServiceTriggers triggers1 = new ServiceTriggers();
        ServiceTriggers triggers2 = new ServiceTriggers();
        LeaderService leader = newLeaderService(reacquireDelayMillis, TimeUnit.MILLISECONDS, supply(
                triggers1.listenTo(new AbstractScheduledService() {
                    @Override
                    protected void runOneIteration() throws Exception {
                        stop();
                    }

                    @Override
                    protected Scheduler scheduler() {
                        return Scheduler.newFixedDelaySchedule(10, 10, TimeUnit.MILLISECONDS);
                    }
                }),
                triggers2.listenTo(new NopService())));
        leader.start();

        assertTrue(triggers1.getRunning().firedWithin(1, TimeUnit.MINUTES));
        assertTrue(triggers1.getTerminated().firedWithin(1, TimeUnit.MINUTES));
        assertTrue(triggers2.getRunning().firedWithin(1, TimeUnit.MINUTES));
    }

    /** Verify that leadership is re-acquired after the connection to ZooKeeper is lost. */
    @Test
    public void testLostZooKeeperConnection() throws Exception {
        int reacquireDelayMillis = 1500;
        ServiceTriggers triggers1 = new ServiceTriggers();
        ServiceTriggers triggers2 = new ServiceTriggers();
        List<Event> events = Collections.synchronizedList(Lists.<Event>newArrayList());
        Service leader = newLeaderService(reacquireDelayMillis, TimeUnit.MILLISECONDS, supply(
                triggers1.listenTo(trackEvents("1", events, new NopService())),
                triggers2.listenTo(trackEvents("2", events, new NopService()))));

        leader.start();
        assertTrue(triggers1.getRunning().firedWithin(1, TimeUnit.MINUTES));

        killSession(_curator);
        assertTrue(triggers1.getTerminated().firedWithin(1, TimeUnit.MINUTES));
        assertTrue(triggers2.getRunning().firedWithin(1, TimeUnit.MINUTES));

        leader.stop();
        assertTrue(triggers2.getTerminated().firedWithin(1, TimeUnit.MINUTES));

        // Verify sequence of events.
        assertEquals(ImmutableList.of(
                new Event("1", Service.State.STARTING),
                new Event("1", Service.State.RUNNING),
                new Event("1", Service.State.STOPPING),
                new Event("1", Service.State.TERMINATED),
                new Event("2", Service.State.STARTING),
                new Event("2", Service.State.RUNNING),
                new Event("2", Service.State.STOPPING),
                new Event("2", Service.State.TERMINATED)
        ), events);
        Event stop1 = events.get(3), start2 = events.get(4);

        // Verify that the re-acquire delay was observed
        long reacquireDelay = start2.getTimestamp() - stop1.getTimestamp();
        assertTrue("Re-acquire delay was not observed: " + reacquireDelay, reacquireDelay >= reacquireDelayMillis);
    }

    /** Verify that leadership is re-acquired after the connection to ZooKeeper is lost. */
    @Test
    public void testStartupFailed() throws Exception {
        int reacquireDelayMillis = 1500;
        ServiceTriggers triggers1 = new ServiceTriggers();
        ServiceTriggers triggers2 = new ServiceTriggers();
        List<Event> events = Collections.synchronizedList(Lists.<Event>newArrayList());
        Service leader = newLeaderService(reacquireDelayMillis, TimeUnit.MILLISECONDS, supply(
                triggers1.listenTo(trackEvents("1", events, new NopService() {
                    @Override
                    protected void startUp() throws Exception {
                        throw new Exception("Startup failed");
                    }
                })),
                triggers2.listenTo(trackEvents("2", events, new NopService()))));

        leader.start();
        assertTrue(triggers1.getFailed().firedWithin(1, TimeUnit.MINUTES));
        assertTrue(triggers2.getRunning().firedWithin(1, TimeUnit.MINUTES));

        // Verify sequence of events.
        assertEquals(ImmutableList.of(
                new Event("1", Service.State.STARTING),
                new Event("1", Service.State.FAILED),
                new Event("2", Service.State.STARTING),
                new Event("2", Service.State.RUNNING)
        ), events);
        Event start1 = events.get(1), start2 = events.get(2);

        // Verify that the re-acquire delay was observed
        long reacquireDelay = start2.getTimestamp() - start1.getTimestamp();
        assertTrue("Re-acquire delay was not observed: " + reacquireDelay, reacquireDelay >= reacquireDelayMillis);
    }

    /** Verify that leadership is re-acquired after the connection to ZooKeeper is lost. */
    @Test
    public void testShutdownFailed() throws Exception {
        int reacquireDelayMillis = 1500;
        ServiceTriggers triggers1 = new ServiceTriggers();
        ServiceTriggers triggers2 = new ServiceTriggers();
        List<Event> events = Collections.synchronizedList(Lists.<Event>newArrayList());
        Service leader = newLeaderService(reacquireDelayMillis, TimeUnit.MILLISECONDS, supply(
                triggers1.listenTo(trackEvents("1", events, new NopService() {
                    @Override
                    protected void shutDown() throws Exception {
                        throw new Exception("ShutDown failed");
                    }
                })),
                triggers2.listenTo(trackEvents("2", events, new NopService()))));

        leader.start();
        assertTrue(triggers1.getRunning().firedWithin(1, TimeUnit.MINUTES));

        killSession(_curator);
        assertTrue(triggers1.getFailed().firedWithin(1, TimeUnit.MINUTES));
        assertTrue(triggers2.getRunning().firedWithin(1, TimeUnit.MINUTES));

        // Verify sequence of events.
        assertEquals(ImmutableList.of(
                new Event("1", Service.State.STARTING),
                new Event("1", Service.State.RUNNING),
                new Event("1", Service.State.STOPPING),
                new Event("1", Service.State.FAILED),
                new Event("2", Service.State.STARTING),
                new Event("2", Service.State.RUNNING)
        ), events);
        Event stop1 = events.get(3), start2 = events.get(4);

        // Verify that the re-acquire delay was observed
        long reacquireDelay = start2.getTimestamp() - stop1.getTimestamp();
        assertTrue("Re-acquire delay was not observed: " + reacquireDelay, reacquireDelay >= reacquireDelayMillis);
    }

    /** Verify that the name of the thread created by LeaderSelector is set correctly. */
    @Test
    public void testThreadName() throws Exception {
        final String expectedThreadName = "TestLeaderService";
        final SettableFuture<String> actualThreadName = SettableFuture.create();
        ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            @SuppressWarnings("NullableProblems")
            public Thread newThread(Runnable r) {
                Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName(expectedThreadName);
                return thread;
            }
        };
        register(new LeaderService(_curator, PATH, "id", threadFactory, 1, TimeUnit.HOURS, new Supplier<Service>() {
            @Override
            public Service get() {
                return new AbstractService() {
                    @Override
                    protected void doStart() {
                        actualThreadName.set(Thread.currentThread().getName());
                        notifyStarted();
                    }

                    @Override
                    protected void doStop() {
                        notifyStopped();
                    }
                };
            }
        })).start();
        assertEquals(expectedThreadName, actualThreadName.get(1, TimeUnit.MINUTES));
    }

    private static Service trackEvents(String id, List<Event> events, Service service) {
        service.addListener(new EventListener(id, events, service), MoreExecutors.sameThreadExecutor());
        return service;
    }

    private static Supplier<Service> supply(Service... services) {
        final Iterator<Service> iter = Iterators.forArray(services);
        return new Supplier<Service>() {
            @Override
            public Service get() {
                return iter.next();
            }
        };
    }

    private static class NopService extends AbstractIdleService {
        @Override
        protected void startUp() throws Exception {}

        @Override
        protected void shutDown() throws Exception {}
    }

    private static class ServiceTriggers implements Service.Listener {
        private final Trigger _starting = new Trigger();
        private final Trigger _running = new Trigger();
        private final Trigger _stopping = new Trigger();
        private final Trigger _terminated = new Trigger();
        private final Trigger _failed = new Trigger();

        public Service listenTo(Service service) {
            service.addListener(this, MoreExecutors.sameThreadExecutor());
            return service;
        }

        @Override
        public void starting() {
            _starting.fire();
        }

        @Override
        public void running() {
            _running.fire();
        }

        @Override
        public void stopping(Service.State from) {
            _stopping.fire();
        }

        @Override
        public void terminated(Service.State from) {
            _terminated.fire();
        }

        @Override
        public void failed(Service.State from, Throwable failure) {
            _failed.fire();
        }

        public Trigger getStarting() {
            return _starting;
        }

        public Trigger getRunning() {
            return _running;
        }

        public Trigger getStopping() {
            return _stopping;
        }

        public Trigger getTerminated() {
            return _terminated;
        }

        public Trigger getFailed() {
            return _failed;
        }
    }

    private static class EventListener implements Service.Listener {
        private final String _id;
        private final List<Event> _events;
        private final Service _service;

        private EventListener(String id, List<Event> events, Service service) {
            _events = events;
            _id = id;
            _service = service;
        }

        private boolean addEvent(Service.State state) {
            return _events.add(new Event(_id, state));
        }

        @Override
        public void starting() {
            addEvent(Service.State.STARTING);
        }

        @Override
        public void running() {
            addEvent(Service.State.RUNNING);
        }

        @Override
        public void stopping(Service.State from) {
            addEvent(Service.State.STOPPING);
        }

        @Override
        public void terminated(Service.State from) {
            addEvent(Service.State.TERMINATED);
        }

        @Override
        public void failed(Service.State from, Throwable failure) {
            addEvent(Service.State.FAILED);
        }
    }

    private static class Event {
        private final String _id;
        private final Service.State _state;
        private final long _timestamp;

        private Event(String id, Service.State state) {
            _id = checkNotNull(id, "id");
            _state = checkNotNull(state, "state");
            _timestamp = System.currentTimeMillis();
        }

        public long getTimestamp() {
            return _timestamp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Event)) {
                return false;
            }
            Event that = (Event) o;
            return _id.equals(that._id) && _state == that._state;

        }

        @Override
        public int hashCode() {
            return Objects.hashCode(_id, _state);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("id", _id)
                    .add("state", _state)
                    .toString();
        }
    }
}
