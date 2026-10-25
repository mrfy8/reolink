/**
 * Reolink Video Doorbell Child (for Home Hub Pro)
 *
 * CHILD_DRIVER_VERSION "20261001-REOLINK-DOORBELL-CHILD-v3"
 *
 *  - Battery-powered WiFi doorbell connected via Home Hub Pro
 *  - Motion sensor: Person, Vehicle, Animal, Package, Visitor (doorbell press)
 *  - Doorbell press: Doorbell Pressed attribute (true/false with configurable timeout)
 *  - Battery percentage
 *  - PIR on/off
 *  - Push Notifications on/off
 *  - Recording on/off
 *  - Doorbell light on/off (Switch capability)
 *  - Auto Reply on/off
 *  - Snapshots saved to Hubitat File Manager
 *  - Standby/wake tracking (battery camera)
 *  - To clear old states and variables during troubleshooting - Uncomment the 3 lines in the metadata
 *
 *  v3 (2026-10-01) - changes from v2
 *  - Recording Search goes through the parent's Search queue (parent.requestSearch); the
 *    Home Hub Pro answers only one Search at a time. The reply arrives in searchResult().
 *  - Search errors show the rspCode, e.g. "API error code=1 rspCode=-17".
 *  - Doorbell press poll (GetEvents) unchanged.
 *  - Requires Reolink Hub Parent v22 or later.
 *
 *  v2 (2026-10-01) - changes from v1 (hub load)
 *  - Doorbell poll and recording probes asynchronous; one doorbell poll in flight at a time.
 *  - Loops reschedule before doing their work.
 *  - Recording Search window 2 hours instead of 24.
 *  - Recording Active and Last Recording attributes only written when they change.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

// Doorbell poll in-flight markers, keyed by device id (in memory - no database writes)
@Field static ConcurrentHashMap<String, Long> DB_IN_FLIGHT = new ConcurrentHashMap<String, Long>()

metadata {
    definition(name: "Reolink Video Doorbell Child", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "MotionSensor"       // AI Motion
        capability "Refresh"
        capability "Switch"             // doorbell light on/off
        capability "Actuator"
        capability "Initialize"
        capability "Battery"

        command "saveSinglePhotoToHubitat"
        command "savePhotoBurstToHubitat"
        command "manualPhotoRetentionCleanup"
        command "ReolinkPushNotificationsOn"
        command "ReolinkPushNotificationsOff"
        command "ReolinkRecordingOn"
        command "ReolinkRecordingOff"
        command "ReolinkPIROn"
        command "ReolinkPIROff"
        command "ReolinkAutoReplyOn"
        command "ReolinkAutoReplyOff"
        // Troubleshooting - Uncomment the next 3 lines
        // command "Clear Current States"
        // command "Clear State Variables"
        // command "Clear Orphaned States"

        attribute "Child Driver Version",            "string"
        attribute "Inferred Camera in Standby",      "string"
        attribute "Last Camera Wake Time",           "string"
        attribute "AI Motion",                       "string"
        attribute "Last AI Detection Type",          "string"
        attribute "Last AI Detection Time",          "string"
        attribute "Doorbell Pressed",                "string"
        attribute "Last Doorbell Press",             "string"
        attribute "Reolink Push Notification Status","string"
        attribute "Recording Enabled",               "string"
        attribute "PIR Enabled",                     "string"
        attribute "Auto Reply",                      "string"
        attribute "Active Light Mode",               "string"
        attribute "Last Light Command Time",         "string"
        attribute "Last Photo URL",                  "string"
        attribute "Saved Photo Count",               "number"
        attribute "Last Saved Photo Cleanup",        "string"
        attribute "Last Poll Success",               "string"
        attribute "Last Recording Poll",             "string"
        attribute "Recording Active",                "string"
        attribute "Last Recording Start",            "string"
        attribute "Last Recording End",              "string"
        attribute "Last Recording Length",           "string"
        attribute "Last Recording Name",             "string"
        attribute "Last Recording Size (MB)",        "number"
    }

    preferences {
        input "debugLogging", "bool",
            title: "Enable Debug Logging",
            description: "Automatically turns off after 2 hours.",
            defaultValue: false

        input "motionHoldSeconds", "number",
            title: "Motion Hold Seconds (AI)",
            description: "How long AI Motion and Motion remain active after detection stops. Set to 0 for real-time polling.<br>Range: 0..600 - Default: 10",
            defaultValue: 10, range: "0..600", required: true

        input "doorbellPressTimeout", "number",
            title: "Doorbell Pressed Timeout (Seconds)",
            description: "How long 'Doorbell Pressed' remains true after the doorbell is pressed.<br>Range: 5..60 - Default: 30",
            defaultValue: 30, range: "5..60", required: true

        input "doorbellPollSeconds", "enum",
            title: "Doorbell Poll Interval (Seconds)",
            description: "How often to poll for doorbell press events. This runs independently of the parent poll cycle for fast detection.<br>Default: 2 seconds",
            options: [
                "1":"Every 1 second",
                "2":"Every 2 seconds (default)",
                "3":"Every 3 seconds",
                "5":"Every 5 seconds",
                "10":"Every 10 seconds"
            ],
            defaultValue: "2", required: true

        input "cameraStandbyTimeout", "number",
            title: "Camera Standby Timeout (Seconds)",
            description: "After activity, wait this long before marking the camera back as being in standby.<br>Range: 1..120 - Default: 10",
            defaultValue: 10, range: "1..120", required: true

        input "lightHeartbeatSeconds", "number",
            title: "Light Heartbeat (Seconds)",
            description: "Keeps the camera awake by resending the light on command while the switch is On.<br>Range: 1..60 - Default: 5",
            defaultValue: 5, range: "1..60", required: true

        input "autoOffMinutes", "enum",
            title: "Light Auto-Off Safety Timeout",
            description: "Automatically turns the doorbell light off after this duration.<br>Default: 5 Minutes",
            options: [
                "0":"Disabled",
                "5":"5 minutes (default)",
                "10":"10 minutes",
                "20":"20 minutes",
                "30":"30 minutes",
                "60":"1 hour"
            ],
            defaultValue: "5", required: true

        input "recordingProbeSeconds", "enum",
            title: "Recording Probe Interval Awake (Seconds)",
            description: "How often to check for active recordings while awake.<br>Default: 3 seconds",
            options: [
                "2":"Every 2 seconds",
                "3":"Every 3 seconds (default)",
                "5":"Every 5 seconds",
                "10":"Every 10 seconds",
                "15":"Every 15 seconds",
                "30":"Every 30 seconds"
            ],
            defaultValue: "3", required: true

        input "recordingProbeStandbySeconds", "enum",
            title: "Recording Probe Interval Standby",
            description: "How often to check for recordings while in standby.<br>Default: 30 minutes",
            options: [
                "60":"Every 1 minute",
                "120":"Every 2 minutes",
                "300":"Every 5 minutes",
                "600":"Every 10 minutes",
                "900":"Every 15 minutes",
                "1800":"Every 30 minutes (default)",
                "3600":"Every 1 hour"
            ],
            defaultValue: "1800", required: true

        input "retentionDays", "number",
            title: "Snapshot Retention (Days)",
            defaultValue: 30, range: "1..365", required: true

        input "minFiles", "number",
            title: "Minimum Snapshots to always keep (Safety Floor)",
            defaultValue: 20, range: "1..500", required: true

        input "hardLimit", "number",
            title: "Maximum Snapshots to keep (Storage Ceiling)",
            defaultValue: 200, range: "21..2000", required: true
    }
}

def CHILD_DRIVER_VERSION() { "20261001-REOLINK-DOORBELL-CHILD-v3" }
def dlog(msg) { if (debugLogging) log.debug msg }

// Search window for the recording probes (seconds)
def SEARCH_WINDOW_SEC() { 7200L }

// ---- Lifecycle ----
def installed() {
    log.info "${device.label ?: device.name}: Installed"
    seedBasics()
    scheduleStandbyRecordingProbe()
    runIn(5, "doorbellPollLoop", [overwrite: true])
}

def updated() {
    log.info "${device.label ?: device.name}: Updated"
    unschedule()
    seedBasics()
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
    scheduleStandbyRecordingProbe()
    runIn(5, "doorbellPollLoop", [overwrite: true])
}

def initialize() {
    log.info "${device.label ?: device.name}: Initialize"
    unschedule()
    seedBasics()
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
    scheduleStandbyRecordingProbe()
    runIn(5, "doorbellPollLoop", [overwrite: true])
}

def seedBasics() {
    try { device.deleteCurrentState("Stub Notice") } catch (ignored) { }
    try { DB_IN_FLIGHT.remove(device.id.toString()) } catch (ignored) { }
    sendEvent(name: "Child Driver Version",         value: CHILD_DRIVER_VERSION(), isStateChange: true)
    state.recMonRunning = false
    sendEvent(name: "switch",                       value: "off",      isStateChange: true)
    sendEvent(name: "motion",                       value: "inactive", isStateChange: true)
    sendEvent(name: "AI Motion",                    value: "inactive", isStateChange: true)
    sendEvent(name: "Inferred Camera in Standby",   value: "true",     isStateChange: true)
    sendEvent(name: "Recording Active",             value: "false",    isStateChange: true)
    sendEvent(name: "Active Light Mode",            value: "Status Light", isStateChange: true)
    seedIfNull("Reolink Push Notification Status",  "Unknown")
    seedIfNull("Recording Enabled",                 "Unknown")
    seedIfNull("PIR Enabled",                       "Unknown")
    seedIfNull("Auto Reply",                        "Unknown")
    seedIfNull("Last AI Detection Type",            "Unknown")
    seedIfNull("Last AI Detection Time",            "Unknown")
    sendEvent(name: "Doorbell Pressed", value: "false", isStateChange: true)
    seedIfNull("Last Doorbell Press",               "Unknown")
    seedIfNull("Last Poll Success",                 "Unknown")
    seedIfNull("Last Recording Poll",               "Unknown")
    seedIfNull("Last Recording Start",              "Unknown")
    seedIfNull("Last Recording End",                "Unknown")
    seedIfNull("Last Recording Length",             "Unknown")
    seedIfNull("Last Recording Name",               "Unknown")
}

def seedIfNull(String name, String val) {
    try { if (device.currentValue(name) == null) sendEvent(name: name, value: val, isStateChange: true) } catch (ignored) { }
}

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

// ---- Troubleshooting ----
def "Clear Current States"() {
    int cleared = 0
    try {
        Set protect = ["DeviceWatch-DeviceStatus", "healthStatus"] as Set
        device.currentStates?.each { st ->
            String n = st?.name
            if (!n || protect.contains(n)) return
            device.deleteCurrentState(n); cleared++
        }
        log.info "${device.label ?: device.name}: Cleared ${cleared} states"
    } catch (e) { log.error "${device.label ?: device.name}: ${e}" }
    seedBasics()
}

def "Clear Orphaned States"() {
    try {
        Set supported = (device.supportedAttributes?.collect { it.name } ?: []) as Set
        Set protect = ["DeviceWatch-DeviceStatus", "healthStatus"] as Set
        int removed = 0
        device.currentStates?.each { st ->
            String n = st?.name
            if (!n || protect.contains(n)) return
            if (!supported.contains(n)) { device.deleteCurrentState(n); removed++ }
        }
        log.info "${device.label ?: device.name}: Cleared ${removed} orphaned states"
    } catch (e) { log.error "${device.label ?: device.name}: ${e}" }
    seedBasics()
}

def "Clear State Variables"() {
    log.warn "${device.label ?: device.name}: Clear State Variables"
    try { unschedule() } catch (ignored) { }
    try { state.clear() } catch (e) { log.warn "state.clear() failed: ${e}" }
    seedBasics()
}

// ---- Standby helpers ----
def markAwakeFromActivity(String reason = "Activity") {
    state.lastActivityTs = now()
    String t = new Date().format("HH:mm:ss dd/MM", location.timeZone)
    sendEvent(name: "Inferred Camera in Standby", value: "false", isStateChange: true)
    sendEvent(name: "Last Camera Wake Time",       value: t,       isStateChange: true)
    scheduleStandbyCheck()
    ensureRecordingMonitorRunning()
    dlog "${device.label ?: device.name}: Awake — ${reason}"
}

def scheduleStandbyCheck() {
    Integer secs = safeInt(cameraStandbyTimeout, 10)
    runIn(secs, "standbyCheck", [overwrite: true])
}

def standbyCheck() {
    String rec = null
    try { rec = device.currentValue("Recording Active")?.toString() } catch (ignored) { }
    if (rec == "true") { scheduleStandbyCheck(); return }
    long lastTs = safeLong(state.lastActivityTs, 0L)
    Integer secs = safeInt(cameraStandbyTimeout, 10)
    if (lastTs > 0L && (now() - lastTs) >= (secs * 1000L)) {
        sendEvent(name: "Inferred Camera in Standby", value: "true", isStateChange: true)
        dlog "${device.label ?: device.name}: Standby true"
    } else {
        scheduleStandbyCheck()
    }
}

// ---- AI Motion + Visitor (doorbell press) ----
def updateAI(isActive, typeLabel, timeStr) {
    boolean active = (isActive == true)
    Boolean lastActive = (state.lastAiActive == true)
    boolean changed = (active != lastActive)
    state.lastAiActive = active

    String pollTs = new Date().format("HH:mm dd/MM", location.timeZone)
    sendEventIfChanged("Last Poll Success", pollTs)

    if (active) {
        String t  = normalizeAiType(typeLabel)
        String ts = timeStr ?: new Date().format("HH:mm:ss dd/MM", location.timeZone)
        markAwakeFromActivity("AI")
        sendEvent(name: "Last AI Detection Type", value: t,  isStateChange: true)
        sendEvent(name: "Last AI Detection Time", value: ts, isStateChange: true)
        sendEvent(name: "AI Motion", value: "active", isStateChange: true)
        sendEvent(name: "motion",    value: "active", isStateChange: true)
        holdMotionActive()

        // Doorbell press handled by dedicated doorbellPollLoop — visitor type just marks AI active
        dlog "${device.label ?: device.name}: AI=true type=${t}"
        return
    }

    Long holdUntil = (state.motionHoldUntil instanceof Number) ? (state.motionHoldUntil as Long) : 0L
    if (holdUntil > now()) { return }
    sendEvent(name: "AI Motion", value: "inactive", isStateChange: true)
    sendEvent(name: "motion",    value: "inactive", isStateChange: true)
    if (debugLogging && changed) dlog "${device.label ?: device.name}: AI=false"
}

def normalizeAiType(def t) {
    String x = (t ?: "").toString()
    if (x.equalsIgnoreCase("Person"))  return "Person"
    if (x.equalsIgnoreCase("Vehicle")) return "Vehicle"
    if (x.equalsIgnoreCase("Animal"))  return "Animal"
    if (x.equalsIgnoreCase("Package")) return "Package"
    if (x.equalsIgnoreCase("Visitor")) return "Visitor"
    return "Unknown"
}

def holdMotionActive() {
    Integer hold = safeInt(motionHoldSeconds, 10)
    if (hold <= 0) { state.motionHoldUntil = 0L; unschedule("clearMotion"); return }
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
    sendEvent(name: "motion",    value: "inactive", isStateChange: true)
    dlog "${device.label ?: device.name}: Motion cleared after hold"
}

// ---- Push Notifications ----
def updatePushState(isOn) {
    boolean on = (isOn == true)
    String status = on ? "On" : "Off"
    String cur = null
    try { cur = device.currentValue("Reolink Push Notification Status")?.toString() } catch (ignored) { }
    if (cur != status) {
        sendEvent(name: "Reolink Push Notification Status", value: status, isStateChange: true)
        dlog "${device.label ?: device.name}: Push -> ${status}"
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

// ---- Recording ----
def updateRecordingEnabled(isEnabled) {
    boolean en = (isEnabled == true)
    String status = en ? "true" : "false"
    String cur = null
    try { cur = device.currentValue("Recording Enabled")?.toString() } catch (ignored) { }
    if (cur != status) {
        sendEvent(name: "Recording Enabled", value: status, isStateChange: true)
        dlog "${device.label ?: device.name}: Recording Enabled -> ${status}"
    }
}

def ReolinkRecordingOn() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetRecording(ch, 1)
    updateRecordingEnabled(true)
}

def ReolinkRecordingOff() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetRecording(ch, 0)
    updateRecordingEnabled(false)
}

// ---- PIR ----
def updatePIREnabled(isEnabled) {
    boolean en = (isEnabled == true)
    String status = en ? "true" : "false"
    String cur = null
    try { cur = device.currentValue("PIR Enabled")?.toString() } catch (ignored) { }
    if (cur != status) {
        sendEvent(name: "PIR Enabled", value: status, isStateChange: true)
        dlog "${device.label ?: device.name}: PIR Enabled -> ${status}"
    }
}

def ReolinkPIROn() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetPIR(ch, 1)
    updatePIREnabled(true)
}

def ReolinkPIROff() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetPIR(ch, 0)
    updatePIREnabled(false)
}

// ---- Battery ----
def updateBatteryPercent(pct) {
    Integer p = safeInt(pct, -1)
    if (p < 0 || p > 100) return
    sendEvent(name: "battery", value: p, isStateChange: true)
}

// ---- Auto Reply ----
def ReolinkAutoReplyOn()  { setAutoReply(1) }
def ReolinkAutoReplyOff() { setAutoReply(0) }

def setAutoReply(int val) {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetAutoReply(ch, val)
    String status = val == 1 ? "On" : "Off"
    sendEvent(name: "Auto Reply", value: status, isStateChange: true)
    log.info "${device.label ?: device.name}: Auto Reply -> ${status}"
}

def updateAutoReply(isEnabled) {
    String status = (isEnabled == true) ? "On" : "Off"
    String cur = null
    try { cur = device.currentValue("Auto Reply")?.toString() } catch (ignored) { }
    if (cur != status) sendEvent(name: "Auto Reply", value: status, isStateChange: true)
}

// ---- Light Switch (doorbell light) ----
def on() {
    log.info "${device.label ?: device.name} - ON"
    sendEvent(name: "switch", value: "on", isStateChange: true)
    markAwakeFromActivity("Light ON")
    setLightRuntime()
    executeLightCommand("On")
    if (safeInt(autoOffMinutes, 5) > 0) runIn(safeInt(autoOffMinutes, 5) * 60, "autoOffSafety", [overwrite: true])
    runIn(safeInt(lightHeartbeatSeconds, 5), "pulseHeartbeat", [overwrite: true])
}

def off() {
    log.info "${device.label ?: device.name} - OFF"
    sendEvent(name: "switch", value: "off", isStateChange: true)
    executeLightCommand("Off")
    unschedule("pulseHeartbeat")
    unschedule("autoOffSafety")
    setLightRuntime()
}

def pulseHeartbeat() {
    if (device.currentValue("switch") == "on") {
        state.lastActivityTs = now()
        sendEvent(name: "Inferred Camera in Standby", value: "false", isStateChange: true)
        scheduleStandbyCheck()
        setLightRuntime()
        executeLightCommand("On")
        runIn(safeInt(lightHeartbeatSeconds, 5), "pulseHeartbeat", [overwrite: true])
    }
}

def autoOffSafety() {
    log.info "${device.label ?: device.name}: Auto-off safety triggered"
    off()
}

def executeLightCommand(String cmdState) {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    // Doorbell status light uses SetPowerLed — confirmed working via hub HTTP API
    parent.childSetStatusLed(ch, cmdState == "On" ? "On" : "Off")
}

def setLightRuntime() {
    sendEvent(name: "Last Light Command Time", value: new Date().format("HH:mm:ss", location.timeZone), isStateChange: true)
}

// ---- Photos ----
def saveSinglePhotoToHubitat()    { executeSingleSnap(true) }
def savePhotoBurstToHubitat()     { takeBurst() }
def manualPhotoRetentionCleanup() { cleanupOldSnaps(true) }

def takeBurst() {
    log.info "${device.label ?: device.name}: Starting 5-photo burst"
    markAwakeFromActivity("Burst")
    executeSingleSnap(false)
    runIn(3,  "burst2", [overwrite: true])
    runIn(6,  "burst3", [overwrite: true])
    runIn(9,  "burst4", [overwrite: true])
    runIn(12, "burst5", [overwrite: true])
}

def burst2() { executeSingleSnap(false) }
def burst3() { executeSingleSnap(false) }
def burst4() { executeSingleSnap(false) }
def burst5() { executeSingleSnap(true) }

def executeSingleSnap(Boolean doCleanup = true) {
    markAwakeFromActivity("Snapshot")
    def ip    = parent.getIPAddress()
    def token = parent.getTokenValue()
    def ch    = getDataValue("channel")
    if (!ip)    { log.warn "${device.label ?: device.name}: No parent IP"; return }
    if (!token) { log.warn "${device.label ?: device.name}: No parent token"; return }
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    def ts       = new Date().format("yyyyMMdd_HHmmss_SSS", location.timeZone)
    def fileName = "reolink_snap_ch${ch}_${ts}.jpg"
    log.info "${device.label ?: device.name}: Snapshot -> ${fileName}"
    try {
        httpGet([uri: "http://${ip}/cgi-bin/api.cgi?cmd=Snap&channel=${ch}&token=${token}", contentType: "image/jpeg", timeout: 20]) { resp ->
            if (resp?.status != 200) { log.warn "${device.label ?: device.name}: Snapshot HTTP ${resp?.status}"; return }
            byte[] imageBytes = resp.data?.bytes
            if (!imageBytes || imageBytes.length == 0) { log.warn "${device.label ?: device.name}: Snapshot empty"; return }
            uploadHubFile(fileName, imageBytes)
            log.info "${device.label ?: device.name}: Uploaded ${fileName} (${imageBytes.length} bytes)"
            String hubIp = location?.hubs?.getAt(0)?.localIP
            sendEvent(name: "Last Photo URL", value: hubIp ? "http://${hubIp}/local/${fileName}" : "http://127.0.0.1/local/${fileName}", isStateChange: true)
            if (doCleanup) runIn(30, "cleanupOldSnaps", [overwrite: true])
        }
    } catch (e) { log.error "${device.label ?: device.name}: Snapshot error: ${e.message}" }
}

def cleanupOldSnaps(Boolean manual = false) {
    log.info "${device.label ?: device.name}: ${manual ? 'Manual' : 'Auto'} cleanup"
    def ch      = getDataValue("channel") ?: ""
    def prefix  = "reolink_snap_ch${ch}_"
    long d      = safeLong(retentionDays, 30L)
    int  floor  = safeInt(minFiles, 20)
    int  ceiling = safeInt(hardLimit, 200)
    long cutoff = now() - (d * 86400000L)
    try {
        httpGet([uri: "http://127.0.0.1:8080/hub/fileManager/json", timeout: 15]) { resp ->
            if (!resp?.data?.files) { sendEvent(name: "Last Saved Photo Cleanup", value: "No Files", isStateChange: true); return }
            def myFiles = resp.data.files.findAll { it?.name?.startsWith(prefix) }
            myFiles.sort { it.name }
            int initialCount = myFiles.size()
            sendEvent(name: "Saved Photo Count", value: initialCount, isStateChange: true)
            if (initialCount == 0) { sendEvent(name: "Last Saved Photo Cleanup", value: "No Files", isStateChange: true); return }
            int deleteCount = 0; String reason = "No Action"
            if (myFiles.size() > ceiling) {
                int overage = myFiles.size() - ceiling; reason = "Storage Limit Reached"
                for (int i = 0; i < overage; i++) { try { deleteHubFile(myFiles[i].name); deleteCount++ } catch (e) { } }
                myFiles = myFiles.drop(overage)
            }
            int agePurgeCount = 0
            if (myFiles.size() > floor) {
                int ageLimit = myFiles.size() - floor
                for (int i = 0; i < ageLimit; i++) {
                    def f = myFiles[i]; if (f?.modified == null) continue
                    long fMod; try { fMod = (f.modified as Long) } catch (e) { continue }
                    long fileTime = (fMod < 2000000000L) ? (fMod * 1000L) : fMod
                    if (fileTime < cutoff) { try { deleteHubFile(f.name); deleteCount++; agePurgeCount++ } catch (e) { } }
                }
                if (agePurgeCount > 0 && reason == "No Action") reason = "Expired (Age)"
                if (agePurgeCount > 0 && reason == "Storage Limit Reached") reason = "Limit + Expired (Age)"
            }
            int remaining = Math.max(0, initialCount - deleteCount)
            sendEvent(name: "Saved Photo Count",        value: remaining, isStateChange: true)
            sendEvent(name: "Last Saved Photo Cleanup", value: "Deleted=${deleteCount}, Remaining=${remaining}, Reason=${reason}", isStateChange: true)
            log.info "${device.label ?: device.name}: Cleanup done. Deleted=${deleteCount} Remaining=${remaining}"
        }
    } catch (e) { log.error "${device.label ?: device.name}: Cleanup error: ${e.message}" }
}

// ---- Standby Recording Probe ----
def scheduleStandbyRecordingProbe() {
    unschedule("standbyRecordingProbeLoop")
    Integer s = safeInt(recordingProbeStandbySeconds, 1800)
    runIn(s, "standbyRecordingProbeLoop", [overwrite: true])
    log.info "${device.label ?: device.name}: Standby recording probe every ${s}s"
}

def standbyRecordingProbeLoop() {
    // Reschedule first, so a stopped probe cannot end the loop
    Integer s = safeInt(recordingProbeStandbySeconds, 1800)
    runIn(s, "standbyRecordingProbeLoop", [overwrite: true])

    if (state.recMonRunning == true) {
        long until = (state.recMonUntil instanceof Number) ? (state.recMonUntil as Long) : 0L
        if (until > 0L && now() > until) { state.recMonRunning = false }
    }
    if (state.recMonRunning != true) probeRecordingNow(false)
}

// ---- Recording Monitor (Awake) ----
def ensureRecordingMonitorRunning() {
    long until = now() + 120000L
    state.recMonUntil = until
    state.recMonRunning = true
    runIn(1, "recordingProbeLoop", [overwrite: true])
}

def recordingProbeLoop() {
    long until = (state.recMonUntil instanceof Number) ? (state.recMonUntil as Long) : 0L
    if (until > 0L && now() > until) { state.recMonRunning = false; return }

    // Reschedule first, so a stopped probe cannot end the loop
    Integer s = safeInt(recordingProbeSeconds, 3)
    s = Math.max(2, Math.min(60, s))
    runIn(s, "recordingProbeLoop", [overwrite: true])

    probeRecordingNow(true)
}

def probeRecordingNow(boolean updateActiveState = true) {
    if (!parent.getTokenValue() || parent.isHubUnavailable()) return
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
        sendEventIfChanged("Last Recording Poll", "No response")
        return
    }

    Integer code = safeIntOrNull(d0?.code)
    def v = d0?.value
    if (code == null) { sendEventIfChanged("Last Recording Poll", "No response"); return }
    if (code != 0) {
        Integer rsp = safeIntOrNull(d0?.error?.rspCode)
        sendEventIfChanged("Last Recording Poll", "API error code=${code}" + (rsp != null ? " rspCode=${rsp}" : ""))
        return
    }

    List files = extractFiles(v)
    if (!files || files.size() == 0) {
        sendEventIfChanged("Last Recording Poll", "${pollTs} — OK, no files in last 2h")
        if (updateActiveState) sendEventIfChanged("Recording Active", "false")
        return
    }
    Map newest = findNewestFile(files)
    if (!newest) { sendEventIfChanged("Last Recording Poll", "${pollTs} — Files found but newest=null"); return }
    sendEventIfChanged("Last Recording Poll", "${pollTs} — OK, files=${files.size()} in last 2h")

    String name  = newest?.name?.toString()
    Long startTs = timeObjToEpochSeconds(newest?.StartTime)
    Long endTs   = timeObjToEpochSeconds(newest?.EndTime)
    Long sizeB   = safeLongOrNull(newest?.size)
    if (name) sendEventIfChanged("Last Recording Name", name)
    if (startTs != null) sendEventIfChanged("Last Recording Start", new Date(startTs * 1000L).format("HH:mm:ss dd/MM", location.timeZone))
    if (endTs   != null) sendEventIfChanged("Last Recording End",   new Date(endTs * 1000L).format("HH:mm:ss dd/MM", location.timeZone))
    if (startTs != null && endTs != null && endTs >= startTs) sendEventIfChanged("Last Recording Length", formatDuration(endTs - startTs))
    if (sizeB   != null && sizeB >= 0L) {
        BigDecimal mb = (sizeB / (1024.0G * 1024.0G)).setScale(1, java.math.RoundingMode.HALF_UP)
        sendNumberIfChanged("Last Recording Size (MB)", mb)
    }

    Long prevEndTs = safeLongOrNull(state.prevRecEndTs)
    Long prevSize  = safeLongOrNull(state.prevRecSize)
    boolean changed = false
    if (endTs != null && prevEndTs != null && endTs > prevEndTs) changed = true
    if (sizeB != null && prevSize  != null && sizeB > prevSize)  changed = true
    if (prevEndTs == null && endTs != null) { state.prevRecEndTs = endTs; changed = true }
    if (prevSize  == null && sizeB != null) { state.prevRecSize  = sizeB; changed = true }
    if (changed) state.prevRecLastChange = nowSec
    if (endTs != null) state.prevRecEndTs = endTs
    if (sizeB != null) state.prevRecSize  = sizeB
    boolean active = false
    Long lc = safeLongOrNull(state.prevRecLastChange)
    if (lc != null) active = ((nowSec - lc) <= 10L)
    if (updateActiveState) sendEventIfChanged("Recording Active", (active ? "true" : "false"))
}

def parseFirst(resp) {
    def j = null
    try { j = resp.getJson() } catch (ignored) { }
    if (j == null) {
        try { j = new JsonSlurper().parseText(resp.getData() as String) } catch (ignored) { }
    }
    return (j instanceof List && !j.isEmpty()) ? j[0] : null
}

def isAuthTokenError(def x) {
    Integer code = null
    Integer rsp  = null
    if (x instanceof Map) {
        try { code = x?.code as Integer }           catch (ignored) { }
        try { rsp  = x?.error?.rspCode as Integer } catch (ignored) { }
    }
    def auth = [-5, -6, -7]
    return (code in auth) || (rsp in auth)
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
    long best = -1L; Map bestFile = null
    files.each { f ->
        Long endTs   = timeObjToEpochSeconds(f?.EndTime)
        Long startTs = timeObjToEpochSeconds(f?.StartTime)
        long key = (endTs != null ? endTs : (startTs != null ? startTs : -1L))
        if (key > best) { best = key; bestFile = f }
    }
    return bestFile
}

// ---- Time helpers ----
Long timeObjToEpochSeconds(def t) {
    try {
        if (!(t instanceof Map)) return null
        Integer y = safeIntOrNull(t.year), mo = safeIntOrNull(t.mon), d = safeIntOrNull(t.day)
        Integer h = safeIntOrNull(t.hour), mi = safeIntOrNull(t.min), s = safeIntOrNull(t.sec)
        if ([y, mo, d, h, mi, s].any { it == null }) return null
        String ts = String.format("%04d-%02d-%02d %02d:%02d:%02d", y, mo, d, h, mi, s)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); df.setTimeZone(location.timeZone)
        return (df.parse(ts).time / 1000L) as Long
    } catch (ignored) { return null }
}

String formatDuration(long seconds) {
    if (seconds < 0) seconds = 0
    long h = (long)(seconds / 3600L), m = (long)((seconds % 3600L) / 60L), s = (long)(seconds % 60L)
    return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s)
}

// ---- Doorbell Poll Loop (independent fast poll for visitor event) ----
def doorbellPollLoop() {
    if (!parent.getTokenValue()) {
        dlog "${device.label ?: device.name}: doorbellPollLoop — no token, retrying in 10s"
        runIn(10, "doorbellPollLoop", [overwrite: true])
        return
    }
    if (parent.isHubUnavailable()) {
        dlog "${device.label ?: device.name}: doorbellPollLoop — hub unavailable, retrying in 30s"
        runIn(30, "doorbellPollLoop", [overwrite: true])
        return
    }
    // Reschedule first, so a stopped poll cannot end the loop
    Integer s = safeInt(doorbellPollSeconds, 2)
    s = Math.max(1, Math.min(10, s))
    runIn(s, "doorbellPollLoop", [overwrite: true])

    pollDoorbellNow()
}

def pollDoorbellNow() {
    String key = device.id.toString()
    Long sent = DB_IN_FLIGHT.get(key)
    if (sent != null && (now() - sent) < 12000L) return   // previous request still in flight

    def ip  = parent.getIPAddress()
    def tok = parent.getTokenValue()
    if (!ip || !tok) return

    Integer ch = safeInt(getDataValue("channel"), 0)
    long end   = (now() / 1000L) as long
    long start = end - 10L   // look back 10 seconds — just need recent events

    Map params = [
        uri               : "http://${ip}/cgi-bin/api.cgi?cmd=GetEvents&token=${tok}".toString(),
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : JsonOutput.toJson([[cmd: "GetEvents", action: 0, param: [channel: ch, startTime: start, endTime: end, num: 5]]]),
        timeout           : 10
    ]
    DB_IN_FLIGHT.put(key, now())
    try {
        asynchttpPost("doorbellEventsHandler", params, [:])
    } catch (e) {
        DB_IN_FLIGHT.remove(key)
        dlog "${device.label ?: device.name}: GetEvents not sent: ${e.message}"
    }
}

def doorbellEventsHandler(resp, data) {
    DB_IN_FLIGHT.remove(device.id.toString())

    if (resp.hasError()) {
        String msg = null
        try { msg = resp.getErrorMessage() } catch (ignored) { }
        if (!msg) msg = "HTTP ${resp.status}"
        dlog "${device.label ?: device.name}: pollDoorbellNow error: ${msg}"
        try { parent.hubError(msg) } catch (ignored) { }
        return
    }

    def d0 = parseFirst(resp)
    if (isAuthTokenError(d0)) {
        try { parent.tokenRejected("GetEvents doorbell") } catch (ignored) { }
        return
    }
    if (d0?.code != 0) return

    def v = d0?.value
    Integer visitor = safeIntOrNull(v?.visitor?.alarm_state)
    if (visitor == 1) {
        String cur = null
        try { cur = device.currentValue("Doorbell Pressed")?.toString() } catch (ignored) { }
        if (cur != "true") {
            // Only fire on transition to true — not on every poll while held
            String ts = new Date().format("HH:mm:ss dd/MM", location.timeZone)
            sendEvent(name: "Doorbell Pressed",    value: "true", isStateChange: true)
            sendEvent(name: "Last Doorbell Press", value: ts,     isStateChange: true)
            log.info "${device.label ?: device.name}: Doorbell pressed at ${ts}"
            markAwakeFromActivity("Doorbell")
        }
        Integer timeout = safeInt(doorbellPressTimeout, 30)
        runIn(timeout, "clearDoorbellPressed", [overwrite: true])
    }
}

// ---- Doorbell Pressed clear ----
def clearDoorbellPressed() {
    sendEvent(name: "Doorbell Pressed", value: "false", isStateChange: true)
    dlog "${device.label ?: device.name}: Doorbell Pressed cleared"
}

// ---- Utils ----
def safeInt(def v, int dflt) {
    try { if (v == null) return dflt; return (v as Integer) } catch (ignored) { }
    try { return v.toString().toInteger() } catch (ignored2) { }
    return dflt
}

def safeLong(def v, long dflt) {
    try { if (v == null) return dflt; return (v as Long) } catch (ignored) { }
    try { return v.toString().toLong() } catch (ignored2) { }
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