package com.dminus14.app.feature.mypage

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.PortfolioNotFoundException
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileReader
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/** PDF 임시 파일과 업로드·삭제·폴링 작업의 수명을 관리한다. */
internal class MyPagePortfolioHandler(
    private val context: MviContext<MyPageState, MyPageMutation, MyPageEffect>,
    private val getPortfolioOverviewUseCase: GetPortfolioOverviewUseCase,
    private val uploadPortfolioUseCase: UploadPortfolioUseCase,
    private val getPortfolioStatusUseCase: GetPortfolioStatusUseCase,
    private val deletePortfolioUseCase: DeletePortfolioUseCase,
    private val portfolioFileReader: PortfolioFileReader,
) {
    private var fileReadJob: Job? = null
    private var fileReadVersion = 0
    private var uploadJob: Job? = null
    private var pollingJob: Job? = null
    private var pendingTempFile: File? = null

    private companion object {
        const val POLLING_INTERVAL_MILLIS = 3_000L
        const val POLLING_INTERVAL_SECONDS = 3
        const val POLLING_TIMEOUT_SECONDS = 120
    }

    suspend fun refreshOverviewSilently() {
        getPortfolioOverviewUseCase().rethrowMyPageCancellation().onSuccess { overview ->
            context.dispatch(MyPageMutation.OverviewLoaded(overview))
        }
    }

    fun selectPortfolioFile(uri: String) {
        if (context.state.value.portfolioState is MyPagePortfolioState.Uploading) return
        fileReadJob?.cancel()
        val version = ++fileReadVersion
        val previousState = context.state.value.portfolioState
        fileReadJob =
            context.launch {
                val result = portfolioFileReader.read(uri)
                if (version != fileReadVersion || !isActive) {
                    if (result is PortfolioFileResult.Valid) result.file.delete()
                    return@launch
                }
                when (result) {
                    is PortfolioFileResult.Valid -> {
                        startUpload(result.file, result.fileName, result.pageCount, previousState)
                    }

                    is PortfolioFileResult.Invalid -> {
                        val rejected = MyPageMutation.FileRejected(result.fileName, result.reason)
                        context.dispatch(rejected)
                    }

                    is PortfolioFileResult.Unreadable -> {
                        context.dispatch(MyPageMutation.UploadFailed(result.fileName))
                    }
                }
            }
    }

    private fun startUpload(
        file: File,
        fileName: String,
        pageCount: Int,
        previousState: MyPagePortfolioState,
    ) {
        pendingTempFile = file
        val previousPortfolio = (previousState as? MyPagePortfolioState.Uploaded)?.portfolio
        val failedPortfolioId = (previousState as? MyPagePortfolioState.Failed)?.portfolioId
        context.dispatch(MyPageMutation.UploadStarted(fileName, previousPortfolio))

        uploadJob =
            context.launch {
                try {
                    val cleanupResult =
                        deletePreviousFailedPortfolioIfNeeded(failedPortfolioId)
                    val cleanupError = cleanupResult.exceptionOrNull()
                    if (cleanupError != null) {
                        val failed = MyPageMutation.UploadFailed(fileName, failedPortfolioId)
                        context.dispatch(failed)
                        context.handleMyPageError(cleanupError)
                        return@launch
                    }

                    uploadPortfolioUseCase(
                        file = file,
                        fileName = fileName,
                        pageCount = pageCount,
                    ).rethrowMyPageCancellation().fold(
                        onSuccess = { result ->
                            context.dispatch(
                                MyPageMutation.UploadAccepted(result.portfolioId, fileName),
                            )
                            startPolling(result.portfolioId, fileName)
                        },
                        onFailure = { error ->
                            context.dispatch(MyPageMutation.UploadFailed(fileName))
                            context.handleMyPageError(error)
                        },
                    )
                } finally {
                    file.delete()
                    if (pendingTempFile == file) pendingTempFile = null
                }
            }
    }

    /** 실패 레코드를 먼저 정리한다. FAILED 삭제는 기존 계약상 월간 교체·삭제 기회를 쓰지 않는다. */
    private suspend fun deletePreviousFailedPortfolioIfNeeded(portfolioId: String?): Result<Unit> {
        if (portfolioId == null) return Result.success(Unit)
        val result = deletePortfolioUseCase(portfolioId).rethrowMyPageCancellation()
        val error = result.exceptionOrNull()
        return if (error == null || error is PortfolioNotFoundException) {
            Result.success(Unit)
        } else {
            Result.failure(error)
        }
    }

    fun startPolling(
        portfolioId: String,
        fileName: String,
    ) {
        pollingJob?.cancel()
        pollingJob =
            context.launch {
                val maxIterations = POLLING_TIMEOUT_SECONDS / POLLING_INTERVAL_SECONDS
                for (iteration in 1..maxIterations) {
                    val elapsedSeconds = iteration * POLLING_INTERVAL_SECONDS
                    context.dispatch(
                        MyPageMutation.PollingAdvanced(fileName, portfolioId, elapsedSeconds),
                    )
                    delay(POLLING_INTERVAL_MILLIS)

                    val status =
                        getPortfolioStatusUseCase(portfolioId)
                            .rethrowMyPageCancellation()
                            .getOrNull()
                            ?.status
                    when (status) {
                        PortfolioStatus.READY -> {
                            context.dispatch(MyPageMutation.UploadCompleted(fileName))
                            return@launch
                        }

                        PortfolioStatus.FAILED_FILE, PortfolioStatus.FAILED_SYSTEM -> {
                            val failed = MyPageMutation.UploadFailed(fileName, portfolioId)
                            context.dispatch(failed)
                            return@launch
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                context.dispatch(MyPageMutation.UploadFailed(fileName, portfolioId))
            }
    }

    /**
     * 접수 전에는 요청만 취소하고, 접수 후에는 서버 상태를 확인한 뒤 삭제한다.
     * 이미 READY면 월간 삭제 기회가 소진되지 않도록 완료를 안내한다.
     * 조회와 삭제 사이 서버 경합은 기존 API 계약의 제한으로 남는다.
     */
    fun cancelUpload() {
        val uploading = context.state.value.portfolioState as? MyPagePortfolioState.Uploading
        if (context.state.value.isSubmitting || uploading == null) return
        val portfolioId = uploading.portfolioId

        if (portfolioId == null) {
            uploadJob?.cancel()
            uploadJob = null
            pendingTempFile?.delete()
            pendingTempFile = null
            revertToPreviousOrEmpty(uploading.previousPortfolio)
            return
        }

        context.dispatch(MyPageMutation.SubmissionStarted)
        context.launch {
            val statusResult =
                getPortfolioStatusUseCase(portfolioId)
                    .rethrowMyPageCancellation()
            val status = statusResult.getOrNull()
            if (status == null) {
                statusResult.exceptionOrNull()?.let(context::handleMyPageError)
                context.dispatch(MyPageMutation.SubmissionFinished)
                return@launch
            }

            when (status.status) {
                PortfolioStatus.READY -> {
                    pollingJob?.cancel()
                    pollingJob = null
                    context.dispatch(MyPageMutation.CancelFoundCompleted(uploading.fileName))
                }

                PortfolioStatus.FAILED_FILE, PortfolioStatus.FAILED_SYSTEM -> {
                    pollingJob?.cancel()
                    pollingJob = null
                    context.dispatch(
                        MyPageMutation.CancelFoundFailed(uploading.fileName, portfolioId),
                    )
                }

                else -> {
                    val deleteResult =
                        deletePortfolioUseCase(portfolioId)
                            .rethrowMyPageCancellation()
                    val deleteError = deleteResult.exceptionOrNull()
                    pollingJob?.cancel()
                    pollingJob = null
                    context.dispatch(MyPageMutation.SubmissionFinished)
                    if (deleteError == null) {
                        revertToPreviousOrEmpty(uploading.previousPortfolio)
                    } else {
                        context.handleMyPageError(deleteError)
                    }
                }
            }
        }
    }

    private fun revertToPreviousOrEmpty(previousPortfolio: MyPagePortfolioUiModel?) {
        context.dispatch(MyPageMutation.UploadReverted(previousPortfolio))
        context.launch { refreshOverviewSilently() }
    }

    fun requestPortfolioDelete() {
        when {
            context.state.value.isInterviewInProgress -> {
                context.dispatch(
                    MyPageMutation.ModalOpened(MyPageModalType.PortfolioDeleteUnavailable),
                )
            }

            !context.state.value.isPortfolioDeleteAvailable -> {
                context.dispatch(
                    MyPageMutation.ModalOpened(MyPageModalType.PortfolioDeleteUnavailable),
                )
            }

            else -> {
                context.dispatch(MyPageMutation.ModalOpened(MyPageModalType.PortfolioDelete))
            }
        }
    }

    fun deletePortfolio() {
        if (context.state.value.isSubmitting) return
        val portfolioId = context.state.value.portfolioId
        if (portfolioId == null) {
            context.dispatch(MyPageMutation.ModalClosed)
            return
        }
        context.dispatch(MyPageMutation.SubmissionStarted)
        context.launch {
            val result = deletePortfolioUseCase(portfolioId).rethrowMyPageCancellation()
            val error = result.exceptionOrNull()
            context.dispatch(MyPageMutation.ModalSubmissionFinished)
            if (error == null) {
                context.dispatch(MyPageMutation.PortfolioDeleted)
                refreshOverviewSilently()
            } else {
                context.handleMyPageError(error)
            }
        }
    }

    fun requestPortfolioReupload() {
        if (!context.state.value.isPortfolioReplaceAvailable) {
            context.dispatch(
                MyPageMutation.ModalOpened(MyPageModalType.PortfolioReuploadUnavailable),
            )
        } else {
            context.dispatch(MyPageMutation.ModalOpened(MyPageModalType.PortfolioReupload))
        }
    }
}
