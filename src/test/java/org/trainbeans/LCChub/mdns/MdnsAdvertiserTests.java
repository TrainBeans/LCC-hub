package org.trainbeans.LCChub.mdns;

import org.junit.jupiter.api.Test;
import org.trainbeans.LCChub.config.OpenLcbHubProperties;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

import javax.jmdns.JmDNS;

class MdnsAdvertiserTests {
    @Test
    void advertisesBothTypesOnConfiguredAddressWithValidHostname() throws Exception {
        InetAddress address = InetAddress.getByName("192.168.2.109");
        NetworkInterface network = mock(NetworkInterface.class);
        when(network.isUp()).thenReturn(true);
        when(network.supportsMulticast()).thenReturn(true);
        JmDNS jmDns = mock(JmDNS.class);
        String hostName = MdnsAdvertiser.dnsHostName(InetAddress.getLocalHost().getHostName());
        OpenLcbHubProperties properties = new OpenLcbHubProperties(true, 12021, true, false, true, "192.168.2.109");
        try (var networks = mockStatic(NetworkInterface.class); var dns = mockStatic(JmDNS.class)) {
            networks.when(() -> NetworkInterface.getByInetAddress(address)).thenReturn(network);
            dns.when(() -> JmDNS.create(address, hostName)).thenReturn(jmDns);
            MdnsAdvertiser advertiser = new MdnsAdvertiser(properties);
            advertiser.start("OpenLCB Hub @ test");
            verify(jmDns).registerService(argThat(info -> info.getType().equals(MdnsAdvertiser.SERVICE_TYPE)
                    && info.getPort() == 12021 && info.getName().equals("OpenLCB Hub @ test")));
            verify(jmDns).registerService(argThat(info -> info.getType().equals(MdnsAdvertiser.CAN_SERVICE_TYPE)
                    && info.getPort() == 12021));
            advertiser.stop();
            verify(jmDns).close();
        }
    }

    @Test
    void usesMachineHostnameWithoutDomainOrIpSuffix() throws Exception {
        assertEquals("jedi", MdnsAdvertiser.dnsHostName("jedi"));
        assertEquals("jedi", MdnsAdvertiser.dnsHostName("Jedi.example.com"));
        assertEquals("lcc-hub", MdnsAdvertiser.dnsHostName("lcc-hub.local"));
        assertThrows(IOException.class, () -> MdnsAdvertiser.dnsHostName("OpenLCB Hub @ jedi"));
        assertThrows(IOException.class, () -> MdnsAdvertiser.dnsHostName("192.168.2.109"));
    }

    @Test
    void advertisesOnAllEligibleAddressesAndClosesEachInstance() throws Exception {
        InetAddress first = InetAddress.getByName("192.168.2.109");
        InetAddress second = InetAddress.getByName("2001:db8::1");
        NetworkInterface network = mock(NetworkInterface.class);
        NetworkInterface secondNetwork = mock(NetworkInterface.class);
        NetworkInterface loopback = mock(NetworkInterface.class);
        when(network.isUp()).thenReturn(true);
        when(network.supportsMulticast()).thenReturn(true);
        when(network.getInetAddresses()).thenReturn(Collections.enumeration(List.of(second, first)));
        when(secondNetwork.isUp()).thenReturn(true);
        when(secondNetwork.supportsMulticast()).thenReturn(true);
        when(secondNetwork.getInetAddresses()).thenReturn(Collections.enumeration(List.of(second)));
        when(loopback.isUp()).thenReturn(true);
        when(loopback.isLoopback()).thenReturn(true);
        JmDNS firstDns = mock(JmDNS.class);
        JmDNS secondDns = mock(JmDNS.class);
        String hostName = MdnsAdvertiser.dnsHostName(InetAddress.getLocalHost().getHostName());
        try (var networks = mockStatic(NetworkInterface.class); var dns = mockStatic(JmDNS.class)) {
            networks.when(NetworkInterface::getNetworkInterfaces)
                    .thenReturn(Collections.enumeration(List.of(network, secondNetwork, loopback)));
            dns.when(() -> JmDNS.create(first, hostName)).thenReturn(firstDns);
            dns.when(() -> JmDNS.create(second, hostName)).thenReturn(secondDns);
            MdnsAdvertiser advertiser = new MdnsAdvertiser(
                    new OpenLcbHubProperties(true, 12021, true, false, true, null));
            advertiser.start("OpenLCB Hub @ test");
            advertiser.start("OpenLCB Hub @ test");
            verify(firstDns, times(2)).registerService(any());
            verify(secondDns, times(2)).registerService(any());
            dns.verify(() -> JmDNS.create(first, hostName));
            dns.verify(() -> JmDNS.create(second, hostName));
            advertiser.stop();
            advertiser.stop();
            verify(firstDns).close();
            verify(secondDns).close();
        }
    }

    @Test
    void cleansUpFailedInterfaceAndContinuesWithOtherAddresses() throws Exception {
        InetAddress first = InetAddress.getByName("192.168.2.109");
        InetAddress second = InetAddress.getByName("192.168.3.109");
        NetworkInterface network = mock(NetworkInterface.class);
        NetworkInterface secondNetwork = mock(NetworkInterface.class);
        when(network.isUp()).thenReturn(true);
        when(network.supportsMulticast()).thenReturn(true);
        when(network.getInetAddresses()).thenReturn(Collections.enumeration(List.of(first)));
        when(secondNetwork.isUp()).thenReturn(true);
        when(secondNetwork.supportsMulticast()).thenReturn(true);
        when(secondNetwork.getInetAddresses()).thenReturn(Collections.enumeration(List.of(second)));
        JmDNS failedDns = mock(JmDNS.class);
        JmDNS workingDns = mock(JmDNS.class);
        doThrow(new IOException("Registration failed")).when(failedDns).registerService(any());
        String hostName = MdnsAdvertiser.dnsHostName(InetAddress.getLocalHost().getHostName());
        try (var networks = mockStatic(NetworkInterface.class); var dns = mockStatic(JmDNS.class)) {
            networks.when(NetworkInterface::getNetworkInterfaces)
                    .thenReturn(Collections.enumeration(List.of(network, secondNetwork)));
            dns.when(() -> JmDNS.create(first, hostName)).thenReturn(failedDns);
            dns.when(() -> JmDNS.create(second, hostName)).thenReturn(workingDns);
            MdnsAdvertiser advertiser = new MdnsAdvertiser(
                    new OpenLcbHubProperties(true, 12021, true, false, true, ""));
            advertiser.start("OpenLCB Hub @ test");
            verify(failedDns).close();
            verify(workingDns, times(2)).registerService(any());
            advertiser.stop();
            verify(workingDns).close();
        }
    }

    @Test
    void doesNotAdvertiseLoopbackAddress() {
        OpenLcbHubProperties properties = new OpenLcbHubProperties(true, 12021, true, false, true, "127.0.0.1");
        try (var dns = mockStatic(JmDNS.class)) {
            MdnsAdvertiser advertiser = new MdnsAdvertiser(properties);
            advertiser.start("OpenLCB Hub @ test");
            dns.verifyNoInteractions();
            advertiser.stop();
        }
    }
}
