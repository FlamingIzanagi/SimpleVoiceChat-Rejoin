# SVCRejoin

[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) addon to reconnect or leave voice chat **without relogging**.

There are two parts. The server uses the plugin. The Fabric client uses the mod if you need rejoin behind Docker/NAT.

## Plugin (Paper)

`SVCRejoin-1.0.0.jar` — Paper **1.21.8**, Simple Voice Chat **2.6.23+**.

- `/voicechat rejoin [player]` — resets the voice connection
- `/voicechat disconnect [player]` — leaves voice chat
- `/svcrejoin reload` — reloads `config.yml` and `messages.yml`

Put it in `plugins/`. Messages are editable (`&` codes, hex, MiniMessage). Permissions default to OP (`voicechat.admin.reconnect`).

## Mod (Fabric)

`SVCRejoin-Fabric-1.0.0.jar` — Minecraft **1.21.11**, Fabric Loader **≥ 0.19.5**.

Put it in `mods/` next to Simple Voice Chat. It reuses the local UDP port that already connected so `/voicechat rejoin` works behind NAT. Without the mod, a normal join still works as usual.

## Install

1. Drop the plugin on the server next to Simple Voice Chat (Bukkit/Paper).
2. Drop the mod on the client (optional; recommended if the server runs in Docker).
3. Restart the server and the client.
