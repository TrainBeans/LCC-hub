package org.trainbeans.LCChub.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trainbeans.LCChub.hub.OpenLcbHubService;

@RestController
@RequestMapping("/api/hub")
public class HubStatusController {
    private final OpenLcbHubService hubService;

    public HubStatusController(OpenLcbHubService hubService) {
        this.hubService = hubService;
    }

    @GetMapping("/status")
    public OpenLcbHubService.HubStatusSnapshot status() {
        return hubService.status();
    }
}
