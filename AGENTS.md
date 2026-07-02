# AGENTS.md

## Build
```bash
./gradlew assembleDebug
```

## Lint / Typecheck
```bash
./gradlew compileDebugKotlin
```

## Tests
```bash
./gradlew test
```

## Key Files
- `DniReader.kt`: Lector DNIe usando SDK FNMT v2.3.111 (Loader.init)
- `EuropeanStructureReader.kt`: Lector universal europeo (jmrtd)
- `ResultActivity.kt`: Presentación de resultados (identidad, DG tree, HEX dump)
- `NFCScanActivity.kt`: Orquestación de readers con cadena de fallback
- `gradle/libs.versions.toml`: BouncyCastle 1.65, AGP 9.2.1
- `app/libs/dniedroid-release.aar`: SDK oficial FNMT v2.3.111 (no modificar)
