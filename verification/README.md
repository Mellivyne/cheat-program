# Development preview check

The optional preview check starts a separate development client, creates a test world inside this project's `run/` directory, checks the registered features, and captures the interface and player/model preview.

```powershell
./gradlew.bat runClient -Popen_checks "-Dcheatprogram.dev=open-check"
```

This check targets Minecraft 1.21.4. Its additional Java sources are enabled only with `-Popen_checks` and are excluded from normal release JARs. Screenshots and development runtime data stay local. A successful compile or preview check does not verify every feature on every supported version.

The isolated check also verifies repeated projection key binding, per-line lyric placement, and curved-screen picking after rotation. Curved-screen settings are checked independently across screens, including migration from older settings.

For the standalone geometry regression (no game needed), compile `src/main/java/com/cheatprogram/render/CastSurface.java` and `verification/java/com/cheatprogram/dev/CurvedSurfaceCheck.java`, then run `com.cheatprogram.dev.CurvedSurfaceCheck`. It covers both curve axes, positive/negative angles, flat compatibility, borders and dragging.

The multi-screen regression uses three synthetic capture sessions to check visible-surface selection, back-face and curved picking, click/drag/wheel routing, returning to a previously selected screen, and rejection of stale frames. It checks local command queues; it does not assert that a real external application handled Windows input messages.
