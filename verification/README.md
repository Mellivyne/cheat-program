# Development preview check

The optional preview check starts a separate development client, creates a test world inside this project's `run/` directory, checks the registered features, and captures the interface and player/model preview.

```powershell
./gradlew.bat runClient -Popen_checks "-Dcheatprogram.dev=open-check"
```

This check targets Minecraft 1.21.4. Its additional Java sources are enabled only with `-Popen_checks` and are excluded from normal release JARs. Screenshots and development runtime data stay local. A successful compile or preview check does not verify every feature on every supported version.
