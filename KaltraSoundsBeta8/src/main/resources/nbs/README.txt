NBS SONGS
=========

NBS means Note Block Studio song. It is a compact song file created by the
Open Note Block Studio editor. KaltraSounds reads the notes and plays them to
Minecraft players with Minecraft or resource-pack instrument sounds.

Place .nbs files in this folder, then use the filename with /pms nbs or in a
custom disc entry. The .nbs suffix is optional in configuration.

Example custom disc:

  discs:
    example-song:
      enabled: true
      material: MUSIC_DISC_CAT
      name: '&bExample NBS Song'
      nbs: example-song.nbs
      volume: 1.0
      radius: 24.0

When an entry contains nbs, it takes priority over sound or sounds.

NBS files can reference custom instrument filenames. Put the matching custom
sound in your resource pack and use a sensible namespaced key when possible.
Very large songs are rejected by safety limits and can still be expensive to
play, so test songs on a staging server first.
