package com.dminus14.app.feature.onboarding

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.InvalidJdUrlException
import com.dminus14.app.domain.exception.JdValidationLimitExceededException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.usecase.ValidateJdUrlUseCase
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.HTTPS_SCHEME
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.JD_TEXT_MIN_LENGTH
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_LINK_INVALID
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_LINK_RATE_LIMIT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.JobDescription as JdIntent
import com.dminus14.app.feature.onboarding.OnBoardingMutation.JobDescription as JdMutation

/** 화면의 공고 입력과 검증 작업을 소유한다. */
internal class OnBoardingJobDescriptionHandler(
    private val context:
        MviContext<OnBoardingInterviewState, OnBoardingMutation, OnBoardingInterviewEffect>,
    private val validateJdUrl: ValidateJdUrlUseCase,
    private val advanceStep: () -> Unit,
) {
    fun cancelValidation() {
        validationGeneration += 1
        jdValidationJob?.cancel()
    }

    fun onIntent(intent: JdIntent) {
        when (intent) {
            is JdIntent.JobDescriptionLinkChange -> {
                onJobDescriptionLinkChange(intent.value)
            }

            is JdIntent.JobDescriptionTextChange -> {
                context.dispatch(JdMutation.JdTextChanged(intent.value))
            }

            is JdIntent.JobDescriptionTabChange -> {
                val nextTab =
                    JobDescriptionTab.entries.getOrNull(intent.index)
                        ?: context.state.value.jobDescriptionTab
                val cancelled =
                    nextTab != context.state.value.jobDescriptionTab &&
                        context.state.value.jdLinkStatus == JdLinkStatus.Validating
                if (cancelled) cancelValidation()
                context.dispatch(JdMutation.JdTabChanged(nextTab, cancelled))
            }
        }
    }

    var jdUrl: String? = null
        private set
    var jdText: String? = null
        private set
    private var jdValidationJob: Job? = null
    private var validationGeneration = 0L

    fun onJobDescriptionLinkChange(value: String) {
        cancelValidation()
        jdUrl = null

        when {
            value.isEmpty() -> {
                context.dispatch(JdMutation.JdLinkEdited(value))
            }

            !value.startsWith(HTTPS_SCHEME) -> {
                context.dispatch(JdMutation.JdLinkFormatRejected(value))
            }

            HTTPS_SCHEME.startsWith(value) -> {
                // 아직 "https://" 스킴을 입력 중이라 포맷 오류로 보지 않는다.
                context.dispatch(JdMutation.JdLinkEdited(value))
            }

            else -> {
                // 새 입력에는 이전 검증 결과를 적용하지 않는다.
                context.dispatch(JdMutation.JdLinkEdited(value))
            }
        }
    }

    private fun validateAndSubmitJdLink(link: String) {
        val generation = ++validationGeneration
        context.dispatch(JdMutation.JdValidationStarted)
        jdValidationJob =
            context.launch {
                validateJdUrl(link)
                    .onSuccess { result ->
                        if (generation != validationGeneration ||
                            !isCurrentJdLinkValidation(link)
                        ) {
                            return@onSuccess
                        }
                        if (result.valid) {
                            jdUrl = link
                            jdText = null
                            context.dispatch(
                                JdMutation.JdValidationSucceeded,
                            )
                            advanceStep()
                        } else {
                            context.dispatch(
                                JdMutation.JdValidationRejected(
                                    result.message ?: MESSAGE_LINK_INVALID,
                                ),
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (generation != validationGeneration ||
                            !isCurrentJdLinkValidation(link)
                        ) {
                            return@onFailure
                        }
                        handleJdLinkValidationFailure(error)
                    }
            }
    }

    private suspend fun handleJdLinkValidationFailure(error: Throwable) {
        when (error) {
            is JdValidationLimitExceededException,
            is InvalidJdUrlException,
            -> {
                // 429는 고정 문구로 강제 노출한다 (스펙 S1 확정 카피).
                val subText =
                    if (error is JdValidationLimitExceededException) {
                        MESSAGE_LINK_RATE_LIMIT
                    } else {
                        error.message
                    }
                context.dispatch(JdMutation.JdValidationRejected(subText))
            }

            else -> {
                context.dispatch(JdMutation.JdValidationInterrupted)
                val event =
                    when (error) {
                        is NetworkUnavailableException -> GlobalAppEvent.ShowNetworkErrorAndExit
                        is ServerException -> GlobalAppEvent.ShowServerErrorAndExit
                        else -> GlobalAppEvent.ShowUnknownError
                    }
                GlobalErrorHandler.emit(event)
            }
        }
    }

    private fun isCurrentJdLinkValidation(link: String): Boolean =
        context.state.value.let { current ->
            current.step == OnBoardingInterviewStep.JobDescription &&
                current.jobDescriptionTab == JobDescriptionTab.Link &&
                current.jobDescriptionLink == link &&
                current.jdLinkStatus == JdLinkStatus.Validating
        }

    fun submitJobDescription() {
        val current = context.state.value
        if (current.jobDescriptionTab == JobDescriptionTab.Text) {
            // 카운터(raw length)와 동일한 기준으로 검증·전송한다.
            val content = current.jobDescriptionText
            if (content.length < JD_TEXT_MIN_LENGTH) {
                context.dispatch(JdMutation.JdTextTooShort)
                return
            }
            jdText = content
            jdUrl = null
            advanceStep()
            return
        }

        // 링크 탭: 빈 입력은 건너뛰고, 입력한 링크는 계속하기를 누를 때 검증한다.
        when {
            current.jdLinkStatus == JdLinkStatus.Validating -> {
                Unit
            }

            current.jdLinkStatus == JdLinkStatus.Valid -> {
                advanceStep()
            }

            current.jobDescriptionLink.isEmpty() -> {
                jdUrl = null
                jdText = null
                advanceStep()
            }

            !current.jobDescriptionLink.startsWith(HTTPS_SCHEME) -> {
                context.dispatch(JdMutation.JdFormatRejected)
            }

            else -> {
                validateAndSubmitJdLink(current.jobDescriptionLink)
            }
        }
    }
}
