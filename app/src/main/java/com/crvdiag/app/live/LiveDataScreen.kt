package com.crvdiag.app.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crvdiag.app.ui.components.MutedText
import com.crvdiag.app.ui.components.ScreenHeader
import com.crvdiag.app.ui.components.SectionCard
import com.crvdiag.app.ui.icons.AppIcons

/** Placeholder until Session 4 (Mode 01 live data + gauges). */
@Composable
fun LiveDataScreen() {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("Live data", "Real-time readings from the engine.")
        SectionCard("Coming in the next update", Modifier.fillMaxWidth(), icon = AppIcons.Gauge) {
            Text("Your engine computer reports 11 live values this screen will show:")
            MutedText("RPM · speed · coolant and intake temperature · engine load · throttle · manifold pressure · air flow (MAF) · timing · short and long fuel trims · O2 sensors")
            MutedText("Designed for checks while parked (for example idle fuel trims and charging voltage), not for watching while driving.")
        }
    }
}
