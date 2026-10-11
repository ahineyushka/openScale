/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.ui.screen.camera.CameraScaleScanActivity
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun RecognitionSettingsScreen(sharedViewModel: SharedViewModel) {
    val scope = rememberCoroutineScope()
    val formatFlow = androidx.compose.runtime.remember(sharedViewModel) {
        sharedViewModel.observeSetting(CameraScaleScanActivity.FORMAT_KEY, "XXX.X")
    }
    val unitFlow = androidx.compose.runtime.remember(sharedViewModel) {
        sharedViewModel.observeSetting(CameraScaleScanActivity.UNIT_KEY, "KG")
    }
    val format by formatFlow.collectAsState(initial = "XXX.X")
    val unit by unitFlow.collectAsState(initial = "KG")

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.camera_format_title), style = MaterialTheme.typography.titleMedium)
        listOf("XXX", "XXX.X", "XXX.XX", "XXX.XXX").forEach { option ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = format == option, onClick = {
                    scope.launch { sharedViewModel.saveSetting(CameraScaleScanActivity.FORMAT_KEY, option) }
                })
                Text(option)
            }
        }
        Text(stringResource(R.string.camera_unit_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        listOf("KG", "LB", "ST").forEach { option ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = unit == option, onClick = {
                    scope.launch { sharedViewModel.saveSetting(CameraScaleScanActivity.UNIT_KEY, option) }
                })
                Text(option.lowercase())
            }
        }
    }
}
