# KaltraSounds 1.0.0-beta.6 changes

## Compatibility and event parity

- Restored command-only Teleport behavior and World Change suppression of the teleport sound.
- Corrected Player Kill origin, Wake Up timing and inventory cursor-item criteria.
- Added immediate biome and region synchronization after joins and successful teleports.
- Preserved legacy `Afk Toggle`, `God Toggle` and `Vanish Toggle` rules while supporting split On/Off rules.
- Missing `Enabled` values now consistently mean disabled.

## Sound engine

- Delayed playback snapshots listeners at trigger time.
- Fixed per-player delayed reservation accounting.
- Dispatch success now reflects permissions, listener resolution and scheduling limits.
- Rejects invalid radii, non-finite volume/pitch and unresolved sound keys.
- Chat playback can be restricted to the recipients selected by a channel plugin.

## GUI

- Replaced substring guessing with structural registry-key parsing.
- Exact block, item, entity and music-disc candidates are attempted first.
- Navigation retains search state.
- Clicks are accepted only for the exact tracked inventory session.

## Regions and biomes

- Tightened owner and `.others` permissions.
- Filtered region lists and info output by visibility permission.
- Corrected delayed stop cancellation on re-entry.
- Prevented Stop On Exit from being scheduled while a biome loop starts.
- Cleans stale tasks for offline players and during reload/disable.

## Custom discs and NBS

- Custom discs now use jukebox insertion/ejection behavior.
- Failed playback does not consume or strand a disc.
- Disabled or deleted definitions can still be ejected safely.
- Multiple sounds, NBS, lore, material and glint settings are supported.
- Added NBS file, string, note and position limits plus malformed-file validation.
- Legacy migration now checks `PlayMoreSounds/Note Block Songs/`.

## Integrations

- Dynamic listeners are unregistered before reinitialization.
- Essentials, AuthMe, CMI and SuperVanish hooks use actual events rather than detection-only status.
- Duplicate AFK and vanish providers suppress later providers.
- VentureChat fails closed to sender-only if recipient extraction becomes incompatible.
- TownyChat and similar plugins use the final Bukkit recipient set.

## Configuration and commands

- Atomic YAML writes with rollback reporting.
- Expanded `/pms validate` across rules, regexes, discs, NBS, replacements, regions and core limits.
- Strict finite-number validation in commands.
- Added scoped reloads and an explicit editor for existing paths.
- Declared every permission referenced by source.

## Final release-blocker fixes

- Keep strict `-Werror` compilation while excluding deprecation/removal warnings for deliberately retained compatibility APIs.
- Parse NBS versions 1 and 2 without reading the song-length field that only exists from version 3 onward.
- Normalize bare NBS custom instrument filenames to valid `minecraft:` sound keys.
- Track and cancel delayed sound tasks during plugin disable and sound/full reloads.
- Reseed biome and world-time state on situational reload without emitting false biome-enter transitions.
