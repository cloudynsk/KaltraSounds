# KaltraSounds beta.7 loop test checklist

1. Configure a short region loop with `Period: 20`, fade-in enabled for four plays, and `Start Volume Multiplier: 0.1`.
2. Enter the region and confirm four successive plays rise toward the configured volume.
3. Set `Period Randomness: 10` and confirm plays vary without scheduling below one tick.
4. Set `Maximum Plays: 3` and confirm no fourth play occurs and loop state does not restart until re-entry or reload.
5. Enable `Stop On Exit` and fade-out for three plays. Leave and confirm decreasing-volume tail replays followed by a named-sound stop.
6. Leave and immediately re-enter. Confirm the old fade-out is cancelled and does not stop the new loop.
7. Reload all/sounds/regions while inside. Confirm one loop remains and no stale fade tasks fire.
8. Disconnect inside a loop and inspect the console for scheduler or collection exceptions.
9. Run `/pms validate` with invalid periods, multipliers and curves and confirm each is reported.
10. Repeat with a long custom clip and confirm the documented replay limitation is understood before production use.
