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

Open a replay in Flashback and click **Auto Create** in the top row. A dialog shows the clip count, estimated cost, and a budget for this run. Click **Start** to begin. Clips appear as they are verified. The button shows `Cancel (placed/target)` while it runs. The budget starts at **Max spend** and does not change that setting.

Existing tracks are kept, and only empty time is used. Each placed clip can be undone on its own.

## What each clip is checked for

The camera path is checked in the real world at that moment in the replay.

| Setting | Meaning |
|---------|---------|
| Reject cuts | Drops clips where the player teleports. |
| Reject dimension changes | Drops clips where the player changes dimension. |
| Reject camera through blocks | Drops clips where the camera passes through blocks, sits in glass, or comes within 0.2 blocks of a solid surface. Also rejects unloaded chunks, since they can't be checked. |
| Reject blocked view | Drops clips where the player is hidden in more than a quarter of the checks. |
| Reject player off screen | Drops clips where the player is off screen for more than a quarter of the checks. |
| Teleport distance | How far the player must jump, in blocks, to count as a teleport. |
| Check every N ticks | How often the camera path is sampled. |

## Other tools

- **Cut** opens a menu with **Remove all cuts** and **Reset cuts**. Each asks first. Reset removes all cuts and adds one in each gap of 5+ ticks between camera tracks, using the current cut length. Each action is one undo step. Undo restores cuts but not their names, matching Delete All's limit.
- To add a cut, click **Add Element** in the timeline, then **Cut**. It runs from the playhead to 5 seconds later. Drag its keyframes to change the range.
- **Delete All** asks first, then removes every track from the current scene and clears the export range. Undo restores the tracks, but not the range or custom track names.
- Scrolling: the wheel scrolls tracks, Ctrl+wheel zooms, and Shift+wheel pans.

## Cuts and short timelapses

Cut length 0 removes all frames between the two keyframes. Cut length 1 or more plays a normal timelapse of that length.

Sounds inside a cut are dropped in preview and export, including sounds from note blocks, pistons, and other block and entity events. Particles are cleared when a cut ends, including any from before it. A jukebox song that starts inside a cut starts when the cut ends, at the position it would have reached. A song already playing when a cut starts keeps its position, so it is out of sync after the cut.

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
| Max spend | Default budget for each run, estimated at DeepSeek prices. |
| Think harder | Lets the model use its default reasoning effort instead of low, which costs more. |
| Screenshot review | Sends screenshots to the model for review. Off by default. |
| Project | Your project description, sent to the API. |
| Clip length | Target length of each clip. |
| Total duration | Target length of the whole scene. |
| Style | Shot style. `auto` by default. `straight-dolly` moves level and straight, forward or back, trying both. `top-down` looks straight down and moves slightly up or down, trying both; focus levels don't change it. |
| Focus level | `Auto` tries player center, eye level, high angle, and low angle, then keeps the best shot that passes checks. You can also fix one of those levels or choose the style preset. |
| Focus player | `auto` compares every player the recording saw, shot by shot. A player can only be filmed while they were near the recording player. Enter a UUID to follow one player; a UUID absent from the replay stops the run with an error. |
| Tries per clip | How many candidates to try per clip. 5 by default; Auto also tries each candidate at every focus level. |
| Plain camera elements | Off by default. When Auto Create places a clip, removes the `CineFile: ` name from that clip and earlier CineFile clips in the scene, so they show as normal Camera elements. Each clip stays its own track. |
| Fill gaps with | What goes between placed clips: a cut, a 1-second timelapse, or nothing. |
| Cut length | Length in ticks of newly made cuts. See above. |

Leaving the key blank uses heuristics only.

Camera distance, side offset, and orbit adjust to the replay's FOV, using 53 degrees as the reference. The replay uses Flashback's FOV override when enabled, or the game's FOV otherwise.

## Privacy

The key is stored in plain text in `config/cinefile.json`, owner-only where the OS allows.

Plain `http://` is refused except for localhost.

Shot timings and your project description go to the API. Screenshots go only if Screenshot review is on, and it is off by default.
