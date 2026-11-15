# Sparrow Desktop

Password manager for Windows (Kotlin, Compose Desktop). One encrypted file, local only.

## Run (development)
1. Install JDK 17 or newer (Temurin 17 or 21) and set `JAVA_HOME`. Or open the folder in IntelliJ IDEA / Android Studio, which can download a JDK.
2. In a terminal in this folder:
   ```
   .\gradlew run
   .\gradlew test
   ```
   The first run downloads Gradle and the libraries, so it takes a few minutes.

## Build the .exe
- Without an installer (no extra tools): `.\gradlew createDistributable`
  Result: `build\compose\binaries\main\app\Sparrow\Sparrow.exe`. Copy the whole `Sparrow` folder.
- Installer: `.\gradlew packageExe` (or `packageMsi`). It needs WiX Toolset installed. Result: `build\compose\binaries\main\exe\`.
- Unsigned files make Windows SmartScreen warn. That is normal for tests.

## Vault file
`SPVAULT1` | header length | header (JSON, authenticated) | nonce | AES-256-GCM(data).
Key = Argon2id(master password, random salt; 64 MiB, 3 passes). A `.bak` copy of the previous version is kept next to the file.

## Notes
- Auto-lock after 15 minutes without activity. Clipboard is cleared 30 s after copying.
- Colors: Settings, pick a preset or type HEX values. They are stored inside the encrypted vault.
- Import: Bitwarden (unencrypted .json), Chrome / Bitwarden / generic .csv.
- Not audited. The master password cannot be recovered.
