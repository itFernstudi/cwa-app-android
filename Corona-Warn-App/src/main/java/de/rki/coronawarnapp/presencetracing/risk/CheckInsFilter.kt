package de.rki.coronawarnapp.presencetracing.risk

import de.rki.coronawarnapp.appconfig.AppConfigProvider
import de.rki.coronawarnapp.appconfig.ConfigData
import de.rki.coronawarnapp.presencetracing.checkins.CheckIn
import de.rki.coronawarnapp.presencetracing.risk.calculation.CheckInWarningOverlap
import de.rki.coronawarnapp.util.TimeAndDateExtensions.toDateTimeAtStartOfDay
import de.rki.coronawarnapp.util.TimeAndDateExtensions.toLocalDateUtc
import de.rki.coronawarnapp.util.TimeStamper
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject

class CheckInsFilter @Inject constructor(
    private val appConfigProvider: AppConfigProvider,
    private val timeStamper: TimeStamper,
) {

    fun filterCheckInWarningsByAge(
        list: List<CheckInWarningOverlap>,
        deadline: Instant
    ): List<CheckInWarningOverlap> {
        return list.filter { !it.endTime.isBefore(deadline) }
    }

    suspend fun filterCheckIns(
        list: List<CheckIn>,
    ): List<CheckIn> = list.filterByAge(
        getMaxAgeInDays(),
        timeStamper.nowUTC
    )

    private suspend fun getMaxAgeInDays() = appConfigProvider.currentConfig.first().maxCheckInAgeInDays.toLong()

    suspend fun calculateDeadline(now: Instant): Instant =
        now.minusDaysAtStartOfDayUtc(getMaxAgeInDays()).toInstant()
}

fun List<CheckIn>.filterByAge(
    maxAgeInDays: Long,
    now: Instant,
): List<CheckIn> {
    val deadline = now.minusDaysAtStartOfDayUtc(maxAgeInDays).toEpochSecond() * 1000
    return filter { it.checkInEnd.toEpochMilli() >= deadline }
}

internal fun Instant.minusDaysAtStartOfDayUtc(days: Long) = toLocalDateUtc()
    .minusDays(days)
    .toDateTimeAtStartOfDay(ZoneOffset.UTC)

internal val ConfigData.maxCheckInAgeInDays
    get() = presenceTracing.riskCalculationParameters.maxCheckInAgeInDays
