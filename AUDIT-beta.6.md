# KaltraSounds beta.6 audit report

## Scope

The supplied archive was a beta.5 source snapshot. The beta.6 changes described in the supplied work log were not present in that archive, so this tree reconstructs and audits them directly from beta.5.

The comparison covered the original PlayMoreSounds core behavior and relevant public addon sources, including Essentials, CMI, AuthMe, SuperVanish, Custom Discs, NBS and chat/channel handling.

## Checks completed

- 20 YAML resources parsed with a duplicate-key rejecting loader.
- Every `playmoresounds.*` permission referenced by Java source is declared in `plugin.yml` or a declared child.
- All Java source passes `javac --release 21 -Xlint:all` against a local structural Bukkit/Paper compatibility harness with zero warnings.
- GUI registry-key resolver tests cover block, item, entity, music-disc, namespaced and fallback cases.
- NBS parser safety checks cover file size, bounded strings, note count, positions, truncation and empty songs.
- Source diff passes whitespace/error checks.
- No Bukkit or Paper implementation classes are bundled in the source tree.

The Java 21 structural compile is not a release compile. It proves source consistency against the local harness, not binary compatibility with Paper 26.1.1.

## Important fixes

- Structural GUI material resolution and exact inventory-session ownership.
- Correct event parity for teleport, world change, kill origin, wake-up and inventory criteria.
- Listener snapshots and truthful dispatch results.
- Delayed biome/region stop race cleanup.
- Transactional custom-disc insertion/ejection and disabled-definition recovery.
- Actual event hooks for Essentials, AuthMe, CMI and SuperVanish.
- Recipient isolation for chat/channel integrations.
- Atomic configuration saves and broad semantic validation.
- Ownership-safe region mutation and visibility.

## Remaining certification work

A trustworthy installable jar was not produced in this environment because it provides Java 21 only, no Gradle/Maven, and no working network resolution for Paper dependencies. Paper 26.1.x requires a Java 25 toolchain.

Before deployment, build this source with Java 25 against Paper API `26.1.1.build.29-alpha`, then complete `TESTING-beta.6.md`. Runtime-reflection integrations, especially PacketEvents, WorldGuard and RedProtect, must be verified against the exact server plugin versions.

## Release decision

This tree is suitable as an audited beta.6 **source release candidate**. It is not yet a runtime-certified production jar.
