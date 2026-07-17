# KaltraSounds development workflow

This file is the durable project memory for future KaltraSounds work.

## Preferred repository layout

Commit the complete source tree directly to GitHub:

- `src/main/java`
- `src/main/resources`
- `src/test/java`
- `build.gradle.kts`
- `settings.gradle.kts`
- Gradle Wrapper files: `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`

Do not use source ZIPs plus split patch fragments for ordinary releases. That
method was only a transport workaround when the repository initially lacked the
source tree.

## Release procedure

1. Create a branch from `main`.
2. Edit the tracked source directly.
3. Update the version in `build.gradle.kts` and `plugin.yml`.
4. Add regression verification for each fixed bug.
5. Run `./gradlew clean build` with Java 25.
6. CI inspects `plugin.yml`, rejects bundled Paper/Bukkit classes and tests the JAR.
7. CI boots Paper 26.1.1, runs `/pms validate`, reload tests and clean shutdown.
8. Download the workflow artifact and independently inspect its checksum and log.
9. Keep the pull request unmerged until the real server test passes.

## CI efficiency

- Trigger the build once per relevant event, not both push and pull request for
  the same branch.
- Use the Gradle Wrapper instead of installing an arbitrary Gradle version.
- Enable Gradle dependency caching through `gradle/actions/setup-gradle`.
- Use path filters so documentation-only changes can skip the expensive Paper boot.
- Split CI into `build` and `paper-smoke` jobs; let smoke depend on build and use
  the compiled artifact. This makes compiler failures return faster.
- Add concurrency cancellation so a newer commit cancels an obsolete run.

## Project-specific facts

- Target: Paper 26.1.1 build 29, Java 25.
- Always test optional integrations on the user's real server versions.
- Minecraft cannot continuously alter an already-playing sound's volume; loop
  fades are volume ramps across repeated plays.
- GUI material mappings require regression tests for dimensions and biome families.
