# KaltraSounds beta.6 runtime test checklist

Run on a disposable Paper 26.1.1 server using Java 25.

## Startup and configuration

- Start with an empty `plugins/KaltraSounds` folder.
- Confirm the plugin enables without exceptions.
- Run `/pms validate`; resolve every reported problem.
- Run `/pms info` and verify integration statuses match installed plugins.
- Reload each scope repeatedly and confirm sounds do not multiply.

## GUI

- Open `/pms list gui` and inspect block, item, entity and music-disc sounds.
- Confirm exact materials are used when available and every fallback is a valid item.
- Search/list, page forward/back, preview, stop all and close the inventory.
- Open another chest with a similar title and confirm KaltraSounds does not intercept it.

## Events and chat

- Test command teleport, plugin teleport, portal/world change and cancelled teleport.
- Test player kill, victim death, wake-up, cursor-item inventory click and cancelled events.
- Test chat in global, local, town, nation and private channels; only real recipients should hear the sound.
- Reload integrations several times and verify one message produces one sound.

## Biomes and regions

- Enter/leave rapidly with delayed Stop On Exit.
- Re-enter before the stop delay expires and confirm the new sound is not stopped.
- Reload while standing inside a loop region or biome.
- Test overlapping regions, disconnect while inside, teleport between worlds and remove/rename active regions.
- Verify owner, admin and `.others` permissions with separate accounts.

## Custom discs and NBS

- Insert, eject and break a jukebox containing each enabled disc.
- Attempt insertion without permission and in a protected/cancelled interaction.
- Disable or delete a disc definition while inserted, reload, then eject it.
- Test a valid NBS file, a truncated file, a missing file and an oversized file.
- Confirm failed playback never consumes the held disc.
- Restart the server with a custom disc in a jukebox and confirm it remains recoverable.

## Integrations

- Essentials: AFK, god and vanish on/off.
- AuthMe: first join, normal join, login and registration.
- CMI: AFK and vanish when Essentials is absent or its matching hook is unavailable.
- SuperVanish/PremiumVanish: vanish and unvanish when no earlier vanish provider is active.
- PacketEvents replacement: verify the original packet is cancelled only when replacement playback succeeds.

## Packaging

- Inspect the built jar and confirm no Paper/Bukkit classes are bundled.
- Check that `plugin.yml` reports beta.6 and API version 26.1.
- Record the final jar SHA-256 before production deployment.
