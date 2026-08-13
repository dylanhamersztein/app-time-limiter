# App Time Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a sideload-only Android app that caps daily and per-session foreground time in chosen apps, enforcing the caps with a full-screen block screen.

**Architecture:** Two Gradle modules. `:rules` is a pure Kotlin JVM library holding every decision the app makes — it has no Android dependency, so all rule logic is exhaustively unit-testable with a fake clock. `:app` is the Android module: an `AccessibilityService` supplies instant foreground-change events, `UsageStatsManager` supplies the authoritative daily ledger, and a coordinator feeds both into `:rules` and acts on the returned `Decision`.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Room, DataStore, coroutines, JUnit4 + Truth, `java.time` (no third-party date library).

**Spec:** `PRD.md` at the repository root. Requirement IDs (`FR-n`, `NFR-n`) below refer to it. Read it before starting any task.

## Global Constraints

- Package root: `com.dylanhamersztein.timelimiter`.
- `minSdk = 31`, `targetSdk = 35`, `compileSdk = 35`, Java 17 toolchain.
- The `:rules` module MUST NOT depend on any Android artifact. Its build file declares only `kotlin("jvm")`, coroutines-core, and test dependencies. This is the mechanism that enforces NFR-4 — do not "temporarily" add an Android dependency to it.
- All time arithmetic uses `java.time.Instant` / `java.time.LocalDate` / `java.time.ZoneId` and `kotlin.time.Duration`. Never `System.currentTimeMillis()` directly in logic — always an injected `java.time.Clock`.
- No `android.permission.INTERNET`. The app is offline (NFR-3). Do not add a networking dependency for any reason.
- Room stores durations as whole minutes (`Int`) and instants as epoch millis (`Long`).
- The safeguard allowlist (FR-18) is enforced in code and is never user-editable.
- Library versions in `gradle/libs.versions.toml` may be bumped to the latest stable release, provided the floors above hold.
- Every task ends with a commit. Conventional-commit prefixes (`feat:`, `test:`, `chore:`, `fix:`).

---

## File Structure

**`:rules` — pure Kotlin, no Android**

| File | Responsibility |
| --- | --- |
| `rules/src/main/kotlin/.../rules/LimitConfig.kt` | The per-app limit configuration and its validation |
| `rules/src/main/kotlin/.../rules/DayBoundary.kt` | Local-date bucketing and next-reset computation |
| `rules/src/main/kotlin/.../rules/SessionState.kt` | Session data class + `SessionMachine` transitions |
| `rules/src/main/kotlin/.../rules/Decision.kt` | `Allow` / `Warn` / `Block` result type |
| `rules/src/main/kotlin/.../rules/LimitEngine.kt` | The single decision function |
| `rules/src/main/kotlin/.../rules/ChangeClassifier.kt` | Tightening-vs-loosening classification |
| `rules/src/testFixtures/kotlin/.../rules/TestClock.kt` | Mutable `java.time.Clock` fixture, shared with `:app` tests |

**`:app` — Android**

| Directory | Responsibility |
| --- | --- |
| `data/` | Room entities, DAOs, database, DataStore settings, repositories |
| `ledger/` | `UsageStatsSource`, `UsageStatsReconciler`, `UsageLedger` |
| `detection/` | `ForegroundDetectionService` (the `AccessibilityService`) |
| `enforcement/` | `SafeguardAllowlist`, `BlockScreenLauncher`, `BlockActivity` |
| `service/` | `TrackingService` (foreground service), `TrackingCoordinator`, `DayResetScheduler`, `BootReceiver` |
| `notify/` | Notification channels and the three notification types |
| `ui/` | Compose screens, view models, theme, navigation |
| `AppContainer.kt`, `TimeLimiterApplication.kt` | Manual dependency container — no DI framework |

Dependency injection is manual: a single `AppContainer` constructed in `Application.onCreate`, read by services and view-model factories. This avoids annotation processing beyond Room's KSP and keeps construction explicit.

---

### Task 1: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `rules/build.gradle.kts`, `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `.gitignore`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/ScaffoldingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: the `:rules` and `:app` Gradle modules; the version catalog aliases used by every later task.

- [ ] **Step 1: Generate the Gradle wrapper**

```bash
gradle wrapper --gradle-version 8.9
```

If `gradle` is not installed, copy a wrapper from any existing project, or install via `sdk install gradle 8.9`.

- [ ] **Step 2: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
coreKtx = "1.13.1"
lifecycle = "2.8.5"
activityCompose = "1.9.2"
composeBom = "2024.09.02"
navigation = "2.8.0"
room = "2.6.1"
datastore = "1.1.1"
coroutines = "1.8.1"
junit = "4.13.2"
truth = "1.4.4"
androidxJunit = "1.2.1"
androidxTestRunner = "1.6.2"

[libraries]
core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-material3 = { module = "androidx.compose.material3:material3" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
androidx-junit = { module = "androidx.test.ext:junit", version.ref = "androidxJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: Write `settings.gradle.kts` and the root `build.gradle.kts`**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "app-time-limiter"
include(":app", ":rules")
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 4: Write `rules/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testFixturesImplementation(libs.junit)
}
```

Do not add anything Android-shaped to this file, now or later.

The `java-test-fixtures` plugin matters: `TestClock` (Task 2) lives in `rules/src/testFixtures/`, and the `:app` module's tests consume it. A plain `src/test/` source set is not visible to other modules, so without this the `:app` tests cannot compile.

- [ ] **Step 5: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dylanhamersztein.timelimiter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dylanhamersztein.timelimiter"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":rules"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":rules")))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
```

- [ ] **Step 6: Write a minimal `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="Time Limiter"
        android:supportsRtl="true"
        android:theme="@style/Theme.TimeLimiter" />
</manifest>
```

Permissions and components are added by the tasks that need them.

- [ ] **Step 7: Write `.gitignore`**

```
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
local.properties
```

- [ ] **Step 8: Write the scaffolding test**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScaffoldingTest {
    @Test
    fun `rules module compiles and runs tests`() {
        assertThat(2 + 2).isEqualTo(4)
    }
}
```

- [ ] **Step 9: Run the build**

Run: `./gradlew :rules:test :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If the Android SDK is missing, create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "chore: scaffold Gradle project with :rules and :app modules"
```

---

### Task 2: Domain primitives — `LimitConfig`, clock, day boundary

**Files:**
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/LimitConfig.kt`
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/DayBoundary.kt`
- Create: `rules/src/testFixtures/kotlin/com/dylanhamersztein/timelimiter/rules/TestClock.kt`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/LimitConfigTest.kt`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/DayBoundaryTest.kt`

**Interfaces:**
- Consumes: Task 1's `:rules` module.
- Produces:
  - `data class LimitConfig(packageName: String, dailyBudget: Duration?, sessionCap: Duration?, sessionGap: Duration, cooldown: Duration, warningThreshold: Duration)`
  - `object DayBoundary { fun localDate(now: Instant, zone: ZoneId): LocalDate; fun nextResetAt(now: Instant, zone: ZoneId): Instant }`
  - `class TestClock(start: Instant, zone: ZoneId) : java.time.Clock` with `fun advanceBy(d: Duration)` and `fun setTo(i: Instant)`

- [ ] **Step 1: Write the failing tests**

```kotlin
// LimitConfigTest.kt
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class LimitConfigTest {
    @Test
    fun `accepts a daily budget only`() {
        val config = LimitConfig(packageName = "com.example", dailyBudget = 30.minutes)
        assertThat(config.dailyBudget).isEqualTo(30.minutes)
        assertThat(config.sessionCap).isNull()
    }

    @Test
    fun `accepts a session cap only`() {
        val config = LimitConfig(packageName = "com.example", sessionCap = 10.minutes)
        assertThat(config.sessionCap).isEqualTo(10.minutes)
        assertThat(config.dailyBudget).isNull()
    }

    @Test
    fun `rejects a config with neither cap`() {
        val error = runCatching { LimitConfig(packageName = "com.example") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects non-positive caps`() {
        assertThat(
            runCatching { LimitConfig("com.example", dailyBudget = 0.minutes) }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `defaults session gap to five minutes and cooldown to fifteen`() {
        val config = LimitConfig("com.example", sessionCap = 10.minutes)
        assertThat(config.sessionGap).isEqualTo(5.minutes)
        assertThat(config.cooldown).isEqualTo(15.minutes)
        assertThat(config.warningThreshold).isEqualTo(5.minutes)
    }
}
```

```kotlin
// DayBoundaryTest.kt
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class DayBoundaryTest {
    private val london = ZoneId.of("Europe/London")

    @Test
    fun `buckets an instant into its local date`() {
        // 2026-08-13T23:30Z is 2026-08-14T00:30 in London (BST, UTC+1)
        val instant = Instant.parse("2026-08-13T23:30:00Z")
        assertThat(DayBoundary.localDate(instant, london)).isEqualTo(LocalDate.of(2026, 8, 14))
    }

    @Test
    fun `next reset is the following local midnight`() {
        val instant = Instant.parse("2026-08-13T10:00:00Z") // 11:00 London
        assertThat(DayBoundary.nextResetAt(instant, london))
            .isEqualTo(Instant.parse("2026-08-13T23:00:00Z")) // 2026-08-14T00:00 London
    }

    @Test
    fun `next reset from exactly midnight is a full day later`() {
        val midnight = Instant.parse("2026-08-13T23:00:00Z") // 2026-08-14T00:00 London
        assertThat(DayBoundary.nextResetAt(midnight, london))
            .isEqualTo(Instant.parse("2026-08-14T23:00:00Z"))
    }

    @Test
    fun `handles a timezone with a different offset`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val instant = Instant.parse("2026-08-13T16:00:00Z") // 2026-08-14T01:00 Tokyo
        assertThat(DayBoundary.localDate(instant, tokyo)).isEqualTo(LocalDate.of(2026, 8, 14))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rules:test`
Expected: FAIL — `Unresolved reference: LimitConfig`, `Unresolved reference: DayBoundary`.

- [ ] **Step 3: Write the implementations**

```kotlin
// LimitConfig.kt
package com.dylanhamersztein.timelimiter.rules

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The limits configured for one tracked app (FR-4, FR-5, FR-6).
 *
 * At least one of [dailyBudget] and [sessionCap] must be set; a tracked app with
 * neither is meaningless, and removing the last cap is modelled as untracking
 * the app instead (FR-6a).
 */
data class LimitConfig(
    val packageName: String,
    val dailyBudget: Duration? = null,
    val sessionCap: Duration? = null,
    val sessionGap: Duration = 5.minutes,
    val cooldown: Duration = 15.minutes,
    val warningThreshold: Duration = 5.minutes,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(dailyBudget != null || sessionCap != null) {
            "a limit must set at least one of dailyBudget or sessionCap"
        }
        require(dailyBudget == null || dailyBudget.isPositive()) {
            "dailyBudget must be positive"
        }
        require(sessionCap == null || sessionCap.isPositive()) {
            "sessionCap must be positive"
        }
        require(sessionGap.isPositive()) { "sessionGap must be positive" }
        require(!cooldown.isNegative()) { "cooldown must not be negative" }
        require(!warningThreshold.isNegative()) { "warningThreshold must not be negative" }
    }
}
```

```kotlin
// DayBoundary.kt
package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The budget day is the local calendar date and resets at local midnight (FR-11).
 */
object DayBoundary {
    fun localDate(now: Instant, zone: ZoneId): LocalDate =
        now.atZone(zone).toLocalDate()

    /** The next local midnight strictly after [now]. */
    fun nextResetAt(now: Instant, zone: ZoneId): Instant =
        localDate(now, zone).plusDays(1).atStartOfDay(zone).toInstant()
}
```

```kotlin
// TestClock.kt — testFixtures source set, so :app tests can use it too
package com.dylanhamersztein.timelimiter.rules

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A [Clock] whose time only moves when a test tells it to. */
class TestClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneId.of("Europe/London"),
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(current, zone)
    override fun instant(): Instant = current

    fun advanceBy(duration: Duration) {
        current = current.plus(duration.toJavaDuration())
    }

    fun setTo(instant: Instant) {
        current = instant
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :rules:test`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add rules/
git commit -m "feat: add LimitConfig, day boundary arithmetic and test clock"
```

---

### Task 3: `SessionMachine` — session lifetime, gap and cooldown

**Files:**
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/SessionState.kt`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/SessionMachineTest.kt`

**Interfaces:**
- Consumes: `LimitConfig` (Task 2).
- Produces:
  - `data class SessionState(packageName: String, startedAt: Instant, accumulated: Duration, enteredForegroundAt: Instant?, leftForegroundAt: Instant?, endedByCap: Boolean)`
  - `object SessionMachine` with `onEnterForeground(prev, packageName, now, gap): SessionState`, `onLeaveForeground(prev, now): SessionState`, `lengthAt(state, now): Duration`, `hasExpired(state, now, gap): Boolean`, `markCapped(state): SessionState`, `cooldownEndsAt(state, gap, cooldown): Instant?`

**Domain note:** a session's *length* is foreground time only — time spent out of the app while the session is still alive (inside the gap) does not count. A session ends once the app has been out of the foreground continuously for `sessionGap` (FR-5), and the cooldown clock starts at that end moment, so a capped session unblocks at `leftForegroundAt + sessionGap + cooldown`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class SessionMachineTest {
    private val gap = 5.minutes
    private val cooldown = 15.minutes
    private val t0 = Instant.parse("2026-08-13T10:00:00Z")

    @Test
    fun `entering foreground with no prior session starts a new one`() {
        val state = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        assertThat(state.packageName).isEqualTo("com.example")
        assertThat(state.startedAt).isEqualTo(t0)
        assertThat(state.accumulated).isEqualTo(0.minutes)
        assertThat(state.enteredForegroundAt).isEqualTo(t0)
    }

    @Test
    fun `length grows while in the foreground`() {
        val state = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        assertThat(SessionMachine.lengthAt(state, t0.plusSeconds(180))).isEqualTo(3.minutes)
    }

    @Test
    fun `leaving the foreground freezes the length`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(180))
        assertThat(SessionMachine.lengthAt(left, t0.plusSeconds(600))).isEqualTo(3.minutes)
    }

    @Test
    fun `returning within the gap resumes the same session`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(180))
        val resumed = SessionMachine.onEnterForeground(left, "com.example", t0.plusSeconds(300), gap)
        assertThat(resumed.startedAt).isEqualTo(t0)
        assertThat(resumed.accumulated).isEqualTo(3.minutes)
        assertThat(SessionMachine.lengthAt(resumed, t0.plusSeconds(360))).isEqualTo(4.minutes)
    }

    @Test
    fun `returning after the gap starts a fresh session`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(180))
        val fresh = SessionMachine.onEnterForeground(left, "com.example", t0.plusSeconds(600), gap)
        assertThat(fresh.startedAt).isEqualTo(t0.plusSeconds(600))
        assertThat(fresh.accumulated).isEqualTo(0.minutes)
    }

    @Test
    fun `a different package always starts a fresh session`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(60))
        val other = SessionMachine.onEnterForeground(left, "com.other", t0.plusSeconds(90), gap)
        assertThat(other.packageName).isEqualTo("com.other")
        assertThat(other.accumulated).isEqualTo(0.minutes)
    }

    @Test
    fun `expiry is exactly one gap after leaving the foreground`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0)
        assertThat(SessionMachine.hasExpired(left, t0.plusSeconds(299), gap)).isFalse()
        assertThat(SessionMachine.hasExpired(left, t0.plusSeconds(300), gap)).isTrue()
    }

    @Test
    fun `a session still in the foreground has not expired`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        assertThat(SessionMachine.hasExpired(entered, t0.plusSeconds(99999), gap)).isFalse()
    }

    @Test
    fun `cooldown runs from the session end, which is one gap after leaving`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val capped = SessionMachine.markCapped(entered)
        val left = SessionMachine.onLeaveForeground(capped, t0.plusSeconds(600))
        assertThat(SessionMachine.cooldownEndsAt(left, gap, cooldown))
            .isEqualTo(t0.plusSeconds(600 + 300 + 900))
    }

    @Test
    fun `a session that was not capped has no cooldown`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(60))
        assertThat(SessionMachine.cooldownEndsAt(left, gap, cooldown)).isNull()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rules:test --tests '*SessionMachineTest'`
Expected: FAIL — `Unresolved reference: SessionMachine`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.toKotlinDuration
import kotlin.time.toJavaDuration

/**
 * One continuous stretch of use of a single app.
 *
 * [accumulated] is foreground time banked before the current stint. While the app
 * is in the foreground [enteredForegroundAt] is set; while it is backgrounded but
 * the session is still alive, [leftForegroundAt] is set. Exactly one of the two is
 * non-null at any time.
 */
data class SessionState(
    val packageName: String,
    val startedAt: Instant,
    val accumulated: Duration,
    val enteredForegroundAt: Instant?,
    val leftForegroundAt: Instant?,
    val endedByCap: Boolean = false,
)

object SessionMachine {

    fun onEnterForeground(
        prev: SessionState?,
        packageName: String,
        now: Instant,
        gap: Duration,
    ): SessionState {
        val continues = prev != null &&
            prev.packageName == packageName &&
            !hasExpired(prev, now, gap)

        return if (continues) {
            prev!!.copy(enteredForegroundAt = now, leftForegroundAt = null)
        } else {
            SessionState(
                packageName = packageName,
                startedAt = now,
                accumulated = ZERO,
                enteredForegroundAt = now,
                leftForegroundAt = null,
            )
        }
    }

    fun onLeaveForeground(prev: SessionState, now: Instant): SessionState {
        val entered = prev.enteredForegroundAt ?: return prev
        val stint = JavaDuration.between(entered, now).toKotlinDuration()
        return prev.copy(
            accumulated = prev.accumulated + stint,
            enteredForegroundAt = null,
            leftForegroundAt = now,
        )
    }

    fun lengthAt(state: SessionState, now: Instant): Duration {
        val entered = state.enteredForegroundAt ?: return state.accumulated
        return state.accumulated + JavaDuration.between(entered, now).toKotlinDuration()
    }

    /** A session ends once the app has been out of the foreground for a full [gap]. */
    fun hasExpired(state: SessionState, now: Instant, gap: Duration): Boolean {
        val left = state.leftForegroundAt ?: return false
        return !now.isBefore(left.plus(gap.toJavaDuration()))
    }

    fun markCapped(state: SessionState): SessionState = state.copy(endedByCap = true)

    /** Non-null only for a capped session that has left the foreground (FR-14.3). */
    fun cooldownEndsAt(state: SessionState, gap: Duration, cooldown: Duration): Instant? {
        if (!state.endedByCap) return null
        val left = state.leftForegroundAt ?: return null
        return left.plus(gap.toJavaDuration()).plus(cooldown.toJavaDuration())
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :rules:test --tests '*SessionMachineTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add rules/
git commit -m "feat: add session state machine with gap and cooldown arithmetic"
```

---

### Task 4: `LimitEngine` — the decision function

**Files:**
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/Decision.kt`
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/LimitEngine.kt`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/LimitEngineTest.kt`

**Interfaces:**
- Consumes: `LimitConfig`, `DayBoundary` (Task 2), `SessionState`, `SessionMachine` (Task 3).
- Produces:
  - `data class UsageSnapshot(packageName: String, usedToday: Duration, session: SessionState?)`
  - `enum class BlockReason { DAILY_BUDGET_EXHAUSTED, SESSION_CAP_REACHED, IN_COOLDOWN }`
  - `sealed interface Decision` with `Decision.Allow`, `Decision.Warn(packageName, remaining)`, `Decision.Block(packageName, reason, liftsAt)`
  - `object LimitEngine { fun decide(config: LimitConfig, usage: UsageSnapshot, now: Instant, zone: ZoneId): Decision }`

**Precedence, decided here and relied on by later tasks:** daily budget is checked first, then session cap, then cooldown. Daily exhaustion is the longest-lived block, so when several conditions hold at once the user is shown the one that lifts last. Warning is only ever returned when nothing blocks.

**On blocking for a session cap:** the user is about to be forced out, so the earliest the block can lift is `now + sessionGap + cooldown`. `liftsAt` carries that value; the coordinator (Task 15) marks the session capped and records the exit so the stored cooldown agrees.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class LimitEngineTest {
    private val zone = ZoneId.of("Europe/London")
    private val t0 = Instant.parse("2026-08-13T10:00:00Z")
    private val nextMidnight = Instant.parse("2026-08-13T23:00:00Z")

    private fun snapshot(
        used: kotlin.time.Duration = 0.minutes,
        session: SessionState? = null,
    ) = UsageSnapshot("com.example", used, session)

    @Test
    fun `allows an app well inside its daily budget`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 10.minutes), t0, zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `blocks when the daily budget is exactly spent`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes)
        val decision = LimitEngine.decide(config, snapshot(used = 30.minutes), t0, zone)
        assertThat(decision).isEqualTo(
            Decision.Block("com.example", BlockReason.DAILY_BUDGET_EXHAUSTED, nextMidnight)
        )
    }

    @Test
    fun `daily block lifts at the next local midnight`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes)
        val decision = LimitEngine.decide(config, snapshot(used = 45.minutes), t0, zone)
        assertThat((decision as Decision.Block).liftsAt).isEqualTo(nextMidnight)
    }

    @Test
    fun `warns once remaining budget reaches the threshold`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, warningThreshold = 5.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 25.minutes), t0, zone))
            .isEqualTo(Decision.Warn("com.example", 5.minutes))
    }

    @Test
    fun `does not warn while remaining budget is above the threshold`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, warningThreshold = 5.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 24.minutes), t0, zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `a session-only limit never warns and never blocks on daily usage`() {
        val config = LimitConfig("com.example", sessionCap = 10.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 600.minutes), t0, zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `blocks when the session cap is reached`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val session = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val now = t0.plusSeconds(600)
        val decision = LimitEngine.decide(config, snapshot(session = session), now, zone)
        assertThat(decision).isEqualTo(
            Decision.Block(
                "com.example",
                BlockReason.SESSION_CAP_REACHED,
                now.plusSeconds(300 + 900),
            )
        )
    }

    @Test
    fun `blocks while inside the cooldown of a capped session`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(600))
        val cooldownEnd = t0.plusSeconds(600 + 300 + 900)
        val decision = LimitEngine.decide(config, snapshot(session = left), t0.plusSeconds(1000), zone)
        assertThat(decision).isEqualTo(
            Decision.Block("com.example", BlockReason.IN_COOLDOWN, cooldownEnd)
        )
    }

    @Test
    fun `allows again once the cooldown has passed`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(600))
        assertThat(LimitEngine.decide(config, snapshot(session = left), t0.plusSeconds(1801), zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `daily exhaustion outranks a session cap when both hold`() {
        val config = LimitConfig(
            "com.example", dailyBudget = 30.minutes, sessionCap = 10.minutes
        )
        val session = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val decision = LimitEngine.decide(
            config, snapshot(used = 30.minutes, session = session), t0.plusSeconds(600), zone
        )
        assertThat((decision as Decision.Block).reason).isEqualTo(BlockReason.DAILY_BUDGET_EXHAUSTED)
    }

    @Test
    fun `daily exhaustion blocks mid-session with no grace`() {
        // FR-15: three minutes into a session, the daily budget runs dry.
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, sessionCap = 60.minutes)
        val session = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val decision = LimitEngine.decide(
            config, snapshot(used = 30.minutes, session = session), t0.plusSeconds(180), zone
        )
        assertThat(decision).isInstanceOf(Decision.Block::class.java)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rules:test --tests '*LimitEngineTest'`
Expected: FAIL — `Unresolved reference: LimitEngine`.

- [ ] **Step 3: Write the implementation**

```kotlin
// Decision.kt
package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import kotlin.time.Duration

/** What the ledger knows about one app right now. */
data class UsageSnapshot(
    val packageName: String,
    val usedToday: Duration,
    val session: SessionState?,
)

enum class BlockReason {
    DAILY_BUDGET_EXHAUSTED,
    SESSION_CAP_REACHED,
    IN_COOLDOWN,
}

sealed interface Decision {
    data object Allow : Decision

    data class Warn(val packageName: String, val remaining: Duration) : Decision

    data class Block(
        val packageName: String,
        val reason: BlockReason,
        val liftsAt: Instant,
    ) : Decision
}
```

```kotlin
// LimitEngine.kt
package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import java.time.ZoneId
import kotlin.time.toJavaDuration

/**
 * The only place that decides whether an app may be used (FR-14).
 *
 * Pure: no clock field, no Android, no I/O. Everything it needs arrives as an
 * argument, which is what makes the whole rule set testable on the JVM.
 */
object LimitEngine {

    fun decide(
        config: LimitConfig,
        usage: UsageSnapshot,
        now: Instant,
        zone: ZoneId,
    ): Decision {
        val pkg = config.packageName

        // 1. Daily budget — the longest-lived block, so it is checked first.
        val budget = config.dailyBudget
        if (budget != null && usage.usedToday >= budget) {
            return Decision.Block(
                pkg,
                BlockReason.DAILY_BUDGET_EXHAUSTED,
                DayBoundary.nextResetAt(now, zone),
            )
        }

        val cap = config.sessionCap
        val session = usage.session

        // 2. Session cap, while the session is running.
        if (cap != null && session != null && session.enteredForegroundAt != null) {
            if (SessionMachine.lengthAt(session, now) >= cap) {
                val liftsAt = now
                    .plus(config.sessionGap.toJavaDuration())
                    .plus(config.cooldown.toJavaDuration())
                return Decision.Block(pkg, BlockReason.SESSION_CAP_REACHED, liftsAt)
            }
        }

        // 3. Cooldown following a capped session.
        if (session != null) {
            val cooldownEnd = SessionMachine.cooldownEndsAt(session, config.sessionGap, config.cooldown)
            if (cooldownEnd != null && now.isBefore(cooldownEnd)) {
                return Decision.Block(pkg, BlockReason.IN_COOLDOWN, cooldownEnd)
            }
        }

        // 4. Warning — daily budgets only (FR-19).
        if (budget != null) {
            val remaining = budget - usage.usedToday
            if (remaining <= config.warningThreshold) {
                return Decision.Warn(pkg, remaining)
            }
        }

        return Decision.Allow
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :rules:test --tests '*LimitEngineTest'`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add rules/
git commit -m "feat: add LimitEngine decision function"
```

---

### Task 5: `ChangeClassifier` — tightening now, loosening tomorrow

**Files:**
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/ChangeClassifier.kt`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/ChangeClassifierTest.kt`

**Interfaces:**
- Consumes: `LimitConfig` (Task 2), `DayBoundary` (Task 2).
- Produces:
  - `enum class ChangeKind { TIGHTENING, LOOSENING }`
  - `object ChangeClassifier { fun classify(old: LimitConfig, new: LimitConfig): ChangeKind }`
  - `enum class PendingKind { UPDATE, REMOVE }`
  - `data class PendingChange(packageName: String, kind: PendingKind, payload: LimitConfig?, effectiveDate: LocalDate)`
  - `object PendingChangeResolver { fun effectiveDateFor(now: Instant, zone: ZoneId): LocalDate; fun due(changes: List<PendingChange>, today: LocalDate): List<PendingChange> }`

**Two decisions made here that the PRD leaves open — implement them exactly as stated:**

1. **A mixed edit is a loosening.** If a single edit tightens one field and loosens another, the *whole* edit becomes pending. Splitting an edit so the tightening half lands today would let a user pair a token tightening with a real loosening and get the loosening applied through a partial write; treating the edit as one unit closes that.
2. **`warningThreshold` is neutral.** Changing when you get warned neither tightens nor loosens a limit, so a warning-only edit applies immediately and never creates a pending change.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class ChangeClassifierTest {
    private val zone = ZoneId.of("Europe/London")
    private val base = LimitConfig(
        packageName = "com.example",
        dailyBudget = 30.minutes,
        sessionCap = 10.minutes,
        sessionGap = 5.minutes,
        cooldown = 15.minutes,
        warningThreshold = 5.minutes,
    )

    @Test
    fun `lowering the daily budget is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(dailyBudget = 20.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `raising the daily budget is a loosening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(dailyBudget = 45.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `removing the daily budget is a loosening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(dailyBudget = null)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `adding a daily budget that did not exist is a tightening`() {
        val sessionOnly = LimitConfig("com.example", sessionCap = 10.minutes)
        assertThat(ChangeClassifier.classify(sessionOnly, sessionOnly.copy(dailyBudget = 30.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `raising the session cap is a loosening and lowering it is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(sessionCap = 20.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
        assertThat(ChangeClassifier.classify(base, base.copy(sessionCap = 5.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `removing the session cap is a loosening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(sessionCap = null)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `lowering the cooldown is a loosening and raising it is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(cooldown = 5.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
        assertThat(ChangeClassifier.classify(base, base.copy(cooldown = 30.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `raising the session gap is a loosening`() {
        // A longer gap means a session ends later, so more use fits in one session.
        assertThat(ChangeClassifier.classify(base, base.copy(sessionGap = 10.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `changing only the warning threshold is a tightening so it applies immediately`() {
        assertThat(ChangeClassifier.classify(base, base.copy(warningThreshold = 10.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `no change at all is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base)).isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `a mixed edit counts as a loosening`() {
        val mixed = base.copy(dailyBudget = 20.minutes, sessionCap = 30.minutes)
        assertThat(ChangeClassifier.classify(base, mixed)).isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `a pending change takes effect on the next local day`() {
        val now = Instant.parse("2026-08-13T22:00:00Z") // 23:00 London
        assertThat(PendingChangeResolver.effectiveDateFor(now, zone))
            .isEqualTo(LocalDate.of(2026, 8, 14))
    }

    @Test
    fun `changes are due on and after their effective date`() {
        val change = PendingChange("com.example", PendingKind.UPDATE, base, LocalDate.of(2026, 8, 14))
        assertThat(PendingChangeResolver.due(listOf(change), LocalDate.of(2026, 8, 13))).isEmpty()
        assertThat(PendingChangeResolver.due(listOf(change), LocalDate.of(2026, 8, 14)))
            .containsExactly(change)
        assertThat(PendingChangeResolver.due(listOf(change), LocalDate.of(2026, 8, 20)))
            .containsExactly(change)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rules:test --tests '*ChangeClassifierTest'`
Expected: FAIL — `Unresolved reference: ChangeClassifier`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration

enum class ChangeKind { TIGHTENING, LOOSENING }

enum class PendingKind { UPDATE, REMOVE }

/**
 * A queued loosening, applied at the next daily reset (FR-23).
 * [payload] is null when [kind] is [PendingKind.REMOVE].
 */
data class PendingChange(
    val packageName: String,
    val kind: PendingKind,
    val payload: LimitConfig?,
    val effectiveDate: LocalDate,
)

object ChangeClassifier {

    /**
     * A change is a LOOSENING if any single field loosens; see the plan's task
     * notes for why a mixed edit is not split.
     */
    fun classify(old: LimitConfig, new: LimitConfig): ChangeKind {
        val loosens = capLoosens(old.dailyBudget, new.dailyBudget) ||
            capLoosens(old.sessionCap, new.sessionCap) ||
            new.cooldown < old.cooldown ||
            new.sessionGap > old.sessionGap
        return if (loosens) ChangeKind.LOOSENING else ChangeKind.TIGHTENING
    }

    /** Removing a cap, or raising it, loosens. Adding one where there was none tightens. */
    private fun capLoosens(old: Duration?, new: Duration?): Boolean = when {
        old == null -> false
        new == null -> true
        else -> new > old
    }
}

object PendingChangeResolver {

    fun effectiveDateFor(now: Instant, zone: ZoneId): LocalDate =
        DayBoundary.localDate(now, zone).plusDays(1)

    fun due(changes: List<PendingChange>, today: LocalDate): List<PendingChange> =
        changes.filter { !today.isBefore(it.effectiveDate) }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :rules:test`
Expected: PASS, all rules-module tests green.

- [ ] **Step 5: Commit**

```bash
git add rules/
git commit -m "feat: classify limit edits as tightening or loosening"
```

---

### Task 6: Room schema, DAOs and domain mapping

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/data/Entities.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/data/Daos.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/data/TimeLimiterDatabase.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/data/Mappers.kt`
- Test: `app/src/androidTest/kotlin/com/dylanhamersztein/timelimiter/data/DaoTest.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/data/MappersTest.kt`

**Interfaces:**
- Consumes: `LimitConfig`, `PendingChange`, `PendingKind`, `SessionState` from `:rules`.
- Produces:
  - Entities `TrackedAppEntity`, `LimitEntity`, `PendingChangeEntity`, `DailyUsageEntity`, `SessionEntity`, `WarningSentEntity`
  - DAOs `LimitDao`, `UsageDao`, `SessionDao`
  - `TimeLimiterDatabase` with `fun limitDao(): LimitDao`, `fun usageDao(): UsageDao`, `fun sessionDao(): SessionDao`
  - Mapping functions `LimitEntity.toDomain(): LimitConfig`, `LimitConfig.toEntity(): LimitEntity`, and the equivalents for `PendingChange` and `SessionState`

- [ ] **Step 1: Write the entities and database**

```kotlin
// Entities.kt
package com.dylanhamersztein.timelimiter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_apps")
data class TrackedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val addedAtEpochMillis: Long,
)

@Entity(tableName = "limits")
data class LimitEntity(
    @PrimaryKey val packageName: String,
    val dailyBudgetMinutes: Int?,
    val sessionCapMinutes: Int?,
    val sessionGapMinutes: Int,
    val cooldownMinutes: Int,
    val warningThresholdMinutes: Int,
)

@Entity(tableName = "pending_changes")
data class PendingChangeEntity(
    @PrimaryKey val packageName: String,
    val kind: String, // "UPDATE" | "REMOVE"
    val dailyBudgetMinutes: Int?,
    val sessionCapMinutes: Int?,
    val sessionGapMinutes: Int?,
    val cooldownMinutes: Int?,
    val warningThresholdMinutes: Int?,
    val effectiveDateIso: String, // ISO-8601 local date
)

@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "localDateIso"])
data class DailyUsageEntity(
    val packageName: String,
    val localDateIso: String,
    val foregroundSeconds: Long,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val packageName: String,
    val startedAtEpochMillis: Long,
    val accumulatedSeconds: Long,
    val enteredForegroundAtEpochMillis: Long?,
    val leftForegroundAtEpochMillis: Long?,
    val endedByCap: Boolean,
)

@Entity(tableName = "warnings_sent", primaryKeys = ["packageName", "localDateIso"])
data class WarningSentEntity(
    val packageName: String,
    val localDateIso: String,
)
```

Note there is exactly one `pending_changes` row per package: a new edit replaces any existing pending change for that app (FR-24), which the `OnConflictStrategy.REPLACE` upsert below gives for free.

```kotlin
// TimeLimiterDatabase.kt
package com.dylanhamersztein.timelimiter.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackedAppEntity::class,
        LimitEntity::class,
        PendingChangeEntity::class,
        DailyUsageEntity::class,
        SessionEntity::class,
        WarningSentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TimeLimiterDatabase : RoomDatabase() {
    abstract fun limitDao(): LimitDao
    abstract fun usageDao(): UsageDao
    abstract fun sessionDao(): SessionDao
}
```

Add the schema export directory to `app/build.gradle.kts` inside `android { }`:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
```

- [ ] **Step 2: Write the DAOs**

```kotlin
// Daos.kt
package com.dylanhamersztein.timelimiter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LimitDao {
    @Query("SELECT * FROM tracked_apps ORDER BY label COLLATE NOCASE")
    fun observeTrackedApps(): Flow<List<TrackedAppEntity>>

    @Query("SELECT * FROM limits")
    fun observeLimits(): Flow<List<LimitEntity>>

    @Query("SELECT * FROM limits WHERE packageName = :packageName")
    suspend fun limitFor(packageName: String): LimitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackedApp(app: TrackedAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLimit(limit: LimitEntity)

    @Query("DELETE FROM tracked_apps WHERE packageName = :packageName")
    suspend fun deleteTrackedApp(packageName: String)

    @Query("DELETE FROM limits WHERE packageName = :packageName")
    suspend fun deleteLimit(packageName: String)

    @Transaction
    suspend fun untrack(packageName: String) {
        deleteLimit(packageName)
        deleteTrackedApp(packageName)
    }

    @Query("SELECT * FROM pending_changes")
    fun observePendingChanges(): Flow<List<PendingChangeEntity>>

    @Query("SELECT * FROM pending_changes")
    suspend fun allPendingChanges(): List<PendingChangeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingChange(change: PendingChangeEntity)

    @Query("DELETE FROM pending_changes WHERE packageName = :packageName")
    suspend fun deletePendingChange(packageName: String)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM daily_usage WHERE localDateIso = :dateIso")
    fun observeUsageOn(dateIso: String): Flow<List<DailyUsageEntity>>

    @Query("SELECT * FROM daily_usage WHERE packageName = :packageName AND localDateIso = :dateIso")
    suspend fun usageFor(packageName: String, dateIso: String): DailyUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: DailyUsageEntity)

    @Query("DELETE FROM daily_usage WHERE localDateIso < :dateIso")
    suspend fun deleteUsageBefore(dateIso: String)

    @Query("SELECT COUNT(*) FROM warnings_sent WHERE packageName = :packageName AND localDateIso = :dateIso")
    suspend fun warningCount(packageName: String, dateIso: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordWarning(warning: WarningSentEntity)

    @Query("DELETE FROM warnings_sent WHERE localDateIso < :dateIso")
    suspend fun deleteWarningsBefore(dateIso: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE packageName = :packageName")
    suspend fun sessionFor(packageName: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE packageName = :packageName")
    suspend fun deleteSession(packageName: String)
}
```

Sessions are persisted so a cooldown survives a service restart or reboot (FR-28). Only the most recent session per package is kept — history is out of scope (§12).

- [ ] **Step 3: Write the mappers and their unit test**

```kotlin
// Mappers.kt
package com.dylanhamersztein.timelimiter.data

import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.PendingChange
import com.dylanhamersztein.timelimiter.rules.PendingKind
import com.dylanhamersztein.timelimiter.rules.SessionState
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun LimitEntity.toDomain(): LimitConfig = LimitConfig(
    packageName = packageName,
    dailyBudget = dailyBudgetMinutes?.minutes,
    sessionCap = sessionCapMinutes?.minutes,
    sessionGap = sessionGapMinutes.minutes,
    cooldown = cooldownMinutes.minutes,
    warningThreshold = warningThresholdMinutes.minutes,
)

fun LimitConfig.toEntity(): LimitEntity = LimitEntity(
    packageName = packageName,
    dailyBudgetMinutes = dailyBudget?.wholeMinutesInt(),
    sessionCapMinutes = sessionCap?.wholeMinutesInt(),
    sessionGapMinutes = sessionGap.wholeMinutesInt(),
    cooldownMinutes = cooldown.wholeMinutesInt(),
    warningThresholdMinutes = warningThreshold.wholeMinutesInt(),
)

fun PendingChangeEntity.toDomain(): PendingChange = PendingChange(
    packageName = packageName,
    kind = PendingKind.valueOf(kind),
    payload = if (kind == PendingKind.REMOVE.name) null else LimitConfig(
        packageName = packageName,
        dailyBudget = dailyBudgetMinutes?.minutes,
        sessionCap = sessionCapMinutes?.minutes,
        sessionGap = requireNotNull(sessionGapMinutes).minutes,
        cooldown = requireNotNull(cooldownMinutes).minutes,
        warningThreshold = requireNotNull(warningThresholdMinutes).minutes,
    ),
    effectiveDate = LocalDate.parse(effectiveDateIso),
)

fun PendingChange.toEntity(): PendingChangeEntity = PendingChangeEntity(
    packageName = packageName,
    kind = kind.name,
    dailyBudgetMinutes = payload?.dailyBudget?.wholeMinutesInt(),
    sessionCapMinutes = payload?.sessionCap?.wholeMinutesInt(),
    sessionGapMinutes = payload?.sessionGap?.wholeMinutesInt(),
    cooldownMinutes = payload?.cooldown?.wholeMinutesInt(),
    warningThresholdMinutes = payload?.warningThreshold?.wholeMinutesInt(),
    effectiveDateIso = effectiveDate.toString(),
)

fun SessionEntity.toDomain(): SessionState = SessionState(
    packageName = packageName,
    startedAt = Instant.ofEpochMilli(startedAtEpochMillis),
    accumulated = accumulatedSeconds.seconds,
    enteredForegroundAt = enteredForegroundAtEpochMillis?.let(Instant::ofEpochMilli),
    leftForegroundAt = leftForegroundAtEpochMillis?.let(Instant::ofEpochMilli),
    endedByCap = endedByCap,
)

fun SessionState.toEntity(): SessionEntity = SessionEntity(
    packageName = packageName,
    startedAtEpochMillis = startedAt.toEpochMilli(),
    accumulatedSeconds = accumulated.inWholeSeconds,
    enteredForegroundAtEpochMillis = enteredForegroundAt?.toEpochMilli(),
    leftForegroundAtEpochMillis = leftForegroundAt?.toEpochMilli(),
    endedByCap = endedByCap,
)

private fun Duration.wholeMinutesInt(): Int = inWholeMinutes.toInt()
```

```kotlin
// MappersTest.kt
package com.dylanhamersztein.timelimiter.data

import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.PendingChange
import com.dylanhamersztein.timelimiter.rules.PendingKind
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class MappersTest {
    @Test
    fun `limit config survives a round trip`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, sessionCap = 10.minutes)
        assertThat(config.toEntity().toDomain()).isEqualTo(config)
    }

    @Test
    fun `a session-only limit round trips with a null daily budget`() {
        val config = LimitConfig("com.example", sessionCap = 10.minutes)
        assertThat(config.toEntity().toDomain().dailyBudget).isNull()
    }

    @Test
    fun `a removal pending change round trips with a null payload`() {
        val change = PendingChange("com.example", PendingKind.REMOVE, null, LocalDate.of(2026, 8, 14))
        assertThat(change.toEntity().toDomain()).isEqualTo(change)
    }

    @Test
    fun `an update pending change round trips`() {
        val payload = LimitConfig("com.example", dailyBudget = 45.minutes)
        val change = PendingChange("com.example", PendingKind.UPDATE, payload, LocalDate.of(2026, 8, 14))
        assertThat(change.toEntity().toDomain()).isEqualTo(change)
    }
}
```

- [ ] **Step 4: Write the DAO instrumented test**

```kotlin
package com.dylanhamersztein.timelimiter.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: TimeLimiterDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TimeLimiterDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun untrackRemovesBothTheAppAndItsLimit() = runTest {
        val dao = db.limitDao()
        dao.upsertTrackedApp(TrackedAppEntity("com.example", "Example", 0L))
        dao.upsertLimit(LimitEntity("com.example", 30, null, 5, 15, 5))

        dao.untrack("com.example")

        assertThat(dao.limitFor("com.example")).isNull()
    }

    @Test
    fun anEditReplacesAnExistingPendingChange() = runTest {
        val dao = db.limitDao()
        dao.upsertPendingChange(
            PendingChangeEntity("com.example", "UPDATE", 45, null, 5, 15, 5, "2026-08-14")
        )
        dao.upsertPendingChange(
            PendingChangeEntity("com.example", "UPDATE", 60, null, 5, 15, 5, "2026-08-14")
        )

        val pending = dao.allPendingChanges()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().dailyBudgetMinutes).isEqualTo(60)
    }

    @Test
    fun aWarningIsRecordedOnlyOncePerAppPerDay() = runTest {
        val dao = db.usageDao()
        dao.recordWarning(WarningSentEntity("com.example", "2026-08-13"))
        dao.recordWarning(WarningSentEntity("com.example", "2026-08-13"))

        assertThat(dao.warningCount("com.example", "2026-08-13")).isEqualTo(1)
    }

    @Test
    fun usageIsKeyedByPackageAndDate() = runTest {
        val dao = db.usageDao()
        dao.upsertUsage(DailyUsageEntity("com.example", "2026-08-13", 600))
        dao.upsertUsage(DailyUsageEntity("com.example", "2026-08-14", 120))
        dao.upsertUsage(DailyUsageEntity("com.example", "2026-08-13", 900))

        assertThat(dao.usageFor("com.example", "2026-08-13")?.foregroundSeconds).isEqualTo(900)
        assertThat(dao.usageFor("com.example", "2026-08-14")?.foregroundSeconds).isEqualTo(120)
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest` (mappers) then `./gradlew :app:connectedDebugAndroidTest` (DAOs, needs a device or emulator).
Expected: PASS. If no device is attached, the instrumented run cannot be verified — say so rather than claiming it passed.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: add Room schema, DAOs and domain mappers"
```

---

### Task 7: Passcode and settings store

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/data/SettingsStore.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/data/PasscodeHasher.kt`
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/PasscodeRecovery.kt`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/PasscodeRecoveryTest.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/data/PasscodeHasherTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `object PasscodeHasher { fun hash(code: String, salt: ByteArray): String; fun newSalt(): ByteArray }`
  - `class SettingsStore(context: Context)` exposing `val passcodeIsSet: Flow<Boolean>`, `suspend fun setPasscode(code: String)`, `suspend fun verify(code: String): Boolean`, `val recoveryRequestedAt: Flow<Instant?>`, `suspend fun requestRecovery(now: Instant)`, `suspend fun cancelRecovery()`, `val onboardingComplete: Flow<Boolean>`, `suspend fun setOnboardingComplete()`
  - `object PasscodeRecovery { val DELAY: Duration; fun availableAt(requestedAt: Instant): Instant; fun isAvailable(requestedAt: Instant?, now: Instant): Boolean; fun remaining(requestedAt: Instant?, now: Instant): Duration? }`

**Honesty note for the implementer:** a 4-digit code has 10,000 possibilities, so hashing it does not withstand anyone with the device and a will to brute-force it. That is fine — the threat model here is the owner in a weak moment, not an attacker. Hash it anyway so the code is not sitting in plaintext in `shared_prefs`, and do not describe it as security in any user-facing copy.

- [ ] **Step 1: Write the failing recovery test**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class PasscodeRecoveryTest {
    private val requestedAt = Instant.parse("2026-08-13T10:00:00Z")

    @Test
    fun `recovery becomes available twenty four hours after the request`() {
        assertThat(PasscodeRecovery.availableAt(requestedAt))
            .isEqualTo(Instant.parse("2026-08-14T10:00:00Z"))
    }

    @Test
    fun `recovery is unavailable before the delay elapses`() {
        assertThat(PasscodeRecovery.isAvailable(requestedAt, requestedAt.plusSeconds(86_399))).isFalse()
    }

    @Test
    fun `recovery is available once the delay elapses`() {
        assertThat(PasscodeRecovery.isAvailable(requestedAt, requestedAt.plusSeconds(86_400))).isTrue()
    }

    @Test
    fun `recovery is unavailable when no request was made`() {
        assertThat(PasscodeRecovery.isAvailable(null, requestedAt)).isFalse()
        assertThat(PasscodeRecovery.remaining(null, requestedAt)).isNull()
    }

    @Test
    fun `remaining time counts down`() {
        assertThat(PasscodeRecovery.remaining(requestedAt, requestedAt.plusSeconds(3600)))
            .isEqualTo(23.hours)
        assertThat(PasscodeRecovery.remaining(requestedAt, requestedAt.plusSeconds(86_100)))
            .isEqualTo(5.minutes)
    }

    @Test
    fun `remaining time never goes negative`() {
        assertThat(PasscodeRecovery.remaining(requestedAt, requestedAt.plusSeconds(200_000)))
            .isEqualTo(kotlin.time.Duration.ZERO)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :rules:test --tests '*PasscodeRecoveryTest'`
Expected: FAIL — `Unresolved reference: PasscodeRecovery`.

- [ ] **Step 3: Implement `PasscodeRecovery`**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** FR-26: a forgotten passcode can be reset, but only after a visible 24-hour wait. */
object PasscodeRecovery {
    val DELAY: Duration = 24.hours

    fun availableAt(requestedAt: Instant): Instant = requestedAt.plus(DELAY.toJavaDuration())

    fun isAvailable(requestedAt: Instant?, now: Instant): Boolean {
        if (requestedAt == null) return false
        return !now.isBefore(availableAt(requestedAt))
    }

    fun remaining(requestedAt: Instant?, now: Instant): Duration? {
        if (requestedAt == null) return null
        val left = JavaDuration.between(now, availableAt(requestedAt))
        return if (left.isNegative) Duration.ZERO else left.toKotlinDuration()
    }
}
```

- [ ] **Step 4: Write the hasher and its test**

```kotlin
// PasscodeHasher.kt
package com.dylanhamersztein.timelimiter.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasscodeHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(code: String, salt: ByteArray): String {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return Base64.getEncoder().encodeToString(key.encoded)
    }
}
```

```kotlin
// PasscodeHasherTest.kt
package com.dylanhamersztein.timelimiter.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasscodeHasherTest {
    @Test
    fun `the same code and salt hash identically`() {
        val salt = PasscodeHasher.newSalt()
        assertThat(PasscodeHasher.hash("1234", salt)).isEqualTo(PasscodeHasher.hash("1234", salt))
    }

    @Test
    fun `different codes hash differently`() {
        val salt = PasscodeHasher.newSalt()
        assertThat(PasscodeHasher.hash("1234", salt)).isNotEqualTo(PasscodeHasher.hash("4321", salt))
    }

    @Test
    fun `the same code under different salts hashes differently`() {
        assertThat(PasscodeHasher.hash("1234", PasscodeHasher.newSalt()))
            .isNotEqualTo(PasscodeHasher.hash("1234", PasscodeHasher.newSalt()))
    }
}
```

- [ ] **Step 5: Implement `SettingsStore`**

```kotlin
package com.dylanhamersztein.timelimiter.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "time_limiter_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val PASSCODE_HASH = stringPreferencesKey("passcode_hash")
        val PASSCODE_SALT = stringPreferencesKey("passcode_salt")
        val RECOVERY_REQUESTED_AT = longPreferencesKey("recovery_requested_at")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    val passcodeIsSet: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PASSCODE_HASH] != null }

    val recoveryRequestedAt: Flow<Instant?> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.RECOVERY_REQUESTED_AT]?.let(Instant::ofEpochMilli)
        }

    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ONBOARDING_COMPLETE] ?: false }

    suspend fun setPasscode(code: String) {
        require(code.length == 4 && code.all(Char::isDigit)) { "passcode must be four digits" }
        val salt = PasscodeHasher.newSalt()
        context.dataStore.edit { prefs ->
            prefs[Keys.PASSCODE_SALT] = Base64.getEncoder().encodeToString(salt)
            prefs[Keys.PASSCODE_HASH] = PasscodeHasher.hash(code, salt)
            prefs.remove(Keys.RECOVERY_REQUESTED_AT)
        }
    }

    suspend fun verify(code: String): Boolean {
        val prefs = context.dataStore.data.first()
        val storedHash = prefs[Keys.PASSCODE_HASH] ?: return false
        val salt = Base64.getDecoder().decode(prefs[Keys.PASSCODE_SALT] ?: return false)
        return PasscodeHasher.hash(code, salt) == storedHash
    }

    suspend fun requestRecovery(now: Instant) {
        context.dataStore.edit { it[Keys.RECOVERY_REQUESTED_AT] = now.toEpochMilli() }
    }

    suspend fun cancelRecovery() {
        context.dataStore.edit { it.remove(Keys.RECOVERY_REQUESTED_AT) }
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = true }
    }
}
```

Note `setPasscode` clears any recovery request, so re-requesting after a reset starts the full 24 hours again (FR-26).

- [ ] **Step 6: Run the tests**

Run: `./gradlew :rules:test :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add rules/ app/
git commit -m "feat: add passcode hashing, settings store and 24-hour recovery"
```

---

### Task 8: `ForegroundTimeCalculator` — folding usage events into totals

**Files:**
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/UsageEvent.kt`
- Create: `rules/src/main/kotlin/com/dylanhamersztein/timelimiter/rules/ForegroundTimeCalculator.kt`
- Test: `rules/src/test/kotlin/com/dylanhamersztein/timelimiter/rules/ForegroundTimeCalculatorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class UsageEventType { RESUMED, PAUSED }`
  - `data class UsageEvent(packageName: String, type: UsageEventType, at: Instant)`
  - `object ForegroundTimeCalculator { fun totals(events: List<UsageEvent>, windowStart: Instant, windowEnd: Instant): Map<String, Duration> }`

**Why this is a separate task:** turning Android's raw event stream into per-app totals is the fiddly part of the ledger — unmatched events at either edge of the window, apps that never paused, events arriving out of order. Keeping it pure means all of that is tested on the JVM, and Task 9's Android class stays a thin adapter with nothing to get wrong.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class ForegroundTimeCalculatorTest {
    private val start = Instant.parse("2026-08-13T00:00:00Z")
    private val end = Instant.parse("2026-08-13T12:00:00Z")

    private fun resumed(pkg: String, minutesIn: Long) =
        UsageEvent(pkg, UsageEventType.RESUMED, start.plusSeconds(minutesIn * 60))

    private fun paused(pkg: String, minutesIn: Long) =
        UsageEvent(pkg, UsageEventType.PAUSED, start.plusSeconds(minutesIn * 60))

    @Test
    fun `sums a single resume-pause pair`() {
        val totals = ForegroundTimeCalculator.totals(
            listOf(resumed("com.a", 10), paused("com.a", 25)), start, end
        )
        assertThat(totals["com.a"]).isEqualTo(15.minutes)
    }

    @Test
    fun `sums multiple pairs for the same app`() {
        val totals = ForegroundTimeCalculator.totals(
            listOf(
                resumed("com.a", 0), paused("com.a", 10),
                resumed("com.a", 30), paused("com.a", 35),
            ),
            start, end,
        )
        assertThat(totals["com.a"]).isEqualTo(15.minutes)
    }

    @Test
    fun `an app still in the foreground accrues up to the window end`() {
        val totals = ForegroundTimeCalculator.totals(listOf(resumed("com.a", 700)), start, end)
        assertThat(totals["com.a"]).isEqualTo(20.minutes)
    }

    @Test
    fun `a pause with no matching resume accrues from the window start`() {
        // The app was already open when the window opened.
        val totals = ForegroundTimeCalculator.totals(listOf(paused("com.a", 12)), start, end)
        assertThat(totals["com.a"]).isEqualTo(12.minutes)
    }

    @Test
    fun `tracks several apps independently`() {
        val totals = ForegroundTimeCalculator.totals(
            listOf(
                resumed("com.a", 0), paused("com.a", 5),
                resumed("com.b", 5), paused("com.b", 20),
            ),
            start, end,
        )
        assertThat(totals["com.a"]).isEqualTo(5.minutes)
        assertThat(totals["com.b"]).isEqualTo(15.minutes)
    }

    @Test
    fun `sorts events that arrive out of order`() {
        val totals = ForegroundTimeCalculator.totals(
            listOf(paused("com.a", 25), resumed("com.a", 10)), start, end
        )
        assertThat(totals["com.a"]).isEqualTo(15.minutes)
    }

    @Test
    fun `ignores a duplicate resume without an intervening pause`() {
        val totals = ForegroundTimeCalculator.totals(
            listOf(resumed("com.a", 10), resumed("com.a", 15), paused("com.a", 25)), start, end
        )
        assertThat(totals["com.a"]).isEqualTo(15.minutes)
    }

    @Test
    fun `ignores events outside the window`() {
        val before = UsageEvent("com.a", UsageEventType.RESUMED, start.minusSeconds(600))
        val after = UsageEvent("com.a", UsageEventType.PAUSED, end.plusSeconds(600))
        val totals = ForegroundTimeCalculator.totals(listOf(before, after), start, end)
        assertThat(totals["com.a"]).isEqualTo(720.minutes) // the entire window
    }

    @Test
    fun `returns an empty map for no events`() {
        assertThat(ForegroundTimeCalculator.totals(emptyList(), start, end)).isEmpty()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rules:test --tests '*ForegroundTimeCalculatorTest'`
Expected: FAIL — `Unresolved reference: ForegroundTimeCalculator`.

- [ ] **Step 3: Write the implementation**

```kotlin
// UsageEvent.kt
package com.dylanhamersztein.timelimiter.rules

import java.time.Instant

enum class UsageEventType { RESUMED, PAUSED }

/** One foreground transition, normalised from whatever the platform reported. */
data class UsageEvent(
    val packageName: String,
    val type: UsageEventType,
    val at: Instant,
)
```

```kotlin
// ForegroundTimeCalculator.kt
package com.dylanhamersztein.timelimiter.rules

import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Folds a stream of resume/pause events into per-package foreground time inside a
 * window. Events are clamped to the window, so an app that was already open when
 * the window opened accrues from [windowStart], and one still open at the end
 * accrues to [windowEnd].
 */
object ForegroundTimeCalculator {

    fun totals(
        events: List<UsageEvent>,
        windowStart: Instant,
        windowEnd: Instant,
    ): Map<String, Duration> {
        val totals = mutableMapOf<String, JavaDuration>()
        val openedAt = mutableMapOf<String, Instant>()

        fun clamp(instant: Instant): Instant = when {
            instant.isBefore(windowStart) -> windowStart
            instant.isAfter(windowEnd) -> windowEnd
            else -> instant
        }

        fun accrue(pkg: String, from: Instant, to: Instant) {
            if (!to.isAfter(from)) return
            totals[pkg] = (totals[pkg] ?: JavaDuration.ZERO).plus(JavaDuration.between(from, to))
        }

        for (event in events.sortedBy { it.at }) {
            val at = clamp(event.at)
            when (event.type) {
                UsageEventType.RESUMED ->
                    // A second RESUMED without a PAUSED is a duplicate; keep the earlier one.
                    openedAt.putIfAbsent(event.packageName, at)

                UsageEventType.PAUSED -> {
                    // No matching RESUMED means the app was already open at windowStart.
                    val from = openedAt.remove(event.packageName) ?: windowStart
                    accrue(event.packageName, from, at)
                }
            }
        }

        // Anything still open accrues to the end of the window.
        for ((pkg, from) in openedAt) {
            accrue(pkg, from, windowEnd)
        }

        return totals.mapValues { (_, duration) -> duration.toKotlinDuration() }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :rules:test --tests '*ForegroundTimeCalculatorTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add rules/
git commit -m "feat: fold usage events into per-package foreground totals"
```

---

### Task 9: Usage stats source and reconciler

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ledger/UsageStatsSource.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ledger/UsageStatsReconciler.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ledger/UsageStatsReconcilerTest.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ledger/FakeUsageDao.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `UsageEvent`, `UsageEventType`, `ForegroundTimeCalculator`, `DayBoundary` (Tasks 2, 8); `UsageDao`, `DailyUsageEntity` (Task 6).
- Produces:
  - `interface UsageStatsSource { fun eventsBetween(from: Instant, to: Instant): List<UsageEvent> }`
  - `class AndroidUsageStatsSource(manager: UsageStatsManager) : UsageStatsSource`
  - `class UsageStatsReconciler(source, usageDao, clock, zone)` with `suspend fun reconcile(packages: Set<String>): Map<String, Duration>`

- [ ] **Step 1: Add the usage-access permission to the manifest**

```xml
<uses-permission
    android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

Add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` element. This is a special-access permission: declaring it is not enough, the user must grant it in Settings (handled in Task 25).

- [ ] **Step 2: Write the fake DAO and the failing reconciler test**

```kotlin
// FakeUsageDao.kt (test source set)
package com.dylanhamersztein.timelimiter.ledger

import com.dylanhamersztein.timelimiter.data.DailyUsageEntity
import com.dylanhamersztein.timelimiter.data.UsageDao
import com.dylanhamersztein.timelimiter.data.WarningSentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeUsageDao : UsageDao {
    val usage = mutableMapOf<Pair<String, String>, DailyUsageEntity>()
    val warnings = mutableSetOf<Pair<String, String>>()

    override fun observeUsageOn(dateIso: String): Flow<List<DailyUsageEntity>> =
        flowOf(usage.values.filter { it.localDateIso == dateIso })

    override suspend fun usageFor(packageName: String, dateIso: String) =
        usage[packageName to dateIso]

    override suspend fun upsertUsage(usageEntity: DailyUsageEntity) {
        usage[usageEntity.packageName to usageEntity.localDateIso] = usageEntity
    }

    override suspend fun deleteUsageBefore(dateIso: String) {
        usage.keys.filter { it.second < dateIso }.forEach(usage::remove)
    }

    override suspend fun warningCount(packageName: String, dateIso: String) =
        if (warnings.contains(packageName to dateIso)) 1 else 0

    override suspend fun recordWarning(warning: WarningSentEntity) {
        warnings.add(warning.packageName to warning.localDateIso)
    }

    override suspend fun deleteWarningsBefore(dateIso: String) {
        warnings.removeAll { it.second < dateIso }
    }
}
```

```kotlin
// UsageStatsReconcilerTest.kt
package com.dylanhamersztein.timelimiter.ledger

import com.dylanhamersztein.timelimiter.rules.TestClock
import com.dylanhamersztein.timelimiter.rules.UsageEvent
import com.dylanhamersztein.timelimiter.rules.UsageEventType
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UsageStatsReconcilerTest {
    private val zone = ZoneId.of("Europe/London")
    // 2026-08-13T10:00 London == 09:00Z; local midnight was 2026-08-12T23:00Z.
    private val now = Instant.parse("2026-08-13T09:00:00Z")
    private val localMidnight = Instant.parse("2026-08-12T23:00:00Z")

    private class FakeSource(val events: List<UsageEvent>) : UsageStatsSource {
        var lastWindow: Pair<Instant, Instant>? = null
        override fun eventsBetween(from: Instant, to: Instant): List<UsageEvent> {
            lastWindow = from to to
            return events
        }
    }

    @Test
    fun `queries the window from local midnight to now`() = runTest {
        val source = FakeSource(emptyList())
        val reconciler = UsageStatsReconciler(source, FakeUsageDao(), TestClock(now, zone), zone)

        reconciler.reconcile(setOf("com.a"))

        assertThat(source.lastWindow).isEqualTo(localMidnight to now)
    }

    @Test
    fun `writes today's total for a tracked package`() = runTest {
        val source = FakeSource(
            listOf(
                UsageEvent("com.a", UsageEventType.RESUMED, localMidnight.plusSeconds(3600)),
                UsageEvent("com.a", UsageEventType.PAUSED, localMidnight.plusSeconds(4500)),
            )
        )
        val dao = FakeUsageDao()
        val reconciler = UsageStatsReconciler(source, dao, TestClock(now, zone), zone)

        val totals = reconciler.reconcile(setOf("com.a"))

        assertThat(totals["com.a"]).isEqualTo(15.minutes)
        assertThat(dao.usageFor("com.a", "2026-08-13")?.foregroundSeconds).isEqualTo(900)
    }

    @Test
    fun `ignores packages that are not tracked`() = runTest {
        val source = FakeSource(
            listOf(
                UsageEvent("com.other", UsageEventType.RESUMED, localMidnight),
                UsageEvent("com.other", UsageEventType.PAUSED, localMidnight.plusSeconds(600)),
            )
        )
        val dao = FakeUsageDao()
        val reconciler = UsageStatsReconciler(source, dao, TestClock(now, zone), zone)

        reconciler.reconcile(setOf("com.a"))

        assertThat(dao.usage).isEmpty()
    }

    @Test
    fun `records zero for a tracked package with no usage today`() = runTest {
        val dao = FakeUsageDao()
        val reconciler = UsageStatsReconciler(FakeSource(emptyList()), dao, TestClock(now, zone), zone)

        val totals = reconciler.reconcile(setOf("com.a"))

        assertThat(totals["com.a"]).isEqualTo(kotlin.time.Duration.ZERO)
        assertThat(dao.usageFor("com.a", "2026-08-13")?.foregroundSeconds).isEqualTo(0)
    }

    @Test
    fun `recovers usage accrued while the service was down`() = runTest {
        // The service missed everything; UsageStatsManager still reports it (FR-9).
        val source = FakeSource(
            listOf(
                UsageEvent("com.a", UsageEventType.RESUMED, localMidnight.plusSeconds(0)),
                UsageEvent("com.a", UsageEventType.PAUSED, localMidnight.plusSeconds(1800)),
            )
        )
        val dao = FakeUsageDao()
        val reconciler = UsageStatsReconciler(source, dao, TestClock(now, zone), zone)

        assertThat(reconciler.reconcile(setOf("com.a"))["com.a"]).isEqualTo(30.minutes)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*UsageStatsReconcilerTest'`
Expected: FAIL — `Unresolved reference: UsageStatsReconciler`.

- [ ] **Step 4: Write the implementation**

```kotlin
// UsageStatsSource.kt
package com.dylanhamersztein.timelimiter.ledger

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.dylanhamersztein.timelimiter.rules.UsageEvent
import com.dylanhamersztein.timelimiter.rules.UsageEventType
import java.time.Instant

/** Seam over the platform so the reconciler is testable on the JVM. */
interface UsageStatsSource {
    fun eventsBetween(from: Instant, to: Instant): List<UsageEvent>
}

class AndroidUsageStatsSource(
    private val manager: UsageStatsManager,
) : UsageStatsSource {

    override fun eventsBetween(from: Instant, to: Instant): List<UsageEvent> {
        val result = mutableListOf<UsageEvent>()
        val events = manager.queryEvents(from.toEpochMilli(), to.toEpochMilli())
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventType.PAUSED
                else -> null
            }
            if (type != null) {
                result += UsageEvent(
                    packageName = event.packageName,
                    type = type,
                    at = Instant.ofEpochMilli(event.timeStamp),
                )
            }
        }
        return result
    }
}
```

```kotlin
// UsageStatsReconciler.kt
package com.dylanhamersztein.timelimiter.ledger

import com.dylanhamersztein.timelimiter.data.DailyUsageEntity
import com.dylanhamersztein.timelimiter.data.UsageDao
import com.dylanhamersztein.timelimiter.rules.DayBoundary
import com.dylanhamersztein.timelimiter.rules.ForegroundTimeCalculator
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration

/**
 * Writes the authoritative daily total for each tracked package (FR-7, FR-9).
 * Because it recomputes from the platform's own event log rather than accumulating,
 * time used while this app's service was dead is still counted.
 */
class UsageStatsReconciler(
    private val source: UsageStatsSource,
    private val usageDao: UsageDao,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    suspend fun reconcile(packages: Set<String>): Map<String, Duration> {
        if (packages.isEmpty()) return emptyMap()

        val now = clock.instant()
        val today = DayBoundary.localDate(now, zone)
        val windowStart = today.atStartOfDay(zone).toInstant()

        val events = source.eventsBetween(windowStart, now)
            .filter { it.packageName in packages }
        val computed = ForegroundTimeCalculator.totals(events, windowStart, now)

        val totals = packages.associateWith { computed[it] ?: Duration.ZERO }
        totals.forEach { (pkg, duration) ->
            usageDao.upsertUsage(
                DailyUsageEntity(
                    packageName = pkg,
                    localDateIso = today.toString(),
                    foregroundSeconds = duration.inWholeSeconds,
                )
            )
        }
        return totals
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*UsageStatsReconcilerTest'`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: reconcile daily usage from UsageStatsManager events"
```

---

### Task 10: `UsageLedger` — reconciled totals plus the live session

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ledger/UsageLedger.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ledger/UsageLedgerTest.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ledger/FakeSessionDao.kt`

**Interfaces:**
- Consumes: `UsageStatsReconciler` (Task 9), `SessionDao`, mappers (Task 6), `UsageSnapshot`, `SessionState` (Tasks 3, 4).
- Produces: `class UsageLedger(reconciler, usageDao, sessionDao, clock, zone)` with
  - `suspend fun snapshot(packageName: String): UsageSnapshot`
  - `suspend fun snapshots(packages: Set<String>): Map<String, UsageSnapshot>`
  - `suspend fun session(packageName: String): SessionState?`
  - `suspend fun saveSession(state: SessionState)`
  - `suspend fun clearSession(packageName: String)`

**Design note:** `snapshot` reconciles first, so `usedToday` is always the platform's figure — never a running total this app maintains itself. The live session contributes through `UsageSnapshot.session`, which `LimitEngine` reads for the session cap. This keeps exactly one source of truth for daily time and avoids double-counting the in-progress session.

- [ ] **Step 1: Write the fake session DAO**

```kotlin
package com.dylanhamersztein.timelimiter.ledger

import com.dylanhamersztein.timelimiter.data.SessionDao
import com.dylanhamersztein.timelimiter.data.SessionEntity

class FakeSessionDao : SessionDao {
    private val sessions = mutableMapOf<String, SessionEntity>()
    override suspend fun sessionFor(packageName: String) = sessions[packageName]
    override suspend fun upsertSession(session: SessionEntity) {
        sessions[session.packageName] = session
    }
    override suspend fun deleteSession(packageName: String) {
        sessions.remove(packageName)
    }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.ledger

import com.dylanhamersztein.timelimiter.data.DailyUsageEntity
import com.dylanhamersztein.timelimiter.rules.SessionMachine
import com.dylanhamersztein.timelimiter.rules.TestClock
import com.dylanhamersztein.timelimiter.rules.UsageEvent
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UsageLedgerTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-08-13T09:00:00Z")

    private object EmptySource : UsageStatsSource {
        override fun eventsBetween(from: Instant, to: Instant): List<UsageEvent> = emptyList()
    }

    private fun ledger(usageDao: FakeUsageDao, sessionDao: FakeSessionDao): UsageLedger {
        val clock = TestClock(now, zone)
        return UsageLedger(
            reconciler = UsageStatsReconciler(EmptySource, usageDao, clock, zone),
            usageDao = usageDao,
            sessionDao = sessionDao,
            clock = clock,
            zone = zone,
        )
    }

    @Test
    fun `snapshot reports zero for an app with no recorded usage`() = runTest {
        val snapshot = ledger(FakeUsageDao(), FakeSessionDao()).snapshot("com.a")
        assertThat(snapshot.usedToday).isEqualTo(kotlin.time.Duration.ZERO)
        assertThat(snapshot.session).isNull()
    }

    @Test
    fun `snapshot reads back a persisted session`() = runTest {
        val sessionDao = FakeSessionDao()
        val ledger = ledger(FakeUsageDao(), sessionDao)
        val session = SessionMachine.onEnterForeground(null, "com.a", now.minusSeconds(300), 5.minutes)

        ledger.saveSession(session)

        assertThat(ledger.snapshot("com.a").session).isEqualTo(session)
    }

    @Test
    fun `snapshot uses the reconciled total rather than any stale row`() = runTest {
        val usageDao = FakeUsageDao()
        // A stale row claiming 45 minutes; the source reports no events at all.
        usageDao.upsertUsage(DailyUsageEntity("com.a", "2026-08-13", 2700))

        val snapshot = ledger(usageDao, FakeSessionDao()).snapshot("com.a")

        assertThat(snapshot.usedToday).isEqualTo(kotlin.time.Duration.ZERO)
    }

    @Test
    fun `clearSession removes the stored session`() = runTest {
        val sessionDao = FakeSessionDao()
        val ledger = ledger(FakeUsageDao(), sessionDao)
        ledger.saveSession(SessionMachine.onEnterForeground(null, "com.a", now, 5.minutes))

        ledger.clearSession("com.a")

        assertThat(ledger.session("com.a")).isNull()
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*UsageLedgerTest'`
Expected: FAIL — `Unresolved reference: UsageLedger`.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.ledger

import com.dylanhamersztein.timelimiter.data.SessionDao
import com.dylanhamersztein.timelimiter.data.UsageDao
import com.dylanhamersztein.timelimiter.data.toDomain
import com.dylanhamersztein.timelimiter.data.toEntity
import com.dylanhamersztein.timelimiter.rules.DayBoundary
import com.dylanhamersztein.timelimiter.rules.SessionState
import com.dylanhamersztein.timelimiter.rules.UsageSnapshot
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class UsageLedger(
    private val reconciler: UsageStatsReconciler,
    private val usageDao: UsageDao,
    private val sessionDao: SessionDao,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    suspend fun snapshot(packageName: String): UsageSnapshot =
        snapshots(setOf(packageName)).getValue(packageName)

    suspend fun snapshots(packages: Set<String>): Map<String, UsageSnapshot> {
        if (packages.isEmpty()) return emptyMap()
        val totals = reconciler.reconcile(packages)
        val today = DayBoundary.localDate(clock.instant(), zone).toString()
        return packages.associateWith { pkg ->
            val used = totals[pkg]
                ?: usageDao.usageFor(pkg, today)?.foregroundSeconds?.seconds
                ?: Duration.ZERO
            UsageSnapshot(pkg, used, session(pkg))
        }
    }

    suspend fun session(packageName: String): SessionState? =
        sessionDao.sessionFor(packageName)?.toDomain()

    suspend fun saveSession(state: SessionState) {
        sessionDao.upsertSession(state.toEntity())
    }

    suspend fun clearSession(packageName: String) {
        sessionDao.deleteSession(packageName)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*UsageLedgerTest'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: add UsageLedger combining reconciled totals and live sessions"
```

---

### Task 11: `LimitRepository` — routing edits through the classifier

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/data/LimitRepository.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/data/LimitRepositoryTest.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/data/FakeLimitDao.kt`

**Interfaces:**
- Consumes: `LimitDao`, mappers (Task 6); `ChangeClassifier`, `ChangeKind`, `PendingChange`, `PendingKind`, `PendingChangeResolver` (Task 5).
- Produces:
  - `data class TrackedApp(packageName: String, label: String, addedAt: Instant)`
  - `sealed interface EditOutcome { data object AppliedNow; data class Pending(effectiveDate: LocalDate) }`
  - `class LimitRepository(dao: LimitDao, clock: Clock, zone: ZoneId)` with `observeTrackedApps(): Flow<List<TrackedApp>>`, `observeLimits(): Flow<Map<String, LimitConfig>>`, `observePendingChanges(): Flow<List<PendingChange>>`, `suspend fun limitFor(packageName): LimitConfig?`, `suspend fun track(packageName, label, config)`, `suspend fun edit(packageName, newConfig): EditOutcome`, `suspend fun untrack(packageName): EditOutcome`, `suspend fun cancelPending(packageName)`, `suspend fun applyDueChanges()`

This is the single choke point for FR-22 through FR-25. No screen writes to `LimitDao` directly.

- [ ] **Step 1: Write the fake DAO**

```kotlin
package com.dylanhamersztein.timelimiter.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeLimitDao : LimitDao {
    private val trackedApps = MutableStateFlow<List<TrackedAppEntity>>(emptyList())
    private val limits = MutableStateFlow<List<LimitEntity>>(emptyList())
    private val pending = MutableStateFlow<List<PendingChangeEntity>>(emptyList())

    override fun observeTrackedApps(): Flow<List<TrackedAppEntity>> = trackedApps.asStateFlow()
    override fun observeLimits(): Flow<List<LimitEntity>> = limits.asStateFlow()
    override fun observePendingChanges(): Flow<List<PendingChangeEntity>> = pending.asStateFlow()

    override suspend fun limitFor(packageName: String) =
        limits.value.firstOrNull { it.packageName == packageName }

    override suspend fun upsertTrackedApp(app: TrackedAppEntity) {
        trackedApps.value = trackedApps.value.filterNot { it.packageName == app.packageName } + app
    }

    override suspend fun upsertLimit(limit: LimitEntity) {
        limits.value = limits.value.filterNot { it.packageName == limit.packageName } + limit
    }

    override suspend fun deleteTrackedApp(packageName: String) {
        trackedApps.value = trackedApps.value.filterNot { it.packageName == packageName }
    }

    override suspend fun deleteLimit(packageName: String) {
        limits.value = limits.value.filterNot { it.packageName == packageName }
    }

    override suspend fun allPendingChanges() = pending.value

    override suspend fun upsertPendingChange(change: PendingChangeEntity) {
        pending.value = pending.value.filterNot { it.packageName == change.packageName } + change
    }

    override suspend fun deletePendingChange(packageName: String) {
        pending.value = pending.value.filterNot { it.packageName == packageName }
    }
}
```

`untrack` has a default body on the DAO interface (`@Transaction`), so the fake inherits it.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.data

import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.TestClock
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LimitRepositoryTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-08-13T09:00:00Z")
    private val tomorrow = LocalDate.of(2026, 8, 14)
    private val base = LimitConfig("com.a", dailyBudget = 30.minutes, sessionCap = 10.minutes)

    private fun repo(dao: FakeLimitDao, clock: TestClock = TestClock(now, zone)) =
        LimitRepository(dao, clock, zone)

    @Test
    fun `tracking a new app applies immediately`() = runTest {
        val dao = FakeLimitDao()
        repo(dao).track("com.a", "App A", base)
        assertThat(dao.limitFor("com.a")?.toDomain()).isEqualTo(base)
    }

    @Test
    fun `a tightening edit applies immediately`() = runTest {
        val dao = FakeLimitDao()
        val repo = repo(dao)
        repo.track("com.a", "App A", base)

        val outcome = repo.edit("com.a", base.copy(dailyBudget = 20.minutes))

        assertThat(outcome).isEqualTo(EditOutcome.AppliedNow)
        assertThat(dao.limitFor("com.a")?.dailyBudgetMinutes).isEqualTo(20)
        assertThat(dao.allPendingChanges()).isEmpty()
    }

    @Test
    fun `a loosening edit is queued for tomorrow and leaves today's limit alone`() = runTest {
        val dao = FakeLimitDao()
        val repo = repo(dao)
        repo.track("com.a", "App A", base)

        val outcome = repo.edit("com.a", base.copy(dailyBudget = 60.minutes))

        assertThat(outcome).isEqualTo(EditOutcome.Pending(tomorrow))
        assertThat(dao.limitFor("com.a")?.dailyBudgetMinutes).isEqualTo(30)
        assertThat(dao.allPendingChanges().single().dailyBudgetMinutes).isEqualTo(60)
    }

    @Test
    fun `untracking is queued for tomorrow`() = runTest {
        val dao = FakeLimitDao()
        val repo = repo(dao)
        repo.track("com.a", "App A", base)

        val outcome = repo.untrack("com.a")

        assertThat(outcome).isEqualTo(EditOutcome.Pending(tomorrow))
        assertThat(dao.limitFor("com.a")).isNotNull()
        assertThat(dao.allPendingChanges().single().kind).isEqualTo("REMOVE")
    }

    @Test
    fun `cancelling a pending change takes effect immediately`() = runTest {
        val dao = FakeLimitDao()
        val repo = repo(dao)
        repo.track("com.a", "App A", base)
        repo.edit("com.a", base.copy(dailyBudget = 60.minutes))

        repo.cancelPending("com.a")

        assertThat(dao.allPendingChanges()).isEmpty()
        assertThat(dao.limitFor("com.a")?.dailyBudgetMinutes).isEqualTo(30)
    }

    @Test
    fun `a second edit replaces the first pending change`() = runTest {
        val dao = FakeLimitDao()
        val repo = repo(dao)
        repo.track("com.a", "App A", base)
        repo.edit("com.a", base.copy(dailyBudget = 60.minutes))

        repo.edit("com.a", base.copy(dailyBudget = 90.minutes))

        assertThat(dao.allPendingChanges()).hasSize(1)
        assertThat(dao.allPendingChanges().single().dailyBudgetMinutes).isEqualTo(90)
    }

    @Test
    fun `due changes are applied at the reset and then removed`() = runTest {
        val dao = FakeLimitDao()
        val clock = TestClock(now, zone)
        val repo = repo(dao, clock)
        repo.track("com.a", "App A", base)
        repo.edit("com.a", base.copy(dailyBudget = 60.minutes))

        clock.setTo(Instant.parse("2026-08-13T23:00:01Z")) // just past local midnight
        repo.applyDueChanges()

        assertThat(dao.limitFor("com.a")?.dailyBudgetMinutes).isEqualTo(60)
        assertThat(dao.allPendingChanges()).isEmpty()
    }

    @Test
    fun `a due removal untracks the app`() = runTest {
        val dao = FakeLimitDao()
        val clock = TestClock(now, zone)
        val repo = repo(dao, clock)
        repo.track("com.a", "App A", base)
        repo.untrack("com.a")

        clock.setTo(Instant.parse("2026-08-13T23:00:01Z"))
        repo.applyDueChanges()

        assertThat(dao.limitFor("com.a")).isNull()
        assertThat(dao.allPendingChanges()).isEmpty()
    }

    @Test
    fun `changes that are not yet due are left alone`() = runTest {
        val dao = FakeLimitDao()
        val repo = repo(dao)
        repo.track("com.a", "App A", base)
        repo.edit("com.a", base.copy(dailyBudget = 60.minutes))

        repo.applyDueChanges() // still 2026-08-13

        assertThat(dao.limitFor("com.a")?.dailyBudgetMinutes).isEqualTo(30)
        assertThat(dao.allPendingChanges()).hasSize(1)
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*LimitRepositoryTest'`
Expected: FAIL — `Unresolved reference: LimitRepository`.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.data

import com.dylanhamersztein.timelimiter.rules.ChangeClassifier
import com.dylanhamersztein.timelimiter.rules.ChangeKind
import com.dylanhamersztein.timelimiter.rules.DayBoundary
import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.PendingChange
import com.dylanhamersztein.timelimiter.rules.PendingChangeResolver
import com.dylanhamersztein.timelimiter.rules.PendingKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class TrackedApp(
    val packageName: String,
    val label: String,
    val addedAt: Instant,
)

sealed interface EditOutcome {
    data object AppliedNow : EditOutcome
    data class Pending(val effectiveDate: LocalDate) : EditOutcome
}

/**
 * The only writer of limits. Tightenings land immediately; loosenings become a
 * pending change applied at the next reset (FR-22, FR-23).
 */
class LimitRepository(
    private val dao: LimitDao,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    fun observeTrackedApps(): Flow<List<TrackedApp>> =
        dao.observeTrackedApps().map { entities ->
            entities.map { TrackedApp(it.packageName, it.label, Instant.ofEpochMilli(it.addedAtEpochMillis)) }
        }

    fun observeLimits(): Flow<Map<String, LimitConfig>> =
        dao.observeLimits().map { entities -> entities.associate { it.packageName to it.toDomain() } }

    fun observePendingChanges(): Flow<List<PendingChange>> =
        dao.observePendingChanges().map { entities -> entities.map { it.toDomain() } }

    suspend fun limitFor(packageName: String): LimitConfig? = dao.limitFor(packageName)?.toDomain()

    /** Adding an app to the tracked set is a tightening, so it applies now (FR-25). */
    suspend fun track(packageName: String, label: String, config: LimitConfig) {
        dao.upsertTrackedApp(TrackedAppEntity(packageName, label, clock.instant().toEpochMilli()))
        dao.upsertLimit(config.toEntity())
        dao.deletePendingChange(packageName)
    }

    suspend fun edit(packageName: String, newConfig: LimitConfig): EditOutcome {
        val current = limitFor(packageName) ?: run {
            dao.upsertLimit(newConfig.toEntity())
            return EditOutcome.AppliedNow
        }
        return when (ChangeClassifier.classify(current, newConfig)) {
            ChangeKind.TIGHTENING -> {
                dao.upsertLimit(newConfig.toEntity())
                dao.deletePendingChange(packageName)
                EditOutcome.AppliedNow
            }
            ChangeKind.LOOSENING -> queue(packageName, PendingKind.UPDATE, newConfig)
        }
    }

    /** Untracking loosens every limit the app had, so it waits (FR-23). */
    suspend fun untrack(packageName: String): EditOutcome =
        queue(packageName, PendingKind.REMOVE, null)

    /** Cancelling a queued loosening is itself a tightening, so it is immediate (FR-24). */
    suspend fun cancelPending(packageName: String) {
        dao.deletePendingChange(packageName)
    }

    suspend fun applyDueChanges() {
        val today = DayBoundary.localDate(clock.instant(), zone)
        val due = PendingChangeResolver.due(dao.allPendingChanges().map { it.toDomain() }, today)
        for (change in due) {
            when (change.kind) {
                PendingKind.UPDATE -> change.payload?.let { dao.upsertLimit(it.toEntity()) }
                PendingKind.REMOVE -> dao.untrack(change.packageName)
            }
            dao.deletePendingChange(change.packageName)
        }
    }

    private suspend fun queue(
        packageName: String,
        kind: PendingKind,
        payload: LimitConfig?,
    ): EditOutcome {
        val effectiveDate = PendingChangeResolver.effectiveDateFor(clock.instant(), zone)
        dao.upsertPendingChange(PendingChange(packageName, kind, payload, effectiveDate).toEntity())
        return EditOutcome.Pending(effectiveDate)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*LimitRepositoryTest'`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: route limit edits through tighten-now, loosen-tomorrow rules"
```

---

### Task 12: Foreground detection accessibility service

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/detection/ForegroundBus.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/detection/ForegroundDetectionService.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/detection/AccessibilityStatus.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Create: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/detection/AccessibilityStatusTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object ForegroundBus { val current: StateFlow<String?>; fun publish(packageName: String?) }`
  - `class ForegroundDetectionService : AccessibilityService`
  - `object AccessibilityStatus { fun isListed(enabledServices: String?, component: String): Boolean; fun isEnabled(context: Context): Boolean }`

- [ ] **Step 1: Write the failing test for the settings-string parser**

```kotlin
package com.dylanhamersztein.timelimiter.detection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilityStatusTest {
    private val component = "com.dylanhamersztein.timelimiter/.detection.ForegroundDetectionService"

    @Test
    fun `finds the service in a single-entry list`() {
        assertThat(AccessibilityStatus.isListed(component, component)).isTrue()
    }

    @Test
    fun `finds the service among several`() {
        val enabled = "com.other/.Service:$component:com.third/.Thing"
        assertThat(AccessibilityStatus.isListed(enabled, component)).isTrue()
    }

    @Test
    fun `reports absent when the list does not contain it`() {
        assertThat(AccessibilityStatus.isListed("com.other/.Service", component)).isFalse()
    }

    @Test
    fun `reports absent for null or blank settings`() {
        assertThat(AccessibilityStatus.isListed(null, component)).isFalse()
        assertThat(AccessibilityStatus.isListed("", component)).isFalse()
    }

    @Test
    fun `does not match on a prefix of another component`() {
        assertThat(AccessibilityStatus.isListed("$component.Extra", component)).isFalse()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*AccessibilityStatusTest'`
Expected: FAIL — `Unresolved reference: AccessibilityStatus`.

- [ ] **Step 3: Write the implementation**

```kotlin
// ForegroundBus.kt
package com.dylanhamersztein.timelimiter.detection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current foreground package, published by the accessibility service and
 * consumed by the tracking coordinator. A process-wide singleton because the
 * accessibility service is constructed by the system, not by AppContainer.
 */
object ForegroundBus {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    fun publish(packageName: String?) {
        _current.value = packageName
    }
}
```

```kotlin
// AccessibilityStatus.kt
package com.dylanhamersztein.timelimiter.detection

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityStatus {

    fun isListed(enabledServices: String?, component: String): Boolean =
        enabledServices
            ?.split(':')
            ?.any { it.equals(component, ignoreCase = true) }
            ?: false

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, ForegroundDetectionService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return isListed(enabled, component.flattenToString()) ||
            isListed(enabled, component.flattenToShortString())
    }
}
```

```kotlin
// ForegroundDetectionService.kt
package com.dylanhamersztein.timelimiter.detection

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Publishes foreground changes the instant they happen (NFR-1). Deliberately does
 * nothing else: all decisions belong to the coordinator, which can be tested.
 */
class ForegroundDetectionService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // Toasts and system dialogs also raise this event; ignore anything with no class.
        if (event.className == null) return
        ForegroundBus.publish(packageName)
    }

    override fun onInterrupt() = Unit
}
```

```xml
<!-- res/xml/accessibility_service_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="false"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="0" />
```

```xml
<!-- res/values/strings.xml -->
<resources>
    <string name="app_name">Time Limiter</string>
    <string name="accessibility_service_description">Watches which app is in the foreground so Time Limiter can enforce the limits you set. Nothing is read from the screen and nothing leaves your device.</string>
</resources>
```

Add to the manifest inside `<application>`:

```xml
<service
    android:name=".detection.ForegroundDetectionService"
    android:exported="false"
    android:label="@string/app_name"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*AccessibilityStatusTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Verify by hand on a device**

Install, enable the service in Settings → Accessibility, and confirm via `adb logcat` (add a temporary `Log.d` in `onAccessibilityEvent`, then remove it) that switching apps publishes the expected package names. Note the result — this is the first task whose behaviour cannot be proven by a unit test.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: detect foreground app changes via accessibility service"
```

---

### Task 13: `SafeguardAllowlist`

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/enforcement/SafeguardAllowlist.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/enforcement/SafeguardAllowlistTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface SystemPackages { fun launchers(): Set<String>; fun dialers(): Set<String>; fun settings(): Set<String>; fun self(): String }`
  - `class AndroidSystemPackages(context: Context) : SystemPackages`
  - `class SafeguardAllowlist(system: SystemPackages)` with `fun isProtected(packageName: String): Boolean`

FR-18 is not user-editable and must hold regardless of what the user has configured. Blocking the launcher would soft-brick the phone; blocking Settings would trap the user in the very state the PRD promises never to create (FR-29).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.enforcement

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafeguardAllowlistTest {
    private object Fake : SystemPackages {
        override fun launchers() = setOf("com.android.launcher3")
        override fun dialers() = setOf("com.android.dialer")
        override fun settings() = setOf("com.android.settings")
        override fun self() = "com.dylanhamersztein.timelimiter"
    }

    private val allowlist = SafeguardAllowlist(Fake)

    @Test
    fun `protects the launcher`() {
        assertThat(allowlist.isProtected("com.android.launcher3")).isTrue()
    }

    @Test
    fun `protects the dialer and emergency calling`() {
        assertThat(allowlist.isProtected("com.android.dialer")).isTrue()
        assertThat(allowlist.isProtected("com.android.emergency")).isTrue()
    }

    @Test
    fun `protects settings`() {
        assertThat(allowlist.isProtected("com.android.settings")).isTrue()
    }

    @Test
    fun `protects system ui`() {
        assertThat(allowlist.isProtected("com.android.systemui")).isTrue()
    }

    @Test
    fun `protects this app itself`() {
        assertThat(allowlist.isProtected("com.dylanhamersztein.timelimiter")).isTrue()
    }

    @Test
    fun `does not protect an ordinary app`() {
        assertThat(allowlist.isProtected("com.instagram.android")).isFalse()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SafeguardAllowlistTest'`
Expected: FAIL — `Unresolved reference: SafeguardAllowlist`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.enforcement

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

interface SystemPackages {
    fun launchers(): Set<String>
    fun dialers(): Set<String>
    fun settings(): Set<String>
    fun self(): String
}

class AndroidSystemPackages(private val context: Context) : SystemPackages {
    private val pm: PackageManager get() = context.packageManager

    override fun launchers(): Set<String> = resolveAll(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    )

    override fun dialers(): Set<String> = resolveAll(Intent(Intent.ACTION_DIAL)) +
        resolveAll(Intent(Intent.ACTION_CALL_BUTTON))

    override fun settings(): Set<String> = resolveAll(Intent(android.provider.Settings.ACTION_SETTINGS))

    override fun self(): String = context.packageName

    private fun resolveAll(intent: Intent): Set<String> =
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
}

/**
 * FR-18. Enforced in code, never user-editable: blocking any of these would either
 * soft-brick the device or trap the user away from the settings and uninstall
 * routes the PRD guarantees stay open (FR-29).
 */
class SafeguardAllowlist(private val system: SystemPackages) {

    private val alwaysProtected = setOf(
        "android",
        "com.android.systemui",
        "com.android.emergency",
        "com.android.server.telecom",
        "com.android.phone",
    )

    fun isProtected(packageName: String): Boolean =
        packageName in alwaysProtected ||
            packageName == system.self() ||
            packageName in system.launchers() ||
            packageName in system.dialers() ||
            packageName in system.settings()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SafeguardAllowlistTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: add non-editable safeguard allowlist"
```

---

### Task 14: Block screen

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/enforcement/BlockCopy.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/enforcement/BlockActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/enforcement/BlockCopyTest.kt`

**Interfaces:**
- Consumes: `BlockReason` (Task 4).
- Produces:
  - `object BlockCopy { fun headline(reason: BlockReason): String; fun liftsAtDescription(reason: BlockReason, liftsAt: Instant, now: Instant, zone: ZoneId): String }`
  - `class BlockActivity : ComponentActivity` reading extras `EXTRA_PACKAGE_LABEL`, `EXTRA_REASON`, `EXTRA_LIFTS_AT_MILLIS`, with `fun intent(context, label, reason, liftsAt): Intent` on its companion

FR-16: state which rule fired and when it lifts, offer exactly one action — go to the launcher. No extension, no snooze, no passcode escape. Do not add one later "for testing".

- [ ] **Step 1: Write the failing copy test**

```kotlin
package com.dylanhamersztein.timelimiter.enforcement

import com.dylanhamersztein.timelimiter.rules.BlockReason
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class BlockCopyTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-08-13T09:00:00Z") // 10:00 London

    @Test
    fun `daily exhaustion says the budget is spent`() {
        assertThat(BlockCopy.headline(BlockReason.DAILY_BUDGET_EXHAUSTED))
            .isEqualTo("Daily limit reached")
    }

    @Test
    fun `session cap and cooldown have their own headlines`() {
        assertThat(BlockCopy.headline(BlockReason.SESSION_CAP_REACHED)).isEqualTo("Session limit reached")
        assertThat(BlockCopy.headline(BlockReason.IN_COOLDOWN)).isEqualTo("Cooling down")
    }

    @Test
    fun `a daily block describes the reset as tomorrow's time`() {
        val liftsAt = Instant.parse("2026-08-13T23:00:00Z") // midnight London
        assertThat(BlockCopy.liftsAtDescription(BlockReason.DAILY_BUDGET_EXHAUSTED, liftsAt, now, zone))
            .isEqualTo("Unlocks at midnight, in 14h 0m")
    }

    @Test
    fun `a cooldown block counts down in minutes`() {
        val liftsAt = now.plusSeconds(1500)
        assertThat(BlockCopy.liftsAtDescription(BlockReason.IN_COOLDOWN, liftsAt, now, zone))
            .isEqualTo("Unlocks at 10:25, in 25m")
    }

    @Test
    fun `a block that has already lifted reads as available now`() {
        assertThat(BlockCopy.liftsAtDescription(BlockReason.IN_COOLDOWN, now.minusSeconds(60), now, zone))
            .isEqualTo("Unlocking now")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*BlockCopyTest'`
Expected: FAIL — `Unresolved reference: BlockCopy`.

- [ ] **Step 3: Write `BlockCopy`**

```kotlin
package com.dylanhamersztein.timelimiter.enforcement

import com.dylanhamersztein.timelimiter.rules.BlockReason
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object BlockCopy {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun headline(reason: BlockReason): String = when (reason) {
        BlockReason.DAILY_BUDGET_EXHAUSTED -> "Daily limit reached"
        BlockReason.SESSION_CAP_REACHED -> "Session limit reached"
        BlockReason.IN_COOLDOWN -> "Cooling down"
    }

    fun liftsAtDescription(
        reason: BlockReason,
        liftsAt: Instant,
        now: Instant,
        zone: ZoneId,
    ): String {
        val remaining = Duration.between(now, liftsAt)
        if (remaining.isZero || remaining.isNegative) return "Unlocking now"

        val at = if (reason == BlockReason.DAILY_BUDGET_EXHAUSTED) {
            "midnight"
        } else {
            timeFormat.format(liftsAt.atZone(zone))
        }

        val hours = remaining.toHours()
        val minutes = remaining.toMinutes() % 60
        val amount = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        return "Unlocks at $at, in $amount"
    }
}
```

- [ ] **Step 4: Write `BlockActivity`**

```kotlin
package com.dylanhamersztein.timelimiter.enforcement

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dylanhamersztein.timelimiter.rules.BlockReason
import java.time.Instant
import java.time.ZoneId

class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)

        val label = intent.getStringExtra(EXTRA_PACKAGE_LABEL).orEmpty()
        val reason = BlockReason.valueOf(
            intent.getStringExtra(EXTRA_REASON) ?: BlockReason.DAILY_BUDGET_EXHAUSTED.name
        )
        val liftsAt = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_LIFTS_AT_MILLIS, 0L))

        setContent {
            MaterialTheme {
                BlockScreen(
                    label = label,
                    reason = reason,
                    liftsAt = liftsAt,
                    onGoHome = ::goHome,
                )
            }
        }
    }

    /** FR-16: the only action. Back does the same thing. */
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE_LABEL = "package_label"
        const val EXTRA_REASON = "reason"
        const val EXTRA_LIFTS_AT_MILLIS = "lifts_at_millis"

        fun intent(context: Context, label: String, reason: BlockReason, liftsAt: Instant): Intent =
            Intent(context, BlockActivity::class.java)
                .putExtra(EXTRA_PACKAGE_LABEL, label)
                .putExtra(EXTRA_REASON, reason.name)
                .putExtra(EXTRA_LIFTS_AT_MILLIS, liftsAt.toEpochMilli())
                .setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                )
    }
}

@Composable
private fun BlockScreen(
    label: String,
    reason: BlockReason,
    liftsAt: Instant,
    onGoHome: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(BlockCopy.headline(reason), style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                BlockCopy.liftsAtDescription(reason, liftsAt, Instant.now(), ZoneId.systemDefault()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onGoHome) { Text("Go to home screen") }
        }
    }
}
```

Register it in the manifest inside `<application>`:

```xml
<activity
    android:name=".enforcement.BlockActivity"
    android:excludeFromRecents="true"
    android:exported="false"
    android:launchMode="singleInstance"
    android:noHistory="true"
    android:taskAffinity=""
    android:theme="@style/Theme.TimeLimiter" />
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*BlockCopyTest'` and `./gradlew :app:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: add block screen with reason and unlock time"
```

---

### Task 15: `BlockScreenLauncher`

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/enforcement/BlockScreenLauncher.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/enforcement/BlockScreenLauncherTest.kt`

**Interfaces:**
- Consumes: `SafeguardAllowlist` (Task 13), `BlockActivity` (Task 14), `Decision.Block` (Task 4).
- Produces:
  - `interface BlockPresenter { fun present(label: String, reason: BlockReason, liftsAt: Instant); fun goHomeOnly() }`
  - `class AndroidBlockPresenter(context: Context) : BlockPresenter`
  - `class BlockScreenLauncher(allowlist: SafeguardAllowlist, presenter: BlockPresenter, labels: (String) -> String)` with `fun launch(block: Decision.Block): Boolean`

`launch` returns whether a block screen was actually shown, so callers can log or count. It refuses for protected packages and is the only place that decides that.

**Why `SYSTEM_ALERT_WINDOW`:** since Android 10, an app cannot start an activity from the background. Holding the draw-over-other-apps permission is the exemption that makes the block screen appear reliably. Declare it, request it in onboarding (Task 25), and if it has been revoked fall back to `performGlobalAction(GLOBAL_ACTION_HOME)` from the accessibility service — the user still gets ejected, just without the explanation.

- [ ] **Step 1: Add the permission to the manifest**

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.enforcement

import com.dylanhamersztein.timelimiter.rules.BlockReason
import com.dylanhamersztein.timelimiter.rules.Decision
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class BlockScreenLauncherTest {
    private val liftsAt = Instant.parse("2026-08-13T23:00:00Z")

    private class RecordingPresenter : BlockPresenter {
        val presented = mutableListOf<Triple<String, BlockReason, Instant>>()
        var wentHome = false
        override fun present(label: String, reason: BlockReason, liftsAt: Instant) {
            presented += Triple(label, reason, liftsAt)
        }
        override fun goHomeOnly() {
            wentHome = true
        }
    }

    private object Fake : SystemPackages {
        override fun launchers() = setOf("com.android.launcher3")
        override fun dialers() = emptySet<String>()
        override fun settings() = setOf("com.android.settings")
        override fun self() = "com.dylanhamersztein.timelimiter"
    }

    private fun launcher(presenter: BlockPresenter) = BlockScreenLauncher(
        allowlist = SafeguardAllowlist(Fake),
        presenter = presenter,
        labels = { pkg -> "Label for $pkg" },
    )

    @Test
    fun `shows the block screen for an ordinary app`() {
        val presenter = RecordingPresenter()
        val shown = launcher(presenter).launch(
            Decision.Block("com.instagram.android", BlockReason.DAILY_BUDGET_EXHAUSTED, liftsAt)
        )

        assertThat(shown).isTrue()
        assertThat(presenter.presented).containsExactly(
            Triple("Label for com.instagram.android", BlockReason.DAILY_BUDGET_EXHAUSTED, liftsAt)
        )
    }

    @Test
    fun `refuses to block the launcher`() {
        val presenter = RecordingPresenter()
        val shown = launcher(presenter).launch(
            Decision.Block("com.android.launcher3", BlockReason.DAILY_BUDGET_EXHAUSTED, liftsAt)
        )

        assertThat(shown).isFalse()
        assertThat(presenter.presented).isEmpty()
    }

    @Test
    fun `refuses to block settings`() {
        val presenter = RecordingPresenter()
        launcher(presenter).launch(
            Decision.Block("com.android.settings", BlockReason.IN_COOLDOWN, liftsAt)
        )
        assertThat(presenter.presented).isEmpty()
    }

    @Test
    fun `refuses to block itself`() {
        val presenter = RecordingPresenter()
        launcher(presenter).launch(
            Decision.Block("com.dylanhamersztein.timelimiter", BlockReason.IN_COOLDOWN, liftsAt)
        )
        assertThat(presenter.presented).isEmpty()
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*BlockScreenLauncherTest'`
Expected: FAIL — `Unresolved reference: BlockScreenLauncher`.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.enforcement

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.dylanhamersztein.timelimiter.rules.BlockReason
import com.dylanhamersztein.timelimiter.rules.Decision
import java.time.Instant

interface BlockPresenter {
    fun present(label: String, reason: BlockReason, liftsAt: Instant)

    /** Fallback when the block screen cannot be started: eject without explaining. */
    fun goHomeOnly()
}

class AndroidBlockPresenter(private val context: Context) : BlockPresenter {

    override fun present(label: String, reason: BlockReason, liftsAt: Instant) {
        if (!Settings.canDrawOverlays(context)) {
            goHomeOnly()
            return
        }
        context.startActivity(BlockActivity.intent(context, label, reason, liftsAt))
    }

    override fun goHomeOnly() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

class BlockScreenLauncher(
    private val allowlist: SafeguardAllowlist,
    private val presenter: BlockPresenter,
    private val labels: (String) -> String,
) {
    /** @return true if a block screen was shown. */
    fun launch(block: Decision.Block): Boolean {
        if (allowlist.isProtected(block.packageName)) return false
        presenter.present(labels(block.packageName), block.reason, block.liftsAt)
        return true
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*BlockScreenLauncherTest'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: launch the block screen, honouring the safeguard allowlist"
```

---

### Task 16: `TrackingCoordinator` and the foreground service

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/service/TrackingCoordinator.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/service/TrackingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/service/TrackingCoordinatorTest.kt`

**Interfaces:**
- Consumes: `UsageLedger` (Task 10), `LimitRepository` (Task 11), `ForegroundBus` (Task 12), `BlockScreenLauncher` (Task 15), `LimitEngine`, `SessionMachine` (Tasks 3, 4).
- Produces:
  - `interface WarningNotifier { suspend fun warn(packageName: String, remaining: Duration) }`
  - `class TrackingCoordinator(ledger, limits, launcher, warnings, clock, zone)` with `suspend fun onForegroundChanged(packageName: String?)` and `suspend fun tick()`
  - `class TrackingService : Service`

This is the wiring task: everything it does is a call into a component already tested in isolation, so its own tests cover the *sequencing* — session bookkeeping on entry and exit, and marking a session capped so the cooldown is right.

**Behaviour, precisely:**
1. On a foreground change to package P: close the session of the previous package (`onLeaveForeground`), then if P is tracked, open or resume P's session (`onEnterForeground`), take a snapshot, and evaluate.
2. On evaluate returning `Block` with reason `SESSION_CAP_REACHED`: mark the session capped and record the exit at `now`, so the stored cooldown matches the `liftsAt` the block screen was given.
3. On `Warn`: notify at most once per app per day, then keep going — a warning never blocks.
4. `tick()` re-evaluates the current foreground app, so a limit reached while the user sits in the app is caught mid-session (FR-15). The service calls it every 30 seconds (NFR-2) while a tracked app is foregrounded, and not at all otherwise.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.service

import com.dylanhamersztein.timelimiter.data.FakeLimitDao
import com.dylanhamersztein.timelimiter.data.LimitRepository
import com.dylanhamersztein.timelimiter.enforcement.BlockPresenter
import com.dylanhamersztein.timelimiter.enforcement.BlockScreenLauncher
import com.dylanhamersztein.timelimiter.enforcement.SafeguardAllowlist
import com.dylanhamersztein.timelimiter.enforcement.SystemPackages
import com.dylanhamersztein.timelimiter.ledger.FakeSessionDao
import com.dylanhamersztein.timelimiter.ledger.FakeUsageDao
import com.dylanhamersztein.timelimiter.ledger.UsageLedger
import com.dylanhamersztein.timelimiter.ledger.UsageStatsReconciler
import com.dylanhamersztein.timelimiter.ledger.UsageStatsSource
import com.dylanhamersztein.timelimiter.rules.BlockReason
import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.TestClock
import com.dylanhamersztein.timelimiter.rules.UsageEvent
import com.dylanhamersztein.timelimiter.rules.UsageEventType
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackingCoordinatorTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-08-13T09:00:00Z")
    private val clock = TestClock(now, zone)

    private class Source(var events: List<UsageEvent> = emptyList()) : UsageStatsSource {
        override fun eventsBetween(from: Instant, to: Instant) = events
    }

    private class RecordingPresenter : BlockPresenter {
        val reasons = mutableListOf<BlockReason>()
        override fun present(label: String, reason: BlockReason, liftsAt: Instant) {
            reasons += reason
        }
        override fun goHomeOnly() = Unit
    }

    private class RecordingWarnings : WarningNotifier {
        val warned = mutableListOf<String>()
        override suspend fun warn(packageName: String, remaining: Duration) {
            warned += packageName
        }
    }

    private object Fake : SystemPackages {
        override fun launchers() = setOf("com.android.launcher3")
        override fun dialers() = emptySet<String>()
        override fun settings() = setOf("com.android.settings")
        override fun self() = "com.dylanhamersztein.timelimiter"
    }

    private class Harness(val clock: TestClock, val zone: ZoneId) {
        val source = Source()
        val usageDao = FakeUsageDao()
        val sessionDao = FakeSessionDao()
        val limitDao = FakeLimitDao()
        val presenter = RecordingPresenter()
        val warnings = RecordingWarnings()
        val ledger = UsageLedger(
            UsageStatsReconciler(source, usageDao, clock, zone), usageDao, sessionDao, clock, zone
        )
        val limits = LimitRepository(limitDao, clock, zone)
        val coordinator = TrackingCoordinator(
            ledger = ledger,
            limits = limits,
            launcher = BlockScreenLauncher(SafeguardAllowlist(Fake), presenter) { it },
            warnings = warnings,
            clock = clock,
            zone = zone,
        )
    }

    @Test
    fun `an untracked app is never blocked`() = runTest {
        val h = Harness(clock, zone)
        h.coordinator.onForegroundChanged("com.untracked")
        assertThat(h.presenter.reasons).isEmpty()
    }

    @Test
    fun `a tracked app inside its budget is allowed`() = runTest {
        val h = Harness(clock, zone)
        h.limits.track("com.a", "A", LimitConfig("com.a", dailyBudget = 30.minutes))

        h.coordinator.onForegroundChanged("com.a")

        assertThat(h.presenter.reasons).isEmpty()
        assertThat(h.ledger.session("com.a")).isNotNull()
    }

    @Test
    fun `an exhausted daily budget blocks on entry`() = runTest {
        val h = Harness(clock, zone)
        h.limits.track("com.a", "A", LimitConfig("com.a", dailyBudget = 30.minutes))
        val midnight = Instant.parse("2026-08-12T23:00:00Z")
        h.source.events = listOf(
            UsageEvent("com.a", UsageEventType.RESUMED, midnight),
            UsageEvent("com.a", UsageEventType.PAUSED, midnight.plusSeconds(1800)),
        )

        h.coordinator.onForegroundChanged("com.a")

        assertThat(h.presenter.reasons).containsExactly(BlockReason.DAILY_BUDGET_EXHAUSTED)
    }

    @Test
    fun `a session cap blocks on tick and marks the session capped`() = runTest {
        val h = Harness(clock, zone)
        h.limits.track("com.a", "A", LimitConfig("com.a", sessionCap = 10.minutes))

        h.coordinator.onForegroundChanged("com.a")
        h.clock.advanceBy(10.minutes)
        h.coordinator.tick()

        assertThat(h.presenter.reasons).containsExactly(BlockReason.SESSION_CAP_REACHED)
        val session = h.ledger.session("com.a")!!
        assertThat(session.endedByCap).isTrue()
        assertThat(session.leftForegroundAt).isNotNull()
    }

    @Test
    fun `leaving the app freezes its session`() = runTest {
        val h = Harness(clock, zone)
        h.limits.track("com.a", "A", LimitConfig("com.a", sessionCap = 30.minutes))

        h.coordinator.onForegroundChanged("com.a")
        h.clock.advanceBy(4.minutes)
        h.coordinator.onForegroundChanged("com.other")

        val session = h.ledger.session("com.a")!!
        assertThat(session.enteredForegroundAt).isNull()
        assertThat(session.accumulated).isEqualTo(4.minutes)
    }

    @Test
    fun `a warning fires once and does not block`() = runTest {
        val h = Harness(clock, zone)
        h.limits.track("com.a", "A", LimitConfig("com.a", dailyBudget = 30.minutes, warningThreshold = 5.minutes))
        val midnight = Instant.parse("2026-08-12T23:00:00Z")
        h.source.events = listOf(
            UsageEvent("com.a", UsageEventType.RESUMED, midnight),
            UsageEvent("com.a", UsageEventType.PAUSED, midnight.plusSeconds(1560)), // 26 minutes
        )

        h.coordinator.onForegroundChanged("com.a")
        h.coordinator.tick()

        assertThat(h.presenter.reasons).isEmpty()
        assertThat(h.warnings.warned).containsExactly("com.a")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*TrackingCoordinatorTest'`
Expected: FAIL — `Unresolved reference: TrackingCoordinator`.

- [ ] **Step 3: Write `TrackingCoordinator`**

```kotlin
package com.dylanhamersztein.timelimiter.service

import com.dylanhamersztein.timelimiter.data.LimitRepository
import com.dylanhamersztein.timelimiter.enforcement.BlockScreenLauncher
import com.dylanhamersztein.timelimiter.ledger.UsageLedger
import com.dylanhamersztein.timelimiter.rules.BlockReason
import com.dylanhamersztein.timelimiter.rules.Decision
import com.dylanhamersztein.timelimiter.rules.LimitEngine
import com.dylanhamersztein.timelimiter.rules.SessionMachine
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration

interface WarningNotifier {
    suspend fun warn(packageName: String, remaining: Duration)
}

/**
 * Sequences detection, ledger, engine and enforcement. Holds no rules of its own —
 * every decision comes from [LimitEngine].
 */
class TrackingCoordinator(
    private val ledger: UsageLedger,
    private val limits: LimitRepository,
    private val launcher: BlockScreenLauncher,
    private val warnings: WarningNotifier,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    private var currentPackage: String? = null

    suspend fun onForegroundChanged(packageName: String?) {
        val now = clock.instant()

        currentPackage?.takeIf { it != packageName }?.let { previous ->
            ledger.session(previous)?.let { session ->
                if (session.enteredForegroundAt != null) {
                    ledger.saveSession(SessionMachine.onLeaveForeground(session, now))
                }
            }
        }

        currentPackage = packageName
        if (packageName == null) return

        val config = limits.limitFor(packageName) ?: return
        val resumed = SessionMachine.onEnterForeground(
            prev = ledger.session(packageName),
            packageName = packageName,
            now = now,
            gap = config.sessionGap,
        )
        ledger.saveSession(resumed)
        evaluate(packageName)
    }

    /** Re-checks the app currently in the foreground (FR-15). */
    suspend fun tick() {
        val packageName = currentPackage ?: return
        if (limits.limitFor(packageName) == null) return
        evaluate(packageName)
    }

    private suspend fun evaluate(packageName: String) {
        val config = limits.limitFor(packageName) ?: return
        val now = clock.instant()
        val snapshot = ledger.snapshot(packageName)

        when (val decision = LimitEngine.decide(config, snapshot, now, zone)) {
            is Decision.Allow -> Unit

            is Decision.Warn -> warnings.warn(decision.packageName, decision.remaining)

            is Decision.Block -> {
                if (decision.reason == BlockReason.SESSION_CAP_REACHED) {
                    // The user is about to be ejected; record it so the stored
                    // cooldown agrees with the liftsAt shown on the block screen.
                    snapshot.session?.let { session ->
                        ledger.saveSession(
                            SessionMachine.onLeaveForeground(SessionMachine.markCapped(session), now)
                        )
                    }
                }
                launcher.launch(decision)
                currentPackage = null
            }
        }
    }
}
```

- [ ] **Step 4: Write `TrackingService`**

```kotlin
package com.dylanhamersztein.timelimiter.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.dylanhamersztein.timelimiter.TimeLimiterApplication
import com.dylanhamersztein.timelimiter.detection.ForegroundBus
import com.dylanhamersztein.timelimiter.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the coordinator alive and ticking. There is no polling loop for detection —
 * the tick only runs while a tracked app is in the foreground (NFR-2).
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val container = (application as TimeLimiterApplication).container
        startForeground(Notifications.SERVICE_ID, container.notifications.serviceNotification())

        scope.launch {
            ForegroundBus.current.collectLatest { packageName ->
                container.coordinator.onForegroundChanged(packageName)
                while (packageName != null) {
                    delay(TICK_INTERVAL_MILLIS)
                    container.coordinator.tick()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TICK_INTERVAL_MILLIS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }
    }
}
```

Manifest additions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

```xml
<service
    android:name=".service.TrackingService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Enforces the user's own app time limits while the device is in use." />
</service>
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*TrackingCoordinatorTest'`
Expected: PASS, 6 tests. `TrackingService` references `TimeLimiterApplication` and `Notifications`, which arrive in Tasks 17 and 20 — if compilation fails on those, implement this task's coordinator and tests now and add `TrackingService` at the end of Task 20.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: coordinate detection, ledger, engine and enforcement"
```

---

### Task 17: Notifications

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/notify/Notifications.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/notify/DailyWarningNotifier.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/notify/DailyWarningNotifierTest.kt`

**Interfaces:**
- Consumes: `UsageDao`, `WarningSentEntity` (Task 6); `WarningNotifier` (Task 16); `DayBoundary` (Task 2).
- Produces:
  - `class Notifications(context: Context)` with `fun createChannels()`, `fun serviceNotification(): Notification`, `fun showWarning(label: String, remaining: Duration)`, `fun showTrackingDown()`, `fun clearTrackingDown()`; companion constants `SERVICE_ID`, `TRACKING_DOWN_ID`
  - `class DailyWarningNotifier(usageDao, notifications, labels, clock, zone) : WarningNotifier`

Three notifications exist and no more (FR-20): the foreground-service one, the once-per-day warning, and the tracking-is-down warning (FR-27).

- [ ] **Step 1: Write the failing test for once-per-day behaviour**

```kotlin
package com.dylanhamersztein.timelimiter.notify

import com.dylanhamersztein.timelimiter.ledger.FakeUsageDao
import com.dylanhamersztein.timelimiter.rules.TestClock
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DailyWarningNotifierTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-08-13T09:00:00Z")

    private class Spy : WarningPresenter {
        val shown = mutableListOf<Pair<String, Duration>>()
        override fun showWarning(label: String, remaining: Duration) {
            shown += label to remaining
        }
    }

    @Test
    fun `warns the first time`() = runTest {
        val spy = Spy()
        val notifier = DailyWarningNotifier(FakeUsageDao(), spy, { "App A" }, TestClock(now, zone), zone)

        notifier.warn("com.a", 5.minutes)

        assertThat(spy.shown).containsExactly("App A" to 5.minutes)
    }

    @Test
    fun `does not warn twice on the same day`() = runTest {
        val spy = Spy()
        val notifier = DailyWarningNotifier(FakeUsageDao(), spy, { "App A" }, TestClock(now, zone), zone)

        notifier.warn("com.a", 5.minutes)
        notifier.warn("com.a", 4.minutes)

        assertThat(spy.shown).hasSize(1)
    }

    @Test
    fun `warns again the next day`() = runTest {
        val spy = Spy()
        val clock = TestClock(now, zone)
        val notifier = DailyWarningNotifier(FakeUsageDao(), spy, { "App A" }, clock, zone)

        notifier.warn("com.a", 5.minutes)
        clock.setTo(Instant.parse("2026-08-14T09:00:00Z"))
        notifier.warn("com.a", 5.minutes)

        assertThat(spy.shown).hasSize(2)
    }

    @Test
    fun `warns separately for separate apps`() = runTest {
        val spy = Spy()
        val notifier = DailyWarningNotifier(FakeUsageDao(), spy, { it }, TestClock(now, zone), zone)

        notifier.warn("com.a", 5.minutes)
        notifier.warn("com.b", 5.minutes)

        assertThat(spy.shown).hasSize(2)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DailyWarningNotifierTest'`
Expected: FAIL — `Unresolved reference: DailyWarningNotifier`.

- [ ] **Step 3: Write the implementation**

```kotlin
// DailyWarningNotifier.kt
package com.dylanhamersztein.timelimiter.notify

import com.dylanhamersztein.timelimiter.data.UsageDao
import com.dylanhamersztein.timelimiter.data.WarningSentEntity
import com.dylanhamersztein.timelimiter.rules.DayBoundary
import com.dylanhamersztein.timelimiter.service.WarningNotifier
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration

/** Seam so the once-per-day rule is testable without the Android notification manager. */
interface WarningPresenter {
    fun showWarning(label: String, remaining: Duration)
}

/** FR-19: at most one warning per app per day. */
class DailyWarningNotifier(
    private val usageDao: UsageDao,
    private val presenter: WarningPresenter,
    private val labels: (String) -> String,
    private val clock: Clock,
    private val zone: ZoneId,
) : WarningNotifier {

    override suspend fun warn(packageName: String, remaining: Duration) {
        val today = DayBoundary.localDate(clock.instant(), zone).toString()
        if (usageDao.warningCount(packageName, today) > 0) return
        usageDao.recordWarning(WarningSentEntity(packageName, today))
        presenter.showWarning(labels(packageName), remaining)
    }
}
```

```kotlin
// Notifications.kt
package com.dylanhamersztein.timelimiter.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlin.time.Duration

class Notifications(private val context: Context) : WarningPresenter {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Tracking", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNING, "Limit warnings", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Tracking problems", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("Time Limiter is running")
            .setContentText("Watching your tracked apps")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

    override fun showWarning(label: String, remaining: Duration) {
        val minutes = remaining.inWholeMinutes
        manager.notify(
            label.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_WARNING)
                .setContentTitle("$label: $minutes min left today")
                .setContentText("Your daily limit is nearly up.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** FR-27: say so, loudly, when tracking is not actually running. */
    fun showTrackingDown() {
        manager.notify(
            TRACKING_DOWN_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setContentTitle("Time Limiter is not tracking")
                .setContentText("Accessibility access or usage access is switched off. Limits are not being enforced.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setOngoing(true)
                .build(),
        )
    }

    fun clearTrackingDown() = manager.cancel(TRACKING_DOWN_ID)

    companion object {
        const val SERVICE_ID = 1
        const val TRACKING_DOWN_ID = 2
        private const val CHANNEL_SERVICE = "tracking"
        private const val CHANNEL_WARNING = "warnings"
        private const val CHANNEL_ALERT = "alerts"
    }
}
```

Manifest: `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*DailyWarningNotifierTest'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: add notification channels and once-per-day limit warnings"
```

---

### Task 18: Day reset scheduler

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/service/DayResetScheduler.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/service/DayResetReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/service/DayResetSchedulerTest.kt`

**Interfaces:**
- Consumes: `DayBoundary` (Task 2), `LimitRepository` (Task 11), `UsageDao` (Task 6).
- Produces:
  - `interface AlarmScheduler { fun scheduleAt(instant: Instant) }`
  - `class AndroidAlarmScheduler(context: Context) : AlarmScheduler`
  - `class DayResetScheduler(limits, usageDao, alarms, clock, zone)` with `suspend fun runReset()` and `fun scheduleNext()`
  - `class DayResetReceiver : BroadcastReceiver`

FR-12: at the boundary, apply due pending changes, drop yesterday's warning records so warnings re-arm, and prune usage rows older than today (there is no history in v1, §12).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.service

import com.dylanhamersztein.timelimiter.data.DailyUsageEntity
import com.dylanhamersztein.timelimiter.data.FakeLimitDao
import com.dylanhamersztein.timelimiter.data.LimitRepository
import com.dylanhamersztein.timelimiter.data.WarningSentEntity
import com.dylanhamersztein.timelimiter.ledger.FakeUsageDao
import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.TestClock
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DayResetSchedulerTest {
    private val zone = ZoneId.of("Europe/London")
    private val beforeMidnight = Instant.parse("2026-08-13T09:00:00Z")
    private val afterMidnight = Instant.parse("2026-08-13T23:00:01Z") // 2026-08-14 local

    private class RecordingAlarms : AlarmScheduler {
        var scheduledFor: Instant? = null
        override fun scheduleAt(instant: Instant) {
            scheduledFor = instant
        }
    }

    @Test
    fun `schedules the next reset for the coming local midnight`() {
        val alarms = RecordingAlarms()
        val scheduler = DayResetScheduler(
            LimitRepository(FakeLimitDao(), TestClock(beforeMidnight, zone), zone),
            FakeUsageDao(),
            alarms,
            TestClock(beforeMidnight, zone),
            zone,
        )

        scheduler.scheduleNext()

        assertThat(alarms.scheduledFor).isEqualTo(Instant.parse("2026-08-13T23:00:00Z"))
    }

    @Test
    fun `the reset applies due pending changes`() = runTest {
        val clock = TestClock(beforeMidnight, zone)
        val limitDao = FakeLimitDao()
        val limits = LimitRepository(limitDao, clock, zone)
        val base = LimitConfig("com.a", dailyBudget = 30.minutes)
        limits.track("com.a", "A", base)
        limits.edit("com.a", base.copy(dailyBudget = 60.minutes))

        clock.setTo(afterMidnight)
        DayResetScheduler(limits, FakeUsageDao(), RecordingAlarms(), clock, zone).runReset()

        assertThat(limitDao.limitFor("com.a")?.dailyBudgetMinutes).isEqualTo(60)
    }

    @Test
    fun `the reset clears yesterday's warnings so they re-arm`() = runTest {
        val clock = TestClock(afterMidnight, zone)
        val usageDao = FakeUsageDao()
        usageDao.recordWarning(WarningSentEntity("com.a", "2026-08-13"))

        DayResetScheduler(
            LimitRepository(FakeLimitDao(), clock, zone), usageDao, RecordingAlarms(), clock, zone
        ).runReset()

        assertThat(usageDao.warningCount("com.a", "2026-08-13")).isEqualTo(0)
    }

    @Test
    fun `the reset prunes usage rows from previous days`() = runTest {
        val clock = TestClock(afterMidnight, zone)
        val usageDao = FakeUsageDao()
        usageDao.upsertUsage(DailyUsageEntity("com.a", "2026-08-13", 600))
        usageDao.upsertUsage(DailyUsageEntity("com.a", "2026-08-14", 0))

        DayResetScheduler(
            LimitRepository(FakeLimitDao(), clock, zone), usageDao, RecordingAlarms(), clock, zone
        ).runReset()

        assertThat(usageDao.usageFor("com.a", "2026-08-13")).isNull()
        assertThat(usageDao.usageFor("com.a", "2026-08-14")).isNotNull()
    }

    @Test
    fun `the reset schedules the following one`() = runTest {
        val clock = TestClock(afterMidnight, zone)
        val alarms = RecordingAlarms()

        DayResetScheduler(
            LimitRepository(FakeLimitDao(), clock, zone), FakeUsageDao(), alarms, clock, zone
        ).runReset()

        assertThat(alarms.scheduledFor).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DayResetSchedulerTest'`
Expected: FAIL — `Unresolved reference: DayResetScheduler`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dylanhamersztein.timelimiter.TimeLimiterApplication
import com.dylanhamersztein.timelimiter.data.LimitRepository
import com.dylanhamersztein.timelimiter.data.UsageDao
import com.dylanhamersztein.timelimiter.rules.DayBoundary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

interface AlarmScheduler {
    fun scheduleAt(instant: Instant)
}

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    override fun scheduleAt(instant: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DayResetReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant.toEpochMilli(), pending)
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}

/** FR-12. */
class DayResetScheduler(
    private val limits: LimitRepository,
    private val usageDao: UsageDao,
    private val alarms: AlarmScheduler,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    fun scheduleNext() {
        alarms.scheduleAt(DayBoundary.nextResetAt(clock.instant(), zone))
    }

    suspend fun runReset() {
        val today = DayBoundary.localDate(clock.instant(), zone).toString()
        limits.applyDueChanges()
        usageDao.deleteWarningsBefore(today)
        usageDao.deleteUsageBefore(today)
        scheduleNext()
    }
}

class DayResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TimeLimiterApplication).container
        val pending = goAsync()
        runBlocking {
            try {
                container.dayResetScheduler.runReset()
            } finally {
                pending.finish()
            }
        }
    }
}
```

Manifest: `<receiver android:name=".service.DayResetReceiver" android:exported="false" />`

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*DayResetSchedulerTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: apply pending changes and re-arm warnings at the daily reset"
```

---

### Task 19: Boot restore and tracking-health monitor

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/service/BootReceiver.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/service/TrackingHealth.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/service/TrackingHealthTest.kt`

**Interfaces:**
- Consumes: `AccessibilityStatus` (Task 12), `Notifications` (Task 17).
- Produces:
  - `data class HealthState(accessibilityEnabled: Boolean, usageAccessGranted: Boolean, overlayGranted: Boolean)` with `val isHealthy: Boolean` and `val summary: String`
  - `interface PermissionProbe { fun accessibilityEnabled(): Boolean; fun usageAccessGranted(): Boolean; fun overlayGranted(): Boolean }`
  - `class AndroidPermissionProbe(context: Context) : PermissionProbe`
  - `class TrackingHealth(probe: PermissionProbe)` with `fun state(): HealthState`
  - `class BootReceiver : BroadcastReceiver`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackingHealthTest {
    private class Probe(
        val accessibility: Boolean = true,
        val usage: Boolean = true,
        val overlay: Boolean = true,
    ) : PermissionProbe {
        override fun accessibilityEnabled() = accessibility
        override fun usageAccessGranted() = usage
        override fun overlayGranted() = overlay
    }

    @Test
    fun `everything granted is healthy`() {
        assertThat(TrackingHealth(Probe()).state().isHealthy).isTrue()
    }

    @Test
    fun `accessibility off is unhealthy`() {
        val state = TrackingHealth(Probe(accessibility = false)).state()
        assertThat(state.isHealthy).isFalse()
        assertThat(state.summary).contains("Accessibility")
    }

    @Test
    fun `usage access revoked is unhealthy`() {
        val state = TrackingHealth(Probe(usage = false)).state()
        assertThat(state.isHealthy).isFalse()
        assertThat(state.summary).contains("Usage access")
    }

    @Test
    fun `overlay revoked is unhealthy because blocks become unreliable`() {
        val state = TrackingHealth(Probe(overlay = false)).state()
        assertThat(state.isHealthy).isFalse()
        assertThat(state.summary).contains("Display over other apps")
    }

    @Test
    fun `several problems are all named`() {
        val state = TrackingHealth(Probe(accessibility = false, usage = false)).state()
        assertThat(state.summary).contains("Accessibility")
        assertThat(state.summary).contains("Usage access")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*TrackingHealthTest'`
Expected: FAIL — `Unresolved reference: TrackingHealth`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.service

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.dylanhamersztein.timelimiter.detection.AccessibilityStatus

data class HealthState(
    val accessibilityEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val overlayGranted: Boolean,
) {
    val isHealthy: Boolean
        get() = accessibilityEnabled && usageAccessGranted && overlayGranted

    /** FR-27: name what is wrong, not just that something is. */
    val summary: String
        get() = buildList {
            if (!accessibilityEnabled) add("Accessibility access is off")
            if (!usageAccessGranted) add("Usage access is off")
            if (!overlayGranted) add("Display over other apps is off")
        }.joinToString(". ").ifEmpty { "Tracking is running" }
}

interface PermissionProbe {
    fun accessibilityEnabled(): Boolean
    fun usageAccessGranted(): Boolean
    fun overlayGranted(): Boolean
}

class AndroidPermissionProbe(private val context: Context) : PermissionProbe {

    override fun accessibilityEnabled(): Boolean = AccessibilityStatus.isEnabled(context)

    override fun usageAccessGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun overlayGranted(): Boolean = Settings.canDrawOverlays(context)
}

class TrackingHealth(private val probe: PermissionProbe) {
    fun state(): HealthState = HealthState(
        accessibilityEnabled = probe.accessibilityEnabled(),
        usageAccessGranted = probe.usageAccessGranted(),
        overlayGranted = probe.overlayGranted(),
    )
}

/** FR-28. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        TrackingService.start(context)
    }
}
```

Manifest:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

```xml
<receiver
    android:name=".service.BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*TrackingHealthTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: restore tracking after boot and report health problems"
```

---

### Task 20: App shell — container, application, navigation

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/AppContainer.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/TimeLimiterApplication.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/MainActivity.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/Theme.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/Navigation.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: every component built so far.
- Produces:
  - `class AppContainer(context: Context)` exposing `database`, `limits`, `ledger`, `settings`, `notifications`, `coordinator`, `dayResetScheduler`, `trackingHealth`, `installedApps`, `clock`, `zone`
  - `class TimeLimiterApplication : Application` with `val container: AppContainer`
  - `class MainActivity : ComponentActivity`
  - `sealed class Route` with `Home`, `Picker`, `Editor(packageName)`, `Passcode`, `Onboarding`

This task also finishes `TrackingService` if it was deferred at the end of Task 16.

- [ ] **Step 1: Write `AppContainer`**

```kotlin
package com.dylanhamersztein.timelimiter

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import com.dylanhamersztein.timelimiter.data.LimitRepository
import com.dylanhamersztein.timelimiter.data.SettingsStore
import com.dylanhamersztein.timelimiter.data.TimeLimiterDatabase
import com.dylanhamersztein.timelimiter.enforcement.AndroidBlockPresenter
import com.dylanhamersztein.timelimiter.enforcement.AndroidSystemPackages
import com.dylanhamersztein.timelimiter.enforcement.BlockScreenLauncher
import com.dylanhamersztein.timelimiter.enforcement.SafeguardAllowlist
import com.dylanhamersztein.timelimiter.ledger.AndroidUsageStatsSource
import com.dylanhamersztein.timelimiter.ledger.UsageLedger
import com.dylanhamersztein.timelimiter.ledger.UsageStatsReconciler
import com.dylanhamersztein.timelimiter.notify.DailyWarningNotifier
import com.dylanhamersztein.timelimiter.notify.Notifications
import com.dylanhamersztein.timelimiter.service.AndroidAlarmScheduler
import com.dylanhamersztein.timelimiter.service.AndroidPermissionProbe
import com.dylanhamersztein.timelimiter.service.DayResetScheduler
import com.dylanhamersztein.timelimiter.service.TrackingCoordinator
import com.dylanhamersztein.timelimiter.service.TrackingHealth
import java.time.Clock
import java.time.ZoneId

/** Manual dependency container. Constructed once, in Application.onCreate. */
class AppContainer(context: Context) {

    val clock: Clock = Clock.systemDefaultZone()
    val zone: ZoneId get() = ZoneId.systemDefault()

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    val database: TimeLimiterDatabase = Room.databaseBuilder(
        appContext, TimeLimiterDatabase::class.java, "time-limiter.db"
    ).build()

    val settings = SettingsStore(appContext)
    val notifications = Notifications(appContext)
    val limits = LimitRepository(database.limitDao(), clock, zone)

    private fun labelFor(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    }.getOrDefault(packageName)

    val ledger = UsageLedger(
        reconciler = UsageStatsReconciler(
            source = AndroidUsageStatsSource(
                appContext.getSystemService(UsageStatsManager::class.java)
            ),
            usageDao = database.usageDao(),
            clock = clock,
            zone = zone,
        ),
        usageDao = database.usageDao(),
        sessionDao = database.sessionDao(),
        clock = clock,
        zone = zone,
    )

    val coordinator = TrackingCoordinator(
        ledger = ledger,
        limits = limits,
        launcher = BlockScreenLauncher(
            allowlist = SafeguardAllowlist(AndroidSystemPackages(appContext)),
            presenter = AndroidBlockPresenter(appContext),
            labels = ::labelFor,
        ),
        warnings = DailyWarningNotifier(
            usageDao = database.usageDao(),
            presenter = notifications,
            labels = ::labelFor,
            clock = clock,
            zone = zone,
        ),
        clock = clock,
        zone = zone,
    )

    val dayResetScheduler = DayResetScheduler(
        limits = limits,
        usageDao = database.usageDao(),
        alarms = AndroidAlarmScheduler(appContext),
        clock = clock,
        zone = zone,
    )

    val trackingHealth = TrackingHealth(AndroidPermissionProbe(appContext))

    val installedApps = InstalledApps(packageManager)
}
```

- [ ] **Step 2: Write `InstalledApps`, the picker's data source**

```kotlin
package com.dylanhamersztein.timelimiter

import android.content.Intent
import android.content.pm.PackageManager

data class InstalledApp(val packageName: String, val label: String)

/**
 * Launchable installed apps, alphabetically (FR-1). No usage figures are read here:
 * FR-3 keeps untracked apps out of the UI entirely.
 */
class InstalledApps(private val packageManager: PackageManager) {

    fun launchable(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map {
                InstalledApp(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
```

- [ ] **Step 3: Write the application class, theme and activity**

```kotlin
// TimeLimiterApplication.kt
package com.dylanhamersztein.timelimiter

import android.app.Application

class TimeLimiterApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
        container.dayResetScheduler.scheduleNext()
    }
}
```

```kotlin
// ui/Theme.kt
package com.dylanhamersztein.timelimiter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun TimeLimiterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
```

```kotlin
// MainActivity.kt
package com.dylanhamersztein.timelimiter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dylanhamersztein.timelimiter.service.TrackingService
import com.dylanhamersztein.timelimiter.ui.TimeLimiterApp
import com.dylanhamersztein.timelimiter.ui.TimeLimiterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TimeLimiterApplication).container
        if (container.trackingHealth.state().isHealthy) {
            TrackingService.start(this)
        }
        setContent {
            TimeLimiterTheme {
                TimeLimiterApp(container)
            }
        }
    }
}
```

```kotlin
// ui/Navigation.kt
package com.dylanhamersztein.timelimiter.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dylanhamersztein.timelimiter.AppContainer

object Route {
    const val HOME = "home"
    const val PICKER = "picker"
    const val EDITOR = "editor/{packageName}"
    const val ONBOARDING = "onboarding"

    fun editor(packageName: String) = "editor/$packageName"
}

@Composable
fun TimeLimiterApp(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Route.HOME) {
        composable(Route.HOME) { /* Task 21 */ }
        composable(Route.PICKER) { /* Task 22 */ }
        composable(Route.EDITOR) { /* Task 24 */ }
        composable(Route.ONBOARDING) { /* Task 25 */ }
    }
}
```

The empty `composable` bodies are filled by the tasks named in each comment; they are not left as placeholders at the end of the plan.

Manifest `<application>` attributes: `android:name=".TimeLimiterApplication"`, and register `MainActivity` with the `LAUNCHER` intent filter.

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug && ./gradlew test`
Expected: BUILD SUCCESSFUL, all unit tests still passing.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: add app container, application class and navigation shell"
```

---

### Task 21: Home screen

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/home/HomeViewModel.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/home/HomeScreen.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/UsageFormat.kt`
- Modify: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/Navigation.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ui/UsageFormatTest.kt`

**Interfaces:**
- Consumes: `LimitRepository`, `UsageLedger`, `TrackingHealth`, `InstalledApps`.
- Produces:
  - `data class TrackedAppRow(packageName, label, used: Duration, dailyBudget: Duration?, sessionCap: Duration?, pending: PendingChange?)`
  - `data class HomeState(rows: List<TrackedAppRow>, health: HealthState)`
  - `class HomeViewModel(container)` exposing `val state: StateFlow<HomeState>` and `fun refresh()`
  - `object UsageFormat { fun duration(d: Duration): String; fun remaining(used: Duration, budget: Duration?): String }`

FR-2 and FR-3: tracked apps only, today only, with the health banner from FR-27.

- [ ] **Step 1: Write the failing formatter test**

```kotlin
package com.dylanhamersztein.timelimiter.ui

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class UsageFormatTest {
    @Test
    fun `formats minutes under an hour`() {
        assertThat(UsageFormat.duration(25.minutes)).isEqualTo("25m")
    }

    @Test
    fun `formats hours and minutes`() {
        assertThat(UsageFormat.duration(95.minutes)).isEqualTo("1h 35m")
    }

    @Test
    fun `rounds seconds down to the minute`() {
        assertThat(UsageFormat.duration(119.seconds)).isEqualTo("1m")
    }

    @Test
    fun `formats zero`() {
        assertThat(UsageFormat.duration(0.minutes)).isEqualTo("0m")
    }

    @Test
    fun `describes remaining budget`() {
        assertThat(UsageFormat.remaining(10.minutes, 30.minutes)).isEqualTo("20m left")
    }

    @Test
    fun `describes an exhausted budget`() {
        assertThat(UsageFormat.remaining(35.minutes, 30.minutes)).isEqualTo("Limit reached")
    }

    @Test
    fun `describes an app with no daily budget`() {
        assertThat(UsageFormat.remaining(35.minutes, null)).isEqualTo("No daily limit")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*UsageFormatTest'`
Expected: FAIL — `Unresolved reference: UsageFormat`.

- [ ] **Step 3: Write `UsageFormat`**

```kotlin
package com.dylanhamersztein.timelimiter.ui

import kotlin.time.Duration

object UsageFormat {
    fun duration(d: Duration): String {
        val totalMinutes = d.inWholeMinutes
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    fun remaining(used: Duration, budget: Duration?): String {
        if (budget == null) return "No daily limit"
        val left = budget - used
        return if (left.isPositive()) "${duration(left)} left" else "Limit reached"
    }
}
```

- [ ] **Step 4: Write the view model and screen**

```kotlin
// HomeViewModel.kt
package com.dylanhamersztein.timelimiter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylanhamersztein.timelimiter.AppContainer
import com.dylanhamersztein.timelimiter.rules.PendingChange
import com.dylanhamersztein.timelimiter.service.HealthState
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class TrackedAppRow(
    val packageName: String,
    val label: String,
    val used: Duration,
    val dailyBudget: Duration?,
    val sessionCap: Duration?,
    val pending: PendingChange?,
)

data class HomeState(
    val rows: List<TrackedAppRow> = emptyList(),
    val health: HealthState? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.limits.observeTrackedApps(),
                container.limits.observeLimits(),
                container.limits.observePendingChanges(),
            ) { apps, limits, pending ->
                Triple(apps, limits, pending.associateBy { it.packageName })
            }.collect { (apps, limits, pending) ->
                val snapshots = container.ledger.snapshots(apps.map { it.packageName }.toSet())
                _state.value = HomeState(
                    rows = apps.map { app ->
                        val config = limits[app.packageName]
                        TrackedAppRow(
                            packageName = app.packageName,
                            label = app.label,
                            used = snapshots[app.packageName]?.usedToday ?: Duration.ZERO,
                            dailyBudget = config?.dailyBudget,
                            sessionCap = config?.sessionCap,
                            pending = pending[app.packageName],
                        )
                    },
                    health = container.trackingHealth.state(),
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(health = container.trackingHealth.state())
        }
    }
}
```

```kotlin
// HomeScreen.kt
package com.dylanhamersztein.timelimiter.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dylanhamersztein.timelimiter.ui.UsageFormat

@Composable
fun HomeScreen(
    state: HomeState,
    onAddApp: () -> Unit,
    onOpenApp: (String) -> Unit,
    onFixPermissions: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Today") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddApp) { Text("Add app") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val health = state.health
            if (health != null && !health.isHealthy) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onFixPermissions),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Limits are not being enforced", style = MaterialTheme.typography.titleMedium)
                        Text(health.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (state.rows.isEmpty()) {
                Text(
                    "No apps tracked yet. Add one to set a limit.",
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyColumn {
                items(state.rows, key = { it.packageName }) { row ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clickable { onOpenApp(row.packageName) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(row.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${UsageFormat.duration(row.used)} today · " +
                                    UsageFormat.remaining(row.used, row.dailyBudget),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            row.sessionCap?.let {
                                Text(
                                    "Session cap ${UsageFormat.duration(it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            row.pending?.let {
                                Text(
                                    "Change scheduled for ${it.effectiveDate}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

Wire `Route.HOME` in `Navigation.kt` to `HomeScreen`, navigating to `Route.PICKER` on add and `Route.editor(packageName)` on tap.

- [ ] **Step 5: Run the tests and build**

Run: `./gradlew test :app:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/
git commit -m "feat: add home screen showing today's usage and tracking health"
```

---

### Task 22: App picker

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/picker/AppPickerViewModel.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/picker/AppPickerScreen.kt`
- Modify: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/Navigation.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ui/picker/AppPickerFilterTest.kt`

**Interfaces:**
- Consumes: `InstalledApps`, `LimitRepository`, `SafeguardAllowlist`.
- Produces:
  - `object AppPickerFilter { fun selectable(installed: List<InstalledApp>, alreadyTracked: Set<String>, isProtected: (String) -> Boolean, query: String): List<InstalledApp> }`
  - `class AppPickerViewModel(container)` with `val state: StateFlow<List<InstalledApp>>`, `fun search(query: String)`
  - `AppPickerScreen` composable

The picker never offers a protected package (FR-18) or an app already tracked, and never shows usage figures (FR-3).

Android 11+ requires a `<queries>` manifest element to see other apps at all:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.ui.picker

import com.dylanhamersztein.timelimiter.InstalledApp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppPickerFilterTest {
    private val installed = listOf(
        InstalledApp("com.instagram.android", "Instagram"),
        InstalledApp("com.android.settings", "Settings"),
        InstalledApp("com.slack", "Slack"),
        InstalledApp("com.spotify", "Spotify"),
    )
    private val isProtected = { pkg: String -> pkg == "com.android.settings" }

    @Test
    fun `excludes protected packages`() {
        val result = AppPickerFilter.selectable(installed, emptySet(), isProtected, "")
        assertThat(result.map { it.packageName }).doesNotContain("com.android.settings")
    }

    @Test
    fun `excludes apps already tracked`() {
        val result = AppPickerFilter.selectable(installed, setOf("com.slack"), isProtected, "")
        assertThat(result.map { it.packageName }).doesNotContain("com.slack")
    }

    @Test
    fun `filters by a case-insensitive label query`() {
        val result = AppPickerFilter.selectable(installed, emptySet(), isProtected, "sp")
        assertThat(result.map { it.label }).containsExactly("Spotify")
    }

    @Test
    fun `an empty query returns everything selectable`() {
        val result = AppPickerFilter.selectable(installed, emptySet(), isProtected, "")
        assertThat(result).hasSize(3)
    }

    @Test
    fun `results stay alphabetical`() {
        val result = AppPickerFilter.selectable(installed.reversed(), emptySet(), isProtected, "")
        assertThat(result.map { it.label }).containsExactly("Instagram", "Slack", "Spotify").inOrder()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*AppPickerFilterTest'`
Expected: FAIL — `Unresolved reference: AppPickerFilter`.

- [ ] **Step 3: Write the filter and screen**

```kotlin
// AppPickerViewModel.kt (filter lives here)
package com.dylanhamersztein.timelimiter.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylanhamersztein.timelimiter.AppContainer
import com.dylanhamersztein.timelimiter.InstalledApp
import com.dylanhamersztein.timelimiter.enforcement.AndroidSystemPackages
import com.dylanhamersztein.timelimiter.enforcement.SafeguardAllowlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object AppPickerFilter {
    fun selectable(
        installed: List<InstalledApp>,
        alreadyTracked: Set<String>,
        isProtected: (String) -> Boolean,
        query: String,
    ): List<InstalledApp> = installed
        .filterNot { it.packageName in alreadyTracked }
        .filterNot { isProtected(it.packageName) }
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedBy { it.label.lowercase() }
}

class AppPickerViewModel(private val container: AppContainer) : ViewModel() {

    private val allowlist = SafeguardAllowlist(AndroidSystemPackages(container.appContext))
    private var query = ""
    private var installed = emptyList<InstalledApp>()
    private var tracked = emptySet<String>()

    private val _state = MutableStateFlow<List<InstalledApp>>(emptyList())
    val state: StateFlow<List<InstalledApp>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            installed = container.installedApps.launchable()
            tracked = container.limits.observeTrackedApps().first().map { it.packageName }.toSet()
            recompute()
        }
    }

    fun search(newQuery: String) {
        query = newQuery
        recompute()
    }

    private fun recompute() {
        _state.value = AppPickerFilter.selectable(installed, tracked, allowlist::isProtected, query)
    }
}
```

`AppContainer` needs to expose `appContext`; add `val appContext: Context = context.applicationContext` and drop the private modifier from the existing field.

```kotlin
// AppPickerScreen.kt
package com.dylanhamersztein.timelimiter.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dylanhamersztein.timelimiter.InstalledApp

@Composable
fun AppPickerScreen(
    apps: List<InstalledApp>,
    onSearch: (String) -> Unit,
    onPick: (InstalledApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Choose an app") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; onSearch(it) },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            LazyColumn {
                items(apps, key = { it.packageName }) { app ->
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app) }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}
```

Picking an app navigates to the editor for that package, which is where the limit is actually set (Task 24).

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew test :app:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: add app picker excluding protected and tracked apps"
```

---

### Task 23: Passcode setup and gate

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/passcode/PasscodeViewModel.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/passcode/PasscodeScreen.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ui/passcode/PasscodeEntryStateTest.kt`

**Interfaces:**
- Consumes: `SettingsStore` (Task 7), `PasscodeRecovery` (Task 7).
- Produces:
  - `data class PasscodeEntryState(entered: String, error: String?, recoveryRemaining: Duration?, recoveryAvailable: Boolean)`
  - `object PasscodeEntry { fun append(state, digit): PasscodeEntryState; fun backspace(state): PasscodeEntryState; fun isComplete(state): Boolean }`
  - `class PasscodeViewModel(container)` with `suspend fun submit(code: String): Boolean`, `fun requestRecovery()`, `fun cancelRecovery()`, `suspend fun setPasscode(code: String)`
  - `PasscodeScreen` composable used both for first-time setup and for the gate

FR-21: this gate stands in front of the limit editor and the picker only. It must not appear on app launch, on the home screen, or anywhere on the block screen.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.ui.passcode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasscodeEntryStateTest {
    private val empty = PasscodeEntryState()

    @Test
    fun `appends digits up to four`() {
        var state = empty
        "12345".forEach { state = PasscodeEntry.append(state, it) }
        assertThat(state.entered).isEqualTo("1234")
    }

    @Test
    fun `is complete at four digits`() {
        var state = empty
        "123".forEach { state = PasscodeEntry.append(state, it) }
        assertThat(PasscodeEntry.isComplete(state)).isFalse()
        state = PasscodeEntry.append(state, '4')
        assertThat(PasscodeEntry.isComplete(state)).isTrue()
    }

    @Test
    fun `backspace removes the last digit`() {
        var state = PasscodeEntry.append(empty, '1')
        state = PasscodeEntry.append(state, '2')
        state = PasscodeEntry.backspace(state)
        assertThat(state.entered).isEqualTo("1")
    }

    @Test
    fun `backspace on empty is a no-op`() {
        assertThat(PasscodeEntry.backspace(empty).entered).isEmpty()
    }

    @Test
    fun `appending clears a previous error`() {
        val errored = empty.copy(error = "Wrong code")
        assertThat(PasscodeEntry.append(errored, '1').error).isNull()
    }

    @Test
    fun `non-digits are ignored`() {
        assertThat(PasscodeEntry.append(empty, 'a').entered).isEmpty()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*PasscodeEntryStateTest'`
Expected: FAIL — `Unresolved reference: PasscodeEntry`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.ui.passcode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylanhamersztein.timelimiter.AppContainer
import com.dylanhamersztein.timelimiter.rules.PasscodeRecovery
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PasscodeEntryState(
    val entered: String = "",
    val error: String? = null,
    val recoveryRemaining: Duration? = null,
    val recoveryAvailable: Boolean = false,
)

object PasscodeEntry {
    private const val LENGTH = 4

    fun append(state: PasscodeEntryState, digit: Char): PasscodeEntryState {
        if (!digit.isDigit() || state.entered.length >= LENGTH) return state
        return state.copy(entered = state.entered + digit, error = null)
    }

    fun backspace(state: PasscodeEntryState): PasscodeEntryState =
        if (state.entered.isEmpty()) state
        else state.copy(entered = state.entered.dropLast(1), error = null)

    fun isComplete(state: PasscodeEntryState): Boolean = state.entered.length == LENGTH
}

class PasscodeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PasscodeEntryState())
    val state: StateFlow<PasscodeEntryState> = _state.asStateFlow()

    init {
        viewModelScope.launch { refreshRecovery() }
    }

    fun onDigit(digit: Char) {
        _state.value = PasscodeEntry.append(_state.value, digit)
    }

    fun onBackspace() {
        _state.value = PasscodeEntry.backspace(_state.value)
    }

    suspend fun submit(): Boolean {
        val code = _state.value.entered
        val correct = container.settings.verify(code)
        _state.value = if (correct) {
            PasscodeEntryState()
        } else {
            _state.value.copy(entered = "", error = "Wrong code")
        }
        return correct
    }

    suspend fun setPasscode(code: String) {
        container.settings.setPasscode(code)
        _state.value = PasscodeEntryState()
    }

    fun requestRecovery() {
        viewModelScope.launch {
            container.settings.requestRecovery(container.clock.instant())
            refreshRecovery()
        }
    }

    fun cancelRecovery() {
        viewModelScope.launch {
            container.settings.cancelRecovery()
            refreshRecovery()
        }
    }

    private suspend fun refreshRecovery() {
        val requestedAt = container.settings.recoveryRequestedAt.first()
        val now = container.clock.instant()
        _state.value = _state.value.copy(
            recoveryRemaining = PasscodeRecovery.remaining(requestedAt, now),
            recoveryAvailable = PasscodeRecovery.isAvailable(requestedAt, now),
        )
    }
}
```

`PasscodeScreen` renders four dots, a 0–9 keypad with backspace, the error text, and a "Forgot code" affordance showing `recoveryRemaining` as a countdown once requested. When `recoveryAvailable` is true it offers to set a new code. Build it with `Column`/`Row` of `Button`s — no new dependencies.

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew test :app:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: add passcode entry, setup and 24-hour recovery UI"
```

---

### Task 24: Limit editor

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/editor/LimitEditorViewModel.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/editor/LimitEditorScreen.kt`
- Modify: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/Navigation.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ui/editor/LimitDraftTest.kt`

**Interfaces:**
- Consumes: `LimitRepository`, `EditOutcome` (Task 11), `LimitConfig` (Task 2), passcode gate (Task 23).
- Produces:
  - `data class LimitDraft(packageName, dailyBudgetMinutes: Int?, sessionCapMinutes: Int?, sessionGapMinutes: Int, cooldownMinutes: Int, warningThresholdMinutes: Int)` with `fun toConfig(): LimitConfig?` and `val validationError: String?`
  - `class LimitEditorViewModel(container, packageName)` with `val draft: StateFlow<LimitDraft>`, mutators, `suspend fun save(): EditOutcome`, `suspend fun untrack(): EditOutcome`, `fun cancelPending()`

The editor is reached only through the passcode gate. On save it shows what happened: "Applied now" or "Takes effect on 14 August" (FR-24). It must never write to `LimitDao` directly — always through `LimitRepository`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.ui.editor

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class LimitDraftTest {
    private val draft = LimitDraft(packageName = "com.a", dailyBudgetMinutes = 30)

    @Test
    fun `a draft with a daily budget converts to a config`() {
        val config = draft.toConfig()
        assertThat(config?.dailyBudget).isEqualTo(30.minutes)
        assertThat(config?.sessionCap).isNull()
    }

    @Test
    fun `a draft with neither cap is invalid`() {
        val empty = draft.copy(dailyBudgetMinutes = null)
        assertThat(empty.toConfig()).isNull()
        assertThat(empty.validationError).isEqualTo("Set a daily limit, a session limit, or both")
    }

    @Test
    fun `a zero daily budget is invalid`() {
        val zero = draft.copy(dailyBudgetMinutes = 0)
        assertThat(zero.toConfig()).isNull()
        assertThat(zero.validationError).isEqualTo("Limits must be at least one minute")
    }

    @Test
    fun `a session-only draft is valid`() {
        val sessionOnly = draft.copy(dailyBudgetMinutes = null, sessionCapMinutes = 10)
        assertThat(sessionOnly.toConfig()?.sessionCap).isEqualTo(10.minutes)
        assertThat(sessionOnly.validationError).isNull()
    }

    @Test
    fun `carries the gap, cooldown and warning threshold through`() {
        val full = draft.copy(
            sessionCapMinutes = 10, sessionGapMinutes = 3, cooldownMinutes = 20, warningThresholdMinutes = 2
        )
        val config = full.toConfig()!!
        assertThat(config.sessionGap).isEqualTo(3.minutes)
        assertThat(config.cooldown).isEqualTo(20.minutes)
        assertThat(config.warningThreshold).isEqualTo(2.minutes)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*LimitDraftTest'`
Expected: FAIL — `Unresolved reference: LimitDraft`.

- [ ] **Step 3: Write `LimitDraft` and the view model**

```kotlin
package com.dylanhamersztein.timelimiter.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylanhamersztein.timelimiter.AppContainer
import com.dylanhamersztein.timelimiter.data.EditOutcome
import com.dylanhamersztein.timelimiter.rules.LimitConfig
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LimitDraft(
    val packageName: String,
    val dailyBudgetMinutes: Int? = null,
    val sessionCapMinutes: Int? = null,
    val sessionGapMinutes: Int = 5,
    val cooldownMinutes: Int = 15,
    val warningThresholdMinutes: Int = 5,
) {
    val validationError: String?
        get() = when {
            dailyBudgetMinutes == null && sessionCapMinutes == null ->
                "Set a daily limit, a session limit, or both"
            dailyBudgetMinutes != null && dailyBudgetMinutes < 1 ->
                "Limits must be at least one minute"
            sessionCapMinutes != null && sessionCapMinutes < 1 ->
                "Limits must be at least one minute"
            sessionGapMinutes < 1 -> "The session gap must be at least one minute"
            else -> null
        }

    fun toConfig(): LimitConfig? {
        if (validationError != null) return null
        return LimitConfig(
            packageName = packageName,
            dailyBudget = dailyBudgetMinutes?.minutes,
            sessionCap = sessionCapMinutes?.minutes,
            sessionGap = sessionGapMinutes.minutes,
            cooldown = cooldownMinutes.minutes,
            warningThreshold = warningThresholdMinutes.minutes,
        )
    }

    companion object {
        fun from(config: LimitConfig) = LimitDraft(
            packageName = config.packageName,
            dailyBudgetMinutes = config.dailyBudget?.inWholeMinutes?.toInt(),
            sessionCapMinutes = config.sessionCap?.inWholeMinutes?.toInt(),
            sessionGapMinutes = config.sessionGap.inWholeMinutes.toInt(),
            cooldownMinutes = config.cooldown.inWholeMinutes.toInt(),
            warningThresholdMinutes = config.warningThreshold.inWholeMinutes.toInt(),
        )
    }
}

class LimitEditorViewModel(
    private val container: AppContainer,
    private val packageName: String,
    private val label: String,
) : ViewModel() {

    private val _draft = MutableStateFlow(LimitDraft(packageName))
    val draft: StateFlow<LimitDraft> = _draft.asStateFlow()

    private val _isNew = MutableStateFlow(true)
    val isNew: StateFlow<Boolean> = _isNew.asStateFlow()

    init {
        viewModelScope.launch {
            container.limits.limitFor(packageName)?.let {
                _draft.value = LimitDraft.from(it)
                _isNew.value = false
            }
        }
    }

    fun update(transform: (LimitDraft) -> LimitDraft) {
        _draft.value = transform(_draft.value)
    }

    suspend fun save(): EditOutcome? {
        val config = _draft.value.toConfig() ?: return null
        return if (_isNew.value) {
            container.limits.track(packageName, label, config)
            EditOutcome.AppliedNow
        } else {
            container.limits.edit(packageName, config)
        }
    }

    suspend fun untrack(): EditOutcome = container.limits.untrack(packageName)

    fun cancelPending() {
        viewModelScope.launch { container.limits.cancelPending(packageName) }
    }
}
```

`LimitEditorScreen` shows number fields for each value, an enable/disable toggle for each of the two caps, the validation error, a Save button, a Remove button, and — when a pending change exists — a card describing it with a Cancel action. On save, surface the `EditOutcome` in a snackbar: `AppliedNow` → "Saved", `Pending(date)` → "Takes effect on <date>".

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew test :app:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: add passcode-gated limit editor with pending change display"
```

---

### Task 25: Onboarding permission wizard

**Files:**
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/onboarding/OnboardingViewModel.kt`
- Create: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/kotlin/com/dylanhamersztein/timelimiter/ui/Navigation.kt`
- Test: `app/src/test/kotlin/com/dylanhamersztein/timelimiter/ui/onboarding/OnboardingStepsTest.kt`

**Interfaces:**
- Consumes: `TrackingHealth`, `HealthState`, `PermissionProbe` (Task 19); `SettingsStore` (Task 7).
- Produces:
  - `enum class OnboardingStep { USAGE_ACCESS, ACCESSIBILITY, OVERLAY, NOTIFICATIONS, PASSCODE, DONE }`
  - `object OnboardingSteps { fun nextIncomplete(health: HealthState, notificationsGranted: Boolean, passcodeSet: Boolean): OnboardingStep }`
  - `class OnboardingViewModel(container)` with `val step: StateFlow<OnboardingStep>`, `fun refresh()`, and intent factories for each Settings deep link

Each step verifies the grant before advancing (§10). The user returns from Settings via `onResume`, at which point `refresh()` re-probes.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.dylanhamersztein.timelimiter.ui.onboarding

import com.dylanhamersztein.timelimiter.service.HealthState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnboardingStepsTest {
    private val allGranted = HealthState(
        accessibilityEnabled = true, usageAccessGranted = true, overlayGranted = true
    )

    @Test
    fun `usage access comes first`() {
        val health = allGranted.copy(usageAccessGranted = false, accessibilityEnabled = false)
        assertThat(OnboardingSteps.nextIncomplete(health, false, false))
            .isEqualTo(OnboardingStep.USAGE_ACCESS)
    }

    @Test
    fun `accessibility comes after usage access`() {
        val health = allGranted.copy(accessibilityEnabled = false)
        assertThat(OnboardingSteps.nextIncomplete(health, false, false))
            .isEqualTo(OnboardingStep.ACCESSIBILITY)
    }

    @Test
    fun `overlay comes after accessibility`() {
        val health = allGranted.copy(overlayGranted = false)
        assertThat(OnboardingSteps.nextIncomplete(health, false, false))
            .isEqualTo(OnboardingStep.OVERLAY)
    }

    @Test
    fun `notifications come after overlay`() {
        assertThat(OnboardingSteps.nextIncomplete(allGranted, false, false))
            .isEqualTo(OnboardingStep.NOTIFICATIONS)
    }

    @Test
    fun `the passcode is the last step`() {
        assertThat(OnboardingSteps.nextIncomplete(allGranted, true, false))
            .isEqualTo(OnboardingStep.PASSCODE)
    }

    @Test
    fun `everything granted is done`() {
        assertThat(OnboardingSteps.nextIncomplete(allGranted, true, true))
            .isEqualTo(OnboardingStep.DONE)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*OnboardingStepsTest'`
Expected: FAIL — `Unresolved reference: OnboardingSteps`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.dylanhamersztein.timelimiter.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.dylanhamersztein.timelimiter.service.HealthState

enum class OnboardingStep { USAGE_ACCESS, ACCESSIBILITY, OVERLAY, NOTIFICATIONS, PASSCODE, DONE }

object OnboardingSteps {
    fun nextIncomplete(
        health: HealthState,
        notificationsGranted: Boolean,
        passcodeSet: Boolean,
    ): OnboardingStep = when {
        !health.usageAccessGranted -> OnboardingStep.USAGE_ACCESS
        !health.accessibilityEnabled -> OnboardingStep.ACCESSIBILITY
        !health.overlayGranted -> OnboardingStep.OVERLAY
        !notificationsGranted -> OnboardingStep.NOTIFICATIONS
        !passcodeSet -> OnboardingStep.PASSCODE
        else -> OnboardingStep.DONE
    }

    fun intentFor(step: OnboardingStep, context: Context): Intent? = when (step) {
        OnboardingStep.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        OnboardingStep.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        OnboardingStep.OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        OnboardingStep.NOTIFICATIONS, OnboardingStep.PASSCODE, OnboardingStep.DONE -> null
    }

    fun explanation(step: OnboardingStep): String = when (step) {
        OnboardingStep.USAGE_ACCESS ->
            "Usage access lets Time Limiter read how long you've spent in each app today. This is the figure your limits are measured against."
        OnboardingStep.ACCESSIBILITY ->
            "Accessibility access lets Time Limiter notice the moment you open a tracked app, so a limit can be enforced immediately. Nothing is read from your screen."
        OnboardingStep.OVERLAY ->
            "Displaying over other apps is what lets the block screen actually appear over the app you've run out of time in."
        OnboardingStep.NOTIFICATIONS ->
            "Notifications are used to warn you before a limit runs out, and to tell you if tracking ever stops working."
        OnboardingStep.PASSCODE ->
            "Set a 4-digit code. You'll need it to change or remove a limit. Loosening a limit still waits until tomorrow."
        OnboardingStep.DONE -> "You're all set."
    }
}
```

`OnboardingScreen` shows the current step's explanation and a button that fires `intentFor` (or the `POST_NOTIFICATIONS` runtime request, or the passcode setup screen). `OnboardingViewModel.refresh()` runs on `ON_RESUME` and advances the step. On `DONE`, call `settings.setOnboardingComplete()`, start `TrackingService`, and navigate home.

`MainActivity` routes to `Route.ONBOARDING` instead of `Route.HOME` when `settings.onboardingComplete` is false or `trackingHealth.state().isHealthy` is false.

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew test :app:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: add onboarding wizard verifying each permission grant"
```

---

### Task 26: README and the manual test matrix

**Files:**
- Create: `README.md`
- Create: `docs/manual-test-matrix.md`

**Interfaces:**
- Consumes: everything.
- Produces: the written record of what cannot be automated.

§11 of the PRD is explicit that the device-dependent behaviour cannot be honestly unit-tested. This task writes that matrix down so it gets run rather than assumed.

- [ ] **Step 1: Write `docs/manual-test-matrix.md`**

Each row: what to do, what should happen, and a column to record the result and the device it was run on.

| # | Scenario | Steps | Expected |
| --- | --- | --- | --- |
| 1 | Block latency (NFR-1) | Set a 1-minute daily limit, use the app until it trips | Block screen appears in well under a second; no visible frame of the app after the limit |
| 2 | Daily exhaustion mid-session (FR-15) | Set a 2-minute daily limit and a 30-minute session cap, sit in the app | Block lands mid-use at 2 minutes, not at any session boundary |
| 3 | Session cap and cooldown (FR-14) | 2-minute session cap, 1-minute gap, 3-minute cooldown | Block at 2 minutes; re-opening before gap+cooldown elapses re-blocks with "Cooling down"; allowed after |
| 4 | Re-entry (FR-17) | After a block, immediately reopen the app | Block screen appears again every time |
| 5 | Loosening waits (FR-23) | Raise a daily limit through the editor | Editor says it takes effect tomorrow; today's block still applies |
| 6 | Tightening applies now (FR-22) | Lower a daily limit below current usage | Block appears immediately on next entry |
| 7 | Untracking waits (FR-23) | Remove a tracked app | App stays limited today; gone after the next reset |
| 8 | Reset rollover (FR-12) | Set the device clock past midnight | Budgets reset, pending changes applied, warnings re-arm |
| 9 | Reboot (FR-28) | Reboot with usage already accrued | Service restarts; today's total is preserved, not zeroed |
| 10 | Service killed | Force-stop Time Limiter, use a tracked app, reopen Time Limiter | Time used while dead is still counted (FR-9) |
| 11 | Accessibility disabled (FR-27) | Turn the service off in Settings | Home banner and persistent notification both appear |
| 12 | Overlay revoked | Revoke "display over other apps", trip a limit | User is still ejected to home (fallback path), no crash |
| 13 | Safeguards (FR-18) | Attempt to configure a limit on the launcher, dialer, Settings, and Time Limiter | None are offered in the picker; none are ever blocked |
| 14 | Passcode gate (FR-21) | Open the editor | Code required; home screen and block screen never ask for it |
| 15 | Passcode recovery (FR-26) | Request recovery | Countdown visible; reset only available after 24h; cancel works |
| 16 | Uninstall (FR-29) | Uninstall from Settings | Nothing obstructs it |
| 17 | OEM battery management (§13) | Leave the device idle overnight with battery optimisation on | Service still running next morning; if not, record the vendor behaviour |
| 18 | Attribution sanity (§13) | Compare a day's figures against Digital Wellbeing | Totals agree within a couple of minutes |

- [ ] **Step 2: Write `README.md`**

Cover: what the app does and the platform ceiling from PRD §3; build instructions (`./gradlew :app:assembleDebug`, `adb install`); the four permissions and why each is needed; how to run the tests (`./gradlew test` for JVM, `./gradlew connectedAndroidTest` for DAOs); the module split and why `:rules` has no Android dependency; and a pointer to `PRD.md` and this plan.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew test`
Expected: PASS. Record the actual counts in the commit message rather than asserting success generically.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/
git commit -m "docs: add README and manual test matrix"
```

---

## Notes for the executor

- **FR-10 is satisfied structurally, not by code.** Time on the block screen is attributed by `UsageStatsManager` to Time Limiter's own package, and the ledger only ever reconciles tracked packages — so block-screen time cannot land on the blocked app's total. Do not "fix" this by having the coordinator subtract anything; if a future change makes the block screen part of another process, revisit it.
- **Do not weaken the block screen.** FR-16 gives it one button. A "just 5 more minutes" affordance added for convenience during development defeats the product.
- **Do not let the `:rules` module gain an Android dependency.** If something there seems to need `Context`, the seam is in the wrong place — pass the data in instead.
- **`UsageStatsManager` needs real usage to test against.** On a fresh emulator every total is zero, which makes the app look broken when it is working. Use the tracked-app harness in `TrackingCoordinatorTest` for logic, and a real device for the matrix in Task 26.
- **Instrumented tests need a device.** If none is attached, say the instrumented tests did not run — do not report them as passing.
