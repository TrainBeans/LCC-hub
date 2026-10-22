package org.trainbeans.LCChub.hub;

import org.openlcb.hub.Hub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.trainbeans.LCChub.config.OpenLcbHubProperties;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenLcbHubService {
    private static final Logger logger = LoggerFactory.getLogger(OpenLcbHubService.class);
    private static final Pattern CONNECTION_STARTED = Pattern.compile("^Connection started with (.+)$");
    private static final Pattern CONNECTION_ENDED = Pattern.compile("^Connection ended with (.+)$");
    private static final int MAX_EVENTS = 50;
    private static final int MAX_TRAFFIC = 500;

    private final OpenLcbHubProperties properties;
    private final Set<String> activeConnections = ConcurrentHashMap.newKeySet();
    private final List<HubEvent> recentEvents = new ArrayList<>();
    private final Object eventLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Maps each Hub.Forwarding (ReaderThread) to its remote address string. */
    private final Map<Hub.Forwarding, String> forwarderToAddress = new ConcurrentHashMap<>();

    private final List<TrafficEntry> recentTraffic = new ArrayList<>();
    private final Object trafficLock = new Object();

    private final List<SseEmitter> trafficEmitters = new CopyOnWriteArrayList<>();

    private Hub hub;
    private Thread hubThread;

    public OpenLcbHubService(OpenLcbHubProperties properties) {
        this.properties = properties;
    }

    public synchronized void start() {
        if (!properties.enabled()) {
            addEvent("Hub is disabled by configuration.");
            return;
        }
        if (running.get()) {
            return;
        }

        hub = new MonitoringHub(properties.port(), properties.sendLineEndings(), properties.requireIncomingLineEndings());
        hubThread = new Thread(() -> {
            running.set(true);
            addEvent("Hub started on port " + hub.getPort() + ".");
            try {
                hub.start();
            } finally {
                running.set(false);
                activeConnections.clear();
                forwarderToAddress.clear();
                addEvent("Hub stopped.");
            }
        }, "openlcb-hub-main");
        hubThread.setDaemon(true);
        hubThread.start();
    }

    public synchronized void stop() {
        if (hub != null) {
            hub.dispose();
        }
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public HubStatusSnapshot status() {
        List<String> connections = activeConnections.stream().sorted(Comparator.naturalOrder()).toList();
        List<HubEvent> events;
        synchronized (eventLock) {
            events = List.copyOf(recentEvents);
        }
        return new HubStatusSnapshot(
                properties.enabled(),
                running.get(),
                properties.port(),
                connections.size(),
                connections,
                events
        );
    }

    public List<TrafficEntry> getRecentTraffic() {
        synchronized (trafficLock) {
            return List.copyOf(recentTraffic);
        }
    }

    /**
     * Registers a new SSE emitter for the live traffic stream.
     * Pre-loads the last {@value MAX_TRAFFIC} entries so the client is not blank on connect.
     */
    public SseEmitter subscribeTraffic() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — client reconnects on drop
        trafficEmitters.add(emitter);
        emitter.onCompletion(() -> trafficEmitters.remove(emitter));
        emitter.onTimeout(() -> trafficEmitters.remove(emitter));
        emitter.onError(e -> trafficEmitters.remove(emitter));

        // Send buffered history so the client has context immediately
        List<TrafficEntry> history = getRecentTraffic();
        if (!history.isEmpty()) {
            try {
                for (TrafficEntry entry : history) {
                    emitter.send(SseEmitter.event().name("traffic").data(entry));
                }
            } catch (IOException e) {
                trafficEmitters.remove(emitter);
            }
        }
        return emitter;
    }

    private void captureTraffic(Hub.Memo memo) {
        String source = forwarderToAddress.getOrDefault(memo.source, "unknown");
        TrafficEntry entry = new TrafficEntry(Instant.now(), source, memo.line);

        synchronized (trafficLock) {
            recentTraffic.add(entry);
            if (recentTraffic.size() > MAX_TRAFFIC) {
                recentTraffic.remove(0);
            }
        }

        for (SseEmitter emitter : trafficEmitters) {
            try {
                emitter.send(SseEmitter.event().name("traffic").data(entry));
            } catch (Exception e) {
                trafficEmitters.remove(emitter);
            }
        }
    }

    private void addEvent(String message) {
        HubEvent event = new HubEvent(Instant.now(), message);
        synchronized (eventLock) {
            recentEvents.add(event);
            if (recentEvents.size() > MAX_EVENTS) {
                recentEvents.remove(0);
            }
        }
        logger.info("OpenLCB Hub: {}", message);
    }

    private void processHubMessage(String message) {
        Matcher startedMatcher = CONNECTION_STARTED.matcher(message);
        if (startedMatcher.matches()) {
            activeConnections.add(startedMatcher.group(1));
            addEvent(message);
            return;
        }

        Matcher endedMatcher = CONNECTION_ENDED.matcher(message);
        if (endedMatcher.matches()) {
            activeConnections.remove(endedMatcher.group(1));
            // Remove the forwarder→address mapping for this client
            String addr = endedMatcher.group(1);
            forwarderToAddress.values().remove(addr);
            addEvent(message);
            return;
        }

        if ("Hub Shutting Down".equals(message)) {
            running.set(false);
            activeConnections.clear();
        }
        addEvent(message);
    }

    // -------------------------------------------------------------------------
    // Public record types
    // -------------------------------------------------------------------------

    public record HubEvent(Instant timestamp, String message) {}

    public record TrafficEntry(Instant timestamp, String source, String frame) {}

    public record HubStatusSnapshot(
            boolean enabled,
            boolean running,
            int port,
            int activeConnections,
            List<String> connectedClients,
            List<HubEvent> recentEvents
    ) {}

    // -------------------------------------------------------------------------
    // Hub subclass — intercepts connection events and all forwarded frames
    // -------------------------------------------------------------------------

    private final class MonitoringHub extends Hub {

        /**
         * The most recently registered Forwarding. In Hub.start(), addForwarder(r) is called
         * immediately before notifyOwner("Connection started with …"), both on the same thread,
         * so this reliably identifies the new client for address mapping.
         */
        private volatile Hub.Forwarding lastAddedForwarder;

        private MonitoringHub(int port, boolean sendLineEndings, boolean requireIncomingLineEndings) {
            super(port, sendLineEndings, requireIncomingLineEndings);
            // Add a passive traffic-capture forwarder.  It is never the source of any Memo
            // (it never puts frames into the queue), so it receives every forwarded frame.
            addForwarder(OpenLcbHubService.this::captureTraffic);
        }

        @Override
        public void addForwarder(Hub.Forwarding f) {
            super.addForwarder(f);
            lastAddedForwarder = f;
        }

        @Override
        public void notifyOwner(String message) {
            Matcher startedMatcher = CONNECTION_STARTED.matcher(message);
            if (startedMatcher.matches() && lastAddedForwarder != null) {
                forwarderToAddress.put(lastAddedForwarder, startedMatcher.group(1));
                lastAddedForwarder = null;
            }
            processHubMessage(message);
        }
    }
}

