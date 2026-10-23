# Local proxy listener fix

The Manager previously used `new ServerSocket(0)` in `c.a()`, which binds an
ephemeral TCP port on wildcard interfaces. Its SOCKS bridge accepts unauthenticated
local requests, so reachable network clients could also use the configured proxy.

The patched Manager uses `LoopbackProxySocket.create(port)`, which binds explicitly
to IPv4 `127.0.0.1` with the same default backlog of 50. The emulator already uses
`-DsocksProxyHost=127.0.0.1`. Upstream proxy configuration and relay behavior are
unchanged. Other processes on the same machine can still connect to this listener.

Only `c.class` is modified in the original Manager JAR, and one small helper class
is added. Existing bytecode offsets, stack-map frames, other classes, and the
manifest are preserved. The helper targets Java 8. This repository does not yet
contain the original Manager source, so the patch script refuses any input other
than the audited original SHA-256:

`d8c64d36f46ac2fe9fdef8d6143a7272f35630ae0ba3f3c64fb47c6c300e6011`

## Reproduce

Requirements: Python 3 and JDK 9 or newer (the helper is compiled with `--release 8`).
Run from the `Emu_1` directory. The default input is the original Manager JAR
in the repository root; the output is always confined to `Emu_1`:

```sh
python3 tools/patch_proxy_loopback.py
```

## Regression test

The test runs the packaged bridge class through reflection, without starting the
Manager UI or connecting to any external proxy. Both upstream servers are local
test fixtures; no real credentials are used.

```sh
mkdir -p build/test-classes
javac --release 8 -d build/test-classes tests/ProxyLoopbackTest.java
java -Xverify:all -cp 'build/test-classes:MicroEmulator Manager.jar' ProxyLoopbackTest
```

On Windows use `;` instead of `:` in the classpath. The test verifies the exact
bind address, ephemeral port selection, refusal at the alternate address
`127.0.0.2`, refusal on each active non-loopback IPv4
interface (or reports a skip if none exists), and SOCKS4-to-SOCKS5 / HTTP CONNECT
bidirectional relaying. Running it against the original JAR must fail the bind
address assertion.

This fix addresses only network exposure of the bridge. It does not change
credential storage, keyboard forwarding, or TLS certificate validation.

## Validation performed

Validated with OpenJDK 17 on Linux and full JVM bytecode verification (`-Xverify:all`):

- Both HTTP CONNECT and SOCKS5 upstream fixtures completed bidirectional relay.
- The listener bound exactly to `127.0.0.1`; connections to `127.0.0.2` were refused.
- The original JAR failed the regression test because it bound to `0.0.0.0`.
- Two rebuilds produced identical bytes.
- Archive comparison confirmed that only `c.class` changed and the helper was added.

The environment had no active non-loopback IPv4 interface, so the LAN connection
test was skipped. Windows UI testing was not performed.
