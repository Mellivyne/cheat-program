# Changelog

## v1.1

v1.1 expands building and particle creation, adds five music-library layouts, and updates all nine supported Fabric builds. Minecraft **1.20.1** and **1.20.4** are newly supported.

### Added

- **Building analysis:** native 3D block icons with names and quantities, maximum length/width/height, and horizontal-layer inspection with separate material totals for each layer.
- **First-person building visits:** enter an independent preview, move with WASD, fly with Space/Shift, and look around without moving your player in the live world.
- **AI building designer:** connect your own API key and model endpoint, describe a building or attach reference images, preview the generated blocks, and request further revisions. Generated buildings use the same materials, layer analysis and visit controls.
- **AI particle designer:** turn text and reference images into native particle compositions and motion paths, preview and revise the result, and export its independent CPS1 configuration code.
- **AI image attachments** in AI Control, AI Players and both designers, with preview/removal before sending. The two new designers have independent refreshable/searchable model catalogues and OpenAI, DeepSeek or custom endpoint controls. Credentials and image attachments stay out of shared configuration codes.
- **Five additional album layouts:** album floor, floating constellation, spiral staircase, music planet and immersive music gallery. There are eight layouts in total; coverflow remains the default. The gallery combines two-sided artwork and camera navigation with liquid glass that matches the surrounding interface.
- **Progressive artwork preloading for every layout:** visible covers are prioritized, and background disk preloading continues through the library without uploading distant covers into the GPU cache. Visible texture protection and bounded caching reduce unnecessary reloads.

### Fixed

- Modern GUI submission and render-state restoration that could clip or hide inventory icons, held items and other native models.
- World-overlay render-target handling that could cause building selection boxes and placement previews to be overwritten.
- Building tasks staying blocked after switching worlds or servers, and library file operations being cancelled when no world was open.
- Missed short **F6/F7/F8** selection taps; marking now reports the selected coordinates or a useful aim hint.
- Chinese input in custom **26.x AI building/particle fields**, including native IME composition/candidate display, caret positioning and focus cleanup.
- Gallery browsing direction and access to later songs; preloading now follows the viewed area independently of the playing track. Aligned renderer and screen prefetch ranges prevent them from cancelling each other's requests.
- Ore-backfill direction tracking during slow or crouched movement.
- Desktop-audio process-tree resolution, inactive-session routing, stalled-helper recovery and route-status reporting.

### Downloads

Project version: **v1.1**. Loader: **Fabric**. Download the JAR matching your exact Minecraft version and install only one Cheat Program JAR in that instance.

| Minecraft | Project version | Java | JAR |
| --- | --- | --- | --- |
| 1.20.1 | v1.1 | 21+ | `cheat-program-v1.1-fabric-mc1.20.1.jar` |
| 1.20.4 | v1.1 | 21+ | `cheat-program-v1.1-fabric-mc1.20.4.jar` |
| 1.21.4 | v1.1 | 21+ | `cheat-program-v1.1-fabric-mc1.21.4.jar` |
| 1.21.10 | v1.1 | 21+ | `cheat-program-v1.1-fabric-mc1.21.10.jar` |
| 1.21.11 | v1.1 | 21+ | `cheat-program-v1.1-fabric-mc1.21.11.jar` |
| 26.1 | v1.1 | 25+ | `cheat-program-v1.1-fabric-mc26.1.jar` |
| 26.1.1 | v1.1 | 25+ | `cheat-program-v1.1-fabric-mc26.1.1.jar` |
| 26.1.2 | v1.1 | 25+ | `cheat-program-v1.1-fabric-mc26.1.2.jar` |
| 26.2 | v1.1 | 25+ | `cheat-program-v1.1-fabric-mc26.2.jar` |

**The 1.20.1 and 1.20.4 builds also require Java 21 or newer.** Source code and `SHA256SUMS.txt` are attached alongside the nine JARs.

### Verification and known limits

All nine targets passed separate builds, exact Minecraft/Java metadata checks, mixin-content checks and feature-scope checks. Isolated in-game workflows were checked on 1.20.1, 1.20.4 and 1.21.4. Additional CPU tests cover building jobs, Unicode editing, album geometry/picking and artwork preloading, and the gallery material was rendered in a separate hidden OpenGL context.

The latest modern rendering, IME, building-marker and music-page integration changes still need broader gameplay feedback. Paid-provider generation, every external application, Vulkan and exhaustive multiplayer behavior have not been tested. Some applications cannot migrate an already-open audio stream: restart playback or select **CABLE Input** in that application when the interface reports that a restart is needed.

Cheat Program is actively maintained. If you try v1.1, reports of what works and what still needs attention are welcome. If the project interests you, consider giving it a Star to follow its progress. Thank you for trying it.
