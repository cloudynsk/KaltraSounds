# Feature matrix

| Original capability | KaltraSounds beta.7 status | Notes |
|---|---|---|
| Event sounds | Implemented | Missing `Enabled` defaults to false; cancellation semantics retained. |
| Situational YAML filters | Implemented | Commands, chat, items, hit, death, game modes, biomes and world time. |
| Native sound regions | Implemented | Ownership, overlap, lifecycle, reload, randomized loop periods, play limits, and replay-volume fades supported. |
| WorldGuard / RedProtect regions | Implemented through reflection | Requires runtime testing with the exact installed provider versions. |
| Sound Commands addon | Integrated | `/pms play`, stop, test, list and editor paths. |
| Custom Discs addon | Implemented | Real jukebox insertion/ejection, multiple sounds, NBS, rollback and recovery. |
| NBS Song Player addon | Implemented | Versions 0-5 parser with size, string, note and position limits. |
| Nature Sound Replacer addon | Partial / optional | PacketEvents reflection bridge; exact runtime signature still needs server testing. |
| Essentials hook | Implemented | AFK, god and vanish events with legacy toggle fallback. |
| AuthMe hook | Implemented | Join sound is deferred until successful login; register event supported. |
| CMI hook | Implemented | AFK and vanish event fallback when an earlier provider is not active. |
| SuperVanish/PremiumVanish hook | Implemented | Uses the public vanish-state event; suppressed when Essentials/CMI already supplies vanish state. |
| VentureChat hook | Implemented defensively | Uses provider recipients when available and fails closed to sender-only. |
| TownyChat / channel recipients | Implemented through final Bukkit recipients | Sound audience is intersected with chat recipients. |
| ChatReaction hook | Implemented | Reaction winner event routed through configured chat rules. |
| Sound Factors | Configuration behavior integrated where represented | Per-rule permissions, radius, delay, volume and pitch are supported; legacy addon-specific time/height schema is not separately loaded. |
| Sound-list GUI | Implemented | Exact registry-path candidates before curated fallbacks; active inventory session verified. |
| Addon installer | Removed by design | Supported addon behavior is internal. |
| Resource-pack manager | Removed by design | No request, force, kick or confirmation workflow. |
