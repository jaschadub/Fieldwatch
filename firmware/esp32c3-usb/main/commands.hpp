// SPDX-License-Identifier: MIT
#pragma once
#include <cstring>

enum class Mode { Off, Wifi, Ble };
enum class CommandKind { Invalid, Hello, Ping, Stop, Start };
struct Command { CommandKind kind; Mode mode = Mode::Off; unsigned channel = 0; };

inline Command parse_command(const char *line) {
    if (!std::strcmp(line, "HELLO")) return {CommandKind::Hello};
    if (!std::strcmp(line, "PING")) return {CommandKind::Ping};
    if (!std::strcmp(line, "STOP")) return {CommandKind::Stop};
    if (!std::strcmp(line, "START BLE 0")) return {CommandKind::Start, Mode::Ble};
    if (!std::strncmp(line, "START WIFI ", 11)) {
        const char *n = line + 11;
        if (n[0] >= '0' && n[0] <= '9' && n[1] == '\0')
            return {CommandKind::Start, Mode::Wifi, unsigned(n[0] - '0')};
        if (n[0] == '1' && (n[1] == '0' || n[1] == '1') && n[2] == '\0')
            return {CommandKind::Start, Mode::Wifi, unsigned(10 + n[1] - '0')};
    }
    return {CommandKind::Invalid};
}
