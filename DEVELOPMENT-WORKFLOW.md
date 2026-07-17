# Development workflow

The complete plugin source is canonical on `develop`.

- Edit source files directly, not ZIP or patch fragments.
- Keep Gradle and plugin.yml versions synchronized.
- Run the committed Gradle Wrapper with Java 25.
- GitHub Actions compiles, inspects the JAR, boots Paper 26.1.1, runs validation and reload checks, then uploads artifacts.
- Patch fragments are historical migration data only.
