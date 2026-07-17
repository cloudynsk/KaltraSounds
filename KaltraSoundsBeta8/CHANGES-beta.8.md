# KaltraSounds 1.0.0-beta.8 changes

## GUI icon accuracy

- Nether ambience and music now prefer Nether materials instead of `ECHO_SHARD`.
- Added specific mappings for Nether Wastes, Soul Sand Valley, Crimson Forest,
  Warped Forest, Basalt Deltas, the End, ordinary caves and sculk sounds.
- Removed the overly broad rule that treated every `ambient.*` sound as sculk.
- Added an automated dependency-free icon resolver verification task to Gradle.

## Configuration documentation

- Added explanatory comments to `config.yml`, `discs.yml`, biome loops and region loops.
- Added generated `CONFIG-GUIDE.txt` in the plugin data folder.
- Added generated `nbs/README.txt` explaining Note Block Studio songs, placement,
  custom discs, precedence and practical limitations.

## Development workflow

- Documented a normal source-first GitHub workflow using a committed Gradle Wrapper.
- Future releases should edit tracked source directly and run one CI workflow,
  rather than rebuilding source from ZIP and patch fragments.
