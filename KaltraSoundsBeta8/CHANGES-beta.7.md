# KaltraSounds 1.0.0-beta.7 changes

## Loop engine

- Added shared region and biome loop scheduling instead of separate fixed-rate implementations.
- Added `Period Randomness` for ambient timing variation.
- Added `Maximum Plays` with `0` meaning unlimited.
- Added replay-volume fade-in with configurable play count, starting multiplier and curve.
- Added optional replay-volume fade-out under `Stop On Exit` with configurable play count, period, ending multiplier and curve.
- Added `LINEAR`, `SMOOTHSTEP` and `EXPONENTIAL` curves.
- Re-entry cancels every pending fade-out task.
- Reload and shutdown stop active loop sounds and cancel all loop/fade tasks.
- Natural completion removes loop state instead of leaving stale handles.

## Validation and commands

- `/pms validate` checks all loop timing, count, multiplier and curve settings.
- `/pms region sound <region> loop ...` now creates the complete disabled-by-default fade configuration so it can be edited explicitly.

## Compatibility note

Minecraft cannot change the volume of an already-playing sound instance. Fade controls operate across replay cycles and are best suited to short loop clips.
