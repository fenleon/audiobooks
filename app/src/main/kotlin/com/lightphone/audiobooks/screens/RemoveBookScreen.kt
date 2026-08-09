package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.MediaClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class RemoveBookViewModel : LightViewModel<Unit>() {

    lateinit var book: LightServiceMethod.GetBooks.Book
    val removing = MutableStateFlow(false)
    val error = MutableStateFlow(false)

    /**
     * Deletes the book. On Android 11+ the companion may need the system
     * delete-consent dialog: the response then carries the companion's consent
     * activity to launch (the tool is the foreground process, so it starts it)
     * and we leave the screen — the library refreshes when it shows again.
     */
    fun remove(screen: SimpleLightScreen<Unit>) {
        if (removing.value) return
        viewModelScope.launch {
            removing.value = true
            error.value = false
            val result = MediaClient.deleteBook(book.id)
            removing.value = false
            when {
                result == null -> error.value = true
                result.deleted -> screen.goBack()
                result.consentPending -> {
                    result.consentComponent?.let { screen.startServerActivity(it) }
                    screen.goBack()
                }
                else -> error.value = true
            }
        }
    }
}

class RemoveBookScreen(
    sealedActivity: SealedLightActivity,
    private val book: LightServiceMethod.GetBooks.Book,
) : LightScreen<Unit, RemoveBookViewModel>(sealedActivity) {

    override val viewModelClass: Class<RemoveBookViewModel>
        get() = RemoveBookViewModel::class.java

    override fun createViewModel(): RemoveBookViewModel =
        RemoveBookViewModel().apply { this.book = this@RemoveBookScreen.book }

    @Composable
    override fun Content() {
        val removing by viewModel.removing.collectAsState()
        val error by viewModel.error.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back to Library",
                    ),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    LightText(
                        text = "Remove this audiobook file from the device?",
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error) {
                        LightText(
                            text = "Could not remove this book.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        LightBarButton.Text(
                            text = if (removing) "REMOVING…" else "REMOVE",
                            onClick = { viewModel.remove(this@RemoveBookScreen) },
                        ),
                        LightBarButton.Text("CANCEL", onClick = { goBack() }),
                    ),
                )
            }
        }
    }
}
