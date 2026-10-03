# Fieldwatch-NG

This fork adds an optional **ESP32-C3 Super Mini USB research receiver**. See the
[USB receiver setup and capture guide](docs/USB_RECEIVER.md) for the Android build,
receiver firmware, OTG connection, capture/export controls, and limitations.
The fork installs as `app.fieldwatch.ng` alongside upstream Fieldwatch. Its USB
controls, including **Install / update receiver firmware**, are under
**Settings → USB research receiver**. The APK includes the C3 firmware for offline
installation from an Android phone with USB OTG.

[Download the Fieldwatch-NG APK](https://github.com/jaschadub/Fieldwatch/releases/download/v1.1.17-ng-usb.4/Fieldwatch-NG-1.1.17-ng-usb.4.apk) · [Firmware, checksums, and release notes](https://github.com/jaschadub/Fieldwatch/releases/tag/v1.1.17-ng-usb.4)

This is a **hardware-tested development prerelease** with a development-signed APK.
Installation and capture have been tested with an ESP32-C3 Super Mini: BLE through
an Android 15 emulator with USB passthrough, and Wi-Fi on a user's Android phone,
including exports with GPS enabled and disabled. The APK installs beside upstream
Fieldwatch. Version `ng-usb.4` also fixes session-label editing and retention.

Fieldwatch-NG contributions by [Jascha Wanger / DX.PE](https://dx.pe).
Copyright 2026 Jascha Wanger. Licensed under the [MIT License](LICENSE), with
upstream and third-party notices preserved.

## Differences from upstream Fieldwatch

Fieldwatch-NG is based on [OffGridPete/Fieldwatch](https://github.com/OffGridPete/Fieldwatch),
version 1.1.17 with catalog 90 (`cf6562d`). It retains the existing live views,
signature matching, alerts, reports, and MIT license. The following changes are
specific to this fork:

| Area | Upstream base | Fieldwatch-NG additions |
| --- | --- | --- |
| Receiver | Phone Wi-Fi and BLE scan APIs | Optional ESP32-C3 Super Mini over native USB, alongside phone scans |
| Wi-Fi capture | AP scan results exposed by Android | Passive management-frame bytes on 2.4 GHz channels 1–11, hopping or fixed channel; external capture is independent of Android's Wi-Fi scan quotas |
| BLE capture | Advertisements exposed by Android | Passive legacy advertisement AD bytes, address/type, RSSI, and receiver timestamps from the C3 |
| Firmware installation | No external receiver installer | Phone-based offline C3 install/update, explicit confirmation, chip/security/flash-size checks, bundled-image SHA-256 checks, on-device MD5 verification, progress, and BOOT/RESET fallback |
| Research exports | Existing logs and report exports | Separate bounded raw JSONL captures with session labels, sequence/loss counters, optional observer GPS, and share/save/delete controls |
| Live integration | Phone observations | USB BLE advertisements and complete AP beacons/probe responses feed the existing matcher; other management frames stay in the raw archive |
| Installation | `app.fieldwatch` | `app.fieldwatch.ng`, displayed as Fieldwatch-NG; installs beside the original app |
| Validation | Existing Android tests/build | Additional USB framing, parser, archive, bootloader and installer tests; C3 firmware build and bundle checks in CI; Android lint error fixes |

**Limits:** the initial receiver supports native USB ESP32-C3 boards with 4 MB flash
and a chip revision compatible with the bundled firmware (currently 0.3–1.99).
Wi-Fi and BLE capture run separately. It cannot receive 5/6 GHz Wi-Fi, Bluetooth
Classic, BLE extended advertising, or angle-of-arrival data. No additional detection
signatures are enabled by this extension. Firmware installation replaces the C3's
application and resets its NVS/settings; phone settings and captures are preserved.
Raw captures retain identifiers even when the ordinary app Privacy mode is on.

**Validation status:** 439 automated tests, Android lint, APK build and firmware-bundle
checks pass. The `ng-usb.3` installer completed physical C3 flashing, verification
and reboot, followed by 871 BLE packet records without reported loss. The user also
confirmed installation from a phone and supplied two Wi-Fi captures: one with GPS
on every packet, and one with GPS disabled. One phone capture reported invalid
records and sequence gaps; testing does not establish lossless reception or
compatibility with every phone/OTG adapter. The `ng-usb.4` label fix passed emulator
checks for keyboard Done, tab navigation, rotation and saved-state restoration;
phone confirmation of that UI fix is pending. See the
[validation details and remaining hardware checks](docs/USB_RECEIVER.md#validation-and-hardware-checklist).

Multi-receiver hotspot sharing and RSSI source-area estimation are documented
ideas, not implemented features.

Report fork-specific issues in [this fork](https://github.com/jaschadub/Fieldwatch/issues).

The prebuilt APK and PDF linked below are upstream releases; they do **not** contain
the USB extension. For Fieldwatch-NG, use the prerelease download above, build this
fork, or use its CI APK artifact. The original author's project description and notices follow.

## Upstream Fieldwatch

I built Fieldwatch as a personal tool to look at what Wi-Fi access points and Bluetooth LE ads my phone was able to pick up, so that I could better understand what devices were being used around me. It’s passive, it only listens, there’s no dongle, no account, and no backend server. I wanted something that would work offline in the field.

My goals were to have a modern interface that was easy to use, flexible in how information was displayed so I could customize a view based on what I was trying to do, a filtering engine so I do not have to look at everything, an extensible signature library so I can identify as many radio sources as possible and add new ones on the fly, as well as create reports of what was seen.

I have been using it and iterating on it for a while now, and it has been useful enough that I thought I would share it.

This is a hobby — something I do for fun in my spare time. There is no Fieldwatch backend. There are no ads. Everything lives on the phone. Source and sideload files are in this repository (`dist/` for the APK, instruction card, and manual).

It was Spectre through 1.2.14. I later learned that name was already in use by another app, so I renamed this one Fieldwatch to keep the two from being mixed up. Same field tool, new application id (`app.fieldwatch`), MIT License. Installing Fieldwatch does not replace Spectre on a phone; it is a separate app.

If you spot an error, something stupid, or have a feature idea — in the app or the documentation — please [open an issue on this repository](https://github.com/OffGridPete/Fieldwatch/issues). This is how we make it better. I hope you find it as useful as I have. I look forward to hearing how it goes.

**Just want to install it?** Download [Fieldwatch.apk](https://github.com/OffGridPete/Fieldwatch/raw/main/dist/Fieldwatch.apk). Instruction card and manual: [instruction.txt](https://github.com/OffGridPete/Fieldwatch/raw/main/dist/instruction.txt), [Fieldwatch_User_Manual.pdf](https://github.com/OffGridPete/Fieldwatch/raw/main/dist/Fieldwatch_User_Manual.pdf). [What’s new](CHANGELOG.md) is the changelog for each build. Leave the APK named `Fieldwatch.apk`. GitHub may say the file is too big to preview — that is their viewer; use Download.

## Safety & disclaimer

This is a hobby project, provided as-is under the MIT License. A few things to know before you do:

- Use at your own risk. Using Fieldwatch is your responsibility. To the maximum extent permitted by law, Off Grid Pete LLC is not liable for indirect, incidental, special, consequential, or punitive damages arising from its use.
- There is no guarantee that trackers, cameras, tags, access points, or any other device will be found, named, or reported. Radios that are off, cellular-only, asleep, randomized, quiet, or outside what this handset’s OS exposes will not appear. Each phone has its own radios, firmware, scan quotas, and OEM battery policies. Software cannot address those limits.
- Pattern matches, GPS co-travel (“Moving with you” / “possible tail”), Debrief language, and AI Export output are hypotheses — not identity, not a legal finding, and not a complete RF capture. You are solely responsible for how you use this app and this document, and for complying with local law. By using the software or this manual you accept these terms and the MIT License.
- Location data, if tagging is on, is this phone at hear-time — not the other radio. There is no Fieldwatch server. Stamps stay on the handset until you share them. Logs keep full coordinates even when Privacy mode masks the screen and sit reports. Debrief, Share log, AI Export (sit or one radio), and radio-detail Share as text can take that path off the phone. Online place names use the system geocoder (often the OEM / Google network), not a Fieldwatch cloud. How you store, share, or publish those files is your responsibility.

## Put it on a phone

On a phone, the install files are in [`dist/`](https://github.com/OffGridPete/Fieldwatch/tree/main/dist), not the root of the repo:

- [Fieldwatch.apk](https://github.com/OffGridPete/Fieldwatch/raw/main/dist/Fieldwatch.apk)
- [instruction.txt](https://github.com/OffGridPete/Fieldwatch/raw/main/dist/instruction.txt)
- [Fieldwatch_User_Manual.pdf](https://github.com/OffGridPete/Fieldwatch/raw/main/dist/Fieldwatch_User_Manual.pdf)

Do not rename the APK. Open `Fieldwatch.apk` from Files (or My Files). Allow install from that app if Android asks.

| File | What it is |
|---|---|
| `dist/Fieldwatch.apk` | Sideload APK |
| `dist/fieldwatch-signatures.json` | Stock catalog for 1.1.11 GitHub update (catalog 77) |
| `dist/fieldwatch-signatures-v2.json` | Stock catalog for 1.1.12+ GitHub update |
| `dist/instruction.txt` | Permissions, first launch |
| `dist/Fieldwatch_User_Manual.pdf` | User manual |
| [`CHANGELOG.md`](CHANGELOG.md) | What’s new in each build |
| `LICENSE` | MIT License |
| `NOTICE` | Third-party attribution |

Android 10+. Allow install from the app you used to open the APK. Play Protect may warn that it is not from Play — expected. Full steps are in `instruction.txt`.

```bash
adb install -r dist/Fieldwatch.apk
```

### Upgrading from 1.0.4 or earlier — Fieldwatch now has a real publisher certificate (one-time reinstall)

This release is a little more professional about how the APK is signed. Android attaches a certificate to every app so the phone can tell “this update is from the same publisher as the app I already have.” Through 1.0.4, Fieldwatch used the generic Android developer certificate that the build tools ship with. That is normal while you are iterating, but people who scan a sideload APK (and some scanners) flag it: a public build should not look like a debug leftover. 1.0.5 is signed with an Off Grid Pete LLC certificate instead. Same hobby app; the file now has a publisher name scanners can check. The fingerprint is in `instruction.txt` if you want to compare.

The catch is one-time. The phone treats a new certificate as a different publisher, so it will not install 1.0.5 on top of 1.0.4 or earlier. You uninstall the old Fieldwatch, then install this APK. After that, later versions use the same certificate, so ordinary updates work again. You will not have to uninstall for 1.1.17.

Uninstall wipes what is on the phone. If you added signatures, changed Settings, named radios, or saved filter presets, do this first: Settings → **Export signatures** and **Export settings**. Share or save those two files somewhere you can get them after. They are not the log and not GPS. Then uninstall (long-press the Fieldwatch icon, or `adb uninstall app.fieldwatch`), install 1.0.5, open it, tap through the disclaimer, and use **Import signatures** and **Import settings**. If you never customized, skip the export and just uninstall, then install.

## What Fieldwatch is not

- Not Wi-Fi clients, probe-only stations, or 802.11 monitor mode
- Not Bluetooth Classic inquiry (HC-05 / HC-06 will not appear)
- Not cellular
- Not direction finding

## Copyright and license

Fieldwatch-NG contributions: Copyright 2026 Jascha Wanger.
[Jascha Wanger / DX.PE](https://dx.pe)

Copyright (c) 2026 Off Grid Pete LLC.

Fieldwatch source is licensed under the [MIT License](LICENSE). AndroidX, Kotlin, and related libraries remain Apache-2.0. IEEE and Bluetooth SIG assigned-number tables in `radiodb.bin` are subject to those organizations’ terms. See [NOTICE](NOTICE).
