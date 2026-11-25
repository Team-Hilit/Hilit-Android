package com.dminus14.app.data.local.interview

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.dminus14.app.domain.model.InterviewProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InterviewProgressStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = {
                    context.noBackupFilesDir
                        .resolve("datastore")
                        .apply { mkdirs() }
                        .resolve(FILE_NAME)
                },
            )

        suspend fun read(): InterviewProgress? = store.data.first().toProgress()

        suspend fun write(progress: InterviewProgress) {
            store.edit { preferences -> preferences.putProgress(progress) }
        }

        /** 단일 edit 안에서 읽고 갱신해 동시 쓰기가 서로 덮어쓰지 않도록 한다. */
        suspend fun update(
            transform: (InterviewProgress) -> InterviewProgress,
        ): InterviewProgress? {
            var updated: InterviewProgress? = null
            store.edit { preferences ->
                val current = preferences.toProgress() ?: return@edit
                val next = transform(current)
                updated = next
                preferences.putProgress(next)
            }
            return updated
        }

        suspend fun clear() {
            store.edit { it.clear() }
        }

        suspend fun clearIfMatching(sessionId: Long): Boolean {
            var cleared = false
            store.edit { preferences ->
                if (preferences[SESSION_ID] == sessionId) {
                    preferences.clear()
                    cleared = true
                }
            }
            return cleared
        }

        private fun Preferences.toProgress(): InterviewProgress? {
            val sessionId = this[SESSION_ID]
            val retentionDeadline = this[RETENTION_DEADLINE]
            if (sessionId == null || retentionDeadline == null) return null
            return InterviewProgress(
                sessionId = sessionId,
                isFrozen = this[FROZEN] ?: false,
                retentionDeadlineEpochMillis = retentionDeadline,
                retentionRemainingAtCheckpointMillis = this[RETENTION_REMAINING] ?: 0L,
                retentionCheckpointElapsedRealtimeMillis = this[RETENTION_REALTIME],
                timerStartedAtEpochMillis = this[TIMER_STARTED_AT],
                elapsedAtCheckpointMillis = this[ELAPSED_AT_CHECKPOINT],
                checkpointedAtEpochMillis = this[CHECKPOINTED_AT],
                elapsedCheckpointElapsedRealtimeMillis = this[ELAPSED_CHECKPOINT_REALTIME],
            )
        }

        private fun MutablePreferences.putProgress(progress: InterviewProgress) {
            if (progress.isFrozen) this[FROZEN] = true else remove(FROZEN)
            this[SESSION_ID] = progress.sessionId
            this[RETENTION_DEADLINE] = progress.retentionDeadlineEpochMillis
            this[RETENTION_REMAINING] = progress.retentionRemainingAtCheckpointMillis
            progress.retentionCheckpointElapsedRealtimeMillis.setOrRemove(this, RETENTION_REALTIME)
            progress.timerStartedAtEpochMillis.setOrRemove(this, TIMER_STARTED_AT)
            progress.elapsedAtCheckpointMillis.setOrRemove(this, ELAPSED_AT_CHECKPOINT)
            progress.checkpointedAtEpochMillis.setOrRemove(this, CHECKPOINTED_AT)
            progress.elapsedCheckpointElapsedRealtimeMillis.setOrRemove(
                this,
                ELAPSED_CHECKPOINT_REALTIME,
            )
        }

        private fun Long?.setOrRemove(
            preferences: MutablePreferences,
            key: Preferences.Key<Long>,
        ) {
            if (this == null) preferences.remove(key) else preferences[key] = this
        }

        private companion object {
            const val FILE_NAME = "interview_progress.preferences_pb"
            val FROZEN = booleanPreferencesKey("is_frozen")
            val SESSION_ID = longPreferencesKey("session_id")
            val RETENTION_DEADLINE = longPreferencesKey("retention_deadline_epoch_millis")
            val RETENTION_REMAINING = longPreferencesKey("retention_remaining_millis")
            val RETENTION_REALTIME = longPreferencesKey("retention_checkpoint_realtime_millis")
            val TIMER_STARTED_AT = longPreferencesKey("timer_started_at_epoch_millis")
            val ELAPSED_AT_CHECKPOINT = longPreferencesKey("elapsed_at_checkpoint_millis")
            val CHECKPOINTED_AT = longPreferencesKey("checkpointed_at_epoch_millis")
            val ELAPSED_CHECKPOINT_REALTIME =
                longPreferencesKey("elapsed_checkpoint_realtime_millis")
        }
    }
