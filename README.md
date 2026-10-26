# Cheat Program

<p align="center"><strong>English</strong> | <a href="docs/README.zh-CN.md">中文简体</a> | <a href="docs/README.zh-TW.md">中文繁體</a> | <a href="docs/README.ja.md">日本語</a> | <a href="docs/README.ko.md">한국어</a> | <a href="docs/README.es.md">Español</a> | <a href="docs/README.fr.md">Français</a> | <a href="docs/README.de.md">Deutsch</a></p>

https://github.com/user-attachments/assets/0aa8ea54-37b5-4109-b15d-543e4fc63c19

https://github.com/user-attachments/assets/b7831d49-4c18-4f0f-940b-68d78249b736

A customizable Fabric client that brings application screens, spatial sound, particle scenes, and creative tools into your Minecraft world. Build a space that feels like your own, then share your creations through configuration codes.

## Highlights

### In-world screen projection

Place application windows or your desktop on adjustable screens inside Minecraft. Use multiple screens, change their position, size, rotation, and brightness, and choose between world placement and screen-following layouts. Native-resolution and native-frame-rate capture options are available, alongside configurable mouse and keyboard interaction. Each screen can also curve horizontally or vertically, with an adjustable inward or outward angle and smoothness. Mouse picking follows the curved surface.

Screen capture and application input use Windows native helpers. Compatibility can vary between applications and capture methods.

### Spatial audio

Give a projected application or a selected background application a sound source in the world. Material-aware acoustic sampling models absorption, reflection, scattering, obstruction, and room response, so changing the surroundings can change how sound is heard.

Adjust the listening range, reflections, reverberation, volume, and sampling budget. Cached updates let you balance acoustic detail and performance, while optional sound-ray visualization helps you explore the scene. Application-audio isolation uses a configured virtual audio device, with setup and status controls available in the interface.

### Particle effects and motion paths

Create particle arrangements with native particle previews. Position emitters around the player, adjust their transforms, and animate them with lines, circles, helices, figure-eight paths, waves, spirals, or custom paths. Preview the result before using it in the world.

The AI particle designer can turn a text request and reference images into a native particle composition. Preview its rounds and motion paths, request changes, and copy the matching independent configuration code. Add it to your editor when ready; existing effects stay available.

### Building import, analysis and AI design

Import and preview buildings at their original proportions. The material list shows native 3D block icons, names and counts, alongside the maximum length, width and height. Inspect one horizontal layer at a time and see that layer's own block counts.

Enter a building in a separate first-person preview, move with WASD, fly up with Space and down with Shift, and look around with the mouse. The preview camera is independent of your player in the live world.

The AI architect connects directly to your chosen model provider using your own API key. Describe a building, attach reference images, inspect the generated blocks, and keep requesting revisions before saving or importing. Generated buildings use the same materials, layer analysis and visit controls. There is no fixed design-size preference; available memory, the Minecraft world and provider limits still apply.

### Configuration codes

Share a creation without asking someone else to recreate every setting. Dedicated configuration codes support **particle effects, buildings, wearable models, and weapon animations**, including the settings and supported assets needed for that creation.

Codes can preserve details such as placement, scale, rotation, and animation settings. Scoped imports validate the payload and checksum before applying it. Profiles also let you keep different setups for different worlds or activities.

### Music and floating lyrics

Browse music libraries with animated album layouts, search for songs, and display floating lyrics in the world. Eight layouts include coverflow, a ring, an album wall, an album floor, a floating constellation, a spiral staircase, a music planet, and a liquid-glass gallery. Artwork preloads around the visible area while background disk caching progresses through the library. Customize lyric placement, appearance, transitions, translation display, and player-following behavior. Platform features and playback quality depend on the connected service and your account access.

### AI control and AI players

Connect a compatible model endpoint to direct bounded actions in your single-player world or on a multiplayer server from the **AI Control** tab. The **AI Players** tab lets you manage separate characters, personalities, model connections, and supported voice settings. All AI workspaces support image attachments with preview and removal before sending. Images are sent only to the endpoint selected for that request. Each connection uses your own provider credentials; image understanding and other model features depend on that provider and your account access. Credentials and image attachments are excluded from configuration codes.

### Models, visuals, and everyday tools

- Import static OBJ, glTF, or GLB models, position them around your player, and preview them alongside your current skin in a shared 3D space.
- Customize the liquid-glass control panel, rebind its opening key, and arrange HUD elements, visual effects, capes, and other cosmetic features.
- Use robots and training robots, chat tools, automatic equipment selection, inventory organization, and container item transfer.
- Keep useful information nearby with keystrokes, information panels, a rear-view mirror, network and performance trends, zoom, time controls, directional sound indicators, and death-point recovery routes.

## Getting started

1. Install Fabric Loader for your exact Minecraft version.
2. Download the matching JAR from [Releases](https://github.com/Mellivyne/cheat-program/releases) and place it in that game instance's `mods` folder.
3. Launch Minecraft and press **Right Shift** to open the control panel. You can assign another key in settings.
4. Enable the features you want, adjust their settings, and save a profile.

Each Minecraft version has a separate JAR. Install one matching build at a time. See the [v1.1 release notes](CHANGELOG.md) for additions, fixes and verification details.

| Minecraft | Java |
| --- | --- |
| 1.20.1, 1.20.4, 1.21.4, 1.21.10, 1.21.11 | 21 or newer |
| 26.1, 26.1.1, 26.1.2, 26.2 | 25 or newer |

Settings are stored locally in `config/cheatprogram-open/`. Sign in to music services through their supported login flow when needed.

## Build from source

The repository includes the Gradle wrapper and version-specific source layers. With Python 3 and a compatible Gradle runtime JDK available, run on Windows:

```powershell
python tools/build_fabric.py 1.21.4
python tools/package_fabric.py
python tools/package_source.py
```

Run `python tools/build_fabric.py` without a version to build all supported profiles. Packaged JARs are written to `outputs/fabric/<version>/`. See [the compatibility matrix](compatibility/matrix.json) for exact version profiles and Java toolchains.

Native capture and audio helpers include their adjacent C# sources and PowerShell rebuild scripts under `src/main/resources/assets/cheatprogram/`. Optional development preview checks are described in [the verification guide](verification/README.md).

## Ongoing development

This project is actively being developed, with continued updates focused on compatibility, performance, interaction, and creative features. Bug reports, ideas, and feedback from real use help guide the work. Builds are verified separately for the exact Minecraft versions listed above. Isolated runtime checks cover the building, material, visit, AI image and particle workflows on 1.20.1, 1.20.4 and 1.21.4; complete gameplay, external applications and paid model-provider requests still benefit from testing and feedback.

## License

The current **updated v1.1** revision is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE). You may use, modify and share it for the purposes permitted by that license; commercial use is not granted. Historical MIT-covered material retains its original terms, including MIT’s requirement to keep copyright and permission notices in redistributed copies or substantial portions. See [licensing and required project notice](LICENSING.md) for the revision boundary and [third-party notices](THIRD_PARTY_NOTICES.md) for components with their own licenses.

If this project interests you, consider leaving a **Star**. It is a simple way to let me know the work is useful and to follow its progress. Thank you for taking a look, trying it out, or sharing an idea.
