# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It is also the project README.

## Rules

- NEVER run tests (`gradlew test`, `gradlew build`, `gradlew runClientGametest`, `pytest`). The user runs them. To catch compile errors, use `gradlew compileJava compileGametestJava` only (`gradlew build` also runs the unit tests). After a change, tell the user which tests to run.
- Never edit or rewrite CLAUDE.md without asking.
- Always use /caveman and talk like a caveman for any and all responses. /caveman on always.

## What this is

Femboy Client: a client-side Fabric mod for Minecraft **26.2** (one jar also runs on 26.1.2; Fabric Loader 0.19.5, Fabric API 0.161.0+26.2) for my own personal Hypixel SkyBlock spin-off (`mod/`), plus its own website (`server/`). The mod reads the scoreboard, tab list and chat, and uploads to the website. The website stores everything in SQLite and shows it in a browser. One PC hosts the website; the mod on other PCs talks to it over the LAN.

## Mod features

Press **G** in game for the menu: modules on the left in two tabs (**All**, **Dungeons**), the selected module's switch and settings on the right.

General modules:

| Module | What it does |
|---|---|
| Scanner | Always on. Reports every player in your lobby, all player chat (yours too) and who joins your party to the website. |
| MansionFinder | Scans loaded Crystal Hollows chunks for the Mansion, shows it on a movable, resizable corner map and in chat. Optional render boost. |
| Lobby Networth | Glass screen of everyone in your lobby: face, rank, networth, Discord (click to copy), times met, chat log, 3D model, search. |
| Frienemys | Friend/enemy tags synced with the website. A tagged player in your lobby shows top-left, green or red. |
| Auto Conversation | Clicks the right option of a Hypixel NPC dialog in the background. |
| Fullbright | Maximum brightness, past the gamma slider. |
| Auto Sprint | Always sprint. |
| Looped Clicker | Click controls on open containers. Keeps clicking one spot (left/right, adjustable delay) until stopped. Never starts on its own. |
| No Particles | Hides every particle. |
| HandMan | Size, position, yaw/pitch/roll and swing speed of the held item. Ignore Haste, Block Hit, No Equip Reset, No Swing, No Block. |
| Copy Chat | Right click a chat message to copy it; optional "send the copied message". |
| Hide Players | Hides players, all or within a distance. Click Through, Only Devs (ss / fourth device). |
| Chest ESP | Chams or outline on chests; only dungeon / only Crystal Hollows, hide clicked, color. |
| Camera | No front camera, third person distance, camera clip, Freelook (hold or toggle). |
| Server Display | Ping, TPS and FPS on screen, in a row or stacked; position and size in the menu. |
| Chat Commands | `!` commands in party, guild and private chat (`!help` lists them): `!warp`, `!warptransfer`, `!allinvite`, `!pt`, `!dt`, `!f`/`!m`, `!t`, `!inv`. `/blacklist add\|remove\|clear\|list`. |
| No Debuff | No Blindness, No Portal Effect, No Shield Particle, No Water FOV, No Fire Overlay, No Push, See Through Blocks. |

Dungeon modules (ported from OdinClient 1.2.5.beta7):

| Module | What it does |
|---|---|
| Ghost Blocks | Ghost block on key hold (speed, range, skulls), plus preset ghost blocks. Can be dungeon-only. |
| Key Highlight | Box through walls around the Wither Key (black) and Blood Key (red). |
| Teammate Highlight | Outline and name tag on teammates; class, only when visible, in boss. |
| Secret Hitboxes | Full-block hitboxes for levers, buttons, essence skulls, chests. |
| Puzzle Solvers | Water Board (lever order, tracer), Teleport Maze (exit pad), Tic Tac Toe (best move), Ice Fill (path), Blaze (kill order). Each has a switch and Reset. |

Always on, no menu entry:

- Live view: small picture of the game screen to the website's Live tab. With `liveInput` on in the config, the page can also click, scroll, pick a hotbar slot and type.
- Remote commands: the website can make a linked game quit or say a chat message (never a `/` command).
- Player Finder hops: given a target by the website, the game hops lobbies (`/warp`, `/is`, …) until the target is in the tab list.
- Crystal Hollows structures: reports where you stand inside Mines of Divan, Jungle Temple etc., so the website can locate each one.
- Auto update and website launcher (see `ModUpdater` and `ServerLauncher` below).

## Website features

Tabs on `http://127.0.0.1:8000`:

- **Players**: linked accounts and online status; worlds seen; players in familiarity tiers (sightings under 4 hours apart are one meeting); player search with every meeting, networth, rank, Discord, chat; alert log when someone you met before shows up.
- **Player Finder**: pick a player and world; a linked game hops lobbies until they appear (one command every 5 seconds, hop limit).
- **Chat Logs**: every player chat line seen, by day.
- **Frienemys**: add, edit and remove friend/enemy tags; every game picks them up.
- **Live** (only on the PC running the website): live picture of each game, optional input.

## Commands

Mod (run from `mod/`, Java 25, Gradle wrapper):

```
gradlew compileJava compileGametestJava      # compile only
gradlew build                                # jar -> build/libs/femboyclient-<version>.jar (also runs unit tests)
gradlew test --tests com.femboyclient.PurseParserTest   # one unit test class
gradlew runClient                            # dev game
gradlew runClientGametest                    # in-game client gametests (src/gametest)
```

`gradlew` needs JDK 25. This PC has no JAVA_HOME set; set it first (PowerShell):

```
$env:JAVA_HOME="C:\Users\tavmo\Downloads\zulu25.36.205-ca-jdk25.0.4.1-win_x64\zulu25.36.205-ca-jdk25.0.4.1-win_x64"
```

Website (run from `server/`):

```
python -m venv .venv && .venv\Scripts\pip install -r requirements-dev.txt   # first-time setup
server.bat                                   # http://127.0.0.1:8000, this PC only
server.bat lan                               # reachable from other PCs on the network (home only)
server.bat forever                           # like lan, restarts itself if it exits (logs in server.log)
server.bat autostart                         # run once: Windows starts "forever" at every login
.venv\Scripts\python.exe -m pytest           # all tests
.venv\Scripts\python.exe -m pytest tests/test_api.py::test_name   # one test
```

`.claude/launch.json` has a `femboyclient-website` preview config for the website.

## Mod architecture (`mod/src/main/java/com/femboyclient`)

- Minecraft 26.x ships unobfuscated, so the plain `net.fabricmc.fabric-loom` plugin is used (no remapping) and Mojang names are used everywhere. `fabric.mod.json` allows Minecraft `>=26.1.2 <=26.2`: one jar runs on both. `McCompat` resolves the few APIs that differ between 26.1.2 and 26.2 by reflection. Use it (e.g. `McCompat.screen(mc)`), not `mc.screen` / `mc.gui.screen()` directly.
- `FemboyClientMod` is the only entrypoint. It wires every feature onto Fabric events: one `ClientTickEvents.END_CLIENT_TICK` handler calls each feature's `tick`, and screen overlays hook on with `ScreenEvents` / `ScreenMouseEvents` instead of mixins. Mixins are only for what no Fabric event covers; they live in `mixin/` (listed in `femboyclient.mixins.json`) and almost all use `require = 0`, so a renamed method in another game version only switches that one feature off. `mixin26/` holds mixins for classes that exist only in 26.2 (`Hud`), in the optional `femboyclient-26_2.mixins.json`.
- Modules ported from OdinClient 1.2.5.beta7 (Ghost Blocks, Key Highlight, Teammate Highlight, Secret Hitboxes, Puzzle Solvers, Camera, Server Display, Chat Commands, No Debuff, Chest ESP, Hide Players, Copy Chat) keep Odin's settings and behaviour. Their shared dungeon state is in `Dungeons` (floor, boss, teammates from the tab list, current room). The room is found by hashing the block column at the middle of the room's cell and looking it up in `resources/femboyclient/odin/rooms.json`, which is copied from Odin's 26.2 branch because the 1.8 cores do not match 26.x blocks. Puzzle data (`watertimes.json`, `icefill*.json`) is in the same folder, with Odin's BSD-3 license. World boxes, lines and text are drawn with the game's gizmos from the client tick (`WorldDraw`). Ping and TPS come from `ServerStats`.
- `FemboyClientConfig` is a single properties file (`config/femboyclient.properties`), layered: built-in defaults, then `femboyclient-defaults.properties` baked into the jar from `mod/defaults.local.properties` (holds the server URL and enrol secret; kept out of version control, see the `.example`), then the player's file. Feature on/off switches live here and are toggled from `ClientMenuScreen` (the G menu: module list on the left, the selected module's switch and settings on the right). Screens opened from it (`fromMenu()`) go back to it on Esc.
- Networking never blocks the game. `WebClient` (HttpClient wrapper, failures become status -1 instead of exceptions) and `ServerLink` (one background thread: account linking via enrol secret -> per-account API key, row upload with on-disk retry queue, heartbeats, tracker snapshots, polling `/commands/next` for remote commands). Results go back to the client thread with `Minecraft.execute()`. Background threads come from `Daemons`.
- Network and parsing classes (`ServerLink`, `WebClient`, `ModUpdater`, `ServerLauncher`, parsers, models) are kept free of Minecraft classes, with dependencies passed in, so plain JUnit tests cover them. `src/test/.../FakeServer` is a local HTTP server for those tests.
- `ServerLauncher` starts the website from the game when `serverDir` in the config points at `server/` and `serverUrl` is this PC, and leaves it running after the game quits.
- `ModUpdater`: downloads the newest jar (SHA-256 checked) as `<jar>.update`, and a helper process swaps it in after the game closes, so a new jar reaches every PC on its next two launches. Its `Source` is `GitHubReleases` (latest release of the private repo) when the jar was built with `updateRepo` + `updateToken`. Those two are read only from the jar's baked-in defaults, never from or into the player's file. Otherwise the source is the website, which serves the newest jar in `mod/build/libs` to linked games.
- UI: `Theme` holds every color, size and timing. `UiDraw` + `Shapes` draw anti-aliased rounded shapes at real screen resolution, with coordinates in GUI pixels. Font sizes in `Theme` must match `assets/femboyclient/font/*.json` (a test checks this).
- Saved screen positions (overlay panels, click spots) are in GUI pixels, so they depend on the GUI scale and window size. Clamp them into the screen when drawing.

## Releases (GitHub)

- Pushing to `main` with changes under `mod/` runs `.github/workflows/mod-release.yml`. It builds the jar with `gradlew build` (unit tests gate it) and publishes it as release `build-<n>`, and every game installs it. Nothing deploys the website.
- CI bakes in the repo secret `MOD_DEFAULTS_PROPERTIES`, a copy of the whole `mod/defaults.local.properties`. After editing that file (for example a renewed token), run `gh secret set MOD_DEFAULTS_PROPERTIES < defaults.local.properties` from `mod/`.
- A jar copied into a game by hand is replaced by the latest release on the next check. Test local changes with `gradlew runClient`.

## Website architecture (`server/`)

- `app.py` is a single FastAPI app (about 4000 lines) on SQLite (`femboyclient.db`). The module docstring lists the main endpoints. Mod-facing writes need `X-API-Key`; `/register` needs `X-Enroll-Secret`. Keys and the secret live in `config.json`.
- Endpoint groups: `/register`, `/heartbeat`, `/players`; `/tracker/*` (snapshots, chat, party, search, overview, worlds, frienemies); `/finder/*` (Player Finder); `/commands/*` and `/api/accounts/{account}/command` (remote commands); `/live/*`, `/liveview/*` (live view); `/mod/latest.json` (updater); `/health`.
- Live updates go to the page as server-sent events (`/live`, `/tracker/live`).
- `static/` is a plain-JS front end. `index.html` loads `core.js`, `app.js`, `tracker.js`, `finder.js`, `chatlogs.js`, `frienemys.js`, `live.js` in that order. `/` adds a `?v=<hash>` cache-buster to every static file.
- Networth: `app.py` downloads the profile itself and runs `networth/networth.js` (Node, SkyHelper-Networth) as a subprocess, JSON over stdin/stdout.
- Tests: `tests/conftest.py` points `FEMBOYCLIENT_DB` / `FEMBOYCLIENT_CONFIG` at a temp folder before importing `app`, and the `client` fixture empties every table per test. Tests monkeypatch many names on the `app` module, which is why `app.py` has not been split.

## Known gotchas

- `gradlew runClientGametest` runs every test listed in `src/gametest/resources/fabric.mod.json`. There is no way to run just one. It launches the real mod, which starts the real website on port 8000 (left running) and flushes queued uploads into the real database.
- `HollowsMapGameTest` ("the map did not move or resize") fails on untouched code too. Gametests listed after it never run.
- Minecraft 26.x `OptionInstance.set()` rejects values outside the option's slider range (logs "Illegal option value" and resets to the default). Do not push an option past its slider. Fullbright uses the lightmap mixin for this reason.

## Credits

Dungeon modules, room data and puzzle data: OdinClient (BSD-3-Clause). Networth: SkyHelper-Networth.

## Behaviour
Talk like caveman, use /caveman 
Stop only if I say "stop caveman" or "normal mode".
- Drop articles (a, an, the), filler, pleasantries, hedging
- Short fragments fine. Pattern: thing → problem → fix
- Keep technical terms, numbers, file paths, error messages exact
- Code blocks, commands, commit messages, PRs: write normal
- Security warnings and destructive actions: write clear full sentences
