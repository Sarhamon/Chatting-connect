**English** | [한국어](README.ko.md)

# Stream Chat Connect

A Minecraft mod that relays Chzzk · Twitch live-stream chat into the in-game chat.

**Displays stream chat, donations, and emotes in the in-game chat in real time.** (Client-side mod)

> SOOP (AfreecaTV) integration is also implemented, but is **disabled in the current release** because mission-donation handling has not yet been verified.

## Supported Loaders

| Loader | Status | Notes |
| --- | --- | --- |
| Forge | Supported | MC 1.20.1 |
| Fabric | Supported | MC 1.20.1, requires Fabric API |
| NeoForge | Supported | MC 1.20.1 (`net.neoforged:forge` 47.1.x). API-identical to Forge on 1.20.1, so it reuses the Forge sources; diverges on 1.20.2+ |

> The initial target is 1.20.1, with expansion to more versions planned.

## Features

- [x] Display Chzzk · Twitch stream chat in the in-game chat in real time
- [x] Inline emote image rendering (Chzzk · Twitch)
- [x] Donation display (Chzzk cheese · Twitch bits)
- [x] Auto-reconnect on disconnect · auto-connect to the last channel
- [x] Forge · Fabric · NeoForge multi-loader support
- [ ] In-game chat input → send to stream chat (planned)

## Project Structure

```
Chatting-connect/
├── common/                 # Loader-agnostic shared logic (ChzzkClient, etc., pure Java)
├── chat-connect-forge/     # Forge entry point + commands + emote rendering (mixin)
├── chat-connect-fabric/    # Fabric entry point + commands + emote rendering (mixin)
├── chat-connect-neoforge/  # NeoForge 1.20.1 (build.gradle only; reuses Forge sources)
├── build.gradle            # Root build script
├── settings.gradle         # Subproject configuration
└── gradle.properties       # Shared versions · mod metadata
```

The shared logic lives in the `common` module; each loader module depends on it and bundles it into its own mod jar. Only the entry point, commands, and chat display — a thin adapter layer — differ per loader.

## Building

The mod targets **Java 17** (per-module toolchain), but the **Gradle daemon runs on Java 21+** due to Fabric Loom 1.12's requirement (`gradle/gradle-daemon-jvm.properties`). If Java 21 is not installed, Gradle provisions it automatically via the toolchain resolver.

```bash
# Build / run Forge
./gradlew :chat-connect-forge:build
./gradlew :chat-connect-forge:runClient

# Build / run Fabric (Fabric API is included automatically in the dev run)
./gradlew :chat-connect-fabric:build
./gradlew :chat-connect-fabric:runClient

# Build / run NeoForge
./gradlew :chat-connect-neoforge:build
./gradlew :chat-connect-neoforge:runClient
```

Build output: `chat-connect-<loader>/build/libs/`

## Installation

1. Install a mod loader (Forge · Fabric · NeoForge, **MC 1.20.1**).
2. Put the matching `chattingconnect-<loader>-1.20.1-*.jar` into your `mods` folder.
3. **On Fabric, also install [Fabric API](https://modrinth.com/mod/fabric-api)** into `mods`. (Forge/NeoForge need no extra dependency.)
4. This is a client-side mod, so it does not need to be installed on the server.

## Usage

Connect via client commands in-game. (As a client-side mod, it works whether or not you're on a server.)

```
/chzzk  connect <channelId>   # Chzzk channel (channelId = chzzk.naver.com/<channelId>)
/twitch connect <login>       # Twitch channel (login = login name, lowercase)

/<platform> disconnect        # Disconnect
```

A connected channel is saved to `config/chattingconnect.json` and auto-connects on the next launch.
Running `disconnect` clears the saved entry so it won't auto-connect.

## How It Works

Uses only the Java standard `java.net.http` (HttpClient · WebSocket) + Minecraft's bundled Gson — no external libraries.

- **Chzzk · SOOP**: **unofficial internal APIs** (REST to look up the chat channel/token → WebSocket receive). Migration to official APIs is under consideration for the stabilization/release phase.
  - On Chzzk, only **text (in-chat) cheese donations** are received. **Video and mission donations** are delivered only to the streamer's alert overlay (a separate, authenticated channel), not to public chat, so a viewer-side client cannot receive them.
- **Twitch**: connects to the public **IRC-over-WebSocket** (`irc-ws.chat.twitch.tv`) anonymously, read-only. Only first-party Twitch emotes and bits (cheers) are supported; third-party emotes (BTTV/FFZ/7TV) and external cash donations are not part of the chat protocol and are not supported.

## License

[MIT License](LICENSE.md) © 2026 sarhamon

> This mod uses the **unofficial internal APIs** of Chzzk · SOOP and Twitch IRC. MIT applies only to this project's **code**; complying with each platform's terms of service is the user's responsibility.
