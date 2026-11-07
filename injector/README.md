# Logic launcher

## Build

Run `build.bat` from Windows. Use `build.bat -Offline` after dependencies have been
cached. Requires JDK 25, Python 3, Rust, and the MSVC C++ build tools.

The build compiles Mica, builds and tests Logic, packs the injectable payload and
its dependencies, then compiles the Rust launcher and native DLL. Output is in
`dist/`. The build stops on the first failure.

If a running game has the previous DLL loaded, use
`build.bat -OutputDirectory dist-fixed` to build into a separate directory.
Restart Minecraft before using the new launcher: a DLL that was already loaded
cannot be replaced inside that session, including after a failed initialization.

## Inject

1. Start Minecraft **26.2**, **Java 25**, **Fabric Loader** and **Fabric API**.
2. Use the **Vulkan** graphics backend (`--graphicsBackend vulkan`).
3. Run `LogicLauncher.exe` with `logic_native.dll` beside it.
4. Select the Minecraft process using Up/Down and press Enter.

Alternatively run `LogicLauncher.exe <pid>`. Mica is embedded and injected too;
neither Logic nor Mica needs to be installed in the mods directory. Existing
active Mica is reused. The launcher shows native loading and Java initialization
progress. A successful native library load alone is not reported as success.

The Windows launcher/DLL and JVM architectures must match. The included build is
Windows x64. Vanilla, Forge and other Minecraft versions are unsupported. Restart
Minecraft to unload an injected session or change the payload. Duplicate injection
is rejected.

Right Shift opens the GUI. Left-click a module to toggle it; right-click opens its
settings. Settings currently last for the session. Windowed or borderless mode is
needed for the capture-excluded Mica overlay; exclusive fullscreen hides it.

## Fabric mod mode

Install `logic-0.1.0.jar` in the mods directory with Fabric API. Mica and its
libraries are nested inside the mod jar: do not install a second Mica copy.
The separate `mica-0.2.jar` is included for standalone use, not required by Logic.
The injectable payload jar is not the Fabric mod jar.

## Tests and logs

`gradlew.bat build --offline` runs module checks and verifies live transformations
against Minecraft 26.2, including class schema preservation and duplicate rejection.
`injector/tests/smoke.ps1` creates its own disposable JVM and verifies native loading,
JNI registration, class-byte capture, redefinition and rollback. It does not inject
into a user's running game. Its separate test DLL is not distributed.

Logs are stored under `%APPDATA%/.logic/logs/`; the embedded payload is extracted to
`%APPDATA%/.logic/runtime/payloads/`. The interactive Windows launcher checks the latest
public GitHub release on startup and asks before updating. Keep `meta.json` beside the
launcher and DLL. Use `--offline` to skip update checks. If a game has the native DLL
loaded, close it before updating. Failed checks leave the installed version available.

For a release, update `version` in `gradle.properties` and run `build-release.bat`.
It creates the Windows zip and update files in `dist-release/`. Publish the zip, plus
`LogicLauncher.exe`, `logic_native.dll`, and `meta.json` from that folder, on a
GitHub release tagged `v<version>`. The build creates the manifest with SHA-256 hashes;
the launcher verifies both downloads before replacing the existing files. The release
must be published as the latest release for update checks to find it. Keep release
artifacts unchanged after the manifest is generated.

See `UPSTREAM.md` for the native loader's origin.


## Unload and reinject

Use Misc > Self Destruct > Unload, wait about two seconds for the DLL to release, then run the launcher again for the same process. Each injection uses a fresh disposable payload class loader. Unload closes/deletes the extracted per-injection payload JAR; the shared runtime bridge and rendering libraries remain loaded for reuse. The JVM controls when eligible Java class definitions are garbage-collected. Restart once when upgrading from pre-isolation builds.
