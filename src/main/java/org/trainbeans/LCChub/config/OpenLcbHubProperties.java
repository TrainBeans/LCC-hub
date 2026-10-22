package org.trainbeans.LCChub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openlcb.hub")
public record OpenLcbHubProperties(
        boolean enabled,
        int port,
        boolean sendLineEndings,
        boolean requireIncomingLineEndings,
        boolean mdnsEnabled,
        String mdnsAddress
) {
    public OpenLcbHubProperties {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("openlcb.hub.port must be between 1 and 65535");
        }
    }
}
