/**
 * Reolink CX-820 Camera Child (for Home Hub Pro)
 *
 * CHILD_DRIVER_VERSION "20261001-REOLINK-CX820-CHILD-v8"
 *
 *  - PoE wired camera — always powered, no battery, no PIR
 *  - AI Motion detection: Person, Vehicle, Animal
 *  - Spotlight on/off (Switch capability)
 *  - Push Notifications on/off
 *  - Recording on/off (fixed in Hub firmware v3.3.0.466_26040849)
 *  - Recording Active detection via Search API
 *  - Snapshots saved to Hubitat File Manager
 *
 *  v8 (2026-10-01) - changes from v7
 *  - Recording Search goes through the parent's Search queue (parent.requestSearch); the
 *    Home Hub Pro answers only one Search at a time. The reply arrives in searchResult().
 *  - Search errors show the rspCode, e.g. "API error code=1 rspCode=-17".
 *  - Requires Reolink Hub Parent v22 or later.
 *
 *  v7 (2026-10-01) - changes from v6 (hub load)
 *  - Recording probe asynchronous; recordingProbeLoop reschedules before probing.
 *  - Search window 2 hours instead of 24.
 *  - Recording Active and Last Recording attributes only written when they change;
 *    Last Recording Poll uses minute precision.
 */

metadata {
    definition(name: "Reolink CX-820 Camera Child", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "MotionSensor"
        capability "Refresh"
        capability "Switch"         // light control
        capability "Actuator"
        capability "Initialize"

        command "saveSinglePhotoToHubitat"
        command "savePhotoBurstToHubitat"
        command "manualPhotoRetentionCleanup"
        command "ReolinkPushNotificationsOn"
        command "ReolinkPushNotificationsOff"
        command "ReolinkRecordingOn"
        command "ReolinkRecordingOff"
        // Troubleshooting - Uncomment the next 3 lines
        // command "Clear Current States"
        // command "Clear State Variables"
        // command "Clear Orphaned States"

        attribute "Child Driver Version",            "string"
        attribute "AI Motion",                       "string"
        attribute "Last AI Detection Type",          "string"
        attribute "Last AI Detection Time",          "string"
        attribute "Reolink Push Notification Status","string"
        attribute "Recording Enabled",               "string"
        attribute "Recording Active",                "string"
        attribute "Last Recording Start",            "string"
        attribute "Last Recording End",              "string"
        attribute "Last Recording Length",           "string"
        attribute "Last Recording Name",             "string"
        attribute "Last Recording Size (MB)",        "number"
        attribute "Last Photo URL",                  "string"
        attribute "Saved Photo Count",               "number"
        attribute "Last Saved Photo Cleanup",        "string"
        attribute "Active Light Mode",               "string"
        attribute "Last Light Command Time",         "string"
        attribute "Last Poll Success",               "string"
        attribute "Last Recording Poll",             "string"
    }

    preferences {
        input "debugLogging", "bool",
            title: "Enable Debug Logging",
            description: "Automatically turns off after 2 hours.",
            defaultValue: false

        input "motionHoldSeconds", "number",
            title: "Motion Hold Seconds (AI)",
            description: "How long AI Motion and Motion remain active after detection stops. Set to 0 for real-time polling.<br>Range: 0..600 - Default: 0",
            defaultValue: 0, range: "0..600", required: true

        input "autoOffMinutes", "enum",
            title: "Spotlight Auto-Off Timeout",
            description: "Automatically turns the spotlight off after this duration.<br>Default: Disabled",
            options: [
                "0":"Disabled (default)",
                "5":"5 minutes",
                "10":"10 minutes",
                "20":"20 minutes",
                "30":"30 minutes",
                "60":"1 hour",
                "120":"2 hours",
                "240":"4 hours"
            ],
            defaultValue: "0", required: true

        input "recordingProbeSeconds", "enum",
            title: "Recording Probe Interval (Seconds)",
            description: "How often to check for active recordings. Detects Recording Active state and updates Last Recording info.<br>Default: 30 seconds",
            options: [
                "5":"Every 5 seconds",
                "10":"Every 10 seconds",
                "15":"Every 15 seconds",
                "30":"Every 30 seconds (default)",
                "60":"Every 1 minute",
                "120":"Every 2 minutes"
            ],
            defaultValue: "30", required: true

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

def CHILD_DRIVER_VERSION() { "20261001-REOLINK-CX820-CHILD-v8" }
def dlog(msg) { if (debugLogging) log.debug msg }

// Search window for the recording probe (seconds) - recordings are ~1 h files
def SEARCH_WINDOW_SEC() { 7200L }

// ---- Lifecycle ----
def installed() {
    log.info "${device.label ?: device.name}: Installed"
    seedBasics()
    runIn(5, "ensureRecordingMonitorRunning")
}

def updated() {
    log.info "${device.label ?: device.name}: Updated"
    unschedule()
    seedBasics()
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
    runIn(5, "ensureRecordingMonitorRunning")
}

def initialize() {
    log.info "${device.label ?: device.name}: Initialize"
    unschedule()
    seedBasics()
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
    runIn(5, "ensureRecordingMonitorRunning")
}

def seedBasics() {
    try { device.deleteCurrentState("Stub Notice") } catch (ignored) { }

    sendEvent(name: "Child Driver Version", value: CHILD_DRIVER_VERSION(), isStateChange: true)
    state.recMonRunning = false
    sendEvent(name: "switch",            value: "off",      isStateChange: true)
    sendEvent(name: "motion",            value: "inactive", isStateChange: true)
    sendEvent(name: "AI Motion",         value: "inactive", isStateChange: true)
    sendEvent(name: "Recording Active",  value: "false",    isStateChange: true)
    sendEvent(name: "Active Light Mode", value: "Spotlight", isStateChange: true)
    seedIfNull("Reolink Push Notification Status", "Unknown")
    seedIfNull("Recording Enabled",      "Unknown")
    seedIfNull("Last AI Detection Type", "Unknown")
    seedIfNull("Last AI Detection Time", "Unknown")
    seedIfNull("Last Poll Success",      "Unknown")
    seedIfNull("Last Recording Poll",    "Unknown")
    seedIfNull("Last Recording Start",   "Unknown")
    seedIfNull("Last Recording End",     "Unknown")
    seedIfNull("Last Recording Length",  "Unknown")
    seedIfNull("Last Recording Name",    "Unknown")
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
    log.info "${device.label ?: device.name}: Auto-disabling debug logging"
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

// ---- AI Motion ----
def updateAI(isActive, typeLabel, timeStr) {
    boolean active = (isActive == true)
    String val = active ? "active" : "inactive"
    Boolean lastActive = (state.lastAiActive == true)
    boolean changed = (active != lastActive)
    state.lastAiActive = active

    String pollTs = new Date().format("HH:mm dd/MM", location.timeZone)
    sendEventIfChanged("Last Poll Success", pollTs)

    if (active) {
        String t  = normalizeAiType(typeLabel)
        String ts = timeStr ?: new Date().format("HH:mm:ss dd/MM", location.timeZone)
        sendEvent(name: "Last AI Detection Type", value: t,  isStateChange: true)
        sendEvent(name: "Last AI Detection Time", value: ts, isStateChange: true)
        sendEvent(name: "AI Motion", value: "active", isStateChange: true)
        sendEvent(name: "motion",    value: "active", isStateChange: true)
        holdMotionActive()
        if (debugLogging) dlog "${device.label ?: device.name}: AI=true type=${t}"
        return
    }

    Long holdUntil = (state.motionHoldUntil instanceof Number) ? (state.motionHoldUntil as Long) : 0L
    if (holdUntil > now()) {
        if (debugLogging && changed) dlog "${device.label ?: device.name}: AI=false (in hold window)"
        return
    }
    sendEvent(name: "AI Motion", value: val, isStateChange: true)
    sendEvent(name: "motion",    value: val, isStateChange: true)
    if (debugLogging && changed) dlog "${device.label ?: device.name}: AI=false"
}

def normalizeAiType(def t) {
    String x = (t ?: "").toString()
    if (x.equalsIgnoreCase("Person"))  return "Person"
    if (x.equalsIgnoreCase("Vehicle")) return "Vehicle"
    if (x.equalsIgnoreCase("Animal"))  return "Animal"
    return "Unknown"
}

def holdMotionActive() {
    Integer hold = safeInt(motionHoldSeconds, 0)
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
    if (debugLogging) dlog "${device.label ?: device.name}: Motion cleared after hold"
}

// ---- Push Notifications ----
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

// ---- PIR — NOT SUPPORTED for PoE cameras (no PIR hardware) ----
def updatePIREnabled(isEnabled) {
    dlog "${device.label ?: device.name}: updatePIREnabled ignored — no PIR on PoE cameras"
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
    parent.childSetRecording(ch, 1)
    updateRecordingEnabled(true)
    log.info "${device.label ?: device.name}: Recording enabled"
}

def ReolinkRecordingOff() {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetRecording(ch, 0)
    updateRecordingEnabled(false)
    log.info "${device.label ?: device.name}: Recording disabled"
}

// ---- Recording Monitor ----
// CX-820 is always on — run recording probe continuously, no standby probe needed
def ensureRecordingMonitorRunning() {
    state.recMonRunning = true
    dlog "${device.label ?: device.name}: Starting recording monitor"
    runIn(1, "recordingProbeLoop", [overwrite: true])
}

def recordingProbeLoop() {
    if (!parent.getTokenValue()) {
        dlog "${device.label ?: device.name}: recordingProbeLoop — no token, retrying in 10s"
        runIn(10, "recordingProbeLoop", [overwrite: true])
        return
    }
    if (parent.isHubUnavailable()) {
        dlog "${device.label ?: device.name}: recordingProbeLoop — hub unavailable, retrying in 30s"
        runIn(30, "recordingProbeLoop", [overwrite: true])
        return
    }

    // Reschedule first, so a stopped probe cannot end the loop
    Integer s = safeInt(recordingProbeSeconds, 30)
    s = Math.max(3, Math.min(120, s))
    runIn(s, "recordingProbeLoop", [overwrite: true])

    probeRecordingNow()
}

def probeRecordingNow() {
    if (!parent.getTokenValue() || parent.isHubUnavailable()) return
    Integer ch = safeInt(getDataValue("channel"), 0)
    long nowSec = (now() / 1000L) as long
    // Sent by the parent's Search queue - the hub answers one Search at a time
    parent.requestSearch(ch, nowSec - SEARCH_WINDOW_SEC(), nowSec, [nowSec: nowSec])
}

// Called by the parent with the hub's reply (d0), the request context, or an error message
def searchResult(d0, ctx, errMsg) {
    long nowSec = safeLong(ctx?.nowSec, (now() / 1000L) as long)
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
        sendEventIfChanged("Recording Active", "false")
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
    sendEventIfChanged("Recording Active", (active ? "true" : "false"))
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

// ---- Light Switch ----
def on() {
    log.info "${device.label ?: device.name} - ON"
    sendEvent(name: "switch", value: "on", isStateChange: true)
    setLightRuntime()
    executeLightCommand("On")
    if (safeInt(autoOffMinutes, 0) > 0) runIn(safeInt(autoOffMinutes, 0) * 60, "autoOffSafety", [overwrite: true])
}

def off() {
    log.info "${device.label ?: device.name} - OFF"
    sendEvent(name: "switch", value: "off", isStateChange: true)
    executeLightCommand("Off")
    unschedule("autoOffSafety")
    setLightRuntime()
}

def autoOffSafety() {
    log.info "${device.label ?: device.name}: Auto-off safety triggered"
    off()
}

def executeLightCommand(String cmdState) {
    def ch = getDataValue("channel")
    if (ch == null) { log.warn "${device.label ?: device.name}: No channel set"; return }
    parent.childSetSpotlight(ch, (cmdState == "On" ? 1 : 0), 0)
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
    executeSingleSnap(false)
    runIn(3,  "burst2", [overwrite: true])
    runIn(6,  "burst3", [overwrite: true])
    runIn(9,  "burst4", [overwrite: true])
    runIn(12, "burst5", [overwrite: true])
}

def burst2() { executeSingleSnap(false) }
def burst3() { executeSingleSnap(false) }
def burst4() { executeSingleSnap(false) }
def burst5() { executeSingleSnap(true)  }

def executeSingleSnap(Boolean doCleanup = true) {
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