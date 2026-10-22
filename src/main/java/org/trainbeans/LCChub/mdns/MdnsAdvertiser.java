package org.trainbeans.LCChub.mdns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.trainbeans.LCChub.config.OpenLcbHubProperties;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Advertises the OpenLCB hub over mDNS/Zeroconf so that clients on the local network
 * can discover it automatically.
 * <p>
 * Advertises the hub using the OpenLCB Hub and OpenLCB CAN service types.
 */
@Service
public class MdnsAdvertiser {
    private static final Logger logger = LoggerFactory.getLogger(MdnsAdvertiser.class);

    /** Service type defined by the OpenLCB/OpenMRN specification. */
    public static final String SERVICE_TYPE = "_openlcb-hub._tcp.local.";
    public static final String CAN_SERVICE_TYPE = "_openlcb-can._tcp.local.";

    private final OpenLcbHubProperties properties;
    private final List<JmDNS> advertisers = new ArrayList<>();

    public MdnsAdvertiser(OpenLcbHubProperties properties) {
        this.properties = properties;
    }

    /**
     * Starts mDNS advertisement for the hub. Safe to call multiple times; subsequent calls
     * are no-ops if already advertising.
     *
     * @param serviceName the mDNS service instance name (e.g. hostname or user-configured label)
     */
    public synchronized void start(String serviceName) {
        if (!properties.enabled() || !properties.mdnsEnabled()) {
            logger.info("mDNS: advertising disabled by configuration");
            return;
        }
        if (!advertisers.isEmpty()) {
            return;
        }
        try {
            List<InetAddress> addresses = resolveAddresses();
            String hostName = dnsHostName(InetAddress.getLocalHost().getHostName());
            for (InetAddress address : addresses) {
                JmDNS jmDns = null;
                try {
                    jmDns = JmDNS.create(address, hostName);
                    jmDns.registerService(ServiceInfo.create(
                            SERVICE_TYPE, serviceName, properties.port(), "OpenLCB Hub"));
                    jmDns.registerService(ServiceInfo.create(
                            CAN_SERVICE_TYPE, serviceName, properties.port(), "OpenLCB CAN"));
                    advertisers.add(jmDns);
                    logger.info("mDNS: advertising '{}' as {} and {} on {}:{} with hostname {}",
                            serviceName, SERVICE_TYPE, CAN_SERVICE_TYPE,
                            address.getHostAddress(), properties.port(), hostName);
                } catch (IOException e) {
                    logger.error("mDNS: failed to advertise on {}", address.getHostAddress(), e);
                    if (jmDns != null) {
                        close(jmDns);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("mDNS: failed to start advertisement", e);
        }
    }

    static String dnsHostName(String hostName) throws IOException {
        String label = hostName.split("\\.", 2)[0].toLowerCase(Locale.ROOT);
        if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
                || label.matches("[0-9]+")) {
            throw new IOException("Machine hostname is not a valid DNS host label: " + hostName);
        }
        return label;
    }

    private List<InetAddress> resolveAddresses() throws IOException {
        String configuredAddress = properties.mdnsAddress();
        if (configuredAddress != null && !configuredAddress.isBlank()) {
            InetAddress address = InetAddress.getByName(configuredAddress);
            NetworkInterface network = NetworkInterface.getByInetAddress(address);
            if (address.isLoopbackAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress()
                    || network == null || !network.isUp() || !network.supportsMulticast()) {
                throw new IOException("openlcb.hub.mdns-address must identify a local, active multicast-capable interface");
            }
            return List.of(address);
        }

        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) {
                continue;
            }
            InetAddress selected = null;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (!address.isLoopbackAddress() && !address.isAnyLocalAddress() && !address.isMulticastAddress()) {
                    if (selected == null || address instanceof Inet4Address) {
                        selected = address;
                    }
                    if (address instanceof Inet4Address) {
                        break;
                    }
                }
            }
            // Multiple JmDNS instances on one link probe against each other.
            if (selected != null) {
                addresses.add(selected);
            }
        }
        if (addresses.isEmpty()) {
            throw new IOException("No active multicast-capable interfaces with usable addresses found");
        }
        return addresses;
    }

    /** Unregisters the mDNS service and releases resources. */
    public synchronized void stop() {
        if (!advertisers.isEmpty()) {
            advertisers.forEach(this::close);
            advertisers.clear();
            logger.info("mDNS: advertisement stopped");
        }
    }

    private void close(JmDNS jmDns) {
        try {
            jmDns.close();
        } catch (IOException e) {
            logger.warn("mDNS: error closing JmDNS", e);
        }
    }
}
