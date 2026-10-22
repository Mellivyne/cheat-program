# Cheat Program

<p align="center"><strong>English</strong> | <a href="docs/README.zh-CN.md">中文简体</a> | <a href="docs/README.zh-TW.md">中文繁體</a> | <a href="docs/README.ja.md">日本語</a> | <a href="docs/README.ko.md">한국어</a> | <a href="docs/README.es.md">Español</a> | <a href="docs/README.fr.md">Français</a> | <a href="docs/README.de.md">Deutsch</a></p>

A customizable Fabric client that brings application screens, spatial sound, particle scenes, and creative tools into your Minecraft world. Build a space that feels like your own, then share your creations through configuration codes.

## Highlights

### In-world screen projection

Place application windows or your desktop on adjustable screens inside Minecraft. Use multiple screens, change their position, size, rotation, and brightness, and choose between world placement and screen-following layouts. Native-resolution and native-frame-rate capture options are available, alongside configurable mouse and keyboard interaction.

Screen capture and application input use Windows native helpers. Compatibility can vary between applications and capture methods.

### Spatial audio

Give a projected application or a selected background application a sound source in the world. Material-aware acoustic sampling models absorption, reflection, scattering, obstruction, and room response, so changing the surroundings can change how sound is heard.

Adjust the listening range, reflections, reverberation, volume, and sampling budget. Cached updates let you balance acoustic detail and performance, while optional sound-ray visualization helps you explore the scene. Application-audio isolation uses a configured virtual audio device, with setup and status controls available in the interface.

### Particle effects and motion paths

Create particle arrangements with native particle previews. Position emitters around the player, adjust their transforms, and animate them with lines, circles, helices, figure-eight paths, waves, spirals, or custom paths. Preview the result before using it in the world.

### Building import and preview

Select and save buildings, preview their placement, and import them into another location or world. Placement tools include position adjustments and air-replacement controls. Server-side placement requires the appropriate permissions.

### Configuration codes

Share a creation without asking someone else to recreate every setting. Dedicated configuration codes support **particle effects, buildings, wearable models, and weapon animations**, including the settings and supported assets needed for that creation.

Codes can preserve details such as placement, scale, rotation, and animation settings. Scoped imports validate the payload and checksum before applying it. Profiles also let you keep different setups for different worlds or activities.

### Music and floating lyrics

Browse music libraries with animated album layouts, search for songs, and display floating lyrics in the world. Customize lyric placement, appearance, transitions, translation display, and player-following behavior. Platform features and playback quality depend on the connected service and your account access.

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

Each Minecraft version has a separate JAR. Install one matching build at a time.

| Minecraft | Java |
| --- | --- |
| 1.21.4, 1.21.10, 1.21.11 | 21 or newer |
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

This project is actively being developed, with continued updates focused on compatibility, performance, interaction, and creative features. Bug reports, ideas, and feedback from real use help guide the work. The current builds compile for the versions listed above; the 1.21.4 interface and player/model preview have also been checked in game. Other version-specific behavior still benefits from testing and feedback.

## License

Original project code is available under the [MIT License](LICENSE). Third-party components retain their own licenses; see [Third-party notices](THIRD_PARTY_NOTICES.md).

If this project interests you, consider leaving a **Star**. It is a simple way to let me know the work is useful and to follow its progress. Thank you for taking a look, trying it out, or sharing an idea.
