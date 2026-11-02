# CineFile for Fabric

Picks camera shots from a Flashback recording and adds them to the timeline while you watch.

## Requirements

- Minecraft 26.3
- Java 25
- Fabric Loader 0.19.5 or newer
- Fabric API
- Flashback 0.43.6 or newer 0.43.x
- Mod Menu

## Build

Run `./gradlew build` from `fabric/`. The jar is in `build/libs/`. Copy it to your instance's `mods/` folder.

Flashback isn't bundled. It's downloaded at build time.

## Use

Open a replay in Flashback and click **Auto Create** in the top row. Clips appear as they are verified. The button shows `Cancel (placed/target)` while it runs.

Existing tracks are kept, and only empty time is used. Each placed clip can be undone on its own.

## What each clip is checked for

The camera path is checked in the real world at that moment in the replay.

| Setting | Meaning |
|---------|---------|
| Reject cuts | Drops clips where the camera jumps between shots. |
| Reject dimension changes | Drops clips where the camera changes dimension. |
| Reject camera through blocks | Drops clips where the camera passes through blocks. Also rejects unloaded chunks, since they can't be checked. |
| Reject blocked view | Drops clips where the player is hidden in more than a quarter of the checks. |
| Reject player off screen | Drops clips where the player is off screen for more than a quarter of the checks. |
| Teleport distance | Largest jump in camera position allowed between checks. |
| Check every N ticks | How often the camera path is sampled. |

## Other tools

- **Cut** removes a range between two keyframes.
- **Delete All** clears the track list.
- Scrolling: the wheel scrolls tracks, Ctrl+wheel zooms, and Shift+wheel pans.

## Cuts and short timelapses

Cut length 0 removes all frames between the two keyframes. Cut length 1 or more plays a normal timelapse of that length.

Short timelapses leave stray frames from the middle of the skipped range. Measured on Flashback 0.43.6's export code for a 1-tick timelapse: 1 frame at 24 or 30 fps, 2-3 at 60, and 5-6 at 120.

Cuts are saved as timelapse tracks, so the replay still opens without this mod. A length-0 cut then plays as a 1-tick timelapse. Export still simulates the ticks inside a cut, so very long cuts export more slowly.

A timelapse also changes how Flashback times later camera moves: its speed carries on after the track ends, until the next speed change. Very fast timelapses can make later cameras stutter. Length 0 cuts are not affected.

## Settings and API keys

Open settings through Mod Menu.

| Setting | Meaning |
|---------|---------|
| API key | Key for your AI provider. Leave blank to use heuristics only. |
| Base URL | Any OpenAI-compatible API. DeepSeek by default. |
| Model | Model name sent to the API. |
| Max spend | Spend cap, estimated at DeepSeek prices. |
| Think harder | Asks the model to reason more before picking shots. |
| Screenshot review | Sends screenshots to the model for review. Off by default. |
| Project | Your project description, sent to the API. |
| Clip length | Target length of each clip. |
| Total duration | Target length of the whole scene. |
| Style | Shot style. `auto` by default. |
| Tries per clip | How many candidates to try per clip. 5 by default. |
| Fill gaps with | What fills empty time when no clip fits. |
| Cut length | Length used by Cut. See above. |

Leaving the key blank uses heuristics only.

## Privacy

The key is stored in plain text in `config/cinefile.json`, owner-only where the OS allows.

Plain `http://` is refused except for localhost.

Shot timings and your project description go to the API. Screenshots go only if Screenshot review is on, and it is off by default.
