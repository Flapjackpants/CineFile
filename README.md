# Flashcine

Python CLI that automates [Flashback](https://modrinth.com/mod/flashback/) cinematic camera keyframes from a replay zip.

## Install

```bash
cd gandroidMelody
python3 -m pip install -e .
```

## Usage

```bash
flashcine \
  --replay "/path/to/flashback/replays/2026-09-15T21_38_50.zip" \
  --clip-length 10 \
  --style locked-dolly \
  --duration 180 \
  --project "building town hall" \
  --timelapse \
  --offline
```

Then **close and reopen** the replay in Flashback so it reloads `editor_states/{uuid}.json`.

### Styles

```bash
flashcine --list-styles
```

- `locked-dolly` — ep34-style locked yaw OTS (default)
- `hero-low` — low angle looking up
- `high-crane` — elevated overview
- `side-track` — strong lateral tracking
- `orbit-reveal` — gentle yaw arc across the shot

### AI (optional)

Without keys (or with `--offline`), selection is heuristic-only ($0).

```bash
export DEEPSEEK_API_KEY=...
export TYPESAFE_API_KEY=...
flashcine --replay ... --duration 180 --timelapse
```

Cost target: well under **$1 per 200 clips** (typically a few cents). Cap with `--max-ai-usd 0.05`.

## Flags

| Flag | Meaning |
|------|---------|
| `--clip-length` | Seconds per shot |
| `--duration` | Target total cinematic seconds |
| `--timelapse` | Fill gaps with 1s (`0→20` tick) timelapse tracks |
| `--editor-dir` | Instance `flashback/` folder if auto-detect fails |
| `--dry-run` | Write `*.json.dry_run` instead of replacing state |
# GandroidMelody
