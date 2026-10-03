package app.fieldwatch.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.radio.usb.CaptureProtocol
import app.fieldwatch.ui.FieldwatchViewModel
import app.fieldwatch.ui.component.FieldwatchActionButton
import app.fieldwatch.ui.component.FieldwatchFilterChip
import app.fieldwatch.ui.component.SectionCard
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsbCaptureCard(vm: FieldwatchViewModel, scanning: Boolean) {
    val state by vm.usbCaptureState.collectAsStateWithLifecycle()
    val label by vm.usbSessionLabel.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var receivers by remember { mutableStateOf(vm.usbReceivers()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var mode by remember { mutableStateOf(CaptureProtocol.Mode.WIFI) }
    var channel by remember { mutableIntStateOf(0) }
    var gps by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(vm.usbCaptureFiles()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    var install by remember { mutableStateOf(false) }
    var installDevice by remember { mutableStateOf<Int?>(null) }
    var manualBoot by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {
        uri ->
        val name = pendingSave
        pendingSave = null
        if (uri != null && name != null) vm.exportUsbCapture(name, uri)
    }
    LaunchedEffect(Unit) {
        while (true) {
            receivers = vm.usbReceivers()
            if (receivers.none { it.id == selected }) selected = receivers.firstOrNull()?.id
            delay(2000)
        }
    }
    LaunchedEffect(state.active, state.file) {
        files = vm.usbCaptureFiles()
        if (selectedFile == null || files.none { it.name == selectedFile }) selectedFile = files.firstOrNull()?.name
    }
    SectionCard("USB research receiver") {
        Text("ESP32-C3 Super Mini · Fieldwatch-NG capture firmware", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(label, vm::setUsbSessionLabel, label = { Text("Session label / location note") },
            singleLine = true, enabled = !state.active,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                keyboard?.hide()
            }))
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        if (state.awaitingUsbPermission) {
            Text("Approve USB access on this phone. Firmware writing has not started.",
                style = MaterialTheme.typography.bodySmall)
            FieldwatchActionButton(vm::cancelUsbPermissionRequest) { Text("Cancel USB request") }
        }
        if (state.installing) {
            if (state.installPercent == 0) {
                LinearProgressIndicator()
                if (!state.awaitingUsbPermission) Text("Preparing receiver · Keep USB connected.")
            } else {
                LinearProgressIndicator(progress = { state.installPercent / 100f })
                Text("${state.installPercent}% · Keep USB connected until verification finishes.")
            }
        }
        Text("${state.packets} packets · ${state.bytes / 1024} KiB · ${state.gaps} sequence gaps · " +
            "${state.deviceDrops} receiver drops · ${state.invalid} invalid records", style = MaterialTheme.typography.bodySmall)
        if (receivers.isEmpty()) Text("Connect the board's native USB port using a data cable / OTG adapter.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            receivers.forEach { device -> FieldwatchFilterChip(selected == device.id,
                { selected = device.id }, { Text(device.label) }, enabled = !state.active) }
        }
        FieldwatchActionButton({ installDevice = selected; install = true },
            enabled = selected != null && !state.active) { Text("Install / update receiver firmware") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(manualBoot, { manualBoot = it }, enabled = !state.active)
            Text("Manual boot mode (BOOT + RESET already pressed)", style = MaterialTheme.typography.bodySmall)
        }
        Text("Works offline. If automatic boot fails: hold BOOT, tap and release RESET, release BOOT, " +
            "select manual boot mode, then retry installation.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptureProtocol.Mode.entries.forEach { m -> FieldwatchFilterChip(mode == m,
                { mode = m }, { Text(if (m == CaptureProtocol.Mode.WIFI) "Wi-Fi management" else "BLE advertisements") },
                enabled = !state.active) }
        }
        if (mode == CaptureProtocol.Mode.WIFI) {
            Text("Channel (2.4 GHz)", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..11).forEach { ch -> FieldwatchFilterChip(channel == ch, { channel = ch },
                    { Text(if (ch == 0) "Hop 1–11" else "$ch") }, enabled = !state.active) }
            }
        }
        Text("Explicit start only. Records raw radio data to a separate file, even when ordinary logging is off. " +
            "Captures contain full addresses and advertised identifiers; Privacy mode does not redact them. " +
            "Phone GPS is optional and identifies where you heard a signal, not its source.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(gps, { gps = it }, enabled = !state.active)
            Text("Include available phone GPS in raw export")
        }
        if (!scanning && !state.active) Text("Start Fieldwatch scanning before starting the USB receiver.")
        Text("Session: ${label.ifBlank { "No label" }}", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldwatchActionButton({
                focusManager.clearFocus()
                keyboard?.hide()
                selected?.let { vm.startUsbCapture(it, mode, channel, gps) }
            },
                enabled = scanning && selected != null && !state.active) { Text("Start USB capture") }
            FieldwatchActionButton(vm::stopUsbCapture, enabled = state.active && !state.installing) { Text("Stop USB capture") }
        }
        Text("Saved USB captures", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEachIndexed { index, file -> FieldwatchFilterChip(selectedFile == file.name,
                { selectedFile = file.name }, { Text("${index + 1} · ${file.length() / 1024} KiB") }, enabled = !state.active) }
        }
        selectedFile?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldwatchActionButton({ selectedFile?.let { vm.exportUsbCapture(it) } },
                enabled = selectedFile != null && !state.active) { Text("Share JSONL") }
            FieldwatchActionButton({ selectedFile?.let { pendingSave = it; save.launch(it) } },
                enabled = selectedFile != null && !state.active) { Text("Save JSONL") }
            FieldwatchActionButton({ delete = true }, enabled = selectedFile != null && !state.active) { Text("Delete capture") }
        }
        Text("AP beacons/probe responses and BLE appear in Live. Other management frames stay in the raw file. " +
            "16 MiB / 30 minutes per capture; export/delete files when storage fills.", style = MaterialTheme.typography.bodySmall)
    }
    if (install) AlertDialog(onDismissRequest = { install = false }, title = { Text("Install C3 receiver firmware?") },
        text = { Text("This replaces firmware and clears settings on the selected ESP32-C3 (4 MB). " +
            "Use your spare receiver, not the Surveillance Hound display board. " +
            "Keep USB connected until verification finishes. Phone captures and settings are preserved.") },
        confirmButton = { TextButton(onClick = {
            installDevice?.let { vm.installUsbFirmware(it, manualBoot) }; install = false
        }) { Text("Install firmware") } },
        dismissButton = { TextButton(onClick = { install = false }) { Text("Cancel") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete this USB capture?") },
        text = { Text("Export it first if you need the original radio observations.") },
        confirmButton = { TextButton(onClick = {
            selectedFile?.let(vm::deleteUsbCapture); files = vm.usbCaptureFiles()
            selectedFile = files.firstOrNull()?.name; delete = false
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}
