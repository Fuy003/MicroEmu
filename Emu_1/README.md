# Emu_1 — development copy

All emulator development and security fixes belong in this directory.
The original `../MicroEmulator Manager.jar` and `../lib/` are reference files
and must remain unchanged.

| Path | Purpose |
| --- | --- |
| `MicroEmulator Manager.jar` | Runnable Manager with the localhost-only proxy fix |
| `lib/` | Full copy of the original libraries, preserving relative classpaths |
| `src/main/java/` | Source for new and modified components |
| `tools/` | Reproducible patch/build tools |
| `tests/` | Regression tests |
| `docs/` | Implementation and validation notes |

## Run

Change into `Emu_1` before launching so relative `lib/` paths and game configuration
files resolve inside this development copy:

```sh
cd Emu_1
java -jar "MicroEmulator Manager.jar"
```

The Manager is intended for Windows. Existing proxy-fix validation was performed
on the isolated bridge class under Linux; the Windows UI has not been tested.
See [proxy-loopback-fix.md](docs/proxy-loopback-fix.md) for build and test commands.
