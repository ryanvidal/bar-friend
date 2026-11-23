# Bar Friend

Native Kotlin Multiplatform (Android & iOS) mobile client for self-hosted [Bar Assistant](https://github.com/karlomikus/bar-assistant) (`v6+`) instances, built with Compose Multiplatform and Material 3 Expressive.

## Stack & Architecture

Bar Friend separates thin platform entry points from shared multiplatform core and feature modules:

- **UI & Navigation**: Compose Multiplatform (`1.12.2`), Material 3 Expressive (`material3`), Material 3 Adaptive (`1.3.0`), and Navigation 3 (`navigation3-ui`) with independent saveable backstacks per tab.
- **Dependency Injection**: Metro (`1.6.0`) compile-time dependency graph aggregation across KMP modules.
- **Networking & Images**: Ktor Client (`3.6.1`, OkHttp on Android / Darwin on iOS), Kotlinx Serialization (`1.13.0`), and Coil 3 (`3.6.3`) with disk caching.
- **Offline-First Storage**: Room 3 KMP (`3.0.3`) with bundled SQLite (`2.7.1`) for cocktail, ingredient, and shelf caching, DataStore Preferences (`1.3.0`) for user settings, and platform-native token storage (Android Keystore AES-GCM / iOS Keychain).

### Modules

| Module | Responsibility |
| --- | --- |
| `:androidApp` | Thin Android application shell (`com.ryanvidal.barfriend`) |
| `:composeApp` | Shared `BarFriendApp` root UI, Nav3 host, Metro `AppGraph`, iOS framework export |
| `:core:model` | Pure domain models and `oz`/`ml`/`cl` unit conversion and fractional formatting |
| `:core:designsystem` | Custom warm dark-first & light Material 3 Expressive theme and shared components |
| `:core:network` | Ktor HTTP client, Bar Assistant `v6+` DTOs, version validation, and API service |
| `:core:database` | Room 3 entities, DAOs, and bundled SQLite database factory |
| `:core:data` | Offline-first repositories, DataStore settings, and session management |
| `:core:platform` | Android Keystore / iOS Keychain token store, file paths, and screen wake lock |
| `:feature:auth` | Server connection and Email/Password authentication |
| `:feature:cocktails` | Cocktail browsing, search, detail Make Mode, and recipe creation/editing |
| `:feature:inventory` | Bar shelf and shopping list management |
| `:feature:settings` | Active bar summary, default units (`oz`/`ml`/`cl`), and theme controls |

## Roadmap

- **Milestone 0 — Scaffold & Manual CI**: Multi-module KMP/Compose foundation, Room 3 database schema, Ktor client, Material 3 Expressive theme, Xcode `iosApp` host, and manual GitHub Actions workflow.
- **Milestone 1 — MVP (Email/Password Auth + Cocktail Recipes)**: Server `v6+` validation, Email/Password login, offline-first cocktail sync, browse/search/filter with missing-ingredient badges, Cocktail Detail with Make Mode (screen wake lock, serving scaler, `oz`/`ml`/`cl` toggle), Add/Edit recipe form with photo upload and URL recipe import.
- **Milestone 2 — Bar Shelf & Shopping List**: Bar inventory quick-toggle (`/bars/{id}/inventory/*`), bottle recommendations, shopping list management with one-tap move-to-shelf, and live recalculation of cocktail availability.
- **Milestone 3 — Extended Features**: Full Ingredients catalog & CRUD and Calculators first, followed by PAT/SSO login options, collections, notes/ratings, bar menus, multi-bar/multi-member support, and optional AI helpers.

## Building & Testing Locally

Run Android builds and host unit tests from the repository root:

```bash
./gradlew :androidApp:assembleDebug
./gradlew testAndroidHostTest
```

On macOS with Xcode installed, open `iosApp/iosApp.xcodeproj` or verify the simulator build from the terminal:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' CODE_SIGN_ALLOWED=NO build
```

## CI

GitHub Actions in [.github/workflows/ci.yml](.github/workflows/ci.yml) is configured with `on: workflow_dispatch` only and never runs automatically on push or pull requests. Trigger it manually from the Actions tab or with `gh workflow run ci.yml`.
