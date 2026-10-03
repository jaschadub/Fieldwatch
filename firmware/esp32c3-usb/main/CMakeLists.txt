idf_component_register(SRCS "receiver.cpp" INCLUDE_DIRS "."
    REQUIRES esp_wifi esp_event esp_timer bt nvs_flash esp_driver_usb_serial_jtag)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
