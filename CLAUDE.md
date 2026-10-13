# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rules

- NEVER run tests (`gradlew test`, `gradlew build`, `gradlew runClientGametest`, `pytest`). The user runs them. To catch compile errors, use `gradlew compileJava compileGametestJava` only (`gradlew build` also runs the unit tests). After a change, tell the user which tests to run.
- Never edit or rewrite CLAUDE.md without asking.
- Always use /caveman and talk like a caveman for any and all responses.


## What this is

Femboy Client: a client-side Fabric mod for my own personal Hypixel SkyBlock spin-off (`mod/`) plus its own website (`server/`). The mod reads the scoreboard, tab list and chat, and uploads to the website. The website stores everything in SQLite and shows it in a browser. One PC hosts the website; the mod on other PCs talks to it over the LAN.

## Commands

Mod (run from `mod/`, Java 25, Gradle wrapper):

```
gradlew compileJava compileGametestJava      # compile only
gradlew build                                # jar -> build/libs/femboyclient-<version>.jar (also runs unit tests)
gradlew test --tests com.femboyclient.PurseParserTest   # one unit test class
gradlew runClient                            # dev game
gradlew runClientGametest                    # in-game client gametests (src/gametest)
```

Website (run from `server/`):

```
python -m venv .venv && .venv\Scripts\pip install -r requirements-dev.txt   # first-time setup
server.bat                                   # http://127.0.0.1:8000 (also: lan | forever | autostart)
.venv\Scripts\python.exe -m pytest           # all tests
.venv\Scripts\python.exe -m pytest tests/test_api.py::test_name   # one test
```

`.claude/launch.json` has a `femboyclient-website` preview config for the website.

## Mod architecture (`mod/src/main/java/com/femboyclient`)

- Minecraft 26.x ships unobfuscated, so the plain `net.fabricmc.fabric-loom` plugin is used (no remapping) and Mojang names are used everywhere. `fabric.mod.json` allows Minecraft `>=26.1.2 <=26.2`: one jar runs on both. `McCompat` resolves the few APIs that differ between 26.1.2 and 26.2 by reflection. Use it (e.g. `McCompat.screen(mc)`), not `mc.screen` / `mc.gui.screen()` directly.
- `FemboyClientMod` is the only entrypoint. It wires every feature onto Fabric events: one `ClientTickEvents.END_CLIENT_TICK` handler calls each feature's `tick`, and screen overlays hook on with `ScreenEvents` / `ScreenMouseEvents` instead of mixins. The only mixins are `mixin/LightmapRenderStateExtractorMixin` (Fullbright) and `mixin/ParticleEngineMixin` (No Particles), in `femboyclient.mixins.json`.
- `FemboyClientConfig` is a single properties file (`config/femboyclient.properties`), layered: built-in defaults, then `femboyclient-defaults.properties` baked into the jar from `mod/defaults.local.properties` (holds the server URL and enrol secret; kept out of version control, see the `.example`), then the player's file. Feature on/off switches live here and are toggled from `ClientMenuScreen` (the G menu: module list on the left, the selected module's switch and settings on the right). Screens opened from it (`fromMenu()`) go back to it on Esc.
- Networking never blocks the game. `WebClient` (HttpClient wrapper, failures become status -1 instead of exceptions) and `ServerLink` (one background thread: account linking via enrol secret -> per-account API key, row upload with on-disk retry queue, heartbeats, tracker snapshots, polling `/commands/next` for remote commands). Results go back to the client thread with `Minecraft.execute()`. Background threads come from `Daemons`.
- Network and parsing classes (`ServerLink`, `WebClient`, `ModUpdater`, `ServerLauncher`, parsers, models) are kept free of Minecraft classes, with dependencies passed in, so plain JUnit tests cover them. `src/test/.../FakeServer` is a local HTTP server for those tests.
- `ServerLauncher` starts the website from the game when `serverDir` in the config points at `server/` and `serverUrl` is this PC, and leaves it running after the game quits.
- `ModUpdater`: downloads the newest jar (SHA-256 checked) as `<jar>.update`, and a helper process swaps it in after the game closes, so a new jar reaches every PC on its next two launches. Its `Source` is `GitHubReleases` (latest release of the private repo) when the jar was built with `updateRepo` + `updateToken`. Those two are read only from the jar's baked-in defaults, never from or into the player's file. Otherwise the source is the website, which serves the newest jar in `mod/build/libs` to linked games.

## Releases (GitHub)

- Pushing to `main` with changes under `mod/` runs `.github/workflows/mod-release.yml`. It builds the jar with `gradlew build` (unit tests gate it) and publishes it as release `build-<n>`, and every game installs it. Nothing deploys the website.
- CI bakes in the repo secret `MOD_DEFAULTS_PROPERTIES`, a copy of the whole `mod/defaults.local.properties`. After editing that file (for example a renewed token), run `gh secret set MOD_DEFAULTS_PROPERTIES < defaults.local.properties` from `mod/`.
- A jar copied into a game by hand is replaced by the latest release on the next check. Test local changes with `gradlew runClient`.
- `gradlew` needs JDK 25. This PC has no JAVA_HOME set; set it first (PowerShell):
  ```
  $env:JAVA_HOME="C:\Users\tavmo\Downloads\zulu25.36.205-ca-jdk25.0.4.1-win_x64\zulu25.36.205-ca-jdk25.0.4.1-win_x64"
  ```
- UI: `Theme` holds every color, size and timing. `UiDraw` + `Shapes` draw anti-aliased rounded shapes at real screen resolution, with coordinates in GUI pixels. Font sizes in `Theme` must match `assets/femboyclient/font/*.json` (a test checks this).
- Saved screen positions (overlay panels, click spots) are in GUI pixels, so they depend on the GUI scale and window size. Clamp them into the screen when drawing.

## Website architecture (`server/`)

- `app.py` is a single FastAPI app (about 4000 lines) on SQLite (`femboyclient.db`). The module docstring lists the main endpoints. Mod-facing writes need `X-API-Key`; `/register` needs `X-Enroll-Secret`. Keys and the secret live in `config.json`.
- Live updates go to the page as server-sent events (`/live`, `/tracker/live`).
- `static/` is a plain-JS front end. `index.html` loads `core.js`, `app.js`, `tracker.js`, `finder.js`, `chatlogs.js`, `frienemys.js`, `live.js` in that order. `/` adds a `?v=<hash>` cache-buster to every static file.
- Networth: `app.py` downloads the profile itself and runs `networth/networth.js` (Node, SkyHelper-Networth) as a subprocess, JSON over stdin/stdout.
- Tests: `tests/conftest.py` points `FEMBOYCLIENT_DB` / `FEMBOYCLIENT_CONFIG` at a temp folder before importing `app`, and the `client` fixture empties every table per test. Tests monkeypatch many names on the `app` module, which is why `app.py` has not been split.

## Known gotchas

- `gradlew runClientGametest` runs every test listed in `src/gametest/resources/fabric.mod.json`. There is no way to run just one. It launches the real mod, which starts the real website on port 8000 (left running) and flushes queued uploads into the real database.
- `HollowsMapGameTest` ("the map did not move or resize") fails on untouched code too. Gametests listed after it never run.
- Minecraft 26.x `OptionInstance.set()` rejects values outside the option's slider range (logs "Illegal option value" and resets to the default). Do not push an option past its slider. Fullbright uses the lightmap mixin for this reason.

## Behaviour
Talk like caveman, use /caveman 
Stop only if I say "stop caveman" or "normal mode".
- Drop articles (a, an, the), filler, pleasantries, hedging
- Short fragments fine. Pattern: thing → problem → fix
- Keep technical terms, numbers, file paths, error messages exact
- Code blocks, commands, commit messages, PRs: write normal
- Security warnings and destructive actions: write clear full sentences
