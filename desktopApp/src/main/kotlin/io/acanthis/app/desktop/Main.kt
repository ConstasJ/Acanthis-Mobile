package io.acanthis.app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.acanthis.app.app.AcanthisApp
import io.acanthis.app.app.di.appModule
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

fun main() {
    if (GlobalContext.getOrNull() == null) {
        startKoin {
            modules(appModule)
        }
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Acanthis Desktop (Debug)",
            state = rememberWindowState(width = 420.dp, height = 860.dp),
        ) {
            AcanthisApp()
        }
    }
}
