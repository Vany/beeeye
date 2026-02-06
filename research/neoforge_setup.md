# NeoForge Mod Development Setup  
## Minecraft 1.21.11

This document explains **how to initialize a NeoForge mod development folder** for **Minecraft 1.21.11**, including:
- What to install
- Where to download files
- What commands to execute
- How the project structure works

---

## 1. Requirements

### Java
- **Java 21 (JDK)** is REQUIRED for Minecraft 1.21+
- Recommended: **Eclipse Temurin JDK 21**

Download:
https://adoptium.net/

Verify installation:
```bash
java -version
You should see 21.x

IDE (choose one)
IntelliJ IDEA (recommended)
https://www.jetbrains.com/idea/

Eclipse
https://www.eclipse.org/downloads/

Git (optional but recommended)
Used for cloning templates.

Download:
https://git-scm.com/

Verify:

git --version
2. Getting the NeoForge Mod Template (MDK)
You have two official ways to initialize your development folder.

OPTION A — NeoForge Mod Generator (Easiest)
Where to get it
NeoForge official website:
https://neoforged.net/

Click "Mod Generator"

Generator settings
Fill in:

Minecraft Version: 1.21.11

Mod Loader: NeoForge

Mod ID: examplemod

Mod Name: Example Mod

Package Name: com.yourname.examplemod

Build System: ModDevGradle (recommended)

Click Generate, download the ZIP.

Initialize dev folder
Extract the ZIP

Rename the folder if you want

This folder IS your development workspace

OPTION B — GitHub MDK Template (Manual / Advanced)
Where to get it
NeoForge MDKs:
https://github.com/NeoForgeMDKs

Choose:

MDK-1.21-ModDevGradle

Clone it
git clone https://github.com/NeoForgeMDKs/MDK-1.21-ModDevGradle.git
OR download ZIP and extract.

This extracted folder is your dev folder.

3. Opening the Project
IntelliJ IDEA
Open IntelliJ

Click Open

Select the folder containing build.gradle

Wait for Gradle to sync

Eclipse
File → Import

Gradle → Existing Gradle Project

Select the project folder

4. Initializing the Workspace (IMPORTANT)
Open a terminal inside the project folder.

Windows
gradlew.bat genIntellijRuns
Linux / macOS
chmod +x gradlew
./gradlew genIntellijRuns
This:

Downloads Minecraft

Sets up mappings

Creates run configurations

If Gradle fails, retry:

./gradlew --refresh-dependencies
5. Running Minecraft with Your Mod
Client
./gradlew runClient
Dedicated Server
./gradlew runServer
Minecraft will start with your mod loaded.

6. Project Structure Explained
project-root/
├─ build.gradle          # Build configuration
├─ settings.gradle
├─ gradle.properties     # Mod info (id, version, name)
├─ src/
│  ├─ main/
│  │  ├─ java/
│  │  │  └─ com/yourname/examplemod/
│  │  │     └─ ExampleMod.java
│  │  └─ resources/
│  │     ├─ META-INF/
│  │     │  └─ neoforge.mods.toml
│  │     └─ assets/examplemod/
└─ run/                  # Generated run directory
7. Important Files
gradle.properties
Edit these:

mod_id=examplemod
mod_name=Example Mod
mod_version=1.0.0
neoforge.mods.toml
Located at:

src/main/resources/META-INF/neoforge.mods.toml
This defines:

Mod ID

Version

Entry class

Dependencies

8. Building the Mod JAR
To compile your mod:

./gradlew build
Output:

build/libs/examplemod-1.0.0.jar
Copy this JAR into:

.minecraft/mods/
(using NeoForge 1.21.11)

9. Common Problems
Wrong Java version
NeoForge 1.21+ WILL NOT run on Java 17.

Fix:

Set IDE JVM to Java 21

Check JAVA_HOME

Gradle stuck or failing
./gradlew clean
./gradlew build
10. Official Docs (Recommended Reading)
NeoForge Docs:
https://docs.neoforged.net/

Getting Started:
https://docs.neoforged.net/docs/1.21.1/gettingstarted/

11. You’re Ready 🎉
You now have:

A working NeoForge dev environment

Run configurations

A buildable mod JAR

Start coding inside:

src/main/java
