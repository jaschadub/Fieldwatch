// SPDX-License-Identifier: MIT
#include "../main/commands.hpp"
#include <cassert>
#include <string>
int main() {
    assert(parse_command("HELLO").kind == CommandKind::Hello);
    assert(parse_command("STOP").kind == CommandKind::Stop);
    assert(parse_command("PING").kind == CommandKind::Ping);
    assert(parse_command("START BLE 0").mode == Mode::Ble);
    for (unsigned c = 0; c <= 11; ++c) {
        auto text = "START WIFI " + std::to_string(c);
        auto cmd = parse_command(text.c_str());
        assert(cmd.kind == CommandKind::Start && cmd.mode == Mode::Wifi && cmd.channel == c);
    }
    for (const char *bad : {"", "START", "START BLE 1", "START WIFI -1", "START WIFI 12",
         "START WIFI 99999999999999999999999999", "START WIFI 1garbage", "TX", "DEAUTH", "START WIFI 01"})
        assert(parse_command(bad).kind == CommandKind::Invalid);
}
