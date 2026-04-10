# HC_PlayerStorage

Replaces Hytale's default disk-based player data storage with PostgreSQL using a Hyxin mixin. All player save/load operations are redirected to a `player_data` table with JSONB storage, enabling centralized persistence, easier backups, and cross-server data sharing. Includes multi-character UUID translation for HC_MultiChar support.

## Features

- Mixin-based replacement of `DiskPlayerStorageProvider.DiskPlayerStorage` with PostgreSQL-backed implementation
- Player data stored as JSONB in a `player_data` table with automatic schema creation
- Multi-character UUID translation -- resolves account UUIDs to active character UUIDs via `mc_active_character` and `mc_characters` tables
- Asynchronous load, save, and remove operations via CompletableFuture
- Loads the PostgreSQL JDBC driver from the HC_PlayerInventory companion plugin JAR at runtime
- Deployed as an early plugin (earlyplugins/) since mixins must load before the target class

## Dependencies

- **HC_PlayerInventory** (runtime) -- provides the PostgreSQL JDBC driver JAR
- No declared manifest dependencies (mixin plugin)

## Building

```bash
./gradlew build
```
