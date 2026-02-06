# Beeeye

Stereoscopic 3D rendering mod for Minecraft 1.21.1 (NeoForge).

Renders the game in side-by-side stereo format for use with VR headsets, 3D monitors, or cross-eye/parallel viewing.

## Features

- True stereoscopic 3D with depth parallax for blocks, entities, and particles
- Side-by-side output (left eye | right eye)
- HUD and GUI rendered correctly in both eyes
- Toggle on/off with a keybind

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1+
- Java 21

## Installation

1. Install NeoForge for Minecraft 1.21.1
2. Download the latest Beeeye release
3. Place the `.jar` file in your `mods` folder
4. Launch the game

## Usage

Press `\` (backslash) to toggle stereo mode on/off.

The keybind can be changed in Options > Controls > Key Binds > Misc.

## Configuration

Config file: `.minecraft/config/beeeye-client.toml`

| Setting | Default | Description |
|---------|---------|-------------|
| `eyeDistance` | `0.25` | Inter-pupillary distance in blocks. Increase for stronger 3D effect. |

## How It Works

Beeeye uses **off-axis projection stereo** combined with **camera offset**:

1. Each eye renders to a separate half-width framebuffer
2. Camera position shifts left/right by half the IPD
3. Projection matrix uses asymmetric frustum for proper convergence
4. Both eye images are composited side-by-side to the screen
5. HUD renders once at half-width, then copied identically to both eyes

This approach provides accurate depth perception while maintaining UI usability.

## Compatibility

Works with most mods. Known limitations:

- Some mod overlays that bypass the standard rendering pipeline may not display correctly in stereo mode

## License

MIT

## Credits

Developed with assistance from Claude (Anthropic).
