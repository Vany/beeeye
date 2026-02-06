# Beeeye v1.0.1

Stereoscopic 3D rendering mod for Minecraft 1.21.11 (NeoForge).
Compatible with all other mods done right.
Renders the game in side-by-side stereo format for use with AR glasses, 3D monitors, or cross-eye/parallel viewing.

## Features

- True stereoscopic 3D with depth parallax for blocks, entities, and particles
- Side-by-side output (left eye | right eye)
- HUD and GUI rendered correctly in both eyes
- Toggle on/off with keybind or command
- Runtime configuration via chat commands

## Requirements

- Minecraft 1.21.11
- NeoForge 21.11+
- Java 21

## Installation

1. Install NeoForge for Minecraft 1.21.11
2. Download the latest Beeeye release
3. Place the `.jar` file in your `mods` folder
4. Launch the game

## Usage

Press `\` (backslash) to toggle stereo mode on/off.

The keybind can be changed in Options > Controls > Key Binds > Beeeye.

### Commands

| Command | Description |
|---------|-------------|
| `/beeeye` | Show current status and settings |
| `/beeeye toggle` | Toggle stereo on/off |
| `/beeeye set eyedistance <value>` | Set eye distance (0.01-1.0 blocks) |
| `/beeeye set convergence <value>` | Set convergence distance (1.0-50.0 blocks) |

## Configuration

Config file: `.minecraft/config/beeeye-client.toml`

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| `eyeDistance` | `0.25` | 0.01-1.0 | Inter-pupillary distance in blocks (IPD) |
| `convergence` | `5.0` | 1.0-50.0 | Zero parallax distance in blocks |

**Eye distance**: Controls stereo separation. Increase for stronger 3D effect.

**Convergence**: Distance where objects appear at screen depth. Objects closer appear in front, farther objects appear behind.

## How It Works

Beeeye uses **off-axis projection stereo** combined with **camera offset**:

1. Each eye renders to a separate half-width framebuffer
2. Camera position shifts left/right by half the IPD
3. Projection matrix uses asymmetric frustum for proper convergence
4. Both eye images are composited side-by-side to the screen
5. HUD renders once at half-width, then alpha-composited to both eyes

This approach provides accurate depth perception while maintaining UI usability.

## Compatibility

Works with most mods. Tested with JourneyMap.

## License

MIT
