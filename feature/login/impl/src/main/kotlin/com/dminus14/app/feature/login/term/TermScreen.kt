package com.dminus14.app.feature.login.term

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dminus14.app.domain.model.ConsentItem
import com.dminus14.app.domain.model.ConsentItemCode
import com.dminus14.app.feature.home.api.Home
import com.dminus14.app.feature.login.api.Onboarding
import com.dminus14.app.feature.login.api.PermissionConsent
import com.dminus14.app.feature.login.component.TermBottomSheet
import com.dminus14.designsystem.component.button.HilitButtonType
import com.dminus14.designsystem.component.button.HilitFixedBottomButton
import com.dminus14.designsystem.component.icon.HilitIcon
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.loading.HilitLoadingIndicator
import com.dminus14.designsystem.component.term.TermBox
import com.dminus14.designsystem.component.term.TermBoxType
import com.dminus14.designsystem.component.topbar.HilitTopBar
import com.dminus14.designsystem.theme.HilitTheme
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermScreen(
    onNavigate: (Any) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TermViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = state.visibleTermDetail == null) {
        viewModel.onIntent(TermIntent.ClickClose)
    }

    LaunchedEffect(Unit) {
        viewModel.onIntent(TermIntent.Load)
    }

    LaunchedEffect(Unit) {
        viewModel.effect.first { effect ->
            when (effect) {
                TermEffect.Closed -> {
                    onClose()
                }

                TermEffect.DeniedPerm -> {
                    onNavigate(PermissionConsent)
                }

                TermEffect.ExistProfile -> {
                    onNavigate(Home)
                }

                TermEffect.NonExistProfile -> {
                    onNavigate(Onboarding)
                }

                is TermEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
            // 닫은 화면의 늦은 응답이 추가 이동을 유발하지 않도록 수집을 끝낸다.
            effect == TermEffect.Closed
        }
    }

    TermContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermContent(
    state: TermState,
    onIntent: (TermIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(HilitTheme.colors.hilitWhite),
        ) {
            TermCloseTopBar(onCloseClick = { onIntent(TermIntent.ClickClose) })

            TermAgreementBody(
                state = state,
                onIntent = onIntent,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            )

            HilitFixedBottomButton(
                text = "동의하고 시작하기",
                enabled = state.canSubmit,
                type = HilitButtonType.Light,
                onClick = { onIntent(TermIntent.ClickAgree) },
            )
        }

        state.visibleTermDetail?.let { term ->
            TermBottomSheet(
                title = term.title,
                body = term.content,
                onDismissRequest = { onIntent(TermIntent.DismissTermDetail) },
            )
        }

        if (state.isLoading) {
            HilitLoadingIndicator(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, // 리플 이펙트 제거
                        ) { /* 아무것도 안 함, 클릭만 소비 */ }
                        .background(HilitTheme.colors.hilitWhite.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
private fun TermCloseTopBar(onCloseClick: () -> Unit) {
    HilitTopBar(
        leading = {
            val interactionSource = remember { MutableInteractionSource() }
            HilitIcon(
                asset = HilitIconAsset.Cancel,
                contentDescription = "닫기",
                tint = HilitTheme.colors.hilitBlack800,
                modifier =
                    Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = onCloseClick,
                        ),
            )
        },
    )
}

@Composable
private fun TermAgreementBody(
    state: TermState,
    onIntent: (TermIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TermScreenHorizontalPadding),
    ) {
        Spacer(modifier = Modifier.height(TermScreenTitleTopSpacing))
        Text(
            text = "Hilit 서비스 약관에\n동의해주세요",
            style = HilitTheme.typography.head4,
            color = HilitTheme.colors.gray900,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(TermScreenTitleBottomSpacing))
        TermAgreementList(
            state = state,
            onIntent = onIntent,
        )
    }
}

@Composable
private fun TermAgreementList(
    state: TermState,
    onIntent: (TermIntent) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TermScreenSectionSpacing),
    ) {
        TermBox(
            type = TermBoxType.AllAgree,
            text = "모두 동의합니다.",
            checked = state.isAllChecked,
            onClick = { onIntent(TermIntent.ClickAllAgree) },
        )

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = TermScreenDividerThickness,
            color = HilitTheme.colors.gray800,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(TermScreenItemSpacing),
        ) {
            state.terms.forEachIndexed { index, term ->
                TermBox(
                    type =
                        if (term.item.hasDocument) {
                            TermBoxType.Term
                        } else {
                            TermBoxType.Text
                        },
                    text = term.item.label,
                    checked = term.isChecked,
                    onClick = { onIntent(TermIntent.ClickTerm(index)) },
                    onViewClick = {
                        if (term.item.hasDocument) {
                            onIntent(TermIntent.ClickViewTerm(index))
                        }
                    },
                )
            }
        }
    }
}

private val TermScreenHorizontalPadding = 20.dp
private val TermScreenTitleTopSpacing = 16.dp
private val TermScreenTitleBottomSpacing = 54.dp
private val TermScreenSectionSpacing = 34.dp
private val TermScreenItemSpacing = 20.dp
private val TermScreenDividerThickness = 1.dp

@Preview(showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun TermContentUncheckedPreview() {
    HilitTheme {
        TermContent(
            state = TermState(terms = PreviewTerms),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun TermContentCheckedPreview() {
    HilitTheme {
        TermContent(
            state =
                TermState(
                    terms = PreviewTerms.map { it.copy(isChecked = true) },
                ),
            onIntent = {},
        )
    }
}

private val PreviewTerms =
    List(3) {
        TermConsentItem(
            item =
                ConsentItem(
                    code = ConsentItemCode.TERMS_OF_SERVICE,
                    rawCode = "",
                    label = "(필수) 만 14세 이상입니다.",
                    version = 0,
                    isRequired = true,
                    hasDocument = true,
                ),
        )
    }
