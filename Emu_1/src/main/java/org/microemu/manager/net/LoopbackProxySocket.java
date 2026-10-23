package org.microemu.manager.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** Creates the IPv4-only listener used by the Manager's local SOCKS bridge. */
public final class LoopbackProxySocket {
    private LoopbackProxySocket() {
    }

    public static ServerSocket create(int port) throws IOException {
        // The Manager connects to 127.0.0.1 explicitly. Do not bind to wildcard
        // or depend on hostname resolution / the preferred IPv6 configuration.
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        return new ServerSocket(port, 50, loopback);
    }
}
