/**
 * Reolink Altas 2K Camera Child (for Home Hub Pro)
 *
 * CHILD_DRIVER_VERSION "20261001-REOLINK-ALTAS2K-CHILD-v14"
 *
 *  - Motion capability = AI Motion (Rule Machine friendly)
 *  - 'Camera in Standby' is inferred if recording, lights, or AI detection is not occurring, as Reolink do not directly expose the PIR or state via API
 *  - Wakes camera on light on, snapshot, burst
 *  - Returns to standby after Camera Standby Timeout (Seconds)
 *  - Photos (snapshots) saved to Hubitat File Manager internal storage
 *  - Enable and Disable Reolink Push Notifications from Parent or single child.
 *      NOTE: the change in Notifications is made through API and the change is not reflected in the Reolink App.
 *            but it is applied globally and overrides what is shown in the app.
 *  - Enable and Disable Camera Recording from Parent or single child.
 *  - Enable and Disable PIR Sensor from Parent or single child.
 *  - To clear old states and variables during troubleshooting - Uncomment the 3 lines in the metadata
 *
 *  v14 (2026-10-01) - changes from v13
 *  - Recording Search goes through the parent's Search queue (parent.requestSearch); the
 *    Home Hub Pro answers only one Search at a time. The reply arrives in searchResult().
 *  - Search errors show the rspCode, e.g. "API error code=1 rspCode=-17".
 *  - Requires Reolink Hub Parent v22 or later.
 *
 *  v13 (2026-10-01) - changes from v12 (hub load)
 *  - Awake and standby recording probes asynchronous; loops reschedule before probing.
 *  - Search window 2 hours instead of 24.
 *  - Recording Active and Last Recording attributes only written when they change;
 *    Last Recording Poll uses minute precision.
 */

metadata {
    definition(name: "Reolink Altas 2K Camera Child", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "MotionSensor"   // AI Motion
        capability "Refresh"
        capability "Switch"         // light control
        capability "Actuator"       // Expose capabilities for automations
        capability "Initialize"
        capability "Battery"        // Expose Battery Percentage

        // Photos
        command "saveSinglePhotoToHubitat"
        command "savePhotoBurstToHubitat"
        command "manualPhotoRetentionCleanup"

        // Push Notifications from Reolink Home Hub Pro
        command "ReolinkPushNotificationsOn"
        command "ReolinkPushNotificationsOff"

        // Recording
        command "ReolinkRecordingOn"
        command "ReolinkRecordingOff"

        // PIR Sensor
        command "ReolinkPIROn"
        command "ReolinkPIROff"

        // Troubleshooting - Uncomment the next 3 lines
        // command "Clear Current States"
        // command "Clear State Variables"
        // command "Clear Orphaned States"

        // Hubitat Driver Version
        attribute "Child Driver Version", "string"

        // Camera Standby
        attribute "Inferred Camera in Standby", "string"
        attribute "Last Camera Wake Time", "string"

        // AI Detection
        attribute "AI Motion", "string"
        attribute "Last AI Detection Type", "string"
        attribute "Last AI Detection Time", "string"

        // Push Notifications
        attribute "Reolink Push Notification Status", "string"

        // Recording control
        attribute "Recording Enabled", "string"

        // PIR control
        attribute "PIR Enabled", "string"

        // Photos
        attribute "Last Photo URL", "string"
        attribute "Saved Photo Count", "number"
        attribute "Last Saved Photo Cleanup", "string"

        // Light
        attribute "Active Light Mode", "string"
        attribute "Last Light Command Time", "string"

        // Polling Checks
        attribute "Last Poll Success", "string"
        attribute "Last Recording Poll", "string"

        // Recording monitor (Altas battery cam specific)
        attribute "Recording Active", "string"            // "true"/"false"
        attribute "Last Recording Start", "string"
        attribute "Last Recording End", "string"
        attribute "Last Recording Length", "string"
        attribute "Last Recording Name", "string"
        attribute "Last Recording Size (MB)", "number"
    }

    preferences {
        input "debugLogging", "bool",
            title: "Enable Debug Logging",
            description: "Automatically turns off after 2 hours.",
            defaultValue: false

        input "motionHoldSeconds", "number",
            title: "Motion Hold Seconds (AI)",
            description: "When motion detection stops, 'AI Motion' and 'Motion' will remain set to active for time set below, before returning to inactive. This can help with automations that need to run in a short time window.<br>NOTE: Both 'Motion' and 'AI Motion' values will be the same. In some Hubitat automations, the default 'Motion' is required to find the capability. Otherwise 'AI Motion' can be used as an actuator.<br>Range: 0..600 - Default: 10",
            defaultValue: 10, range: "0..600", required: true

        input "cameraStandbyTimeout", "number",
            title: "Camera Standby Timeout (Seconds)",
            description: "After activity (AI, light on, snapshot, burst, recording finishes), wait this long before marking the camera back as being in standby. This can help with automations that need to run in a short time window.<br>Range: 1..120 - Default: 10",
            defaultValue: 10, range: "1..120", required: true

        input "lightMode", "enum", title: "Which light on the Camera should the Switch control?",
            description: "The options are Spotlight or Status LED. On the Commands page, the On and Off switches will control this light.",
            options: ["Spotlight", "Status LED"], defaultValue: "Spotlight", required: true

        input "lightHeartbeatSeconds", "number",
            title: "Light Heartbeat (Seconds)",
            description: "Keeps the camera from going back into standby by resending the 'light on' command while the light switch is 'On'.<br>Range: 1..60 - Default: 5",
            defaultValue: 5, range: "1..60", required: true

        input "retentionDays", "number", title: "Snapshot Retention (Days)",
            description: "Maximum number of days to keep snapshots (photos) in the Hubitat File Manager local storage.<br>Range: 1..365 - Default: 30",
            defaultValue: 30, range: "1..365", required: true

        input "minFiles", "number", title: "Minimum Snapshots to always keep (Safety Floor)",
            description: "When snapshot (photo) cleanup runs, this number of snapshots will be kept in the Hubitat File Manager local storage, even if they exceeds the maximum days retention.<br>Range: 1..500 - Default: 20",
            defaultValue: 20, range: "1..500", required: true

        input "hardLimit", "number", title: "Maximum Snapshots to keep (Storage Ceiling)",
            description: "Maximum number of snapshots (photos) to be kept in the Hubitat File Manager local storage. Cleanup will remove the oldest files even if not yet retained for max retention setting. e.g: If value here is set at 200, and then a snapshot burst is taken, the value now becomes 205, then the 5 oldest photos will be deleted.<br>Range: 21-2000 - Default: 200",
            defaultValue: 200, range: "21..2000", required: true

        input "autoOffMinutes", "enum",
            title: "Light Auto-Off Safety Timeout",
            description: "If the light switch is left on, the driver will automatically turn it off after this duration. This is a safety fallback to prevent the spotlight or status LED from being left on indefinitely. Ensure this is set higher than what you may use in automations.<br>This option can also be set to 'Disabled'.<br>Default: 5 Minutes",
            options: [
                "0":"Disabled",
                "5":"5 minutes (default)",
                "10":"10 minutes",
                "20":"20 minutes",
                "30":"30 minutes",
                "60":"1 hour",
                "120":"2 hours",
                "240":"4 hours"
            ],
            defaultValue: "5", required: true

        input "recordingProbeSeconds", "enum",
            title: "Recording Probe Interval Awake (Seconds)",
            description: "How often to poll the Home Hub Pro 'Search' API while the camera is awake to detect active recordings. Lower = faster detection, higher = less load.<br>Default: 3 seconds",
            options: [
                "2":"Every 2 seconds",
                "3":"Every 3 seconds (default)",
                "5":"Every 5 seconds",
                "10":"Every 10 seconds",
                "15":"Every 15 seconds",
                "30":"Every 30 seconds"
            ],
            defaultValue: "3",
            required: true

        input "recordingProbeStandbySeconds", "enum",
            title: "Recording Probe Interval Standby (Seconds)",
            description: "How often to poll the Home Hub Pro 'Search' API while the camera is in standby to detect recordings triggered outside of this driver (e.g. by PIR motion). Higher values reduce Hub Pro load.<br>Default: 30 minutes.",
            options: [
                "60":"Every 1 minute",
                "120":"Every 2 minutes",
                "300":"Every 5 minutes",
                "600":"Every 10 minutes",
                "900":"Every 15 minutes",
                "1800":"Every 30 minutes (default)",
                "3600":"Every 1 hour"
            ],
            defaultValue: "1800",
            required: true
    }
}

def CHILD_DRIVER_VERSION() { "20261001-REOLINK-ALTAS2K-CHILD-v14" }
def dlog(msg) { if (debugLogging) log.debug msg }

// Search window for the recording probes (seconds)
def SEARCH_WINDOW_SEC() { 7200L }

// ---- Lifecycle ----
def installed() {
    log.info "${device.label ?: device.name}: Installed"
    seedBasics()
    scheduleStandbyRecordingProbe()
}

def updated() {
    log.info "${device.label ?: device.name}: Updated"
    unschedule()
    seedBasics()
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
    scheduleStandbyRecordingProbe()
}

def initialize() {
    log.info "${device.label ?: device.name}: Initialize (reboot recovery)"
    unschedule()
    seedBasics()
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
    scheduleStandbyRecordingProbe()
}

def seedBasics() {
    // Clear stub placeholder attribute if this device was previously on the stub driver
    try { device.deleteCurrentState("Stub Notice") } catch (ignored) { }

    sendEvent(name: "Child Driver Version", value: CHILD_DRIVER_VERSION(), isStateChange: true)

    // Always reset on reboot or save — never trust stale pre-reboot values
    state.recMonRunning = false
    sendEvent(name: "switch", value: "off", isStateChange: true)
    sendEvent(name: "motion", value: "inactive", isStateChange: true)
    sendEvent(name: "AI Motion", value: "inactive", isStateChange: true)
    sendEvent(name: "Inferred Camera in Standby", value: "true", isStateChange: true)
    sendEvent(name: "Recording Active", value: "false", isStateChange: true)

    // Only seed these if null — they are historical records or persistent settings, not live state
    seedIfNull("Reolink Push Notification Status", "Unknown")
    seedIfNull("Recording Enabled", "Unknown")
    seedIfNull("PIR Enabled", "Unknown")
    seedIfNull("Last AI Detection Type", "Unknown")
    seedIfNull("Last AI Detection Time", "Unknown")
    seedIfNull("Last Poll Success", "Unknown")
    seedIfNull("Last Recording Poll", "Unknown")
    seedIfNull("Last Recording Start", "Unknown")
    seedIfNull("Last Recording End", "Unknown")
    seedIfNull("Last Recording Length", "Unknown")
    seedIfNull("Last Recording Name", "Unknown")
}

def seedIfNull(String name, String val) {
    try {
        if (device.currentValue(name) == null) sendEvent(name: name, value: val, isStateChange: true)
    } catch (ignored) { }
}

// Only fires an event if the value has actually changed — prevents repeated identical events
def sendEventIfChanged(String name, String value) {
    String cur = null
    try { cur = device.currentValue(name)?.toString() } catch (ignored) { }
    if (cur != value) sendEvent(name: name, value: value, isStateChange: true)
}

def sendNumberIfChanged(String name, BigDecimal value) {
    def cur = null
    try { cur = device.currentValue(name) } catch (ignored) { }
    boolean same = false
    try { same = (cur != null && (cur as BigDecimal).compareTo(value) == 0) } catch (ignored) { }
    if (!same) sendEvent(name: name, value: value, isStateChange: true)
}

def disableDebugLogging() {
    log.info "${device.label ?: device.name}: Auto-disabling debug logging after 2 hours"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

def refresh() {
    def ch = safeInt(getDataValue("channel"), 0)
    parent.refreshChildChannel(ch)
}

// ---- Clear Current States ----
def "Clear Current States"() {
    log.info "${device.label ?: device.name}: Clear Current States started"
    int cleared = 0
    try {
        Set protect = ["DeviceWatch-DeviceStatus", "healthStatus"] as Set
        device.currentStates?.each { st ->
            String n = st?.name
            if (!n) return
            if (protect.contains(n)) return
            device.deleteCurrentState(n)
            cleared++
        }
        log.info "${device.label ?: device.name}: Clear Current States done (valuesCleared=${cleared})"
    } catch (e) {
        log.error "${device.label ?: device.name}: Clear Current States error: ${e}"
    }
    seedBasics()
}

def "Clear Orphaned States"() {
    try {
        Set supported = (device.supportedAttributes?.collect { it.name } ?: []) as Set
        Set protect = ["DeviceWatch-DeviceStatus", "healthStatus"] as Set
        int removed = 0
        device.currentStates?.each { st ->
            String n = st?.name
            if (!n) return
            if (protect.contains(n)) return
            if (!supported.contains(n)) {
                device.deleteCurrentState(n)
                removed++
            }
        }
        log.info "${device.label ?: device.name}: Cleared ${removed} orphaned currentStates"
    } catch (e) {
        log.error "${device.label ?: device.name}: Clear Orphaned States error: ${e}"
    }
    seedBasics()
}

def "Clear State Variables"() {
    log.warn "${device.label ?: device.name}: Clear State Variables requested"
    try { unschedule() } catch (ignored) { }
    try { state.clear() } catch (e) { log.warn "state.clear() failed: ${e}" }
    seedBasics()
}

// ---- Standby helpers ----
def markAwakeFromActivity(String reason = "Activity") {
    state.lastActivityTs = now()
    String t = new Date().format("HH:mm:ss dd/MM", location.timeZone)
    sendEvent(name: "Inferred Camera in Standby", value: "false", isStateChange: true)
    sendEvent(name: "Last Camera Wake Time", value: t, isStateChange: true)
    scheduleStandbyCheck()
    ensureRecordingMonitorRunning()
    dlog "${device.label ?: device.name}: Awake due to ${reason}"
}

def scheduleStandbyCheck() {
    Integer secs = safeInt(cameraStandbyTimeout, 10)
    runIn(secs, "standbyCheck", [overwrite: true])
}

def standbyCheck() {
    String rec = null
    try { rec = device.currentValue("Recording Active")?.toString() } catch (ignored) {}

    if (rec == "true") {
        if (debugLogging) dlog "${device.label ?: device.name}: Standby blocked (recording active)"
        scheduleStandbyCheck()
        return
    }

    long lastTs = safeLong(state.lastActivityTs, 0L)
    Integer secs = safeInt(cameraStandbyTimeout, 10)
    long age = now() - lastTs

    if (lastTs > 0L && age >= (secs * 1000L)) {
        sendEvent(name: "Inferred Camera in Standby", value: "true", isStateChange: true)
        dlog "${device.label ?: device.name}: Standby true"
    } else {
        scheduleStandbyCheck()
    }
}

// ---- AI update (Motion capability = AI) ----
def updateAI(isActive, typeLabel, timeStr) {
    boolean active = (isActive == true)
    String val = active ? "active" : "inactive"

    Boolean lastActive = (state.lastAiActive == true)
    boolean changed = (active != lastActive)
    state.lastAiActive = active

    // Last Poll Success — minute precision, only fires when minute changes
    String pollTs = new Date().format("HH:mm dd/MM", location.timeZone)
    sendEventIfChanged("Last Poll Success", pollTs)

    if (active) {
        String t = normalizeAiType(typeLabel)
        String ts = timeStr ?: new Date().format("HH:mm:ss dd/MM", location.timeZone)

        markAwakeFromActivity("AI")
        sendEvent(name: "Last AI Detection Type", value: t, isStateChange: true)
        sendEvent(name: "Last AI Detection Time", value: ts, isStateChange: true)

        sendEvent(name: "AI Motion", value: "active", isStateChange: true)
        sendEvent(name: "motion", value: "active", isStateChange: true)

        holdMotionActive()

        if (debugLogging) dlog "${device.label ?: device.name}: AI=true type=${t}"
        return
    }

    Long holdUntil = (state.motionHoldUntil instanceof Number) ? (state.motionHoldUntil as Long) : 0L
    if (holdUntil > now()) {
        if (debugLogging && changed) dlog "${device.label ?: device.name}: AI=false (ignored; in hold window)"
        return
    }

    sendEvent(name: "AI Motion", value: val, isStateChange: true)
    sendEvent(name: "motion", value: val, isStateChange: true)

    if (debugLogging && changed) dlog "${device.label ?: device.name}: AI=false"
}

def normalizeAiType(def t) {
    String x = (t ?: "").toString()
    if (x.equalsIgnoreCase("Person")) return "Person"
    if (x.equalsIgnoreCase("Vehicle")) return "Vehicle"
    if (x.equalsIgnoreCase("Animal")) return "Animal"
    return "Unknown"
}

def holdMotionActive() {
    Integer hold = safeInt(motionHoldSeconds, 10)
    if (hold <= 0) {
        state.motionHoldUntil = 0L
        unschedule("clearMotion")
        return
    }
    state.motionHoldUntil = now() + (hold * 1000L)
    runIn(hold, "clearMotion", [overwrite: true])
}

def clearMotion() {
    Long holdUntil = (state.motionHoldUntil instanceof Number) ? (state.motionHoldUntil as Long) : 0L
    if (holdUntil > now()) {
        int remaining = Math.max(1, ((holdUntil - now()) / 1000L) as int)
        runIn(remaining, "clearMotion", [overwrite: true])
        return
    }

    sendEvent(name: "AI Motion", value: "inactive", isStateChange: true)
    sendEvent(name: "motion", value: "inactive", isStateChange: true)
    if (debugLogging) dlog "${device.label ?: device.name}: Motion cleared after hold"
}

// ---- Push Notification Status ----
def updatePushState(isOn) {
    boolean on = (isOn == true)
    String status = on ? "On" : "Off"
    String cur = null
    try { cur = device.currentValue("Reolink Push Notification Status")?.toString() } catch (ignored) { }
    if (cur != status) {
        sendEvent(name: "Reolink Push Notification Status", value: status, isStateChange: true)
        if (debugLogging) dlog "${device.label ?: device.name}: Push -> ${status}"
    }
}

def ReolinkPushNotificationsOn() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetPush(ch, 1)
    updatePushState(true)
}

def ReolinkPushNotificationsOff() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetPush(ch, 0)
    updatePushState(false)
}

// ---- Recording Enable / Disable ----
def updateRecordingEnabled(isEnabled) {
    boolean en = (isEnabled == true)
    String status = en ? "true" : "false"
    String cur = null
    try { cur = device.currentValue("Recording Enabled")?.toString() } catch (ignored) { }
    if (cur != status) {
        sendEvent(name: "Recording Enabled", value: status, isStateChange: true)
        if (debugLogging) dlog "${device.label ?: device.name}: Recording Enabled -> ${status}"
    }
}

def ReolinkRecordingOn() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetRecording(ch, 1)   // scheduleEnable=1
    updateRecordingEnabled(true)
    log.info "${device.label ?: device.name}: Recording enabled"
}

def ReolinkRecordingOff() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetRecording(ch, 0)   // scheduleEnable=0
    updateRecordingEnabled(false)
    log.info "${device.label ?: device.name}: Recording disabled"
}

// ---- PIR Enable / Disable ----
def updatePIREnabled(isEnabled) {
    boolean en = (isEnabled == true)
    String status = en ? "true" : "false"
    String cur = null
    try { cur = device.currentValue("PIR Enabled")?.toString() } catch (ignored) { }
    if (cur != status) {
        sendEvent(name: "PIR Enabled", value: status, isStateChange: true)
        if (debugLogging) dlog "${device.label ?: device.name}: PIR Enabled -> ${status}"
    }
}

def ReolinkPIROn() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetPIR(ch, 1)
    updatePIREnabled(true)
    log.info "${device.label ?: device.name}: PIR enabled"
}

def ReolinkPIROff() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetPIR(ch, 0)
    updatePIREnabled(false)
    log.info "${device.label ?: device.name}: PIR disabled"
}

// ---- Camera Battery ----
def updateBatteryPercent(pct) {
    Integer p = safeInt(pct, -1)
    if (p < 0 || p > 100) return
    sendEvent(name: "battery", value: p, isStateChange: true)
}

// ---- Light Switch ----
def on() {
    log.info "${device.label ?: device.name} - ON triggered"
    sendEvent(name: "switch", value: "on", isStateChange: true)
    sendEvent(name: "Active Light Mode", value: (lightMode ?: "Spotlight"), isStateChange: true)

    markAwakeFromActivity("Light ON")
    setLightRuntime()

    executeLightCommand("On")

    if (safeInt(autoOffMinutes, 5) > 0) runIn(safeInt(autoOffMinutes, 5) * 60, "autoOffSafety", [overwrite: true])
    runIn(safeInt(lightHeartbeatSeconds, 5), "pulseHeartbeat", [overwrite: true])
}

def off() {
    log.info "${device.label ?: device.name} - OFF triggered"
    sendEvent(name: "switch", value: "off", isStateChange: true)
    executeLightCommand("Off")
    unschedule("pulseHeartbeat")
    unschedule("autoOffSafety")
    setLightRuntime()
}

def pulseHeartbeat() {
    if (device.currentValue("switch") == "on") {
        // Update activity timestamp and standby state without restarting the recording monitor
        state.lastActivityTs = now()
        sendEvent(name: "Inferred Camera in Standby", value: "false", isStateChange: true)
        scheduleStandbyCheck()
        setLightRuntime()
        executeLightCommand("On")
        runIn(safeInt(lightHeartbeatSeconds, 5), "pulseHeartbeat", [overwrite: true])
    }
}

def autoOffSafety() {
    log.info "${device.label ?: device.name}: Auto-off safety triggered after ${autoOffMinutes ?: 5} minutes"
    off()
}

def executeLightCommand(String cmdState) {
    def mode = lightMode ?: "Spotlight"
    def ch = getDataValue("channel")
    if (ch == null) {
        log.warn "${device.label ?: device.name}: No channel set — cannot send light command"
        return
    }

    if (mode == "Spotlight") {
        parent.childSetSpotlight(ch, (cmdState == "On" ? 1 : 0), (cmdState == "On" ? 1 : 0))
    } else {
        parent.childSetStatusLed(ch, (cmdState == "On" ? "On" : "Off"))
    }
}

def setLightRuntime() {
    def t = new Date().format("HH:mm:ss", location.timeZone)
    sendEvent(name: "Last Light Command Time", value: t, isStateChange: true)
}

// ---- Photos - Snapshot and Burst ----
def saveSinglePhotoToHubitat() { executeSingleSnap(true) }
def savePhotoBurstToHubitat() { takeBurst() }
def manualPhotoRetentionCleanup() { cleanupOldSnaps(true) }

def takeBurst() {
    log.info "${device.label ?: device.name}: Starting 5-photo burst"
    markAwakeFromActivity("Burst")
    executeSingleSnap(false)
    runIn(3, "burst2", [overwrite: true])
    runIn(6, "burst3", [overwrite: true])
    runIn(9, "burst4", [overwrite: true])
    runIn(12, "burst5", [overwrite: true])
}

def burst2() { executeSingleSnap(false) }
def burst3() { executeSingleSnap(false) }
def burst4() { executeSingleSnap(false) }
def burst5() { executeSingleSnap(true) }

def executeSingleSnap(Boolean doCleanup = true) {
    markAwakeFromActivity("Snapshot")

    def ip = parent.getIPAddress()
    def token = parent.getTokenValue()
    def ch = getDataValue("channel")

    if (!ip) { log.warn "${device.label ?: device.name}: No parent IP — cannot snapshot"; return }
    if (!token) { log.warn "${device.label ?: device.name}: No parent token — cannot snapshot"; return }
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set — cannot snapshot"; return }

    def ts = new Date().format("yyyyMMdd_HHmmss_SSS", location.timeZone)
    def fileName = "reolink_snap_ch${ch}_${ts}.jpg"
    log.info "${device.label ?: device.name}: Snapshot request -> ${fileName}"

    try {
        httpGet([
            uri: "http://${ip}/cgi-bin/api.cgi?cmd=Snap&channel=${ch}&token=${token}",
            contentType: "image/jpeg",
            timeout: 20
        ]) { resp ->
            if (resp?.status != 200) {
                log.warn "${device.label ?: device.name}: Snapshot HTTP status ${resp?.status}"
                return
            }

            byte[] imageBytes = resp.data?.bytes
            if (!imageBytes || imageBytes.length == 0) {
                log.warn "${device.label ?: device.name}: Snapshot response was empty"
                return
            }

            uploadHubFile(fileName, imageBytes)

            log.info "${device.label ?: device.name}: Uploaded ${fileName} (${imageBytes.length} bytes)"

            String hubIp = location?.hubs?.getAt(0)?.localIP
            String fullUrl = hubIp ? "http://${hubIp}/local/${fileName}" : "http://127.0.0.1/local/${fileName}"
            sendEvent(name: "Last Photo URL", value: fullUrl, isStateChange: true)

            if (doCleanup) runIn(30, "cleanupOldSnaps", [overwrite: true])
        }
    } catch (e) {
        log.error "${device.label ?: device.name}: Snapshot error: ${e.message}"
    }
}

// ---- Photos Clean-up ----
def cleanupOldSnaps(Boolean manual = false) {
    String who = manual ? "Manual" : "Auto"
    log.info "${device.label ?: device.name}: ${who} saved photo cleanup started"

    def ch = getDataValue("channel") ?: ""
    def prefix = "reolink_snap_ch${ch}_"
    long d = safeLong(retentionDays, 30L)
    int floor = safeInt(minFiles, 20)
    int ceiling = safeInt(hardLimit, 200)
    long cutoff = now() - (d * 86400000L)

    try {
        httpGet([uri: "http://127.0.0.1:8080/hub/fileManager/json", timeout: 15]) { resp ->
            if (!resp?.data?.files) {
                sendEvent(name: "Last Saved Photo Cleanup", value: "No Files", isStateChange: true)
                return
            }

            def myFiles = resp.data.files.findAll { it?.name?.startsWith(prefix) }
            myFiles.sort { it.name }

            int initialCount = myFiles.size()
            sendEvent(name: "Saved Photo Count", value: initialCount, isStateChange: true)

            if (initialCount == 0) {
                sendEvent(name: "Last Saved Photo Cleanup", value: "No Files", isStateChange: true)
                return
            }

            int deleteCount = 0
            String reason = "No Action"

            // Phase 1: Hard ceiling
            if (myFiles.size() > ceiling) {
                int overage = myFiles.size() - ceiling
                reason = "Storage Limit Reached"
                for (int i = 0; i < overage; i++) {
                    try {
                        deleteHubFile(myFiles[i].name)
                        deleteCount++
                    } catch (e) {
                        log.warn "${device.label ?: device.name}: Failed to delete ${myFiles[i]?.name}: ${e.message}"
                    }
                }
                myFiles = myFiles.drop(overage)
            }

            // Phase 2: Age-based cleanup (only above safety floor)
            int agePurgeCount = 0
            if (myFiles.size() > floor) {
                int ageLimit = myFiles.size() - floor
                for (int i = 0; i < ageLimit; i++) {
                    def f = myFiles[i]
                    if (f?.modified == null) continue

                    long fMod
                    try { fMod = (f.modified as Long) }
                    catch (e) { continue }

                    long fileTime = (fMod < 2000000000L) ? (fMod * 1000L) : fMod
                    if (fileTime < cutoff) {
                        try {
                            deleteHubFile(f.name)
                            deleteCount++
                            agePurgeCount++
                        } catch (e) {
                            log.warn "${device.label ?: device.name}: Failed to delete ${f?.name}: ${e.message}"
                        }
                    }
                }
                if (agePurgeCount > 0 && reason == "No Action") reason = "Expired (Age)"
                if (agePurgeCount > 0 && reason == "Storage Limit Reached") reason = "Limit + Expired (Age)"
            }

            int remaining = Math.max(0, initialCount - deleteCount)
            sendEvent(name: "Saved Photo Count", value: remaining, isStateChange: true)

            String summary = "Deleted=${deleteCount}, Remaining=${remaining}, Reason=${reason}"
            sendEvent(name: "Last Saved Photo Cleanup", value: summary, isStateChange: true)

            log.info "${device.label ?: device.name}: Cleanup done. ${summary}"
        }
    } catch (e) {
        log.error "${device.label ?: device.name}: Cleanup error: ${e.message}"
    }
}

// ---- Standby Recording Probe (independent of camera wake) ----
def scheduleStandbyRecordingProbe() {
    unschedule("standbyRecordingProbeLoop")
    Integer s = safeInt(recordingProbeStandbySeconds, 1800)
    runIn(s, "standbyRecordingProbeLoop", [overwrite: true])
    log.info "${device.label ?: device.name}: Standby recording probe scheduled every ${s}s"
}

def standbyRecordingProbeLoop() {
    // Reschedule first, so a stopped probe cannot end the loop
    Integer s = safeInt(recordingProbeStandbySeconds, 1800)
    runIn(s, "standbyRecordingProbeLoop", [overwrite: true])

    // Cross-check recMonRunning against the monitor window expiry to detect stuck flags
    if (state.recMonRunning == true) {
        long until = (state.recMonUntil instanceof Number) ? (state.recMonUntil as Long) : 0L
        if (until > 0L && now() > until) {
            state.recMonRunning = false
            log.info "${device.label ?: device.name}: Standby probe — cleared stuck recMonRunning flag"
        }
    }

    if (state.recMonRunning == true) {
        dlog "${device.label ?: device.name}: Standby probe skipped — awake monitor is running"
    } else {
        dlog "${device.label ?: device.name}: Standby probe running"
        probeRecordingNow(false)
    }
}

// ---- Recording Monitor (Awake) ----
def ensureRecordingMonitorRunning() {
    long until = now() + 120000L   // 2 minutes
    state.recMonUntil = until
    state.recMonRunning = true
    dlog "${device.label ?: device.name}: ensureRecordingMonitorRunning — scheduling recordingProbeLoop in 1s"
    runIn(1, "recordingProbeLoop", [overwrite: true])
}

def recordingProbeLoop() {
    long until = (state.recMonUntil instanceof Number) ? (state.recMonUntil as Long) : 0L
    if (until > 0L && now() > until) {
        state.recMonRunning = false
        log.info "${device.label ?: device.name}: recordingProbeLoop — monitor window expired, stopping"
        return
    }

    // Reschedule first, so a stopped probe cannot end the loop
    Integer s = safeInt(recordingProbeSeconds, 3)
    s = Math.max(2, Math.min(60, s))
    runIn(s, "recordingProbeLoop", [overwrite: true])

    // Recordings are stored on the hub — camera awake/standby state is irrelevant.
    probeRecordingNow(true)
}

def probeRecordingNow(boolean updateActiveState = true) {
    if (!parent.getTokenValue()) { dlog "${device.label ?: device.name}: probeRecordingNow skipped — parent token not ready"; return }
    if (parent.isHubUnavailable()) { dlog "${device.label ?: device.name}: probeRecordingNow skipped — hub unavailable"; return }
    Integer ch = safeInt(getDataValue("channel"), 0)
    long nowSec = (now() / 1000L) as long
    // Sent by the parent's Search queue - the hub answers one Search at a time
    parent.requestSearch(ch, nowSec - SEARCH_WINDOW_SEC(), nowSec, [nowSec: nowSec, update: updateActiveState])
}

// Called by the parent with the hub's reply (d0), the request context, or an error message
def searchResult(d0, ctx, errMsg) {
    long nowSec = safeLong(ctx?.nowSec, (now() / 1000L) as long)
    boolean updateActiveState = (ctx?.update != false)
    String pollTs = new Date().format("HH:mm dd/MM", location.timeZone)

    if (errMsg) {
        dlog "${device.label ?: device.name}: Search failed: ${errMsg}"
        sendEventIfChanged("Last Recording Poll", "No response (null code)")
        return
    }

    Integer code = safeIntOrNull(d0?.code)
    def v = d0?.value
    dlog "${device.label ?: device.name}: Search response code=${code}"

    if (code == null) {
        sendEventIfChanged("Last Recording Poll", "No response (null code)")
        return
    }
    if (code != 0) {
        Integer rsp = safeIntOrNull(d0?.error?.rspCode)
        sendEventIfChanged("Last Recording Poll", "API error code=${code}" + (rsp != null ? " rspCode=${rsp}" : ""))
        return
    }

    List files = extractFiles(v)
    if (!files || files.size() == 0) {
        sendEventIfChanged("Last Recording Poll", "${pollTs} — OK, no files in last 2 hours")
        if (updateActiveState) sendEventIfChanged("Recording Active", "false")
        return
    }

    Map newest = findNewestFile(files)
    if (!newest) {
        sendEventIfChanged("Last Recording Poll", "${pollTs} — Files found but newest=null")
        return
    }

    sendEventIfChanged("Last Recording Poll", "${pollTs} — OK, files=${files.size()} in last 2 hours")

    String name = newest?.name?.toString()
    Long startTs = timeObjToEpochSeconds(newest?.StartTime)
    Long endTs   = timeObjToEpochSeconds(newest?.EndTime)
    Long sizeB   = safeLongOrNull(newest?.size)

    if (name) sendEventIfChanged("Last Recording Name", name)
    if (startTs != null) sendEventIfChanged("Last Recording Start", new Date(startTs * 1000L).format("HH:mm:ss dd/MM", location.timeZone))
    if (endTs != null)   sendEventIfChanged("Last Recording End",   new Date(endTs * 1000L).format("HH:mm:ss dd/MM", location.timeZone))
    if (startTs != null && endTs != null && endTs >= startTs) sendEventIfChanged("Last Recording Length", formatDuration(endTs - startTs))
    if (sizeB != null && sizeB >= 0L) {
        BigDecimal mb = (sizeB / (1024.0G * 1024.0G))
        mb = mb.setScale(1, java.math.RoundingMode.HALF_UP)
        sendNumberIfChanged("Last Recording Size (MB)", mb)
    }

    // Active detection: growth-based window
    Long prevEndTs = safeLongOrNull(state.prevRecEndTs)
    Long prevSize  = safeLongOrNull(state.prevRecSize)

    boolean changed = false
    if (endTs != null && prevEndTs != null && endTs > prevEndTs) changed = true
    if (sizeB != null && prevSize  != null && sizeB > prevSize)  changed = true

    if (prevEndTs == null && endTs != null) { state.prevRecEndTs = endTs; changed = true }
    if (prevSize  == null && sizeB != null) { state.prevRecSize  = sizeB; changed = true }

    if (changed) state.prevRecLastChange = nowSec

    if (endTs != null) state.prevRecEndTs = endTs
    if (sizeB != null) state.prevRecSize = sizeB

    boolean active = false
    Long lc = safeLongOrNull(state.prevRecLastChange)
    if (lc != null) {
        long age = nowSec - lc
        active = (age <= 10L)   // probe interval + buffer
    }

    if (updateActiveState) sendEventIfChanged("Recording Active", (active ? "true" : "false"))
}

List extractFiles(def v) {
    try {
        def sr = v?.SearchResult ?: v?.searchResult
        def fileNode = sr?.File ?: sr?.file
        if (fileNode instanceof List) return fileNode
    } catch (ignored) { }
    return []
}

Map findNewestFile(List files) {
    long best = -1L
    Map bestFile = null
    files.each { f ->
        Long endTs = timeObjToEpochSeconds(f?.EndTime)
        Long startTs = timeObjToEpochSeconds(f?.StartTime)
        long key = (endTs != null ? endTs : (startTs != null ? startTs : -1L))
        if (key > best) {
            best = key
            bestFile = f
        }
    }
    return bestFile
}

Long timeObjToEpochSeconds(def t) {
    try {
        if (!(t instanceof Map)) return null
        Integer y = safeIntOrNull(t.year)
        Integer mo = safeIntOrNull(t.mon)
        Integer d = safeIntOrNull(t.day)
        Integer h = safeIntOrNull(t.hour)
        Integer mi = safeIntOrNull(t.min)
        Integer s = safeIntOrNull(t.sec)
        if ([y, mo, d, h, mi, s].any { it == null }) return null

        String ts = String.format("%04d-%02d-%02d %02d:%02d:%02d", y, mo, d, h, mi, s)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        df.setTimeZone(location.timeZone)
        return (df.parse(ts).time / 1000L) as Long
    } catch (ignored) {
        return null
    }
}

String formatDuration(long seconds) {
    if (seconds < 0) seconds = 0
    long h = (long)(seconds / 3600L)
    long m = (long)((seconds % 3600L) / 60L)
    long s = (long)(seconds % 60L)
    if (h > 0) return String.format("%d:%02d:%02d", h, m, s)
    return String.format("%d:%02d", m, s)
}

// ---- Utils ----
def safeInt(def v, int dflt) {
    try {
        if (v == null) return dflt
        return (v as Integer)
    } catch (ignored) {
        try { return v.toString().toInteger() } catch (ignored2) { }
    }
    return dflt
}

def safeLong(def v, long dflt) {
    try {
        if (v == null) return dflt
        return (v as Long)
    } catch (ignored) {
        try { return v.toString().toLong() } catch (ignored2) { }
    }
    return dflt
}

def safeLongOrNull(def v) {
    try { if (v == null) return null; return (v as Long) } catch (ignored) { }
    try { return v.toString().toLong() } catch (ignored2) { }
    return null
}

def safeIntOrNull(def v) {
    try { if (v == null) return null; return (v as Integer) } catch (ignored) { }
    try { return v.toString().toInteger() } catch (ignored2) { }
    return null
}