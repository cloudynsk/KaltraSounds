# KaltraSounds

KaltraSounds is an all-in-one Paper sound platform intended to replace PlayMoreSounds on Kaltra RPG while preserving its public configuration style and useful addon behavior.

## Target

- Paper 26.1.1
- Paper API `26.1.1.build.29-alpha`
- Java 25
- Plugin version `1.0.0-beta.7`

## Included

- Legacy `sounds.yml` and all eleven `Sounds/*.yml` situational files
- Event, command, chat, item, hit, death, game-mode, biome and world-time rules
- Native cuboid regions plus optional WorldGuard and RedProtect discovery
- Custom jukebox discs with scalar, multi-sound and NBS definitions
- NBS playback with bounded parsing and malformed-file rejection
- Registry-aware sound-list GUI with exact block/item/entity/disc icon resolution
- Essentials, AuthMe, CMI, SuperVanish/PremiumVanish, VentureChat, ChatReaction and recipient-aware chat integration
- Optional PacketEvents nature-sound replacement bridge
- `/pms` administration, validation, editor, region, disc and NBS commands
- `playmoresounds.*` permission compatibility

Resource-pack requesting, forcing, kicking and confirmation logic are intentionally absent. Custom namespaced sounds remain supported.

## beta.7 status

This archive is an audited **source release candidate**, not a runtime-certified jar. The source passes the included static, YAML, permission, parser and structural compile checks. A final Java 25 compile against the real Paper API and a clean-server runtime pass are still required before deployment. See `AUDIT-beta.6.md`, `CHANGES-beta.7.md`, `LOOPING.md`, and `TESTING-beta.7.md`.

## License

GPL-3.0-or-later. This replacement was designed around the public configuration and behavior of the GPL-licensed PlayMoreSounds project.


## Loop controls

Region and biome loop rules support initial delay, fixed or randomized periods, maximum play counts, replay-volume fade-in, and optional replay-volume fade-out on exit. See `LOOPING.md` for the schema and the client-audio limitation: Minecraft cannot change the volume of a sound instance that is already playing, so fades are applied across loop replays rather than continuously modifying one active clip.
