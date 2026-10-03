package app.fieldwatch.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.radio.usb.CaptureEntry
import app.fieldwatch.radio.usb.CaptureGps
import app.fieldwatch.radio.usb.CaptureProtocol
import app.fieldwatch.ui.FieldwatchUi
import app.fieldwatch.ui.FieldwatchViewModel
import app.fieldwatch.ui.NestedTabInsets
import app.fieldwatch.ui.NestedTopBar
import app.fieldwatch.ui.component.FieldwatchActionButton
import app.fieldwatch.ui.component.FieldwatchFilterChip
import app.fieldwatch.ui.component.SectionCard
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(appState: FieldwatchUi, vm: FieldwatchViewModel) {
    val state by vm.usbCaptureState.collectAsStateWithLifecycle()
    val label by vm.usbSessionLabel.collectAsStateWithLifecycle()
    val modeName by vm.usbMode.collectAsStateWithLifecycle()
    val channel by vm.usbChannel.collectAsStateWithLifecycle()
    val gps by vm.usbGpsEnabled.collectAsStateWithLifecycle()
    val files by vm.usbLibrary.collectAsStateWithLifecycle()
    val busy by vm.usbBusy.collectAsStateWithLifecycle()
    val mode = CaptureProtocol.Mode.valueOf(modeName)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var receivers by remember { mutableStateOf(vm.usbReceivers()) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var library by rememberSaveable { mutableStateOf(false) }
    var expandedFile by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CaptureEntry?>(null) }
    var deleting by remember { mutableStateOf<CaptureEntry?>(null) }
    var installerOpen by rememberSaveable { mutableStateOf(false) }
    var installDevice by remember { mutableStateOf<Int?>(null) }
    var manualBoot by rememberSaveable { mutableStateOf(false) }
    var location by remember { mutableStateOf(CaptureGps.Reading(CaptureGps.Status.OFF)) }
    val recorderScroll = rememberLazyListState()
    val libraryScroll = rememberLazyListState()
    val available = !state.active && !busy
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {
        uri -> pendingSave?.let { name -> if (uri != null) vm.exportUsbCapture(name, uri) }; pendingSave = null
    }
    val savePcap = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pcapng")) {
        uri -> pendingSave?.let { name -> if (uri != null) vm.exportUsbCapture(name, uri, pcap = true) }; pendingSave = null
    }
    LaunchedEffect(Unit) {
        while (true) {
            receivers = vm.usbReceivers()
            if (receivers.none { it.id == selected }) selected = receivers.firstOrNull()?.id
            delay(1500)
        }
    }
    LaunchedEffect(gps, state.gpsIncluded, state.active) {
        while (true) {
            location = vm.usbGpsReading(if (state.active && !state.installing) state.gpsIncluded else gps)
            delay(1000)
        }
    }
    LaunchedEffect(state.active, state.file) { vm.refreshUsbLibrary() }
    fun finishEditing() { focus.clearFocus(); keyboard?.hide() }
    Scaffold(
        contentWindowInsets = NestedTabInsets,
        topBar = { NestedTopBar("Capture") },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                    if (state.awaitingUsbPermission) {
                        FieldwatchActionButton(vm::cancelUsbPermissionRequest, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Cancel USB request")
                        }
                    } else if (state.installing) {
                        LinearProgressIndicator(progress = { state.installPercent / 100f }, Modifier.fillMaxWidth())
                        Text("${state.installPercent}% · Keep USB connected", style = MaterialTheme.typography.bodySmall)
                    } else if (state.active) {
                        FieldwatchActionButton(vm::stopUsbCapture, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Stop USB capture · ${duration(state.elapsedMs)}")
                        }
                    } else if (!appState.scanning) {
                        FieldwatchActionButton(vm::startScan, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy) {
                            Text("Start phone scanning")
                        }
                    } else {
                        FieldwatchActionButton({ finishEditing(); selected?.let { vm.startUsbCapture(it, mode, channel, gps) } },
                            Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = selected != null && !busy) {
                            Text("Start USB capture")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldwatchFilterChip(!library, { library = false }, { Text("Recorder") })
                FieldwatchFilterChip(library, { library = true; vm.refreshUsbLibrary() }, { Text("Library (${files.size})") })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize(), state = if (library) libraryScroll else recorderScroll,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!library) {
                    item {
                        SectionCard("USB receiver") {
                            Text(if (receivers.isEmpty()) "No receiver connected" else "ESP32-C3 · USB connected",
                                style = MaterialTheme.typography.titleMedium)
                            if (receivers.isEmpty()) Text("Connect the C3's native USB port with a data cable / OTG adapter.")
                            if (receivers.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                receivers.forEach { device -> FieldwatchFilterChip(selected == device.id, { selected = device.id },
                                    { Text(device.label) }, enabled = available) }
                            }
                            val rate = if (state.elapsedMs > 0) state.packets * 1000.0 / state.elapsedMs else 0.0
                            Text("${duration(state.elapsedMs)} elapsed · ${state.packets} packets · ${String.format(Locale.US, "%.1f", rate)} avg pkt/s")
                            Text("${state.bytes / 1024} KiB · ${state.gaps} gaps · ${state.deviceDrops} receiver drops · ${state.invalid} invalid",
                                style = MaterialTheme.typography.bodySmall)
                            if (state.active && !state.installing) Text("${state.mode} · ${if (state.channel == 0) "automatic channels" else "channel ${state.channel}"}")
                        }
                    }
                    item {
                        SectionCard("Session") {
                            OutlinedTextField(label, vm::setUsbSessionLabel, Modifier.fillMaxWidth(),
                                label = { Text("Session label / location note") }, singleLine = true, enabled = available,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { finishEditing() }))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CaptureProtocol.Mode.entries.forEach { option ->
                                    FieldwatchFilterChip(mode == option, { vm.setUsbMode(option.name) },
                                        { Text(if (option == CaptureProtocol.Mode.WIFI) "Wi-Fi management" else "BLE advertisements") }, enabled = available)
                                }
                            }
                            if (mode == CaptureProtocol.Mode.WIFI) {
                                Text("2.4 GHz channel", style = MaterialTheme.typography.labelMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    (0..11).forEach { ch -> FieldwatchFilterChip(channel == ch, { vm.setUsbChannel(ch) },
                                        { Text(if (ch == 0) "Hop 1–11" else "$ch") }, enabled = available) }
                                }
                            }
                            Text("Session: ${label.ifBlank { "No label" }}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        SectionCard("Observer GPS") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(gps, vm::setUsbGpsEnabled, enabled = available)
                                Text("Include phone GPS in capture", Modifier.weight(1f))
                            }
                            Text(gpsMessage(location), style = MaterialTheme.typography.titleSmall,
                                color = if (location.status == CaptureGps.Status.READY) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface)
                            if (gps && !appState.settings.tagLocation) {
                                Text("Location updates are off. Enable location tagging to request fresh fixes for captures and ordinary detections.",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { vm.updateSettings { it.copy(tagLocation = true) } }) { Text("Enable location tagging") }
                            }
                            Text("Accepted fixes: no older than 30 seconds, accuracy 75 m or better. Missing fixes are recorded as null. This is your position, not the transmitter's.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        SectionCard("Receiver setup") {
                            TextButton(onClick = { installerOpen = !installerOpen }) {
                                Text(if (installerOpen) "Hide firmware installer" else "Install / update receiver firmware")
                            }
                            if (installerOpen) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(manualBoot, { manualBoot = it }, enabled = available)
                                    Text("Manual boot mode", Modifier.weight(1f))
                                }
                                Text("If automatic boot fails: hold BOOT, tap RESET, release BOOT, then select manual boot mode.", style = MaterialTheme.typography.bodySmall)
                                FieldwatchActionButton({ installDevice = selected }, enabled = available && selected != null) { Text("Install firmware…") }
                            }
                            Text("Captures save raw identifiers even when Privacy mode is on. Wi-Fi and BLE run separately. Each recording is limited to 16 MiB / 30 minutes.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    item { Text("Raw exports include radio identifiers and any recorded GPS. Library names and notes are included in exports.",
                        style = MaterialTheme.typography.bodySmall) }
                    if (files.isEmpty()) item { Text("No captures yet. Start a recording from Recorder; it will appear here when saved.") }
                    items(files, key = { it.name }) { entry ->
                        SectionCard(entry.title) {
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.startedAtMs)),
                                style = MaterialTheme.typography.bodySmall)
                            Text("${entry.mode} · ${entry.durationMs?.let(::duration) ?: "duration unavailable"} · ${if (entry.gpsRequested) "GPS requested" else "GPS off"}")
                            Text("${entry.packets?.let { "$it packets · " }.orEmpty()}${entry.bytes / 1024} KiB · ${if (entry.complete) "Complete" else if (state.file == entry.name && state.active) "Recording" else "Incomplete"}",
                                style = MaterialTheme.typography.bodySmall)
                            if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { expandedFile = if (expandedFile == entry.name) null else entry.name }) { Text("Details & export") }
                            if (expandedFile == entry.name) {
                                Text(entry.name, style = MaterialTheme.typography.labelSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FieldwatchActionButton({ editing = entry }, enabled = available) { Text("Name & notes") }
                                    FieldwatchActionButton({ deleting = entry }, enabled = available) { Text("Delete") }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FieldwatchActionButton({ vm.exportUsbCapture(entry.name) }, enabled = available) { Text("Share JSONL") }
                                    FieldwatchActionButton({ pendingSave = entry.name; saveJson.launch(exportName(entry, "jsonl")) }, enabled = available) { Text("Save JSONL") }
                                }
                                if (entry.mode == "WIFI") {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FieldwatchActionButton({ vm.exportUsbCapture(entry.name, pcap = true) }, enabled = available) { Text("Share PCAPNG") }
                                        FieldwatchActionButton({ pendingSave = entry.name; savePcap.launch(exportName(entry, "pcapng")) }, enabled = available) { Text("Save PCAPNG") }
                                    }
                                    Text("Wireshark: Wi-Fi frames with channel, RSSI and phone receipt timestamps. GPS, when captured, is in packet comments.", style = MaterialTheme.typography.bodySmall)
                                } else Text("BLE exports use JSONL; the receiver saves advertisement data, not complete Bluetooth packets.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { entry -> CaptureDetailsDialog(entry, onDismiss = { editing = null }, onSave = { title, notes ->
        vm.renameUsbCapture(entry.name, title, notes); editing = null
    }) }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${entry.title}?") },
        text = { Text("Export it first if you need the original observations. Its saved notes will also be deleted.") },
        confirmButton = { TextButton(onClick = { vm.deleteUsbCapture(entry.name); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    installDevice?.let { id -> AlertDialog(onDismissRequest = { installDevice = null }, title = { Text("Install C3 receiver firmware?") },
        text = { Text("This replaces firmware and clears settings on the selected ESP32-C3 (4 MB). Use your spare receiver, not the Surveillance Hound display board. Keep USB connected until verification finishes. Phone captures and settings are preserved.") },
        confirmButton = { TextButton(onClick = { vm.installUsbFirmware(id, manualBoot); installDevice = null }) { Text("Install firmware") } },
        dismissButton = { TextButton(onClick = { installDevice = null }) { Text("Cancel") } }) }
}

@Composable
private fun CaptureDetailsDialog(entry: CaptureEntry, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by rememberSaveable(entry.name) { mutableStateOf(entry.title) }
    var notes by rememberSaveable(entry.name) { mutableStateOf(entry.notes) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Capture name & notes") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it.take(80) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Notes") }, minLines = 2, maxLines = 5)
            Text("Original session label: ${entry.originalLabel.ifBlank { "None" }}. The recording stays unchanged.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(title, notes) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun duration(ms: Long): String = "%d:%02d".format(Locale.US, ms / 60000, ms / 1000 % 60)
private fun exportName(entry: CaptureEntry, extension: String) =
    entry.title.replace(Regex("[^A-Za-z0-9_-]+"), "-").take(60).ifBlank { "capture" } + "-${entry.startedAtMs}.$extension"
private fun gpsMessage(reading: CaptureGps.Reading): String {
    val status = when (reading.status) {
        CaptureGps.Status.OFF -> "GPS off · no location will be saved"
        CaptureGps.Status.PERMISSION -> "Precise location permission needed"
        CaptureGps.Status.DISABLED -> "Phone location is disabled"
        CaptureGps.Status.WAITING -> "Waiting for a location fix"
        CaptureGps.Status.STALE -> "GPS fix is too old · no location saved"
        CaptureGps.Status.INACCURATE -> "GPS accuracy is too low · no location saved"
        CaptureGps.Status.READY -> "GPS ready"
    }
    return status + (reading.accuracyM?.let { " · ±${it.toInt()} m" } ?: "") +
        (reading.ageSeconds?.let { " · fix ${it}s old" } ?: "")
}
