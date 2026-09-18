# PvPTournament

A 4-player last-man-standing tournament plugin for PaperMC 1.21.11 (works on Aternos).

## Building the jar

You need the compiled `.jar` to upload to Aternos. Two ways:

**Option A — on your own PC**

1. Install JDK 21 and Maven.
2. In this folder run: `mvn package`
3. The jar appears at `target/PvPTournament.jar`.

**Option B — GitHub (no installs)**

1. Create a new GitHub repo and upload this whole folder.
2. The included workflow (`.github/workflows/build.yml`) runs automatically.
3. Open the **Actions** tab → newest run → download the `PvPTournament-jar` artifact.

## Installing on Aternos

1. Aternos → **Software** → make sure the server is on **Paper 1.21.11**.
2. Aternos → **Files** → `plugins` folder → **Upload** `PvPTournament.jar`.
3. Start the server. `plugins/PvPTournament/config.yml` is created on first run.

## Commands

All commands need OP (permission `tournament.admin`).

| Command | What it does |
|---|---|
| `/setplayer1 <player>` … `/setplayer4 <player>` | Choose the four participants (saved to config). |
| `/start` (alias `/tstart`) | Wipes tournament data, teleports the 4 players to the arena, runs 5→GO, 30s no-PvP, then live PvP. |
| `/stop` (alias `/tstop`) | Full stop: timers, border, leaderboard, placements, inventories cleared, everyone to the hub. |
| `/reset` (alias `/treset`) | Leaderboard only. Does not stop the round, does not teleport anyone. |
| `/setstartborder <50\|100\|150\|200\|250\|300>` | Starting world border size, used on the next `/start`. |
| `/setshrink <true\|false>` | Turn automatic border shrinking on or off. |
| `/tournament` (alias `/tinfo`) | Shows current state, slots, border settings and placements. |

## How a round runs

1. `/start` — data reset, all 4 players to `657 100 395`, gamemode Survival.
2. Title countdown `5 4 3 2 1 GO!`
3. 30 seconds with PvP blocked, action bar shows `PvP starts in 30 … 1`.
4. PvP enabled. Deaths are tracked:
   - 1st death → **4th place**, sent to `657 163 395`, set to Adventure.
   - 2nd death → **3rd place**, same.
   - 3rd death → **2nd place**, same.
   - Survivor → **1st place**.
5. Winner gets the title `LAST MAN STANDING` + their name.
6. 5 second pause, then `Returning to hub in 5 … 1`, everyone teleports to `657 163 395`, round finishes.

The sidebar leaderboard updates on every elimination:

```
TOURNAMENT LEADERBOARD
1st  -
2nd  -
3rd  -
4th  Player3
```

## buritokaiMC rule

Anyone listed under `creative-on-elimination` in `config.yml` (default: `buritokaiMC`) plays as a
normal participant — counts on the leaderboard, can win, can place 2nd/3rd/4th — but when
eliminated they are put into **Creative** at the hub instead of Adventure. Add or change names in
the config; it is not hardcoded.

## Border shrinking

With `/setshrink true`, the timer starts the moment `GO!` appears:

- wait 50s → `BORDER SHRINKING IN 10 SECONDS`
- 10 → 1 countdown
- border shrinks 50 blocks over 30s
- 20s pause
- repeat until the border is 50 × 50

All of those numbers (50 / 10 / 30 / 20, shrink amount, minimum size) live under `border:` in
`config.yml` if you want to tune them. The border is centred on the arena coordinates.

## Notes

- Coordinates, world name and the grace period are all in `config.yml`. Change them there rather
  than in code.
- `/start` temporarily sets the `doImmediateRespawn` gamerule so eliminated players don't sit on
  the death screen; the old value is restored when the round ends or you run `/stop`.
- `/start`, `/stop` and `/reset` are common command names. If another plugin claims them, use the
  aliases `/tstart`, `/tstop`, `/treset`.
- Death drops are cleared by default (`clear-drops-on-death`). `/start` does not clear inventories
  unless you set `clear-inventory-on-start: true`; `/stop` always does.
