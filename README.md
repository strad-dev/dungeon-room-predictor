# Strad Rooms Predictor

A client-side Fabric mod (Minecraft 26.2, Java) for Hypixel SkyBlock dungeons. It guesses the
type (and, for normal rooms, the shape) of every unopened room, shows a confidence for each guess,
and predicts where the Blood room is — even with no information. It learns from every run you play.

It uses **only what the dungeon map item shows** (opened rooms, grey `?` rooms, door colours), plus
the floor (sidebar) and puzzle count (tab list). It never reads world blocks of unopened rooms and
**never sends anything to the server**: no packets, no networking, no mixins.

## Commands

| Command | |
|---|---|
| `/srp` | help |
| `/srp maps` | browse every stored map, replay how it opened, re-run the predictor on any step |
| `/srp toggle` | enable / disable |
| `/srp pos <x> <y>`, `/srp size <px>` | move / resize the map |
| `/srp text <scale>`, `/srp head <size>` | label text size, player head size |
| `/srp rooms` | room names on opened rooms (coloured by checkmark) |
| `/srp names`, `/srp names leap` | teammate names; only while holding a Spirit Leap |
| `/srp wither` | predicted wither doors (the rest of the Blood-rush path) |
| `/srp blank` | guesses on cells nobody has seen yet |
| `/srp heat` | red Blood-probability overlay |
| `/srp importdv [position]` | copy Devonian's map look (and HUD position) |
| `/srp stats` | model, accuracy, average map opened |
| `/srp debug`, `/srp debug map`, `/srp debug overlay` | what the mod sees; dump the map item; status line |
| `/srp lr <rate>` | learning rate (default 0.15) |
| `/srp rebuild` | retrain from every stored map, each weighted by how much of it was opened |
| `/srp save`, `/srp reload`, `/srp reset confirm` | model file |

## Learning

The model learns live from each room as it opens, for the current run's predictions. When the run
ends, the saved model learns from the whole run instead, replayed in the order rooms opened, with
every update weighted by the fraction of the map that was opened. `/srp rebuild` (and any change of
feature layout) retrains from scratch on the whole map database the same way.

## Files (`config/roompredictor/`)

- `model.json` — learned weights. Updated live during runs; `/srp reload` picks up hand edits.
- `maps.jsonl` — one line per run: floor, puzzles, the map in a compact text form and the second
  each cell was opened. See `RunRecord.java` for the format.
- `config.json` — HUD settings.

## How it works

See `Predictor.java` (inference), `Model.java` (online learning), `Priors.java` (the generation rules
used as starting weights) and `PredictorService.java` (background thread). Build with
`./gradlew build`; the jar is in `build/libs/`.

Map style inspired by [Devonian](https://github.com/Synnerz/devonian). No Devonian code is used.

Room names come from the room database of [Odin](https://github.com/odtheking/Odin)
(`src/main/resources/assets/roompredictor/rooms.json`), used under the BSD 3-Clause License,
Copyright (c) 2025, odtheking — see `LICENSE-Odin`. Odin's authors do not endorse this mod.
Only rooms the dungeon map shows as opened are identified.
