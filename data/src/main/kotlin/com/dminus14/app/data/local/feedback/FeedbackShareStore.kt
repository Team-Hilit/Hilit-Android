package com.dminus14.app.data.local.feedback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 지인 피드백 공유 기록(token, 공유 링크, 선택한 평가 항목)을 sessionId 별로 기기에 저장한다.
 *
 * 세션당 활성 링크는 1개뿐이지만(feedback.md), 사용자는 여러 세션(면접)의 리포트를 오갈 수
 * 있어 sessionId 로 동적 키를 만들어 하나의 Preferences 파일에 여러 세션의 기록을 함께
 * 보관한다. [InterviewProgressStore][com.dminus14.app.data.local.interview.InterviewProgressStore]
 * 와 같은 이유로 클라우드 백업 대상에서 제외되는 `noBackupFilesDir` 아래에 둔다.
 *
 * 공유 링크는 그것을 가진 사람이 면접 영상을 볼 수 있는 값이므로 token 과 같은 등급으로
 * 다룬다. 저장 위치·시점·삭제 시점을 token 과 동일하게 맞추고 평문 로그로 남기지 않는다.
 *
 * 파일명은 token 만 저장했던 때와 같게 유지한다. 바꾸면 기존 설치에 남은 기록을 읽지 못해
 * 활성 링크를 가진 사용자가 종료도 재복사도 못 하는 상태가 된다.
 */
@Singleton
class FeedbackShareStore
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

        /** token 이 없으면 기록 자체가 없는 것으로 본다. */
        suspend fun getShare(sessionId: Long): StoredShare? {
            val preferences = store.data.first()
            val token = preferences[tokenKey(sessionId)] ?: return null
            return StoredShare(
                token = token,
                link = preferences[linkKey(sessionId)],
                axes = preferences[axesKey(sessionId)].orEmpty().toAxisNames(),
            )
        }

        suspend fun setShare(
            sessionId: Long,
            share: StoredShare,
        ) {
            store.edit { preferences ->
                preferences[tokenKey(sessionId)] = share.token
                share.link?.let { preferences[linkKey(sessionId)] = it }
                preferences[axesKey(sessionId)] = share.axes.joinToString(AXES_SEPARATOR)
            }
        }

        suspend fun clearShare(sessionId: Long) {
            store.edit { preferences ->
                preferences.remove(tokenKey(sessionId))
                preferences.remove(linkKey(sessionId))
                preferences.remove(axesKey(sessionId))
            }
        }

        /** 저장 형태. 평가 항목은 enum 이름 문자열로만 다루고 domain 모델을 알지 않는다. */
        data class StoredShare(
            val token: String,
            val link: String?,
            val axes: List<String>,
        )

        private fun tokenKey(sessionId: Long) =
            stringPreferencesKey("feedback_share_token_$sessionId")

        private fun linkKey(sessionId: Long) =
            stringPreferencesKey("feedback_share_link_$sessionId")

        private fun axesKey(sessionId: Long) =
            stringPreferencesKey("feedback_share_axes_$sessionId")

        private fun String.toAxisNames(): List<String> =
            split(AXES_SEPARATOR).filter { it.isNotBlank() }

        private companion object {
            const val FILE_NAME = "feedback_share_token.preferences_pb"
            const val AXES_SEPARATOR = ","
        }
    }
