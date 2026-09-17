package ar.com.longcasting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ar.com.longcasting.feature.history.HistoryScreen
import ar.com.longcasting.feature.history.HistoryViewModel
import ar.com.longcasting.feature.measure.MeasureScreen
import ar.com.longcasting.feature.measure.MeasureViewModel
import ar.com.longcasting.feature.rosters.RostersScreen
import ar.com.longcasting.feature.rosters.RostersViewModel
import ar.com.longcasting.feature.settings.SettingsScreen
import ar.com.longcasting.feature.settings.SettingsViewModel
import ar.com.longcasting.ui.LongcastingTheme

private enum class Tab(val label: String) {
    MEDIR("Medir"),
    HISTORIAL("Historial"),
    GENTE("Gente"),
    AJUSTES("Ajustes"),
}

/**
 * Estructura plana con cuatro pestañas en vez de una pila de navegación: en cancha lo que
 * importa es llegar a cualquier pantalla de un toque, y el flujo de un tiro es una máquina de
 * estados dentro de la pestaña Medir, no una secuencia de pantallas por las que retroceder.
 */
@OptIn(ExperimentalComposeApi::class)
@Composable
fun App(
    graph: AppGraph?,
    darkTheme: Boolean = false,
    bannerContent: @Composable () -> Unit = {},
) {
    LongcastingTheme(darkTheme = darkTheme) {
        if (graph == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@LongcastingTheme
        }

        CompositionLocalProvider(LocalAppGraph provides graph) {
            var tab by remember { mutableStateOf(Tab.MEDIR) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Longcasting") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                },
                bottomBar = {
                    Column {
                        // El banner va fuera de cualquier scroll de pantalla y arriba de la
                        // barra de navegacion: anclado, nunca al final del contenido.
                        bannerContent()
                        NavigationBar {
                            Tab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry,
                                    onClick = {
                                        if (tab != entry) graph.ads.maybeShowInterstitialOnTabChange()
                                        tab = entry
                                    },
                                    icon = {},
                                    label = { Text(entry.label) },
                                    alwaysShowLabel = true,
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        Tab.MEDIR -> {
                            val vm = viewModel { MeasureViewModel(graph) }
                            MeasureScreen(vm)
                        }
                        Tab.HISTORIAL -> {
                            val vm = viewModel { HistoryViewModel(graph) }
                            HistoryScreen(vm)
                        }
                        Tab.GENTE -> {
                            val vm = viewModel { RostersViewModel(graph) }
                            RostersScreen(vm)
                        }
                        Tab.AJUSTES -> {
                            val vm = viewModel { SettingsViewModel(graph) }
                            SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
