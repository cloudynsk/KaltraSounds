# Loop controls

KaltraSounds beta.7 adds shared loop controls for native regions, WorldGuard/RedProtect regions, and biome loops.

```yaml
Loop:
  Enabled: true
  Delay: 0
  Period: 100
  Period Randomness: 0
  Maximum Plays: 0

  Fade In:
    Enabled: false
    Plays: 3
    Start Volume Multiplier: 0.2
    Curve: SMOOTHSTEP

  Stop On Exit:
    Enabled: true
    Delay: 0
    Fade Out:
      Enabled: false
      Plays: 3
      Period: 20
      End Volume Multiplier: 0.0
      Curve: SMOOTHSTEP

  Sounds:
    1:
      Sound: ambient.cave
      Volume: 1.0
      Pitch: 1.0
      Options:
        Radius: 0
```

## Settings

- `Delay`: ticks before the first play.
- `Period`: base ticks between plays.
- `Period Randomness`: random plus/minus variation applied to each period. The effective period never drops below one tick.
- `Maximum Plays`: total loop plays before stopping automatically. `0` means unlimited.
- `Fade In.Plays`: number of loop replays used to reach full configured volume.
- `Fade In.Start Volume Multiplier`: starting fraction of each sound's configured volume, from `0.0` to `1.0`.
- `Stop On Exit.Delay`: delay before the fade-out or immediate stop begins.
- `Fade Out.Plays`: number of decreasing-volume tail replays after exit.
- `Fade Out.Period`: ticks between tail replays.
- `Fade Out.End Volume Multiplier`: final replay volume fraction, from `0.0` to `1.0`.
- `Curve`: `LINEAR`, `SMOOTHSTEP`, or `EXPONENTIAL`.

## Important audio limitation

Minecraft's server sound packet cannot continuously change the volume of a sound instance that is already playing. KaltraSounds therefore fades the volume **between repeated plays**. This works best with short ambient clips whose duration is at or below `Period`.

For one long music or ambience file, a fade-out replay would restart or overlap the clip. Keep `Fade Out.Enabled: false` for long files and use `Stop On Exit` for an immediate stop, or split the resource-pack audio into short seamless segments designed for replay fading.
