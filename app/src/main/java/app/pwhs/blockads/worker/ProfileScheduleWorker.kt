package app.pwhs.blockads.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pwhs.blockads.data.ProfileManager
import app.pwhs.blockads.data.ProtectionProfileDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
        private const val TAG = "ProfileScheduleWorker"

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
    }

    override suspend fun doWork(): Result {
        return try {
            val schedules = profileDao.getEnabledSchedules()
            if (schedules.isEmpty()) return Result.success()

            val now = Calendar.getInstance()
            val currentDay = now.get(Calendar.DAY_OF_WEEK)
            // Convert Calendar day (Sun=1..Sat=7) to our format (Mon=1..Sun=7)
            val day = if (currentDay == Calendar.SUNDAY) 7 else currentDay - 1
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            for (schedule in schedules) {
                val days = schedule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (day !in days) continue

                val startMinutes = schedule.startHour * 60 + schedule.startMinute
                val endMinutes = schedule.endHour * 60 + schedule.endMinute

                val isInRange = if (startMinutes <= endMinutes) {
                    currentMinutes in startMinutes until endMinutes
                } else {
                    // Overnight schedule (e.g., 18:00 – 08:00)
                    currentMinutes >= startMinutes || currentMinutes < endMinutes
                }

                if (isInRange) {
                    val activeProfile = profileDao.getActive()
                    if (activeProfile?.id != schedule.profileId) {
                        Log.d(TAG, "Schedule triggered: switching to profile ${schedule.profileId}")
                        profileManager.switchToProfile(schedule.profileId)
                    }
                    return Result.success()
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Schedule check failed", e)
            Result.retry()
        }
    }
}
