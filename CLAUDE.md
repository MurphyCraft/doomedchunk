# Doomed Chunks — Fabric Mod for Minecraft 26.1.2

## Project Overview
This is a server-side Fabric mod for Minecraft Java Edition 26.1.2.

**Core mechanic:** At dusk each in-game day, the exact chunk each online player is standing in gets scheduled for destruction. After a 5-minute countdown with escalating warnings, every block in those chunks is replaced with air — players inside are NOT teleported and face the consequences.

---

## Tech Stack
| Tool | Version |
|------|---------|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.18.5 |
| Fabric API | 0.119.0+26.1.2 |
| Loom | 1.15-SNAPSHOT |
| Gradle | 9.4.0 |
| Java | 25 |
| IDE | IntelliJ IDEA 2025.3+ |

> ⚠️ MC 26.1+ uses **official Mojang mappings** (unobfuscated). No Yarn mappings. No `modImplementation` — use `implementation`. No `remapJar` — use `jar`.

---

## Build Instructions

```bash
# Build the mod jar
./gradlew build

# Output jar will be at:
build/libs/doomedchunks-1.0.0.jar

# Clean build
./gradlew clean build

# Refresh dependencies if something looks wrong
./gradlew --refresh-dependencies
```

The Gradle JVM **must be Java 25**. Configure this in IntelliJ under:
`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM`

---

## Project Structure

```
src/main/java/com/doomedchunks/
├── DoomedChunksMod.java          # Mod entrypoint — registers the tick listener
├── DuskChunkScheduler.java       # Core logic: dusk detection, warnings, destruction
└── ScheduledChunkDeletion.java   # Data class: holds doomed chunks + destroy timestamp

src/main/resources/
├── fabric.mod.json               # Mod metadata
└── assets/doomedchunks/lang/
    └── en_us.json                # Language strings
```

---

## How It Works

### Tick-based lifecycle (DuskChunkScheduler.java)
1. **Every server tick**, check the overworld day time
2. When `dayTime % 24000 >= 13000` (dusk) and not yet triggered today → `onDusk()`
3. `onDusk()` records each player's `ChunkPos`, creates a `ScheduledChunkDeletion`, schedules destruction at `currentTick + 6000` (5 minutes)
4. Each tick, check pending deletions for countdown thresholds and send warnings
5. At `ticksRemaining <= 0` → call `destroyChunk()` for each doomed chunk

### Warning system (two layers)
- **Global broadcast** — sent to all players on the server
- **Personal warning** — sent only to players currently standing inside a doomed chunk

Warning thresholds: `6000t (5min) → 3600t (3min) → 1200t (1min) → 600t (30sec) → 200t (10sec) → 100t (5sec)`

### Chunk destruction (destroyChunk)
- Iterates all 16×16 columns from `minBuildHeight` to `maxBuildHeight`
- Sets every non-air block to `Blocks.AIR` with flag `3` (update + notify)
- Marks chunk as unsaved to force persistence
- Players inside are **not teleported** — they fall into the void

---

## Key Classes & Methods

### DuskChunkScheduler
| Method | Purpose |
|--------|---------|
| `onEndTick(server)` | Main tick loop — dusk detection + pending deletion processing |
| `onDusk(server, overworld, tick)` | Captures player chunks, creates scheduled deletion, sends initial warnings |
| `sendCountdownWarnings(server, deletion, ticksRemaining)` | Fires global + personal warnings at each threshold |
| `sendPersonalWarnings(server, deletion, threshold)` | Finds players still in doomed chunks and sends targeted messages |
| `executeDestruction(server, overworld, deletion)` | Broadcasts destruction and calls destroyChunk for each chunk |
| `destroyChunk(level, chunkPos, minY, maxY)` | Fills the chunk with air block by block |

---

## Known Issues / TODOs
- `fabric_api_version` in `gradle.properties` may need updating — verify the latest at https://fabricmc.net/develop
- No config file yet — delay (6000 ticks), dusk threshold (13000), and warning times are hardcoded constants in `DuskChunkScheduler.java`
- No per-world support — currently only tracks the overworld (`Level.OVERWORLD`)
- Chunk destruction iterates block-by-block which may cause a brief TPS spike on destruction for large servers — consider chunking the work across multiple ticks if needed
- No persistence — if the server restarts mid-countdown, the scheduled deletion is lost

## Suggested Improvements (if asked)
1. **Config file** — expose delay, dusk threshold, and warning messages via a `doomedchunks.json` config
2. **Radius option** — optionally destroy N×N chunks around each player instead of just their exact chunk
3. **Nether/End support** — track players across all dimensions
4. **Admin command** — `/doomedchunks status` to list pending deletions, `/doomedchunks cancel` to abort
5. **Async block fill** — spread the air-fill across multiple ticks to avoid TPS impact
6. **Sound effect** — play a rumble/explosion sound at the chunk location on destruction

---

## Testing the Mod
1. Set up a local Fabric server for MC 26.1.2
2. Drop the built jar into `mods/`
3. Also add the [Fabric API jar](https://modrinth.com/mod/fabric-api) for 26.1.2
4. Start the server and join
5. Use `/time set 12500` to fast-forward to near dusk and watch the warnings trigger
6. Use `/time set 13000` to trigger dusk immediately

---

## Resources
- [Fabric Docs](https://docs.fabricmc.net)
- [Fabric for MC 26.1 blog post](https://fabricmc.net/2026/03/14/261.html)
- [Fabric develop versions](https://fabricmc.net/develop)
- [Fabric Example Mod (26.1.2 branch)](https://github.com/FabricMC/fabric-example-mod/tree/26.1.2)
