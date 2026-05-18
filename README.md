# Doomed Chunks

A **server-side** [Fabric](https://fabricmc.net/) mod for Minecraft Java Edition **26.1.2**.

> Every in-game day at dusk, the exact chunk each online player is standing in
> is marked for destruction. A 5-minute countdown begins with escalating
> warnings — broadcast to the whole server, plus a personal alert and a red
> boss bar for anyone still standing on doomed ground. When the timer hits
> zero, every block in those chunks is replaced with air. Players inside are
> **not** teleported. They fall.

## Features

- Automatic dusk detection — arms once per in-game day
- 5-minute countdown with warnings at 5m / 3m / 1m / 30s / 10s / 5s
- Global broadcasts **and** personal warnings for players in danger
- Persistent red **"You Are In A Doomed Chunk"** boss bar (top of screen) while
  you stand on doomed ground
- Server-side only — players don't install anything

## Requirements

| | Version |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.18.5 |
| Fabric API | 0.149.0+26.1.2 |
| Java (runtime **and** build) | 25 |

> Minecraft 26.1+ uses official Mojang mappings — Java 25 is mandatory both to
> build the mod and to run the server.

## Install

1. Download `doomedchunks-<version>.jar` from the
   [Releases](https://github.com/MurphyCraft/doomedchunk/releases) page.
2. Drop it **and** the matching [Fabric API](https://modrinth.com/mod/fabric-api)
   jar into your server's `mods/` folder.
3. Start the server with **Java 25** and join.

## Build from source

Requires JDK 25 on the build machine.

```bash
./gradlew build
```

The mod jar is produced at `build/libs/doomedchunks-<version>.jar`
(the `-sources` jar alongside it is not needed to run the mod).

## Usage

The mod is always active once loaded. To trigger the event on demand, run a
time command from the server console or as an OP in chat:

- `/time set 12500` — just before dusk; watch the countdown ramp up
- `/time set 13000` — trigger dusk immediately

It arms once per in-game day; advance to the next day (`/time add 24000`) to
run it again.

> ⚠️ Block destruction is **permanent**. Do not test on a world you care about.

## Releasing (maintainers)

Pushing a tag of the form `v*` builds the jar and publishes a GitHub Release
with the jar attached. The version baked into the jar comes from
`mod_version` in `gradle.properties`, so bump it before tagging:

```bash
# set mod_version=1.2.0 in gradle.properties, commit, then:
git tag v1.2.0
git push origin v1.2.0
```

See [.github/workflows/release.yml](.github/workflows/release.yml).

## License

[MIT](LICENSE) © 2026 MurphyCraft
