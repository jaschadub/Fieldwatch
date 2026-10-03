// SPDX-License-Identifier: MIT
// Passive management-frame / legacy-advertisement receiver. No association, active scanning or RF transmit commands.
#include "commands.hpp"
#include "driver/usb_serial_jtag.h"
#include "esp_event.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nvs_flash.h"
#include <algorithm>
#include <atomic>
#include <cinttypes>
#include <cstdio>
#include <cstring>

namespace {
constexpr size_t MaxBytes = 1536;
constexpr unsigned QueueLength = 24;
struct Packet {
    uint64_t us;
    uint32_t seq, epoch;
    uint16_t length, original;
    int8_t rssi;
    uint8_t channel, address_type, event_type;
    Mode mode;
    uint8_t address[6];
    uint8_t bytes[MaxBytes];
};
std::atomic<Mode> mode{Mode::Off};
std::atomic<uint32_t> epoch{0}, sequence{0}, dropped{0};
std::atomic<bool> ble_ready{false};
StaticQueue_t queue_state;
uint8_t queue_memory[QueueLength * sizeof(Packet)];
QueueHandle_t queue;
unsigned requested_channel = 0, current_channel = 1;
int64_t last_command = 0, last_hop = 0;
bool framing_lost = false;

void enqueue(Packet &packet) {
    if (packet.epoch != epoch.load() || packet.mode != mode.load()) return;
    packet.seq = sequence.fetch_add(1);
    if (xQueueSend(queue, &packet, 0) != pdTRUE) dropped.fetch_add(1);
}

void wifi_receive(void *buffer, wifi_promiscuous_pkt_type_t type) {
    if (mode != Mode::Wifi || type != WIFI_PKT_MGMT) return;
    const auto *rx = static_cast<wifi_promiscuous_pkt_t *>(buffer);
    if (rx->rx_ctrl.rx_state != 0 || rx->rx_ctrl.sig_len < 28) return;
    Packet p{};
    p.epoch = epoch.load(); p.mode = Mode::Wifi;
    p.us = esp_timer_get_time(); p.rssi = rx->rx_ctrl.rssi;
    p.channel = rx->rx_ctrl.channel;
    if (p.channel < 1 || p.channel > 11) return;
    // Espressif's received length includes four trailing FCS bytes; exports exclude them.
    p.original = rx->rx_ctrl.sig_len - 4;
    p.length = std::min<size_t>(p.original, MaxBytes);
    std::memcpy(p.bytes, rx->payload, p.length);
    enqueue(p);
}

int ble_event(ble_gap_event *event, void *) {
    if (mode != Mode::Ble || event->type != BLE_GAP_EVENT_DISC) return 0;
    const auto &rx = event->disc;
    if (rx.length_data > 31 || rx.event_type > 3) return 0;
    Packet p{};
    p.epoch = epoch.load(); p.mode = Mode::Ble;
    p.us = esp_timer_get_time(); p.rssi = rx.rssi;
    p.original = p.length = rx.length_data;
    p.address_type = rx.addr.type; p.event_type = rx.event_type;
    std::reverse_copy(rx.addr.val, rx.addr.val + 6, p.address);
    std::memcpy(p.bytes, rx.data, p.length);
    enqueue(p);
    return 0;
}

void ble_sync() { ble_ready = ble_hs_util_ensure_addr(0) == 0; }
void ble_host(void *) { nimble_port_run(); nimble_port_freertos_deinit(); }

bool send_line(const char *line) {
    if (framing_lost) {
        if (usb_serial_jtag_write_bytes("\n", 1, pdMS_TO_TICKS(30)) != 1) return false;
        framing_lost = false;
    }
    const size_t length = std::strlen(line);
    const int sent = usb_serial_jtag_write_bytes(line, length, pdMS_TO_TICKS(50));
    if (sent != static_cast<int>(length)) { framing_lost = true; return false; }
    return true;
}
void error(const char *message) {
    char line[200];
    std::snprintf(line, sizeof(line), "{\"v\":1,\"type\":\"error\",\"message\":\"%s\"}\n", message);
    send_line(line);
}
void state() {
    char line[100];
    const char *name = mode == Mode::Wifi ? "WIFI" : mode == Mode::Ble ? "BLE" : "OFF";
    std::snprintf(line, sizeof(line), "{\"v\":1,\"type\":\"state\",\"mode\":\"%s\",\"channel\":%u}\n",
                  name, mode == Mode::Wifi ? requested_channel : 0);
    send_line(line);
}
bool stop() {
    mode = Mode::Off; epoch.fetch_add(1);
    const bool wifi_ok = esp_wifi_set_promiscuous(false) == ESP_OK;
    if (ble_gap_disc_active()) ble_gap_disc_cancel();
    for (int i = 0; ble_gap_disc_active() && i < 20; ++i) vTaskDelay(pdMS_TO_TICKS(10));
    xQueueReset(queue);
    return wifi_ok && !ble_gap_disc_active();
}

void command(const char *text) {
    const auto cmd = parse_command(text);
    if (cmd.kind == CommandKind::Invalid) { error("Unknown command"); return; }
    last_command = esp_timer_get_time();
    if (cmd.kind == CommandKind::Ping) return;
    if (cmd.kind == CommandKind::Hello) {
        send_line("{\"v\":1,\"type\":\"hello\",\"chip\":\"ESP32-C3\",\"firmware\":\"fieldwatch-ng-usb-0.1.0\"}\n");
        return;
    }
    if (!stop()) { error("Radio did not stop"); return; }
    if (cmd.kind == CommandKind::Stop) { state(); return; }
    requested_channel = cmd.channel;
    dropped = 0;
    if (cmd.mode == Mode::Wifi) {
        current_channel = cmd.channel ? cmd.channel : 1;
        if (esp_wifi_set_channel(current_channel, WIFI_SECOND_CHAN_NONE) != ESP_OK) {
            error("Cannot select Wi-Fi channel"); return;
        }
        mode = Mode::Wifi;
        if (esp_wifi_set_promiscuous(true) != ESP_OK) {
            stop(); error("Cannot start Wi-Fi receiver"); return;
        }
        last_hop = esp_timer_get_time();
    } else {
        if (!ble_ready) { error("BLE receiver not ready"); return; }
        ble_gap_disc_params params{};
        params.passive = 1; params.itvl = 80; params.window = 80; params.filter_duplicates = 0;
        mode = Mode::Ble;
        if (ble_gap_disc(BLE_OWN_ADDR_PUBLIC, BLE_HS_FOREVER, &params, ble_event, nullptr)) {
            stop(); error("Cannot start BLE receiver"); return;
        }
    }
    state();
}

void emit(const Packet &p) {
    static const char digits[] = "0123456789ABCDEF";
    static char hex[MaxBytes * 2 + 1], line[MaxBytes * 2 + 400];
    for (size_t i = 0; i < p.length; ++i) {
        hex[2*i] = digits[p.bytes[i] >> 4]; hex[2*i+1] = digits[p.bytes[i] & 15];
    }
    hex[p.length * 2] = '\0';
    int length;
    if (p.mode == Mode::Wifi) {
        length = std::snprintf(line, sizeof(line),
            "{\"v\":1,\"type\":\"packet\",\"radio\":\"WIFI\",\"seq\":%" PRIu32 ",\"us\":%" PRIu64
            ",\"rssi\":%d,\"channel\":%u,\"original_length\":%u,\"fcs_included\":false,\"data\":\"%s\"}\n",
            p.seq, p.us, p.rssi, p.channel, p.original, hex);
    } else {
        length = std::snprintf(line, sizeof(line),
            "{\"v\":1,\"type\":\"packet\",\"radio\":\"BLE\",\"seq\":%" PRIu32 ",\"us\":%" PRIu64
            ",\"rssi\":%d,\"channel\":0,\"original_length\":%u,\"address\":\"%02X:%02X:%02X:%02X:%02X:%02X\","
            "\"address_type\":%u,\"event_type\":%u,\"data\":\"%s\"}\n",
            p.seq, p.us, p.rssi, p.original, p.address[0], p.address[1], p.address[2],
            p.address[3], p.address[4], p.address[5], p.address_type, p.event_type, hex);
    }
    if (length < 0 || static_cast<size_t>(length) >= sizeof(line) || !send_line(line)) dropped.fetch_add(1);
}
} // namespace

extern "C" void app_main() {
    usb_serial_jtag_driver_config_t usb{.tx_buffer_size = 4096, .rx_buffer_size = 256};
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&usb));
    ESP_ERROR_CHECK(nvs_flash_init());
    queue = xQueueCreateStatic(QueueLength, sizeof(Packet), queue_memory, &queue_state);
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&config));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_NULL));
    ESP_ERROR_CHECK(esp_wifi_start());
    wifi_country_t country{};
    std::memcpy(country.cc, "US", 2); country.schan = 1; country.nchan = 11;
    country.policy = WIFI_COUNTRY_POLICY_MANUAL;
    ESP_ERROR_CHECK(esp_wifi_set_country(&country));
    wifi_promiscuous_filter_t filter{}; filter.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT;
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_filter(&filter));
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_rx_cb(wifi_receive));
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = ble_sync;
    nimble_port_freertos_init(ble_host);
    char input[64]{}; size_t used = 0; bool discard = false;
    uint8_t bytes[64];
    int64_t last_stats = 0;
    static Packet packet;
    for (;;) {
        const int got = usb_serial_jtag_read_bytes(bytes, sizeof(bytes), pdMS_TO_TICKS(10));
        for (int i = 0; i < got; ++i) {
            if (bytes[i] == '\n') {
                if (!discard && used) { input[used] = '\0'; command(input); }
                used = 0; discard = false;
            } else if (!discard && bytes[i] != '\r') {
                if (bytes[i] < 32 || bytes[i] > 126 || used >= sizeof(input) - 1) discard = true;
                else input[used++] = static_cast<char>(bytes[i]);
            }
        }
        const int64_t now = esp_timer_get_time();
        if (mode != Mode::Off && now - last_command > 5'000'000) { stop(); state(); }
        if (mode == Mode::Wifi && requested_channel == 0 && now - last_hop > 250'000) {
            current_channel = current_channel % 11 + 1;
            if (esp_wifi_set_channel(current_channel, WIFI_SECOND_CHAN_NONE) != ESP_OK) {
                stop(); error("Channel hop failed");
            }
            last_hop = now;
        }
        for (int i = 0; i < 4 && xQueueReceive(queue, &packet, 0) == pdTRUE; ++i)
            if (packet.mode == mode.load() && packet.epoch == epoch.load()) emit(packet);
        if (now - last_stats > 1'000'000) {
            char line[120];
            const char *name = mode == Mode::Wifi ? "WIFI" : mode == Mode::Ble ? "BLE" : "OFF";
            std::snprintf(line, sizeof(line), "{\"v\":1,\"type\":\"stats\",\"mode\":\"%s\",\"dropped\":%" PRIu32 "}\n", name, dropped.load());
            send_line(line); last_stats = now;
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }
}
