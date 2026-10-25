/**
 * Reolink Camera Child — Minimal Stub
 *
 * STUB_DRIVER_VERSION "20260318-REOLINK-CAMERA-STUB-v1"
 *
 * This is a placeholder driver automatically assigned to new child devices
 * created by the Reolink Hub Parent driver.
 *
 * AFTER adding and configuring your camera in the Reolink app:
 *   1. Open this device in Hubitat
 *   2. Click "Edit" on the device
 *   3. Change "Type" to the correct driver for your camera model:
 *        - Reolink Atlas 2K (battery)  → "Reolink Altas 2K Camera Child"
 *        - Reolink CX-820 (PoE)        → "Reolink CX-820 Camera Child"
 *        - Reolink Argus MagiCam (AA)  → "Reolink Argus MagiCam Camera Child"
 *   4. Click Save Device
 */

metadata {
    definition(name: "Reolink Camera Child — Stub", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "MotionSensor"
        capability "Refresh"
        capability "Actuator"

        attribute "Stub Notice", "string"
        attribute "Child Driver Version", "string"
    }
}

def STUB_VERSION() { "20260318-REOLINK-CAMERA-STUB-v1" }

def installed() {
    sendEvent(name: "Child Driver Version", value: STUB_VERSION(), isStateChange: true)
    sendEvent(name: "Stub Notice", value: "⚠️ Placeholder — change Type to the correct camera driver", isStateChange: true)
    sendEvent(name: "motion", value: "inactive", isStateChange: true)
    log.warn "${device.label ?: device.name}: Stub driver installed — change Type to the correct camera driver for this model"
}

def updated()    { installed() }
def initialize() { installed() }
def refresh()    { log.warn "${device.label ?: device.name}: Stub driver — change Type to the correct camera driver" }

// Stub receivers — parent will call these on every poll cycle
// They log a warning so you know the stub is still active
def updateAI(isActive, typeLabel, timeStr) {
    log.warn "${device.label ?: device.name}: updateAI called on stub driver — change Type to the correct camera driver"
}

def updatePushState(isOn)           { /* silent — not critical */ }
def updateRecordingEnabled(isEnabled) { /* silent — not critical */ }
def updateBatteryPercent(pct)       { /* silent — not critical */ }
def updatePIREnabled(isEnabled)     { /* silent — not critical */ }