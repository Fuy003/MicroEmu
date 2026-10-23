import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Exercises the actual c.class packaged in the Manager, without starting its UI. */
public final class ProxyLoopbackTest {
    private static final InetAddress LOOPBACK;
    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static byte[] read(InputStream input, int count) throws Exception {
        byte[] bytes = new byte[count];
        new DataInputStream(input).readFully(bytes);
        return bytes;
    }

    private static void verifyNonLoopbackDenied(int port) throws Exception {
        // An alternate loopback IP catches wildcard binding even in an isolated
        // test environment with no LAN interface.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.2", port), 1500);
            throw new AssertionError("Proxy is reachable at 127.0.0.2");
        } catch (ConnectException expected) {
            System.out.println("PASS alternate address refused: 127.0.0.2");
        }
        int checked = 0;
        for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                interfaces.hasMoreElements();) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp()) continue;
            for (Enumeration<InetAddress> addresses = network.getInetAddresses(); addresses.hasMoreElements();) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, port), 1500);
                    throw new AssertionError("Proxy is reachable at non-loopback address " + address);
                } catch (ConnectException expected) {
                    System.out.println("PASS non-loopback refused: " + address.getHostAddress());
                    checked++;
                }
            }
        }
        if (checked == 0) System.out.println("SKIP non-loopback connect: no active IPv4 network interface");
    }

    private static void run(String protocol) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        File log = Files.createTempFile("proxy-loopback-test-", ".log").toFile();
        Object bridge = null;
        Method stop = null;
        try (ServerSocket upstream = new ServerSocket(0, 50, LOOPBACK)) {
            upstream.setSoTimeout(5000);
            Class<?> configClass = Class.forName("d");
            Constructor<?> configConstructor = configClass.getDeclaredConstructor();
            configConstructor.setAccessible(true);
            Object config = configConstructor.newInstance();
            field(config, "d", "127.0.0.1");
            field(config, "c", Integer.toString(upstream.getLocalPort()));
            field(config, "f", protocol);

            Class<?> bridgeClass = Class.forName("c");
            Constructor<?> constructor = bridgeClass.getDeclaredConstructor(configClass, File.class);
            constructor.setAccessible(true);
            bridge = constructor.newInstance(config, log);
            stop = bridgeClass.getDeclaredMethod("b");
            stop.setAccessible(true);
            Method start = bridgeClass.getDeclaredMethod("a");
            start.setAccessible(true);
            int port = (Integer) start.invoke(bridge);
            Field socketField = bridgeClass.getDeclaredField("c");
            socketField.setAccessible(true);
            ServerSocket listener = (ServerSocket) socketField.get(bridge);
            require(listener.getInetAddress().equals(LOOPBACK), "Expected 127.0.0.1, got " + listener.getInetAddress());
            require(port > 0, "Expected an ephemeral port");
            System.out.println("PASS " + protocol + " bound to 127.0.0.1:" + port);
            verifyNonLoopbackDenied(port);

            Future<Void> remote = executor.submit(() -> {
                try (Socket socket = upstream.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    if (protocol.equals("SOCKS5")) {
                        require(Arrays.equals(read(input, 4), new byte[] {5, 2, 0, 2}), "SOCKS5 greeting");
                        output.write(new byte[] {5, 0});
                        output.flush();
                        require(Arrays.equals(read(input, 4), new byte[] {5, 1, 0, 3}), "SOCKS5 CONNECT");
                        int length = input.read();
                        require("127.0.0.1".equals(new String(read(input, length), StandardCharsets.US_ASCII)), "Target host");
                        require(Arrays.equals(read(input, 2), new byte[] {0x23, 0x45}), "Target port");
                        output.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                    } else {
                        StringBuilder header = new StringBuilder();
                        while (!header.toString().endsWith("\r\n\r\n") && header.length() < 8192) {
                            int value = input.read();
                            require(value >= 0, "Incomplete HTTP header");
                            header.append((char) value);
                        }
                        require(header.toString().startsWith("CONNECT 127.0.0.1:9029 HTTP/1.1\r\n"), "HTTP CONNECT target");
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    output.flush();
                    require("ping".equals(new String(read(input, 4), StandardCharsets.US_ASCII)), "Forward payload");
                    output.write("pong".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                }
                return null;
            });
            try (Socket client = new Socket(LOOPBACK, port)) {
                client.setSoTimeout(5000);
                OutputStream output = client.getOutputStream();
                InputStream input = client.getInputStream();
                // Manager launches the emulator with SOCKS version 4.
                output.write(new byte[] {4, 1, 0x23, 0x45, 127, 0, 0, 1, 0});
                output.flush();
                byte[] reply = read(input, 8);
                require(reply[1] == 90, "SOCKS4 bridge reply");
                output.write("ping".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                require("pong".equals(new String(read(input, 4), StandardCharsets.US_ASCII)), "Return payload");
            }
            remote.get(5, TimeUnit.SECONDS);
            System.out.println("PASS " + protocol + " handshake and bidirectional relay");
        } finally {
            if (bridge != null && stop != null) stop.invoke(bridge);
            executor.shutdownNow();
            Files.deleteIfExists(log.toPath());
        }
    }

    public static void main(String[] args) throws Exception {
        run("SOCKS5");
        run("HTTP");
        System.out.println("ALL TESTS PASSED");
    }
}
