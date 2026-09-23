package app.pwhs.blockads.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pwhs.blockads.data.entities.ProfileManager
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.entities.ProfileSchedule
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ProfileScheduleWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val profileDao: ProtectionProfileDao by inject()
    private val profileManager: ProfileManager by inject()

    companion object {
        const val WORK_NAME = "profile_schedule_work"

        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<ProfileScheduleWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** First schedule covering [day] (Mon=1..Sun=7) at [minuteOfDay], in list order. */
        internal fun activeScheduleAt(
            schedules: List<ProfileSchedule>,
            day: Int,
            minuteOfDay: Int
        ): ProfileSchedule? = schedules.firstOrNull { schedule ->
            val days = schedule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (day !in days) return@firstOrNull false

            val startMinutes = schedule.startHour * 60 + schedule.startMinute
            val endMinutes = schedule.endHour * 60 + schedule.endMinute

            if (startMinutes <= endMinutes) {
                minuteOfDay in startMinutes until endMinutes
            } else {
                // Overnight schedule (e.g., 18:00 – 08:00)
                minuteOfDay !in endMinutes..<startMinutes
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            // Schedules are ordered by startHour, startMinute, id (deterministic precedence).
            // The first matching in-range schedule wins when multiple overlap.
            val schedules = profileDao.getEnabledSchedules()
            if (schedules.isEmpty()) return Result.success()

            val now = Calendar.getInstance()
            val currentDay = now.get(Calendar.DAY_OF_WEEK)
            // Convert Calendar day (Sun=1..Sat=7) to our format (Mon=1..Sun=7)
            val day = if (currentDay == Calendar.SUNDAY) 7 else currentDay - 1
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            val schedule = activeScheduleAt(schedules, day, currentMinutes)
            if (schedule != null) {
                val activeProfile = profileDao.getActive()
                if (activeProfile?.id != schedule.profileId) {
                    Timber
                        .d("Schedule triggered: switching to profile ${schedule.profileId}")
                    profileManager.switchToProfile(schedule.profileId)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Schedule check failed")
            Result.retry()
        }
    }
}
