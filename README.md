# Custom-AutoDrill

Custom-AutoDrill is a DarkMooN-SYS-maintained fork of [Pointifix's AutoDrill](https://github.com/Pointifix/AutoDrill) for Mindustry. The original mod and its GPL-3.0 license are credited in this repository.

## Features

- Automatically fills ore patches with suitable drills.
- Supports wall drills and bridge layouts from the original mod.
- Detects registered modded `Drill` blocks, including custom blocks synchronized when joining a modded server.
- Adds a water extractor only when the selected drill consumes water, and a power node only when it has a power consumer.

Custom block definitions must be present in the client's Mindustry content registry. The mod does not fetch private server files or query arbitrary server configuration.

## Build

Requires JDK 17. Build for Mindustry v160.7 with:

```powershell
.\gradlew.bat jar
```

The build downloads the official v160.7 `Mindustry.jar` for compilation and creates `build/libs/Custom-AutoDrill.jar`.

## Attribution and license

The upstream AutoDrill project was created by Pointifix. This fork keeps the upstream GPL-3.0 license; see [LICENSE](LICENSE). Custom-AutoDrill's changes are maintained by DarkMooN-SYS.
