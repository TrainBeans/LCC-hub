package org.trainbeans.LCChub.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.trainbeans.LCChub.hub.OpenLcbHubService;

import java.util.List;

@RestController
@RequestMapping("/api/hub/traffic")
public class HubTrafficController {
    private final OpenLcbHubService hubService;

    public HubTrafficController(OpenLcbHubService hubService) {
        this.hubService = hubService;
    }

    /** Returns the buffered traffic history (last 500 frames) as a JSON array. */
    @GetMapping
    public List<OpenLcbHubService.TrafficEntry> recentTraffic() {
        return hubService.getRecentTraffic();
    }

    /**
     * Server-Sent Events stream of live traffic.
     * Each event has name {@code traffic} and a JSON-serialised {@link OpenLcbHubService.TrafficEntry}.
     * On connect the server replays the current buffer so the client has immediate context.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter trafficStream() {
        return hubService.subscribeTraffic();
    }
}
