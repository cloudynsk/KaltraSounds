# KaltraSounds 1.0.0-beta.9 changes

## Safe region loop periods

- Native region loop commands now require an explicit replay period.
- Replay periods accept ticks, seconds, or minutes (`1200t`, `60s`, or `1m`).
- The selected period is shown in the command confirmation.
- Removed the unsafe command-created five-second default that caused long
  namespaced `.ogg` tracks to overlap repeatedly.
- Added dependency-free verification for replay-period parsing and overflow.

Existing YAML loop rules keep their configured `Period`. For long tracks, set
that value to at least the complete audio duration and keep replay-based fade-out
disabled.
