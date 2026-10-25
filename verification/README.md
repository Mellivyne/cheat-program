# Development preview check

The optional preview check starts a separate development client, creates a test world inside this project's `run/` directory, checks the registered features, and captures the interface and player/model preview.

```powershell
./gradlew.bat runClient -Popen_checks "-Dcheatprogram.dev=open-check"
```

This check targets Minecraft 1.21.4. Its additional Java sources are enabled only with `-Popen_checks` and are excluded from normal release JARs. Screenshots and development runtime data stay local. A successful compile or preview check does not verify every feature on every supported version.

The isolated check also verifies repeated projection key binding, per-line lyric placement, and curved-screen picking after rotation. Curved-screen settings are checked independently across screens, including migration from older settings.

For the standalone geometry regression (no game needed), compile `src/main/java/com/cheatprogram/render/CastSurface.java` and `verification/java/com/cheatprogram/dev/CurvedSurfaceCheck.java`, then run `com.cheatprogram.dev.CurvedSurfaceCheck`. It covers both curve axes, positive/negative angles, flat compatibility, borders and dragging.

The multi-screen regression uses three synthetic capture sessions to check visible-surface selection, back-face and curved picking, click/drag/wheel routing, returning to a previously selected screen, and rejection of stale frames. It checks local command queues; it does not assert that a real external application handled Windows input messages.

The v1.1 building fixture checks native block validation, invalid dimensions and fills, material totals excluding air, per-layer indexing, and visit-camera movement without moving the real player. It also captures the material list, a selected floor and the first-person tour. Real paid AI-provider calls are not part of these local checks.

The legacy preview fixture is enabled separately, with an output directory per Minecraft target:

```powershell
./gradlew.bat runClient "-Pminecraft_version=1.20.4" -Plegacy_checks "-Dcheatprogram.dev=open-check"
./gradlew.bat runClient "-Pminecraft_version=1.20.1" -Plegacy_checks "-Dcheatprogram.dev=open-check"
```

It creates a fresh isolated test world and verifies native glass shaders and player previews as well as the shared AI/building checks. Development fixtures are excluded from normal JARs. All supported builds require the Java version listed in the compatibility matrix, including Java 21 for the legacy targets.

The optional gameplay diagnostic uses `-Pgameplay_checks` and `-Dcheatprogram.dev=gameplay-check`, with a separate world and configuration under `run-gameplay/<version>/`. Its checked 1.21.11 path injects the native inventory key event and invokes native attack/use routines, then checks the resulting server block states. Glass inventory rendering is disabled in this diagnostic to establish the native input baseline; its screenshots are not evidence that the glass overlay is correct. Runtime probes are excluded from release JARs and their worlds/configuration are excluded from source packages.

The standalone BuildingJobQueueCheck can be compiled with BuildingJobQueue alone (Java 21 or newer); no game is needed. It simulates an old integrated server abandoning work and checks that later jobs still advance. TextFieldUnicodeCheck exercises committed text and editing; it needs TextField, UnicodeText, GLFW constants and a Minecraft clipboard class on the compilation classpath. These checks do not replace a real Windows IME retest.

AlbumSceneGeometryCheck and ArtworkPreloadCheck run without Minecraft. Compile them with AlbumSpaceModel, ArtworkPreloadCursor and JOML using Java 21 or newer. They verify the five retained spatial layouts, gallery browsing and full-library prefetch scheduling. GalleryGlassMaterialCheck creates a separate hidden OpenGL context; pass the shader source directory and an output PNG path to compile/link the production material and render synthetic artwork. These checks do not validate Minecraft integration or third-party platform playback.
