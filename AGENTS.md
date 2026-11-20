# Acanthis Mobile Agent Guide

## Repository Shape

- Kotlin Multiplatform + Compose Multiplatform project managed by Gradle (`9.3.1`) and Version Catalog (`gradle/libs.versions.toml`).
- Uses the **AGP 9 dual-module layout**:
  - `:composeApp` (`com.android.kotlin.multiplatform.library`) owns all shared KMP code (`commonMain`), Android actuals (`androidMain`), and shared tests (`commonTest`).
  - `:androidApp` (`com.android.application`, `applicationId = io.acanthis.app`) is the thin Android binary wrapper (`AcanthisApplication`, `MainActivity`, `AndroidManifest.xml`). Do NOT apply `org.jetbrains.kotlin.android` in `:androidApp` because AGP 9 has built-in Kotlin support.

## Dependency & DSL Conventions

- **Compose Multiplatform 1.11+**: Always use explicit Maven coordinates declared in `gradle/libs.versions.toml` (e.g. `libs.compose.runtime`, `libs.compose.material3`). Do NOT use deprecated `compose.runtime` / `compose.material3` plugin accessors.
- **AGP 9 KMP Library**: `:composeApp` configures Android inside `kotlin { androidLibrary { ... } }` (not top-level `android { }`), enables unit tests via `withHostTest {}`, and maps Room KSP compiler via `kspAndroid`.
- **Platform isolation**: Never import `android.*` or `java.*` inside `composeApp/src/commonMain`. All platform dependencies must go behind `expect`/`actual` in `core/platform/` or injected interfaces so iOS/Desktop targets can be enabled without refactoring.

## API Alignment with Acanthis Backend

- Backend repository: sibling `../Acanthis` (`apps/api-server` and `apps/web`).
- Media URLs (`/v1/...`) must be normalized through `rebaseMediaUrlWithBase` (`core/network/MediaUrlRebase.kt`), matching `Acanthis/apps/web/src/utils/mediaUrl.ts`.
- HTML chapter blocks parsed in `core/parser/ChapterParser.kt` must keep `blockIndex` aligned with Web `chapterParser.ts` for `/v1/sync/state` `paragraphIndex` cross-device sync compatibility.

## Verification & Git Discipline

- Run unit tests: `.\gradlew.bat :composeApp:testAndroidHostTest`
- Build Debug APK: `.\gradlew.bat :androidApp:assembleDebug`
- Never commit `local.properties`, `build/`, or `.gradle/`.
- Follow Conventional Commits (Chinese description summary + detailed body). Commit atomically after verified changes; never push without explicit user approval.
