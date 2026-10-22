# LCC Hub

An OpenLCB TCP hub with a web-based connection and traffic monitor.

## Docker

With Docker Engine and the Docker Compose plugin installed on Linux:

```sh
docker compose up --build -d
```

Open <http://localhost:8080> for the monitor. Connect OpenLCB clients to
the Docker host's LAN address on TCP port `12021`.

The multi-stage image builds and tests the application using Maven and Java 17,
then runs the packaged application as a non-root user in a Java 17 runtime.
No local Java or Maven installation is required.

Compose uses host networking so LAN clients can discover both
`_openlcb-hub._tcp.local.` and `_openlcb-can._tcp.local.` on port `12021`.
The advertised DNS hostname is derived dynamically from the machine hostname
(for example, `jedi.local`), independently of the selected LAN address.
Allow inbound UDP `5353` (mDNS multicast) and TCP `12021` in the host firewall;
allow TCP `8080` if the monitor should be accessible from other machines.
Host networking is intended for Linux Docker Engine. Docker Desktop requires
host networking support to be enabled and may have multicast limitations.

The application accepts `openlcb.hub.mdns-address` (environment variable
`OPENLCB_HUB_MDNSADDRESS`) as an optional override to restrict advertisement to
one local address. By default, it discovers all active, non-loopback,
multicast-capable interfaces at startup. Each interface uses its IPv4 address,
or IPv6 when no IPv4 address is available. One advertiser per interface avoids
conflicting probes from multiple addresses on the same link.
No IP address or `.env` file is required. Restart the application
after interface addresses change.

Ports `8080` and `12021` must be free on the host before starting the container.
These ports listen on all host interfaces; only expose them to trusted
networks. Stop a locally running copy of the application first.

```sh
docker compose logs -f
docker compose down
```

To run without Compose on Linux:

```sh
docker build -t lcc-hub:local .
docker run --rm --name lcc-hub \
  --network host \
  lcc-hub:local
```

For bridge networking without discovery, replace `--network host` with
`-p 8080:8080 -p 12021:12021` and add
`-e OPENLCB_HUB_MDNSENABLED=false`. Configure clients with the host's LAN address.

## Continuous integration and releases

GitHub Actions run on every push and pull request (`.github/workflows/ci.yml`),
building and testing the application with Maven and verifying the Docker image
builds.

Publishing a GitHub release (`.github/workflows/release.yml`) builds the Docker
image and pushes it to the GitHub Container Registry, tagged with the release
version (and `latest` for non-prerelease versions):

```sh
docker pull ghcr.io/trainbeans/lcc-hub:latest
```
