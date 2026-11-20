package com.yuesf.remotecontrol.data

enum class DeviceRole(val wireValue: String) {
    AGENT("agent"),
    CONTROLLER("controller");

    companion object {
        fun fromWire(value: String): DeviceRole =
            entries.firstOrNull { it.wireValue == value } ?: CONTROLLER
    }
}
