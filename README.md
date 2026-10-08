# SVCRejoin

Addon de [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) para reconectar o salir del chat de voz **sin reloguear**.

Hay dos piezas. El servidor usa el plugin; el cliente Fabric usa el mod si quieres reconectar detrás de Docker/NAT.

## Plugin (Paper)

`SVCRejoin-1.0.0.jar` — Paper **1.21.8**, Simple Voice Chat **2.6.23+**.

- `/voicechat rejoin [jugador]` — reinicia la conexión de voz
- `/voicechat disconnect [jugador]` — sale del chat de voz
- `/svcrejoin reload` — recarga `config.yml` y `messages.yml`

Carpeta `plugins/`. Mensajes editables (`&`, hex, MiniMessage). Permisos OP por defecto (`voicechat.admin.reconnect`).

## Mod (Fabric)

`SVCRejoin-Fabric-1.0.0.jar` — Minecraft **1.21.11**, Fabric Loader **≥ 0.19.5**.

Va en `mods/` junto a Simple Voice Chat. Reutiliza el puerto UDP local que ya conectó para que `/voicechat rejoin` funcione detrás de NAT. Sin el mod, el join normal sigue igual.

## Instalación

1. Plugin en el servidor, junto a Simple Voice Chat (Bukkit/Paper).
2. Mod en el cliente (opcional, recomendado si el servidor está en Docker).
3. Reinicia servidor y cliente.
