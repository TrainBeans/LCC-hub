package org.trainbeans.LCChub.hub;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.trainbeans.LCChub.mdns.MdnsAdvertiser;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class OpenLcbHubLifecycle {
    private final OpenLcbHubService hubService;
    private final MdnsAdvertiser mdnsAdvertiser;

    public OpenLcbHubLifecycle(OpenLcbHubService hubService, MdnsAdvertiser mdnsAdvertiser) {
        this.hubService = hubService;
        this.mdnsAdvertiser = mdnsAdvertiser;
    }

    @PostConstruct
    void startHub() {
        hubService.start();
        mdnsAdvertiser.start(resolveServiceName());
    }

    @PreDestroy
    void stopHub() {
        mdnsAdvertiser.stop();
        hubService.stop();
    }

    private String resolveServiceName() {
        try {
            return "OpenLCB Hub @ " + InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "OpenLCB Hub";
        }
    }
}

