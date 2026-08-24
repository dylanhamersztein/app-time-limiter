package com.dylanhamersztein.timelimiter.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.dylanhamersztein.timelimiter.rules.SessionState
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
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
        assertThat(dao.limitFor("com.example")).isNotNull()

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

    @Test
    fun aCappedSessionSurvivesBeingPersisted() = runTest {
        val dao = db.sessionDao()
        val session = SessionState(
            packageName = "com.example",
            startedAt = Instant.ofEpochMilli(1_000_000),
            accumulated = 10.minutes,
            enteredForegroundAt = null,
            leftForegroundAt = Instant.ofEpochMilli(1_600_000),
            endedByCap = true,
        )

        dao.upsertSession(session.toEntity())

        assertThat(dao.sessionFor("com.example")?.toDomain()).isEqualTo(session)
    }

    @Test
    fun onlyTheMostRecentSessionPerPackageIsKept() = runTest {
        val dao = db.sessionDao()
        dao.upsertSession(SessionEntity("com.example", 1_000, 0, 1_000, null, false))
        dao.upsertSession(SessionEntity("com.example", 9_000, 60, 9_000, null, false))

        assertThat(dao.sessionFor("com.example")?.startedAtEpochMillis).isEqualTo(9_000)
    }

    @Test
    fun trackedAppsAreListedInCaseInsensitiveLabelOrder() = runTest {
        val dao = db.limitDao()
        dao.upsertTrackedApp(TrackedAppEntity("com.zebra", "zebra", 0L))
        dao.upsertTrackedApp(TrackedAppEntity("com.apple", "Apple", 0L))
        dao.upsertTrackedApp(TrackedAppEntity("com.banana", "banana", 0L))

        val labels = dao.observeTrackedApps().first().map { it.label }

        assertThat(labels).containsExactly("Apple", "banana", "zebra").inOrder()
    }

    @Test
    fun observedUsageIsScopedToASingleDay() = runTest {
        val dao = db.usageDao()
        dao.upsertUsage(DailyUsageEntity("com.example", "2026-08-13", 600))
        dao.upsertUsage(DailyUsageEntity("com.other", "2026-08-13", 300))
        dao.upsertUsage(DailyUsageEntity("com.example", "2026-08-14", 120))

        val today = dao.observeUsageOn("2026-08-13").first()

        assertThat(today.map { it.packageName }).containsExactly("com.example", "com.other")
    }

    @Test
    fun limitsAndPendingChangesAreObservable() = runTest {
        val dao = db.limitDao()
        dao.upsertLimit(LimitEntity("com.example", 30, null, 5, 15, 5))
        dao.upsertPendingChange(
            PendingChangeEntity("com.example", "REMOVE", null, null, null, null, null, "2026-08-14")
        )

        assertThat(dao.observeLimits().first().map { it.packageName }).containsExactly("com.example")
        assertThat(dao.observePendingChanges().first().map { it.kind }).containsExactly("REMOVE")
    }
}
