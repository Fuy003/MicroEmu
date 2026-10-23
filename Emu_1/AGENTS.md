# Development scope

- User instruction: make code changes, build outputs, tests, and future merged
  development changes inside `Emu_1/`.
- Preserve the original repository-root `MicroEmulator Manager.jar` and `lib/`
  byte-for-byte. Treat them as read-only reference inputs.
- Run the Manager with `Emu_1` as the working directory so its relative library
  and configuration paths remain local to this copy.
- Do not assume decompiled code can rebuild the entire original application.
  Use the pinned patch workflow for the current loopback fix.
- Build the helper with `python3 tools/patch_proxy_loopback.py` from `Emu_1`.
  This reads the root original JAR and writes only within `Emu_1`.
- Compile `tests/ProxyLoopbackTest.java` with `javac --release 8` and run it
  against the packaged Manager JAR with `-Xverify:all`; see `docs/` for commands.
- State when Windows UI or real LAN connectivity has not been tested.
