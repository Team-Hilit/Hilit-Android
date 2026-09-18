package com.dminus14.app.feature.interview

/**
 * `interview:impl` 내 둘 이상의 Screen이 공유하는 상수.
 * 특정 Screen 하나에만 쓰이는 값은 해당 Contract나 사용처 파일에 둔다.
 */
object InterviewConstants {
    const val MAX_INTERVIEW_SECONDS: Int = 720
    const val CAN_FINISH_INTERVIEW_SECONDS: Int = 480
    const val WRAP_UP_SECONDS: Int = 525
    const val COUNTDOWN_START_SECONDS: Int = 710
    const val SESSION_POLL_INTERVAL_MILLIS: Long = 5_000L
    const val PREPARATION_STAGE_MIN_DURATION_MILLIS: Long = 3_000L
    const val TIMER_TICK_INTERVAL_MILLIS: Long = 1_000L
    const val REQUIRED_STORAGE_BYTES: Long = 450L * 1024L * 1024L
    const val SPEECH_START_AMPLITUDE: Double = 0.08
    const val SILENCE_AMPLITUDE: Double = 0.03
    const val SPEECH_START_SAMPLE_COUNT: Int = 2
    const val ANSWER_SILENCE_MILLIS: Long = 10_000L
    const val FINALIZATION_WATCHDOG_MILLIS: Long = 15_000L
}
