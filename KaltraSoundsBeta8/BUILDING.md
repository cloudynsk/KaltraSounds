# Building KaltraSounds

## Required toolchain

- Java 25 JDK
- Gradle 9.x or another Gradle release that supports Java 25 toolchains
- Network access to the Paper Maven repository

The project targets Paper API `26.1.1.build.29-alpha` and compiles for Java 25. It must not be compiled for Java 21 merely because an older local JDK happens to be nearby looking hopeful.

## Recommended build

```bash
gradle clean build
```

The plugin jar will be created under:

```text
build/libs/KaltraSounds-1.0.0-beta.8.jar
```

Paper is a `compileOnly` dependency and is not bundled into the plugin jar.

## Direct javac build

`build-with-paper-api.sh` is retained for controlled environments that already provide the complete Paper API compile classpath:

```bash
./build-with-paper-api.sh /path/to/paper-api-and-required-compile-classpath
```

Using Gradle is preferred because Paper API has transitive compile dependencies. A single isolated API jar may not be sufficient.

## Required verification before deployment

1. Build with Java 25 and `-Xlint:all -Werror`.
2. Inspect the jar to confirm it contains no `org/bukkit`, `io/papermc`, or dependency classes.
3. Start a clean Paper 26.1.1 test server.
4. Run `/pms validate`.
5. Exercise the cases in `TESTING-beta.7.md`.
6. Only then promote the jar to the production server.
