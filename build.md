# Build

## Requirements

- macOS, Linux, or Windows with a shell capable of running the Gradle wrapper.
- Java Development Kit (JDK) installed through `asdf`.
  - This repository pins `openjdk-25.0.2` in `.tool-versions`.
  - `.envrc` uses `direnv` to set `JAVA_HOME` and add the JDK to `PATH`.
  - Gradle runs the build; this project compiles Java 25 bytecode.
- `direnv` enabled for the repository.
- Network access for the first build so Gradle can download its wrapper distribution and dependencies.

## Recommended setup

From the repository root:

```sh
asdf install
direnv allow
```

After that, `direnv` loads the pinned JDK automatically when you enter the repository.

## Create the jar

From the repository root:

```sh
./gradlew shadowJar
```

The runnable jar is written to:

```text
build/libs/converter-all.jar
```

Run it with:

```sh
java -jar build/libs/converter-all.jar
```

## Other useful commands

```sh
# Build the regular jar and fat jar
./gradlew assemble

# Run unit tests
./gradlew test
```
