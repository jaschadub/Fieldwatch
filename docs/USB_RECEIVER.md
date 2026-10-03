# ESP32-C3 USB research receiver

Fieldwatch-NG can use an ESP32-C3 Super Mini as a USB receiver for sample collection.
The board receives radio data; Android stores it and displays compatible observations.
This extension requires both the fork's APK and the receiver firmware. The upstream
Fieldwatch APK and bundled upstream PDF do not describe or implement it.

## Hardware

- An Android phone with USB host/OTG support.
- ESP32-C3 Super Mini with its **native USB Serial/JTAG** connector.
- A USB data cable and, if needed, a phone OTG adapter. The phone normally powers the board.

The initial driver accepts Espressif VID `303A`, PID `1001` and its CDC serial
interfaces. CH340/CP210x UART bridges, other ESP families and arbitrary USB serial
devices are not supported by this version. A board must complete the protocol
handshake before capture starts. USB-C implementations differ on inexpensive boards;
if a direct C-to-C cable does not enumerate, try the phone's OTG adapter plus a known
data cable. A powered OTG hub may help a phone that cannot supply stable power.

## Build and install

### Install the receiver from the phone

The Fieldwatch-NG APK bundles the receiver firmware; no Internet or computer is
needed for this step. Install the APK on an Android phone with USB host/OTG support:

1. Connect the spare C3 and open **Settings → USB research receiver**.
2. Stop any active USB capture. Select the connected receiver.
3. Tap **Install / update receiver firmware**, review the replacement notice, and
   tap **Install firmware**. Grant Android USB access if requested. Main radio
   scanning does not have to be running for installation.
4. Keep USB connected while the app checks the chip, erases/writes, and verifies
   each region. A foreground notification shows progress if the screen turns off.
5. Wait for **Firmware installed and verified**. If the app instead reports that
   firmware is verified but needs reconnect/reset, reconnect USB or tap RESET.
   Then explicitly start a capture.

### Stuck waiting for USB access

In `1.1.17-ng-usb.2`, accepting the Android USB dialog can leave the installer at
**0% / Allow USB access in the Android dialog**. The permission callback was
immutable but depended on device information Android adds to that callback; the
app could discard the reply before starting the installer.

The `1.1.17-ng-usb.3` fix identifies the pending request using an app-supplied token
and checks the connected device and permission directly with Android. Missing,
denied, cancelled and expired requests cannot start flashing. A request expires
after 90 seconds and can also be cancelled before installation starts. Permission
waiting is shown separately from firmware-writing progress.

On the older APK, leave the C3 connected, force-stop Fieldwatch-NG in Android's app
settings, reopen it and retry. If access was granted, the retry bypasses the faulty
permission callback. If the Android access dialog never appears, reconnect the
board and check that the phone is using USB host/OTG mode and a data-capable cable.
Do not use this workaround once erasing or writing has started.

### Bootloader recovery

Version `1.1.17-ng-usb.3` also removes an unnecessary final ROM command that the C3
rejected after all firmware regions passed MD5 verification. Verification now
finishes before the separate USB reset, matching the
[esptool ROM-loader flow](https://github.com/espressif/esptool/blob/master/esptool/cmds.py).

If automatic bootloader entry fails, hold **BOOT**, tap and release **RESET**, then
release **BOOT**. Select **Manual boot mode** in the app and retry installation.
If the board has no RESET button, hold BOOT while reconnecting USB, then release it.
The built-in ROM bootloader supports first installation and recovery after an
interrupted write; the receiver application need not already be installed.

Only the bundled firmware is accepted. Before erasing, the app checks bundle
SHA-256 hashes, ESP32-C3 identity/revision, security flags, and 4 MB SPI flash. The
current images accept C3 revisions 0.3–1.99, as declared in their image headers. It declines
secured/encrypted devices and does not program eFuses. Each region is verified
using the ROM's MD5 command. Installation replaces the bootloader, partition table,
receiver application, and NVS/PHY settings on the C3; it does not erase phone data.
On interruption, reconnect and reinstall using manual boot if necessary. Capturing
cannot run during installation, and nothing flashes automatically on USB attach.

The installer has been tested with a physical C3 passed through to an Android 15
emulator. A user also confirmed installation from an Android phone and supplied
Wi-Fi captures with and without GPS. Other phone USB controllers, adapters and
power arrangements still need testing; see the validation details below.

### Build from source

Build the app with JDK 17 (JDK 21 also works), Android SDK 35 and the checked-in Gradle wrapper:

```sh
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The package is `app.fieldwatch.ng`, displayed as **Fieldwatch-NG**. It installs beside
the upstream app. Use upstream Export settings / Export signatures and import them
into this fork if desired. The USB permission dialog is independent of radio permissions.
APK signing follows the repository's existing build configuration; production release
signing still needs the maintainer's own key.

The checked-in `app/src/main/assets/receiver/` bundle makes APK builds independent
of an installed ESP toolchain. Run `python3 scripts/package_receiver.py --check`
from the repository root to verify hashes and the firmware source fingerprint.

Build the receiver with **ESP-IDF 6.0.2**, from `firmware/esp32c3-usb/`:

```sh
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash
```

Choose the port belonging to the spare C3. Flashing replaces its application. The
normal Surveillance Hound display-board firmware is a different build.
Do not leave `idf.py monitor` or another serial terminal attached when Android needs
the USB interface. The receiver powers up with capture **off**.

After changing receiver source, rebuild with ESP-IDF 6.0.2 and run
`python3 scripts/package_receiver.py` from the repository root before rebuilding
the APK. Commit the regenerated images and manifest together. CI rejects stale
source fingerprints and corrupted bundles. The phone installer intentionally
resets the receiver's settings for a known partition layout; a normal `idf.py flash`
does not clear NVS unless you erase it separately.

## Collect a session

1. Open Fieldwatch-NG, grant its normal scanning permissions, and start scanning.
2. Connect the C3 to the phone. Open **Settings → USB research receiver**.
3. Choose the receiver. Add a short session label such as `store-a-cart-area`.
   In `ng-usb.4`, the keyboard **Done/checkmark** finishes editing and dismisses the
   keyboard. The label survives tab navigation, rotation and Android saved-state
   restoration; confirm it beside **Start USB capture**. Tap the field to edit it
   again before the next capture. The label is written in the first `session`
   record of the JSONL file; it does not rename the file.
4. Choose **Wi-Fi management** (hop 1–11 or hold a channel) or **BLE advertisements**.
5. Optionally enable **Include available phone GPS in raw export**. A fix must be
   at most 30 seconds old and have reported accuracy of 75 m or better. Missing fixes
   are `null`; the position belongs to the observer, never the transmitter. Existing
   Settings → Tag detections with GPS controls the phone's location update requests.
6. Tap **Start USB capture** and allow Android's USB access request. Confirm the
   status says capture is running and the packet counter increases near a known source.
7. Tap **Stop USB capture**, then select the saved capture and **Share JSONL** or
   **Save JSONL**. Export is enabled after the writer closes.

These controls create a separate research file, even if ordinary Write to disk is off.
The normal Reports → Log export is not the USB raw archive. BLE observations and
complete AP beacons/probe responses also enter Fieldwatch's usual live display,
signatures and configured alerts. Probe requests, deauthentication and other
management frames are retained in the research file; they are not labeled as APs.
Phone scans continue alongside USB capture. That can produce repeated live sightings
of the same advertiser, while USB raw files contain only external-receiver records.

No capture begins automatically on attach, permission grant from a previous session,
app restart or reconnect. Stop the main scan or unplug USB to end the active capture.
After reconnecting, start a new session. A short partial file survives an interruption.

## Coverage and limits

| Mode | Captured | Limits |
| --- | --- | --- |
| Wi-Fi | Management-frame bytes, receive channel, RSSI, receiver time and sequence | 2.4 GHz channels 1–11, one at a time; hopping dwells about 250 ms per channel. No application data or control-frame capture. |
| BLE | Original legacy advertisement AD bytes, address and address type, event type, RSSI and receiver time | Passive legacy LE 1M advertisements up to 31 bytes. No scan responses, extended advertising, GATT connections or Bluetooth Classic. |

The C3 does not run both capture modes simultaneously. A single receiver misses
transmissions on other channels. This is a packet receiver, not an SDR or an
angle-of-arrival instrument; it does not resolve rotating identities or identify a
physical product from a manufacturer number alone.

Wi-Fi FCS bytes are removed. Up to **1536 bytes** of each management frame are saved;
`original_length` and `truncated` make larger frames explicit. Truncated frames are
not converted into live AP detections. Malformed BLE AD structures remain available
for analysis but do not enter the live matcher.

Firmware uses a bounded 24-packet queue and reports drops instead of blocking the
radio callback. JSON framing is bounded to 4096 bytes. Android reports captured
packets, malformed records, sequence gaps and receiver drops. **Do not add gaps and
drops together**: they can describe the same loss. Neither counter includes radio
packets that the receiver never heard. Live-view queueing may discard observations
independently of the raw archive. Receiver drops are the last reported count, not a
guarantee that the final queue was drained; stopping or unplugging can leave packets
undelivered.

Each session stops at **16 MiB or 30 minutes**. The archive retains at most 20 files
within a 128 MiB budget, reserving room for a new session before starting. Old files
are never silently overwritten. Export and delete old captures to make room. App
uninstall removes app-private captures. Privacy mode does not redact raw captures.

The receiver stops on STOP or after five seconds without a valid host command. The
app sends a heartbeat each second, detects unresponsive firmware, and holds a bounded
CPU wake lock while capturing. Android process termination, OTG power loss and vendor
battery policies can still interrupt a session. Writes flush at approximately 500 ms
intervals; abrupt power loss may lose the tail of a file. Firmware has no commands for
RF injection, pairing or association. The phone's ordinary Android Wi-Fi scans still
follow the OS's behavior and may send probe requests.

## Export format and analysis

Files are JSON Lines in private `files/usb-captures/`. Every file starts with a
`session` record containing protocol/app/firmware versions, mode, requested channel,
label, start time and GPS choice. Each `packet` preserves the receiver's raw hex and
adds Android `received_at_ms` and optional `observer_gps`. `stats` records retain drop
counts; a clean close adds an `end` record with totals and reason. An interrupted file
may lack `end` or have a partial final line.

Use receiver `us` for intervals within one boot. `received_at_ms` is the phone's
receipt clock and includes USB buffering delay; it is not synchronized RF arrival
time. `seq` is monotonic across captures until receiver reboot (32-bit counter).
BLE channel `0` means unknown: the NimBLE discovery report does not provide the
physical advertising channel. Wi-Fi channel is the actual receive channel.

Start with a known home beacon. Collect separate short sessions at each field location,
plus an away-from-source comparison. Keep visible device labels and physical notes
with the session. A strong RSSI alone does not prove which object transmitted it.
Before publishing samples, remove embedded identifiers as well as addresses and GPS.
Use synthetic fixtures for public detection tests.

## Protocol v1

Transport is native USB CDC, line coding 115200/8N1, ASCII JSON Lines from receiver to
phone and bounded ASCII commands in the other direction. USB throughput is not a
physical 115200-baud UART limit. Commands are `HELLO`, `PING`, `STOP`,
`START WIFI n` (`n=0` hop, `1..11` hold), and `START BLE 0`, each newline terminated.
Radio state changes are acknowledged before packets are emitted.

```json
{"v":1,"type":"hello","chip":"ESP32-C3","firmware":"fieldwatch-ng-usb-0.1.0"}
{"v":1,"type":"state","mode":"BLE","channel":0}
{"v":1,"type":"packet","radio":"BLE","seq":0,"us":123456,"rssi":-55,"channel":0,"original_length":3,"address":"C0:11:22:33:44:55","address_type":1,"event_type":3,"data":"020106"}
{"v":1,"type":"stats","mode":"BLE","dropped":0}
```

Wi-Fi records replace BLE address/event fields with `fcs_included:false`. BLE
addresses use normal display order, not NimBLE's internal byte order. Service and
manufacturer data remain inside `data`; nothing is selected or shortened to one
manufacturer record. Unsupported protocol versions fail the handshake.

## Validation and hardware checklist

Host tests cover chunked USB reads, oversize/binary framing recovery, malformed fields,
BLE service/manufacturer preservation, beacon parsing, separation of non-AP frames,
archive integrity, quotas and firmware command validation. Installer tests exercise
fragmented ROM replies, real bundled images, chip/security/capacity rejection,
integrity failures, disconnect timeouts, cancellation and manual boot entry. Android
permission regression tests cover immutable callback delivery, grants/denials, stale
requests, detach, cancellation and timeouts on API 29 and 35. CI builds the APK and C3
firmware. These automated checks do not establish actual OTG interoperability.

On 2026-10-03, the `1.1.17-ng-usb.3` APK was tested with a physical ESP32-C3 Super
Mini through native USB passthrough into an Android 15 AOSP emulator (API 35,
emulator 36.1.9). The test image enabled `android.hardware.usb.host` through its
`/data/system/extra_feature.xml` hook so Android exposed its normal USB host APIs.
The original permission callback failure was reproduced in Android framework
regression tests. Hardware testing additionally exposed an unnecessary final ROM
command; both fixes are included in `ng-usb.3`.

The final hardware test completed automatic bootloader entry, all four region MD5
checks, native USB reset and a matching receiver HELLO. A short BLE session saved
871 packet records with zero reported sequence gaps, receiver drops or invalid
records, followed by a clean end record. All JSONL records parsed successfully.
This validates this C3/host/emulator combination, not every phone or OTG adapter;
zero reported loss does not mean every over-the-air advertisement was received.
The final app build passed 439 tests, Android lint and the firmware-bundle check.

The user subsequently confirmed firmware installation from a phone using
`ng-usb.3` and supplied two Wi-Fi channel-hopping exports from the C3. Both files
had valid JSONL and clean session-end records:

| Phone capture | Saved packets | Observer GPS | Reported invalid / sequence gaps / receiver drops |
| --- | ---: | --- | --- |
| About 12 seconds | 278 | Present on all packets; seven fix timestamps, fixes less than four seconds old | 0 / 0 / 0 |
| About 30 seconds | 789 | Disabled; no observer GPS fields | 7 / 11 / 0 |

The GPS-enabled session retained its label. The other session's label was empty
despite the user entering one, which prompted the `ng-usb.4` label fix. Invalid
counts describe rejected incoming records, not malformed lines in the exported
file. These short captures demonstrate phone/C3 operation and the GPS opt-in
behavior; they do not establish long-run stability or lossless reception. The
phone model was not recorded, and raw captures and precise locations are not
included in the repository.

The `ng-usb.4` label controls were checked in the Android 15 emulator: the keyboard
Done/checkmark dismisses the keyboard and clears focus, the capture summary shows
the entered label, and the label survives tab navigation, rotation and process
recreation using Android's saved state. Confirmation of this UI update on the
user's phone remains pending. Receiver firmware is unchanged at `0.1.0`.

Before field use, repeat the relevant checks on your phone and spare C3. Interrupted
installation recovery, prolonged capture and broader phone compatibility remain
open hardware checks:

- Native USB enumerates; grant and deny permission both leave a clear status.
- Install from the phone onto a spare C3, including automatic and manual boot entry;
  confirm flash verification and a matching receiver HELLO after reboot.
- Interrupt a test installation on a spare board, reconnect in manual boot mode,
  and verify reinstall recovers. Wrong chip/security/capacity must fail before erase.
- Known BLE tag appears and raw bytes agree with an independent observation.
- Known 2.4 GHz AP appears; hold/hop captures show appropriate receive channels.
- Stop/restart, unplug/replug and screen-off collection work; no automatic restart.
- Removing the USB host stops the receiver, and reconnect requires another Start.
- JSONL exports reopen with valid records, a session header and end totals.
- Optional GPS is the phone's recent position; disabled GPS leaves no coordinates.

Multiple phones sharing measurements over a local hotspot is a future extension.
It would need receiver position/calibration and a shared session clock. RSSI can
support an uncertain source-area estimate, not precise angle-of-arrival direction
finding. No networking or location solver is included here.

A possible first version would use one phone as the coordinator and at least three
receivers at known, separated positions. Phones would share timestamped signal
strength and channel observations for the same transmitter over a local-only Wi-Fi
hotspot, while their USB C3s listen. Indoors, manually placed receiver pins would be
more useful than assuming precise phone GPS. Different antennas, reflections, body
blocking and rotating identities must be handled before a map can show a credible
confidence region. A hotspot alone provides no bearing or precise ranging.

Technical references: [Android local-only hotspots](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot)
support communication between nearby connected apps without Internet access;
[Bluetooth AoA](https://www.bluetooth.com/learn-about-bluetooth/feature-enhancements/direction-finding/)
uses antenna arrays and special transmitted signals, which this receiver does not supply.
