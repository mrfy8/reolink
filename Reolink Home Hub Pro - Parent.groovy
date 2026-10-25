/**
 * Reolink Home Hub Pro - Parent
 *
 * PARENT_DRIVER_VERSION "20261007-REOLINK-HOMEHUBPRO-PARENT-v25"
 *
 *  - Enable and Disable Reolink Push Notifications from Parent or single child.
 *      NOTE: the change in Notifications is made through API and the change is not reflected in the Reolink App.
 *            but it is applied globally and overrides what is shown in the app.
 *  - Enable and Disable Camera Recording from Parent or single child.
 *  - To clear old states and variables during troubleshooting - Uncomment the 3 lines in the metadata
 *
 *  v25 (2026-10-07) - changes from v24
 *  - Battery reads: a channel that has never given a battery reading and times out on the
 *    battery read in 3 slow polls in a row is marked "no battery" and no longer asked
 *    (mains-powered cameras do not answer GetBatteryInfo at all, so v24 waited 10 s on each
 *    of them every 30 minutes and logged the same warning). A channel that has ever given a
 *    battery reading is never written off by timeouts (a sleeping battery camera can time
 *    out). Initialize clears this so channels are re-discovered.
 *
 *  v24 (2026-10-07) - changes from v23
 *  - Async reads: only a read timeout on the AI poll (GetEvents) marks the hub unavailable.
 *    Battery and PIR reads in the 30-minute slow poll can wait on a sleeping battery
 *    camera; a timeout there now skips that channel instead of stopping all polling for
 *    30 s and forcing a re-login every half hour. Connection-level failures (refused, no
 *    route, 502, connect timeout) still trigger the back-off for every read.
 *  - A skipped async read is logged as a throttled warning naming the command and channel.
 *
 *  v23 (2026-10-06) - changes from v22
 *  - Settings reads (GetPushV20 / GetRecV20 in the 30-minute slow poll and the read-back
 *    after push / recording commands) no longer mark the whole hub unavailable on a read
 *    timeout. Only connection-level failures (refused, no route, 502, connect timeout)
 *    trigger the back-off, the same rule as Search and PIR. Previously one slow channel
 *    stopped all polling for 30 s and forced a re-login every half hour.
 *  - Those reads use a 10 s timeout (READ_TIMEOUT) instead of 5 s.
 *  - A channel whose read fails is skipped and logged as a throttled warning naming the
 *    command and channel; the slow poll and read-back carry on with the other channels.
 *  - Read-back log lists any channels that did not answer.
 *
 *  v22 (2026-10-01) - changes from v21
 *  - Search queue: the Home Hub Pro answers only one Search at a time (concurrent Searches
 *    get rspCode -17, and the one accepted returns no files). Camera drivers now call
 *    requestSearch(); the parent sends one Search at a time (async, 30 s timeout) and hands
 *    each reply to the camera's searchResult(). A newer request for a camera replaces its
 *    queued one; a reply overdue by 35 s is abandoned and the queue moves on; the watchdog
 *    also restarts the queue. Search errors count as hub unavailable only for
 *    connection-level failures (battery camera searches can be slow).
 *  - apiPost removed (no camera driver uses it).
 *  - Initialize deletes the attributes retired in v21 (Polling Lock, HDD Status,
 *    HDD Capacity GB, HDD Free GB, Retention Days, Recordings 24h, Hub Checks Last Run).
 *  - Requires camera drivers CX-820 v8, Doorbell v3, Altas 2K v14 or later.
 *
 *  v21 (2026-10-01) - changes from v20
 *  - Hub load: the AI poll (GetEvents) and the slow-poll battery and PIR reads are
 *    asynchronous, sent one at a time in a chain (each reply sends the next request).
 *  - Unchanged and still synchronous (with explicit timeouts): push / recording / PIR
 *    on-off, spotlight, status LED, auto reply, login / logout, GetPushV20 / GetRecV20
 *    and the v20 read-back. PIR calls use 15 s (battery camera wake), others 5 s.
 *  - Self-healing: the poll loop reschedules itself before polling; a 5-minute watchdog
 *    restarts polling if it stalls, retries login if no token is held, retries recovery
 *    if the hub stays unavailable, and refreshes the token once it is over 50 minutes old.
 *  - "Polling Lock" attribute removed (lock kept in state only).
 *  - Read timeouts on the async poll and sync reads count as "Hub Unavailable".
 *  - Channels with no battery are remembered; PIR marked unsupported from GetPirInfo too.
 *  - New for child drivers: tokenRejected() and hubError(msg).
 *  - Removed: hub storage, daily hub checks and "Run Hub Checks" (homedash reads these
 *    from the hub via the reolink-api MCP).
 *
 *  v20 (2026-09-29) - changes from v19
 *  - Push commands log the hub's reply per channel (code / rspCode), like recording.
 *  - Read-back after any push or recording command: ~10 s later GetPushV20 and GetRecV20
 *    are read for every channel, each camera device shows the hub's real setting, and the
 *    parent's "Reolink Push Notification Status" / "Recording Enabled" show All On, All Off
 *    or Mixed from those readings.
 *
 *  v19 (2026-09-29) - changes from v18
 *  - AI detection counts: onset of each AI type per channel counted from the GetEvents
 *    poll -> AI Counts Today / AI Counts Yesterday (JSON), rolled over at local midnight.
 *
 *  v18 (2026-09-25) - changes from v16
 *  - Token errors detected via code OR error.rspCode (-5/-6/-7); immediate re-login.
 *  - Single login path: duplicate logins within 10s are skipped, leftover retry and
 *    recovery logins are unscheduled after a successful login.
 *  - safeChildCall uses explicit child method calls (computed method names are
 *    blocked by current Hubitat firmware on recompile).
 *  - Throttled warning when the hub rejects the token.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final int SYNC_TIMEOUT = 5
@Field static final int READ_TIMEOUT = 10
@Field static final int PIR_TIMEOUT = 15
@Field static final int ASYNC_TIMEOUT = 10
@Field static final int SEARCH_TIMEOUT = 30
@Field static final long SEARCH_STUCK_MS = 35000L
@Field static final List BATT_CMDS = ["GetBatteryInfo", "GetBattery", "GetPowerStatus", "GetDevInfo"]
@Field static final List RETIRED_ATTRS = ["Polling Lock", "HDD Status", "HDD Capacity GB", "HDD Free GB",
                                          "Retention Days", "Recordings 24h", "Hub Checks Last Run"]

// Search queue (in memory): "<parentId>:<channel>" -> request, and "<parentId>" -> time the in-flight Search was sent
@Field static ConcurrentHashMap<String, Map> SEARCH_Q = new ConcurrentHashMap<String, Map>()
@Field static ConcurrentHashMap<String, Long> SEARCH_BUSY = new ConcurrentHashMap<String, Long>()

metadata {
    definition(name: "Reolink Hub Parent", namespace: "mr_fy_hubitat", author: "mr_fy") {
        capability "Refresh"
        capability "Initialize"
        capability "Actuator"

        command "createChildDevices"
        command "ReolinkPushNotifications_AllOn"
        command "ReolinkPushNotifications_AllOff"
        command "ReolinkRecording_AllOn"
        command "ReolinkRecording_AllOff"
        command "ReolinkPIR_AllOn"
        command "ReolinkPIR_AllOff"
        // Disconnect and disable polling. Use Initialize to login again.
        command "Logout Home Hub Pro"

        // Troubleshooting - Uncomment the next 3 lines
        // command "Clear Current States Values"
        // command "Clear State Variables"
        // command "Clear Orphaned States"

        attribute "Reolink Push Notification Status", "string"
        attribute "Recording Enabled", "string"
        attribute "PIR Enabled", "string"
        attribute "Connection Status", "string"
        attribute "Last Login Time", "string"
        attribute "Parent Driver Version", "string"
        attribute "AI Counts Today", "string"
        attribute "AI Counts Yesterday", "string"
    }

    preferences {
        input name: "cmdNote", type: "paragraph", element: "paragraph",
    		title: "Notes: Commands Page",
    		description: "Initialize - Use this to Log into the Home Hub Pro.<br>ReolinkPIR_All On/Off - applies only to battery cameras. PoE cameras have no PIR sensor."
        
        input "ipAddress", "text",
            title: "Home Hub Pro IP Address",
            description: "Enter the local IP address of the Reolink Home Hub Pro (IPv4 only), e.g. 192.168.1.50 — do not include http://, ports, or a trailing slash.",
            required: true

        input "username", "text",
            title: "Home Hub Pro Username",
            defaultValue: "admin",
            description: "Enter the username of the local Reolink account on the Home Hub Pro used for API access (e.g. admin).",
            required: true

        input "password", "password",
            title: "Home Hub Pro Password",
            description: "Enter the password for the Reolink account on the Home Hub Pro used for API authentication.",
            required: true

        input "channelCount", "number",
            title: "Number of Channels",
            description: "Set the number of Channels (Cameras) to manage on the Home Hub Pro. This value is used when creating/recreating child devices",
            defaultValue: 1, range: "1..24", required: true

        input "pruneExtraChildren", "bool",
            title: "Remove child devices above channel count",
            description: "When enabled, runs when you press Create Child Devices.",
            defaultValue: false

        input "pollInterval", "enum",
            title: "Home Hub Pro Polling Interval",
            description: "How often to poll the Home Hub Pro for AI Motion and AI Detection Type updates.<br>This value is also used for an automatic slow poll which runs every 60 cycles to update Camera Battery Percentage, Push Notification Status, Recording Enabled and PIR Enabled. (e.g. every 5 min at 5s, every 30 min at 30s).<br>If using Hubitat automation to wake the a camera, it has its own fast poll (Recording Probe Interval Awake), therefore the value set in this field does not need to be too aggressive.<br>Default: 30 Seconds.",
            options: [
                "1":"Every 1 second",
                "2":"Every 2 seconds",
                "3":"Every 3 seconds",
                "4":"Every 4 seconds",
                "5":"Every 5 seconds",
                "10":"Every 10 seconds",
                "15":"Every 15 seconds",
                "30":"Every 30 seconds (default)",
                "60":"Every 1 minute",
                "120":"Every 2 minutes",
                "300":"Every 5 minutes"
            ],
            defaultValue: "30",
            required: true

        input "debugLogging", "bool",
            title: "Enable Debug Logging",
            description: "Automatically turns off after 2 hours.",
            defaultValue: false
    }
}

def PARENT_DRIVER_VERSION() { "20261007-REOLINK-HOMEHUBPRO-PARENT-v25" }
def dlog(msg) { if (debugLogging) log.debug msg }

// --- Helpers for Child ---
def getIPAddress() { return ipAddress }
def getTokenValue() { return state.token }
def isHubUnavailable() { return (state.hubUnavailable == true) }

// Called by child drivers when the hub rejects the token on their own requests
def tokenRejected(String source = "child") {
    warnThrottled("auth", "Reolink Hub: token rejected (${source}) — re-login")
    state.token = null
    state.tokenPending = false
    ensureToken()
}

// Called by child drivers when their own request fails; applies the hub-unavailable back-off
def hubError(String msg) {
    return noteHubError(msg, true)
}

// --- Lifecycle ---
def installed() { initialize() }

def updated() {
    unschedule()
    resetRuntimeState()
    clearSearchQueue()
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])
    // Do not reconnect if user explicitly logged out — they must press Initialize
    if (state.loggedOut == true) {
        log.info "Reolink Hub: updated() — staying logged out (press Initialize to reconnect)"
        return
    }
    scheduleWatchdog()
    runIn(5, "getToken", [overwrite: true])
}

def initialize() {
    sendEvent(name: "Parent Driver Version", value: PARENT_DRIVER_VERSION(), isStateChange: true)

    if (device.currentValue("Reolink Push Notification Status") == null) sendEvent(name: "Reolink Push Notification Status", value: "Unknown", isStateChange: true)
    if (device.currentValue("Recording Enabled") == null) sendEvent(name: "Recording Enabled", value: "Unknown", isStateChange: true)
    if (device.currentValue("PIR Enabled") == null) sendEvent(name: "PIR Enabled", value: "Unknown", isStateChange: true)
    if (device.currentValue("Connection Status") == null) sendEvent(name: "Connection Status", value: "Unknown", isStateChange: true)

    deleteRetiredAttributes()

    unschedule()

    resetRuntimeState()
    clearSearchQueue()
    state.loggedOut = false   // Always clear on Initialize so reconnect works
    state.remove("hubChecksTs")

    // Clear unsupported / no-battery channel caches on Initialize
    // This allows re-discovery after a firmware update or a camera change
    clearUnsupportedCaches()

    // Restart 2 hour debug auto-off countdown after reboot so it still turns off
    if (debugLogging) runIn(7200, "disableDebugLogging", [overwrite: true])

    scheduleWatchdog()

    // Delay first login to allow network stack to be ready after reboot
    // schedulePolling is called inside getToken() on success
    runIn(30, "getToken", [overwrite: true])
}

def deleteRetiredAttributes() {
    int removed = 0
    RETIRED_ATTRS.each { n ->
        try {
            if (device.currentValue(n) != null) {
                device.deleteCurrentState(n)
                removed++
            }
        } catch (ignored) { }
    }
    if (removed > 0) log.info "Reolink Hub: Removed ${removed} retired attributes"
}

def resetRuntimeState() {
    state.token = null
    state.tokenPending = false
    state.pollingLock = false
    state.hubUnavailable = false
    state.pollCycle = 0
    state.lastPollTs = now()
}

def clearUnsupportedCaches() {
    int cleared = 0
    List keys = state.keySet().findAll { it.startsWith("recUnsupported_ch") || it.startsWith("pirUnsupported_ch") || it.startsWith("noBatt_ch") || it.startsWith("battOk_ch") || it.startsWith("battTo_ch") } as List
    keys.each { key ->
        state.remove(key)
        cleared++
    }
    if (cleared > 0) log.info "Reolink Hub: Cleared ${cleared} unsupported / no-battery channel cache entries — will re-discover on next slow poll"
}

def refresh() { pollAll() }

def refreshChildChannel(Integer ch) {
    if (state.hubUnavailable == true) return
    if (!ensureToken()) return
    def child = findChild(ch)
    if (child == null) return
    pollPushStatus(ch, child)
    pollRecording(ch, child)
    startChain("refresh_ch${ch}", [[t: "ai", ch: ch], [t: "batt", ch: ch, i: 0], [t: "pir", ch: ch]])
}

def "Logout Home Hub Pro"() {
    log.info "Reolink Hub: Manual logout requested — disconnecting and stopping polling"
    unschedule()
    clearSearchQueue()
    if (state.token) {
        try {
            httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=Logout&token=${state.token}", body: [[cmd: "Logout", param: []]], timeout: SYNC_TIMEOUT]) { }
        } catch (ignored) { }
    }
    state.token = null
    state.tokenPending = false
    state.pollingLock = false
    state.hubUnavailable = false
    state.pollCycle = 0
    state.loggedOut = true   // Prevents updated(), ensureToken() and the watchdog from reconnecting
    sendEvent(name: "Connection Status", value: "Logged Out", isStateChange: true)
    log.info "Reolink Hub: Logged out. Press Initialize to reconnect."
}

def disableDebugLogging() {
    log.info "${device.label ?: device.name}: Auto-disabling debug logging after 2 hours"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

// --- Commands (Parent maintenance) ---
def "Clear Current States Values"() {
    int cleared = 0
    try {
        def protect = ["DeviceWatch-DeviceStatus", "healthStatus"] as Set
        device.currentStates?.each { st ->
            String n = st?.name
            if (!n) return
            if (protect.contains(n)) return
            device.deleteCurrentState(n)
            cleared++
        }
        log.info "Reolink Hub: Cleared ${cleared} current state values"
    } catch (e) {
        log.error "Clear Current States Values error: ${e}"
    }
}

def "Clear State Variables"() {
    log.warn "Reolink Hub: Clear State Variables requested"
    try { unschedule() } catch (ignored) { }
    try { state.clear() } catch (e) { log.warn "state.clear() failed: ${e}" }
    initialize()
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
        log.info "Reolink Hub: Cleared ${removed} orphaned currentStates"
    } catch (e) {
        log.error "Clear Orphaned States error: ${e}"
    }
}

// --- Polling schedule ---
Integer pollSeconds() { return safeInt(pollInterval, 30) }

def schedulePolling() {
    unschedule("pollAll")
    unschedule("dynamicPollLoop")

    Integer seconds = pollSeconds()
    state.lastPollTs = now()

    // Hubitat only supports runEvery* for 1 minute and above
    // Everything under 60s must use dynamicPollLoop with runIn
    if (seconds == 60) runEvery1Minute("pollAll")
    else if (seconds == 120) runEvery2Minutes("pollAll")
    else if (seconds == 300) runEvery5Minutes("pollAll")
    else runIn(seconds, "dynamicPollLoop", [overwrite: true])

    log.info "Reolink Hub: Polling every ${seconds}s"
}

def dynamicPollLoop() {
    // Reschedule first, so a poll that is stopped part-way cannot end the loop
    runIn(pollSeconds(), "dynamicPollLoop", [overwrite: true])
    pollAll()
}

// --- Watchdog (platform schedule, every 5 minutes) ---
def scheduleWatchdog() {
    unschedule("pollWatchdog")
    runEvery5Minutes("pollWatchdog")
}

def pollWatchdog() {
    if (state.loggedOut == true) return
    long t = now()

    if (state.hubUnavailable == true) {
        if (t - safeLong(state.hubUnavailableTs, 0L) > 120000L) {
            log.warn "Reolink Hub: Watchdog — hub still unavailable, retrying login"
            attemptHubRecovery()
        }
        return
    }

    if (!state.token) {
        if (state.tokenPending != true || (t - safeLong(state.tokenPendingTs, 0L)) > 120000L) {
            log.warn "Reolink Hub: Watchdog — no token held, logging in"
            state.tokenPending = false
            getToken()
        }
        return
    }

    // Proactive token refresh (Reolink tokens last 60 minutes)
    if (t - safeLong(state.lastLoginTs, 0L) > 50L * 60000L) {
        refreshToken()
        return
    }

    // Polling stalled?
    long limit = Math.max(180000L, pollSeconds() * 3000L)
    long age = t - safeLong(state.lastPollTs, 0L)
    if (age > limit) {
        log.warn "Reolink Hub: Watchdog — no poll for ${(int)(age / 1000L)}s, restarting polling"
        state.pollingLock = false
        schedulePolling()
    }

    // Keep the Search queue moving
    pumpSearch()
}

def refreshToken() {
    log.info "Reolink Hub: Proactive token refresh"
    state.token = null
    state.tokenPending = false
    getToken()
}

// --- Login / Token ---
def getToken() {
    if (state.loggedOut == true) {
        log.info "Reolink Hub: getToken() skipped — logged out. Press Initialize to reconnect."
        return
    }
    if (!ipAddress || !username || !password) {
        sendEvent(name: "Connection Status", value: "Config Missing", isStateChange: true)
        log.warn "Reolink Hub: Missing config"
        state.token = null
        return
    }

    // Single login path: ignore a second login if we already hold a fresh token
    long sinceLogin = now() - safeLong(state.lastLoginTs, 0L)
    if (state.token && sinceLogin < 10000L) {
        log.info "Reolink Hub: getToken() skipped — already logged in ${(int)(sinceLogin / 1000L)}s ago"
        state.tokenPending = false
        return
    }

    state.tokenPending = true
    state.tokenPendingTs = now()

    if (state.token) {
        try {
            httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=Logout&token=${state.token}", body: [[cmd: "Logout", param: []]], timeout: SYNC_TIMEOUT]) { }
        } catch (ignored) { }
        state.token = null
    }

    def body = [[cmd: "Login", param: [User: [userName: username, password: password]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=Login", body: body, timeout: SYNC_TIMEOUT]) { resp ->
            if (resp?.data?.getAt(0)?.code == 0) {
                state.token = resp.data[0].value.Token.name
                state.tokenPending = false
                state.hubUnavailable = false
                state.lastLoginTs = now()
                // Cancel any leftover retry/recovery logins so they cannot replace this token
                unschedule("getToken")
                unschedule("attemptHubRecovery")
                def t = new Date().format("HH:mm:ss dd/MM", location.timeZone)
                sendEvent(name: "Last Login Time", value: t, isStateChange: true)
                sendEvent(name: "Connection Status", value: "Connected", isStateChange: true)
                log.info "Reolink Hub: Login success"
                dlog "Reolink Hub: token=${state.token}"
                runIn(5, "schedulePolling", [overwrite: true])
            } else {
                state.token = null
                state.tokenPending = false
                sendEvent(name: "Connection Status", value: "Login Failed", isStateChange: true)
                dlog "Login response: ${resp?.data}"
            }
        }
    } catch (e) {
        state.token = null
        state.tokenPending = false
        sendEvent(name: "Connection Status", value: "Error", isStateChange: true)
        log.error "Login error: ${e}"
        runIn(30, "getToken", [overwrite: true])
    }
}

def ensureToken() {
    if (state.loggedOut == true) return false
    if (state.token) return true
    if (state.tokenPending == true) return false
    state.tokenPending = true
    state.tokenPendingTs = now()
    runIn(1, "getToken", [overwrite: true])
    return false
}

def isAuthTokenError(def x) {
    // Accepts a response element (map) or a bare code. The Home Hub reports an
    // invalid or expired token as code=1 with error.rspCode=-6, so check both.
    Integer code = null
    Integer rsp  = null
    if (x instanceof Map) {
        try { code = x?.code as Integer }           catch (ignored) { }
        try { rsp  = x?.error?.rspCode as Integer } catch (ignored) { }
    } else {
        try { code = x as Integer } catch (ignored) { }
    }
    def auth = [-5, -6, -7]
    return (code in auth) || (rsp in auth)
}

// --- Hub unavailability ---
// Returns true if the error means the hub is (or already was) unavailable.
// readTimeoutCounts=false: only connection-level failures count (used where a slow
// reply is expected, e.g. PIR or Search waking a battery camera).
boolean noteHubError(String msg, boolean readTimeoutCounts = true) {
    if (state.hubUnavailable == true) return true
    String m = (msg ?: "").toLowerCase()
    boolean down = m.contains("502") || m.contains("connection refused") || m.contains("connect timed out") ||
                   m.contains("failed to respond") || m.contains("no route to host") || m.contains("host is unreachable")
    if (!down && readTimeoutCounts) down = m.contains("timed out") || m.contains("timeout")
    if (!down) return false
    state.hubUnavailable = true
    state.hubUnavailableTs = now()
    sendEvent(name: "Connection Status", value: "Hub Unavailable", isStateChange: true)
    log.warn "Reolink Hub: Hub Pro unreachable (${msg}) — backing off until recovery"
    runIn(30, "attemptHubRecovery", [overwrite: true])
    return true
}

def attemptHubRecovery() {
    log.info "Reolink Hub: Attempting recovery — re-login"
    state.token = null
    state.tokenPending = false
    getToken()
}

// --- Search queue (one Search at a time to the Home Hub Pro) ---
// Camera drivers call requestSearch(); the reply is handed to the camera's searchResult().
def requestSearch(def ch, def startSec, def endSec, Map ctx = [:]) {
    if (state.loggedOut == true || state.hubUnavailable == true || !state.token) return false
    Integer c = safeIntOrNull(ch)
    if (c == null) return false
    // A newer request for the same camera replaces its queued one
    SEARCH_Q.put("${device.id}:${c}".toString(),
                 [ch: c, start: safeLong(startSec, 0L), end: safeLong(endSec, 0L), ctx: (ctx ?: [:]), ts: now()])
    pumpSearch()
    return true
}

def clearSearchQueue() {
    String pre = "${device.id}:".toString()
    List keys = SEARCH_Q.keySet().findAll { it.startsWith(pre) } as List
    keys.each { SEARCH_Q.remove(it) }
    SEARCH_BUSY.remove(device.id.toString())
}

Map takeNextSearch() {
    String pre = "${device.id}:".toString()
    for (int i = 0; i < 5; i++) {
        String bestKey = null
        long bestTs = Long.MAX_VALUE
        SEARCH_Q.each { k, v ->
            if (k.startsWith(pre)) {
                long ts = safeLong(v?.ts, 0L)
                if (ts < bestTs) { bestTs = ts; bestKey = k }
            }
        }
        if (bestKey == null) return null
        Map req = SEARCH_Q.remove(bestKey)
        if (req != null) return req
    }
    return null
}

def pumpSearch() {
    String bk = device.id.toString()
    long t = now()
    Long prev = SEARCH_BUSY.putIfAbsent(bk, t)
    if (prev != null) {
        if (t - prev < SEARCH_STUCK_MS) return          // a Search is in flight
        if (!SEARCH_BUSY.replace(bk, prev, t)) return   // another run took over
        log.warn "Reolink Hub: Search reply overdue — moving on"
    }
    Map req = takeNextSearch()
    if (req == null) { SEARCH_BUSY.remove(bk); return }
    String tok = state.token
    if (!tok || state.hubUnavailable == true || state.loggedOut == true) {
        SEARCH_BUSY.remove(bk)
        clearSearchQueue()
        return
    }
    int ch = safeInt(req.ch, 0)
    Map param = [Search: [channel: ch, onlyStatus: 0, streamType: "main",
                          StartTime: tmObj(safeLong(req.start, 0L)), EndTime: tmObj(safeLong(req.end, 0L))]]
    Map params = [
        uri               : "http://${ipAddress}/cgi-bin/api.cgi?cmd=Search&token=${tok}".toString(),
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : JsonOutput.toJson([[cmd: "Search", action: 0, param: param]]),
        timeout           : SEARCH_TIMEOUT
    ]
    try {
        asynchttpPost("searchReply", params, [ch: ch, ctx: (req.ctx ?: [:])])
    } catch (e) {
        SEARCH_BUSY.remove(bk)
        log.warn "Reolink Hub: Search ch${ch} not sent: ${e.message}"
    }
}

def searchReply(resp, data) {
    SEARCH_BUSY.remove(device.id.toString())
    int ch = safeInt(data?.ch, 0)
    Map ctx = (data?.ctx instanceof Map) ? (data.ctx as Map) : [:]
    def child = findChild(ch)

    if (resp.hasError()) {
        String msg = null
        try { msg = resp.getErrorMessage() } catch (ignored) { }
        if (!msg) msg = "HTTP ${resp.status}"
        dlog "Reolink Hub: Search ch${ch} failed: ${msg}"
        if (child != null) safeChildCall(child, "searchResult", [null, ctx, msg])
        // Battery camera searches can be slow - only connection failures count as hub down
        if (!noteHubError(msg, false)) pumpSearch()
        return
    }

    def d0 = parseFirst(resp)
    if (isAuthTokenError(d0)) {
        if (child != null) safeChildCall(child, "searchResult", [d0, ctx, null])
        tokenRejected("Search")
        return
    }
    if (child != null) safeChildCall(child, "searchResult", [d0, ctx, null])
    pumpSearch()
}

// Hub times are local: epoch seconds -> Reolink time object in the hub's time zone
Map tmObj(long epochSec) {
    Calendar c = Calendar.getInstance(location.timeZone)
    c.setTimeInMillis(epochSec * 1000L)
    return [year: c.get(Calendar.YEAR), mon: c.get(Calendar.MONTH) + 1, day: c.get(Calendar.DAY_OF_MONTH),
            hour: c.get(Calendar.HOUR_OF_DAY), min: c.get(Calendar.MINUTE), sec: c.get(Calendar.SECOND)]
}

// --- Asynchronous request chain ---
// Requests are sent one at a time: each reply handler sends the next request.
// A newer chain with the same key supersedes an older one still in flight.
def startChain(String key, List tasks) {
    if (!tasks) return
    long id = now()
    state."chain_${key}" = id
    runNextTask(key, id, tasks)
}

def runNextTask(String key, long id, List tasks) {
    if (state.hubUnavailable == true || !state.token) return
    if (safeLong(state."chain_${key}", 0L) != id) return
    while (tasks) {
        Map task = tasks.remove(0) as Map
        if (sendTask(key, id, task, tasks)) return
    }
}

boolean sendTask(String key, long id, Map task, List rest) {
    int ch = safeInt(task.ch, 0)
    Map data = [key: key, id: id, task: task, rest: rest]
    switch (task.t) {
        case "ai":
            long end = (now() / 1000L) as long
            return sendAsync("GetEvents", [channel: ch, startTime: end - 90L, endTime: end, num: 20], ASYNC_TIMEOUT, data)
        case "batt":
            if (state."noBatt_ch${ch}" == true) return false
            String cached = state."battCmd_ch${ch}"
            if (cached) {
                task.cmd = cached
                task.cached = true
                return sendAsync(cached, [channel: ch], ASYNC_TIMEOUT, data)
            }
            int i = safeInt(task.i, 0)
            if (i >= BATT_CMDS.size()) return false
            task.cmd = BATT_CMDS[i]
            return sendAsync(task.cmd as String, [channel: ch], ASYNC_TIMEOUT, data)
        case "pir":
            if (state."pirUnsupported_ch${ch}" == true) return false
            return sendAsync("GetPirInfo", [channel: ch, PirInfo: [channel: ch]], PIR_TIMEOUT, data, 1, "&channel=${ch}")
    }
    return false
}

boolean sendAsync(String cmd, Map param, int timeout, Map data, int action = 0, String extraQuery = "") {
    String tok = state.token
    if (!tok) return false
    Map params = [
        uri               : "http://${ipAddress}/cgi-bin/api.cgi?cmd=${cmd}&token=${tok}${extraQuery}".toString(),
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : JsonOutput.toJson([[cmd: cmd, action: action, param: param]]),
        timeout           : timeout
    ]
    data.cmd = cmd
    try {
        asynchttpPost("asyncTaskHandler", params, data)
        return true
    } catch (e) {
        log.warn "Reolink Hub: ${cmd} not sent: ${e.message}"
        return false
    }
}

def asyncTaskHandler(resp, data) {
    String key = data?.key as String
    long id = safeLong(data?.id, 0L)
    Map task = (data?.task instanceof Map) ? (data.task as Map) : [:]
    List rest = (data?.rest instanceof List) ? (data.rest as List) : []
    String cmd = data?.cmd as String

    if (resp.hasError()) {
        String msg = null
        try { msg = resp.getErrorMessage() } catch (ignored) { }
        if (!msg) msg = "HTTP ${resp.status}"
        dlog "Reolink Hub: ${cmd} ch${task.ch} failed: ${msg}"
        // Only the AI poll's read timeouts mean the hub is down. Battery and PIR reads can
        // wait on a sleeping battery camera - skip that channel and carry on.
        if (noteHubError(msg, task.t == "ai")) return
        if (task.t == "batt") noteBattTimeout(safeInt(task.ch, 0), msg)
        if (task.t != "ai") {
            warnThrottled("async_${cmd}_ch${task.ch}",
                          "Reolink Hub: ${cmd} ch${task.ch} failed (${msg}) — channel skipped, polling continues", 600000L)
        }
        runNextTask(key, id, rest)
        return
    }

    def d0 = parseFirst(resp)
    if (isAuthTokenError(d0)) {
        tokenRejected(cmd)
        return
    }
    try {
        handleTask(task, d0, rest)
    } catch (e) {
        log.warn "Reolink Hub: ${cmd} ch${task.ch} handler error: ${e.message}"
    }
    runNextTask(key, id, rest)
}

def parseFirst(resp) {
    def j = null
    try { j = resp.getJson() } catch (ignored) { }
    if (j == null) {
        try { j = new JsonSlurper().parseText(resp.getData() as String) } catch (ignored) { }
    }
    return (j instanceof List && !j.isEmpty()) ? j[0] : null
}

def handleTask(Map task, d0, List rest) {
    int ch = safeInt(task.ch, 0)
    switch (task.t) {
        case "ai":   handleAIEvents(ch, d0); break
        case "batt": handleBattery(ch, task, d0, rest); break
        case "pir":  handlePir(ch, d0); break
    }
}

// --- Child call safety wrapper ---
// Explicit calls only - Hubitat blocks computed method names (child."${method}")
// on newly compiled code ("Computed device command ... is not allowed").
def safeChildCall(child, String method, List args = []) {
    try {
        switch (method) {
            case "updateAI":               child.updateAI(args[0], args[1], args[2]); break
            case "updatePushState":        child.updatePushState(args[0]); break
            case "updateRecordingEnabled": child.updateRecordingEnabled(args[0]); break
            case "updatePIREnabled":       child.updatePIREnabled(args[0]); break
            case "updateBatteryPercent":   child.updateBatteryPercent(args[0]); break
            case "searchResult":           child.searchResult(args[0], args[1], args[2]); break
            default:
                log.warn "Reolink Hub: safeChildCall — unsupported method ${method}"
                return false
        }
        return true
    } catch (MissingMethodException ignored) {
        return false
    } catch (e) {
        log.warn "Child call failed ${child?.displayName}.${method}(${args}): ${e.message}"
        return false
    }
}

def findChild(def ch) {
    Integer c = safeIntOrNull(ch)
    if (c == null) return null
    def cd = getChildDevice("${device.id}-ch${c}".toString())
    if (cd != null) return cd
    return getChildDevices().find { safeIntOrNull(it.getDataValue("channel")) == c }
}

List sortedChildren() {
    List out = []
    getChildDevices().each { cd ->
        Integer ch = safeIntOrNull(cd.getDataValue("channel"))
        if (ch != null) out << [ch: ch, child: cd]
    }
    return out.sort { it.ch }
}

// --- Child creation ---
def createChildDevices() {
    int count = safeInt(channelCount, 1)
    count = Math.max(1, Math.min(24, count))
    int maxCh = count - 1

    log.info "Reolink Hub: Ensuring children exist for channels 0-${maxCh}."

    Map existing = [:]
    getChildDevices().each { cd ->
        Integer ch = safeIntOrNull(cd.getDataValue("channel"))
        if (ch != null) existing[ch] = cd
    }

    (0..maxCh).each { ch ->
        if (!existing.containsKey(ch)) {
            def dni = "${device.id}-ch${ch}"
            try {
                def child = addChildDevice(
                    "mr_fy_hubitat",
                    "Reolink Camera Child — Stub",
                    dni,
                    [name: "Camera Ch${ch}", label: "${device.displayName} Ch${ch}"]
                )
                child.updateDataValue("channel", ch.toString())
                log.info "Reolink Hub: Created child for channel ${ch}"
            } catch (e) {
                log.error "Reolink Hub: Failed creating child for channel ${ch}: ${e.message}"
            }
        }
    }

    if (pruneExtraChildren) {
        int pruned = 0
        getChildDevices().each { cd ->
            Integer ch = safeIntOrNull(cd.getDataValue("channel"))
            if (ch != null && ch > maxCh) {
                try {
                    deleteChildDevice(cd.deviceNetworkId)
                    pruned++
                    log.info "Reolink Hub: Pruned child for channel ${ch}"
                } catch (e) {
                    log.warn "Reolink Hub: Failed to prune child for channel ${ch}: ${e.message}"
                }
            }
        }
        if (pruned > 0) log.info "Reolink Hub: Pruned ${pruned} extra children"
    }
}

// --- Reolink Push Notifications (bulk) ---
def ReolinkPushNotifications_AllOn() {
    log.info "Reolink Hub: Turning Reolink Push Notifications ON for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetPush(ch, 1)
            safeChildCall(child, "updatePushState", [true])
        }
    }
    sendEvent(name: "Reolink Push Notification Status", value: "All On", isStateChange: true)
}

def ReolinkPushNotifications_AllOff() {
    log.info "Reolink Hub: Turning Reolink Push Notifications OFF for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetPush(ch, 0)
            safeChildCall(child, "updatePushState", [false])
        }
    }
    sendEvent(name: "Reolink Push Notification Status", value: "All Off", isStateChange: true)
}

def childSetPush(ch, val) {
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)

    def full = "1" * 168
    def body = [[cmd: "SetPushV20", param: [Push: [
        enable: val,
        scheduleEnable: val,
        schedule: [channel: channel, table: [
            AI_PEOPLE : full,
            AI_VEHICLE: full,
            AI_DOG_CAT: full,
            AI_OTHER  : full
        ]]
    ]]]]

    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body, timeout: SYNC_TIMEOUT]) { resp ->
            def d0 = resp?.data?.getAt(0)
            if (isAuthTokenError(d0)) {
                tokenRejected("SetPushV20")
                return
            }
            if (d0?.code == 0) {
                dlog "SetPushV20 ch${channel} enable=${val}: applied (rspCode=${d0?.value?.rspCode})"
            } else {
                log.warn "SetPushV20 ch${channel} enable=${val} error: code=${d0?.code} rspCode=${d0?.error?.rspCode} detail=${d0?.error?.detail}"
            }
        }
    } catch (e) {
        log.error "Push Error: ${e}"
        noteHubError(e.message, false)
    }
    scheduleReadback()
}

// --- Reolink Recording (bulk) ---
def ReolinkRecording_AllOn() {
    log.info "Reolink Hub: Enabling Recording for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetRecording(ch, 1)
            safeChildCall(child, "updateRecordingEnabled", [true])
        }
    }
    sendEvent(name: "Recording Enabled", value: "All On", isStateChange: true)
}

def ReolinkRecording_AllOff() {
    log.info "Reolink Hub: Disabling Recording for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetRecording(ch, 0)
            safeChildCall(child, "updateRecordingEnabled", [false])
        }
    }
    sendEvent(name: "Recording Enabled", value: "All Off", isStateChange: true)
}

def childSetRecording(ch, val) {
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)
    int schedEnable = safeInt(val, 0)

    // Channel must be in URL query string, param.channel, AND param.Rec.channel
    // Both scheduleEnable and enable are sent — scheduleEnable for battery cameras,
    // enable for PoE cameras which use a different recording model
    def body = [[cmd: "SetRecV20", action: 0, param: [channel: channel, Rec: [channel: channel, enable: schedEnable, scheduleEnable: schedEnable]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=SetRecV20&token=${state.token}&channel=${channel}", body: body, timeout: SYNC_TIMEOUT]) { resp ->
            def d0 = resp?.data?.getAt(0)
            Integer code = null
            Integer rspCode = null
            try { code    = d0?.code as Integer           } catch (ignored) { }
            try { rspCode = d0?.error?.rspCode as Integer } catch (ignored) { }
            if (isAuthTokenError(d0)) {
                tokenRejected("SetRecV20")
                return
            }
            if (rspCode == -4) {
                log.warn "SetRecV20 ch${channel}: param error (rspCode=-4) — check hub firmware"
                return
            }
            if (code == 0) {
                dlog "SetRecV20 ch${channel} enable=${schedEnable}: applied"
            } else {
                log.warn "SetRecV20 ch${channel} error: code=${code} rspCode=${rspCode}"
            }
        }
    } catch (e) {
        log.error "SetRecV20 Error: ${e}"
        noteHubError(e.message, false)
    }
    scheduleReadback()
}

// --- Reolink PIR (bulk) ---
def ReolinkPIR_AllOn() {
    log.info "Reolink Hub: Enabling PIR for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetPIR(ch, 1)
            safeChildCall(child, "updatePIREnabled", [true])
        }
    }
    sendEvent(name: "PIR Enabled", value: "All On", isStateChange: true)
}

def ReolinkPIR_AllOff() {
    log.info "Reolink Hub: Disabling PIR for all channels"
    getChildDevices().each { child ->
        Integer ch = safeIntOrNull(child.getDataValue("channel"))
        if (ch != null) {
            childSetPIR(ch, 0)
            safeChildCall(child, "updatePIREnabled", [false])
        }
    }
    sendEvent(name: "PIR Enabled", value: "All Off", isStateChange: true)
}

def childSetPIR(ch, val) {
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)
    int enable = safeInt(val, 0)

    // Skip channels confirmed to not support PIR (e.g. PoE cameras — rspCode=-12)
    if (state."pirUnsupported_ch${channel}" == true) {
        dlog "SetPirInfo ch${channel} skipped — channel marked as PIR-unsupported"
        return
    }

    // Channel must be in URL query string, param.channel, AND param.pirInfo.channel
    // pirInfo must be lowercase — hub ignores PirInfo (uppercase)
    def body = [[cmd: "SetPirInfo", action: 0, param: [channel: channel, pirInfo: [channel: channel, enable: enable]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=SetPirInfo&token=${state.token}&channel=${channel}", body: body, timeout: PIR_TIMEOUT]) { resp ->
            def d0 = resp?.data?.getAt(0)
            Integer code = null
            Integer rspCode = null
            try { code    = d0?.code as Integer           } catch (ignored) { }
            try { rspCode = d0?.error?.rspCode as Integer } catch (ignored) { }
            if (isAuthTokenError(d0)) {
                tokenRejected("SetPirInfo")
                return
            }
            // rspCode=-12 = not supported on this camera type (e.g. PoE) — cache and skip future calls
            if (rspCode == -12) {
                state."pirUnsupported_ch${channel}" = true
                dlog "SetPirInfo ch${channel}: PIR not supported (rspCode=-12) — marking channel as PIR-unsupported"
                return
            }
            // rspCode=-17 means hub woke camera but timed out waiting for ACK
            // Command is still applied — treat as success
            if (code == 0 || rspCode == -17) {
                dlog "SetPirInfo ch${channel} enable=${enable}: applied (code=${code} rspCode=${rspCode})"
            } else {
                log.warn "SetPirInfo ch${channel} error: code=${code} rspCode=${rspCode}"
            }
        }
    } catch (e) {
        log.error "SetPirInfo Error: ${e}"
        noteHubError(e.message, false)
    }
}

// --- Auto Reply (doorbell specific) ---
def childSetAutoReply(ch, val) {
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)
    int enable = safeInt(val, 0)
    def body = [[cmd: "SetAutoReply", action: 0, param: [AutoReply: [channel: channel, enable: enable]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=SetAutoReply&token=${state.token}&channel=${channel}", body: body, timeout: SYNC_TIMEOUT]) { resp ->
            def code = resp?.data?.getAt(0)?.code
            if (isAuthTokenError(resp?.data?.getAt(0))) { tokenRejected("SetAutoReply"); return }
            dlog "SetAutoReply ch${channel} enable=${enable}: code=${code}"
        }
    } catch (e) {
        log.error "AutoReply Error: ${e}"
        noteHubError(e.message, false)
    }
}

// --- Light control (child calls) ---
def childSetSpotlight(ch, stateVal, modeVal) {
    if (state.hubUnavailable == true) return
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)
    int s = safeInt(stateVal, 0)
    int m = safeInt(modeVal, 0)

    def body = [[cmd: "SetWhiteLed", param: [WhiteLed: [channel: channel, mode: m, state: s]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body, timeout: SYNC_TIMEOUT]) { resp ->
            if (isAuthTokenError(resp?.data?.getAt(0))) tokenRejected("SetWhiteLed")
        }
    } catch (e) {
        log.error "Spotlight Error: ${e}"
        noteHubError(e.message, false)
    }
}

def childSetStatusLed(ch, stateStr) {
    if (state.hubUnavailable == true) return
    if (!ensureToken()) return
    int channel = safeInt(ch, 0)

    def body = [[cmd: "SetPowerLed", param: [PowerLed: [channel: channel, state: stateStr]]]]
    try {
        httpPostJson([uri: "http://${ipAddress}/cgi-bin/api.cgi?token=${state.token}", body: body, timeout: SYNC_TIMEOUT]) { resp ->
            if (isAuthTokenError(resp?.data?.getAt(0))) tokenRejected("SetPowerLed")
        }
    } catch (e) {
        log.error "LED Error: ${e}"
        noteHubError(e.message, false)
    }
}

// --- Polling lock (state only, no events) ---
def acquirePollingLock() {
    if (state.pollingLock == true && (now() - safeLong(state.pollingLockTs, 0L)) < 60000L) return false
    state.pollingLock = true
    state.pollingLockTs = now()
    return true
}

def releasePollingLock() {
    state.pollingLock = false
}

// --- Polling (AI every cycle; Push + Recording + Battery + PIR every 60 cycles) ---
def pollAll() {
    if (state.hubUnavailable == true) return
    if (!ensureToken()) return
    if (!acquirePollingLock()) return

    try {
        state.lastPollTs = now()
        int cycle = safeInt(state.pollCycle, 0) + 1
        if (cycle >= 60) cycle = 0
        state.pollCycle = cycle
        boolean slowPoll = (cycle == 0)

        rollAICounts()

        List kids = sortedChildren()
        List tasks = []
        kids.each { k -> tasks << [t: "ai", ch: k.ch] }

        if (slowPoll) {
            // Push and recording stay synchronous (same readers as the v20 read-back)
            for (Map k : kids) {
                if (state.hubUnavailable == true) break
                pollPushStatus(k.ch as int, k.child)
                if (state.hubUnavailable == true) break
                pollRecording(k.ch as int, k.child)
            }
            kids.each { k ->
                tasks << [t: "batt", ch: k.ch, i: 0]
                tasks << [t: "pir", ch: k.ch]
            }
        }

        if (state.hubUnavailable != true) startChain("main", tasks)
    } finally {
        releasePollingLock()
    }
}

// --- AI via GetEvents (async reply) ---
def handleAIEvents(int ch, d0) {
    if (d0?.code != 0) return
    def child = findChild(ch)
    if (child == null) return
    def v = d0?.value
    def ai = v?.ai

    Integer people  = safeIntOrNull(ai?.people?.alarm_state)
    Integer vehicle = safeIntOrNull(ai?.vehicle?.alarm_state)
    Integer animal  = safeIntOrNull(ai?.dog_cat?.alarm_state)
    Integer other   = safeIntOrNull(ai?.other?.alarm_state)
    Integer pkg     = safeIntOrNull(ai?.package?.alarm_state)
    // visitor is at top level of value, not inside ai (doorbell specific)
    Integer visitor = safeIntOrNull(v?.visitor?.alarm_state)

    boolean active = ((people == 1) || (vehicle == 1) || (animal == 1) || (other == 1) || (pkg == 1) || (visitor == 1))

    String type = "Unknown"
    if (visitor == 1) type = "Visitor"
    else if (people == 1) type = "Person"
    else if (vehicle == 1) type = "Vehicle"
    else if (pkg == 1) type = "Package"
    else if ((animal == 1) || (other == 1)) type = "Animal"

    String ts = new Date().format("HH:mm:ss dd/MM", location.timeZone)

    // Count the start of each detection type on this channel
    List nowTypes = []
    if (people == 1) nowTypes << "Person"
    if (vehicle == 1) nowTypes << "Vehicle"
    if ((animal == 1) || (other == 1)) nowTypes << "Animal"
    if (pkg == 1) nowTypes << "Package"
    if (visitor == 1) nowTypes << "Visitor"
    countAIOnsets(ch, nowTypes)

    safeChildCall(child, "updateAI", [active, type, ts])
    if (!child.hasAttribute("AI Motion")) {
        child.sendEvent(name: "AI Motion", value: (active ? "active" : "inactive"), isStateChange: true)
    }
}

// --- AI detection counts (onset per type per channel, reset at local midnight) ---
def countAIOnsets(int ch, List nowTypes) {
    String key = "aiPrevTypes_ch${ch}"
    List prev = (state."${key}" instanceof List) ? (state."${key}" as List) : []
    List started = nowTypes.findAll { !prev.contains(it) }
    state."${key}" = nowTypes
    if (started.isEmpty()) return
    rollAICounts()
    Map counts = (state.aiCounts instanceof Map) ? (state.aiCounts as Map) : [date: todayStr(), ch: [:]]
    Map chMap = (counts.ch instanceof Map) ? (counts.ch as Map) : [:]
    Map c = (chMap["${ch}"] instanceof Map) ? (chMap["${ch}"] as Map) : [:]
    started.each { t -> c[t] = safeInt(c[t], 0) + 1 }
    chMap["${ch}"] = c
    counts.ch = chMap
    state.aiCounts = counts
    sendEvent(name: "AI Counts Today", value: JsonOutput.toJson(counts))
    dlog "AI onset ch${ch}: ${started}"
}

def rollAICounts() {
    String today = todayStr()
    Map counts = (state.aiCounts instanceof Map) ? (state.aiCounts as Map) : null
    if (counts == null) {
        state.aiCounts = [date: today, ch: [:]]
        sendEvent(name: "AI Counts Today", value: JsonOutput.toJson(state.aiCounts))
        return
    }
    if (counts.date == today) return
    // Keep the finished day as yesterday only if it really was yesterday
    Map y = (counts.date == yesterdayStr()) ? counts : [date: yesterdayStr(), ch: [:]]
    state.aiCountsYesterday = y
    state.aiCounts = [date: today, ch: [:]]
    sendEvent(name: "AI Counts Yesterday", value: JsonOutput.toJson(y))
    sendEvent(name: "AI Counts Today", value: JsonOutput.toJson(state.aiCounts))
    log.info "Reolink Hub: AI counts rolled over to ${today}"
}

def todayStr() { new Date().format("yyyy-MM-dd", location.timeZone) }
def yesterdayStr() { new Date(now() - 86400000L).format("yyyy-MM-dd", location.timeZone) }

// --- Battery (async reply) ---
// A battery read that timed out. Channels that have never given a battery reading are
// written off after BATT_TIMEOUTS_NO_BATTERY slow polls in a row; battery cameras that
// have answered before are never written off (they can time out while asleep).
@Field static final int BATT_TIMEOUTS_NO_BATTERY = 3

def noteBattTimeout(int ch, String msg) {
    String m = (msg ?: "").toLowerCase()
    if (!(m.contains("timed out") || m.contains("timeout"))) return
    if (state."battOk_ch${ch}" == true || state."noBatt_ch${ch}" == true) return
    int n = safeInt(state."battTo_ch${ch}", 0) + 1
    if (n >= BATT_TIMEOUTS_NO_BATTERY) {
        state."noBatt_ch${ch}" = true
        state.remove("battTo_ch${ch}")
        log.info "Reolink Hub: ch${ch}: no battery reading after ${n} tries — skipping battery reads until Initialize"
    } else {
        state."battTo_ch${ch}" = n
    }
}

def handleBattery(int ch, Map task, d0, List rest) {
    Integer pct = extractBatteryPercent(d0)
    if (pct != null) {
        def child = findChild(ch)
        if (child != null) safeChildCall(child, "updateBatteryPercent", [pct])
        if (task.cached != true) state."battCmd_ch${ch}" = task.cmd
        state."battOk_ch${ch}" = true
        state.remove("battTo_ch${ch}")
        return
    }
    if (task.cached == true) {
        // Cached command stopped answering - re-probe on the next slow poll
        state.remove("battCmd_ch${ch}")
        return
    }
    boolean sawWake = (task.sawWake == true) || (safeIntOrNull(d0?.error?.rspCode) == -17)
    int next = safeInt(task.i, 0) + 1
    if (next < BATT_CMDS.size()) {
        rest.add(0, [t: "batt", ch: ch, i: next, sawWake: sawWake])
        return
    }
    // No command returned a battery level. Only remember that if no reply was a
    // camera-wake timeout (-17), so a sleeping battery camera is not written off.
    if (!sawWake) {
        state."noBatt_ch${ch}" = true
        dlog "Battery ch${ch}: no battery reported — skipping battery reads until Initialize"
    }
}

def extractBatteryPercent(d0) {
    try {
        if (d0?.code != 0) return null
        def v = d0?.value
        def candidates = [ v?.battery, v?.Battery, v?.Power, v?.power, v ]
        for (def c : candidates) {
            def p = c?.percent
            if (p == null) p = c?.batteryPercent
            if (p == null) p = c?.battery_level
            if (p == null) p = c?.batteryLevel
            if (p != null) {
                Integer x = safeIntOrNull(p)
                if (x != null && x >= 0 && x <= 100) return x
            }
        }
    } catch (ignored) { }
    return null
}

// --- PIR (async reply) ---
def handlePir(int ch, d0) {
    if (d0?.code != 0) {
        Integer rsp = safeIntOrNull(d0?.error?.rspCode)
        if (rsp == -12 || rsp == -9) {
            state."pirUnsupported_ch${ch}" = true
            dlog "GetPirInfo ch${ch}: PIR not supported (rspCode=${rsp}) — skipping PIR reads until Initialize"
        }
        return
    }
    def pi = d0?.value?.pirInfo ?: d0?.value?.PirInfo
    Integer enable = safeIntOrNull(pi?.enable)
    if (enable == null) return
    def child = findChild(ch)
    if (child != null) safeChildCall(child, "updatePIREnabled", [enable == 1])
}

// --- Push status via GetPushV20 (synchronous) ---
def pollPushStatus(int ch, child) {
    if (!ensureToken()) return null
    Boolean result = null
    try {
        httpGet([uri: "http://${ipAddress}/cgi-bin/api.cgi?cmd=GetPushV20&token=${state.token}&channel=${ch}", contentType: "application/json", timeout: READ_TIMEOUT]) { resp ->
            def d0 = resp?.data?.getAt(0)
            if (isAuthTokenError(d0)) { tokenRejected("GetPushV20"); return }
            def v = d0?.value
            def candidates = [ v?.Push, v?.push, v ]
            Integer enable = null
            for (def c : candidates) {
                def en = c?.enable
                if (en != null) { enable = safeIntOrNull(en); break }
            }
            if (enable == null) return
            boolean isOn = (enable == 1)
            result = isOn
            safeChildCall(child, "updatePushState", [isOn])
        }
    } catch (e) {
        noteReadFailure("GetPushV20", ch, e.message)
    }
    return result
}

// --- Recording enabled via GetRecV20 (synchronous) ---
def pollRecording(int ch, child) {
    if (!ensureToken()) return null
    Boolean result = null
    try {
        httpPostJson([
            uri    : "http://${ipAddress}/cgi-bin/api.cgi?cmd=GetRecV20&token=${state.token}&channel=${ch}",
            body   : [[cmd: "GetRecV20", action: 1, param: [channel: ch]]],
            timeout: READ_TIMEOUT
        ]) { resp ->
            def d0 = resp?.data?.getAt(0)
            if (isAuthTokenError(d0)) { tokenRejected("GetRecV20"); return }
            if (d0?.code != 0) return
            def schedEnable = safeIntOrNull(d0?.value?.Rec?.scheduleEnable)
            if (schedEnable == null) return
            result = (schedEnable == 1)
            safeChildCall(child, "updateRecordingEnabled", [schedEnable == 1])
        }
    } catch (e) {
        noteReadFailure("GetRecV20", ch, e.message)
    }
    return result
}

// --- Read-back after push / recording commands (v20) ---
// One read-back ~10 s after the last command (bulk commands send one per channel;
// overwrite keeps it to a single run). Shows the hub's real settings in Hubitat.
def scheduleReadback() {
    runIn(10, "readbackSettings", [overwrite: true])
}

def readbackSettings() {
    if (state.loggedOut == true) return
    if (state.hubUnavailable == true) {
        runIn(60, "readbackSettings", [overwrite: true])
        return
    }
    if (!ensureToken()) {
        runIn(15, "readbackSettings", [overwrite: true])
        return
    }
    List pushVals = []
    List recVals = []
    List missed = []
    for (Map k : sortedChildren()) {
        if (state.hubUnavailable == true) break
        Boolean p = pollPushStatus(k.ch as int, k.child)
        Boolean r = pollRecording(k.ch as int, k.child)
        if (p != null) pushVals << p
        if (r != null) recVals << r
        if (p == null || r == null) missed << k.ch
    }
    if (state.hubUnavailable == true) {
        log.warn "Reolink Hub: Read-back interrupted — hub unavailable, retrying in 60s"
        runIn(60, "readbackSettings", [overwrite: true])
        return
    }
    String pushAgg = aggregateOnOff(pushVals)
    String recAgg = aggregateOnOff(recVals)
    if (pushAgg) sendEvent(name: "Reolink Push Notification Status", value: pushAgg)
    if (recAgg) sendEvent(name: "Recording Enabled", value: recAgg)
    log.info "Reolink Hub: Read-back - push ${pushAgg ?: 'unknown'} ${pushVals}, recording ${recAgg ?: 'unknown'} ${recVals}" +
             (missed ? " - no reading from ch${missed.join(', ch')}" : "")
}

def aggregateOnOff(List vals) {
    if (vals == null || vals.isEmpty()) return null
    if (vals.every { it == true }) return "All On"
    if (vals.every { it == false }) return "All Off"
    return "Mixed"
}

// A settings read that fails: only a connection-level failure marks the hub unavailable.
// Anything else (e.g. a read timeout while the hub waits on a battery camera) skips this
// channel and is logged once per 10 minutes per command and channel.
def noteReadFailure(String cmd, int ch, String msg) {
    dlog "${cmd} failed ch${ch}: ${msg}"
    if (noteHubError(msg, false)) return
    warnThrottled("read_${cmd}_ch${ch}", "Reolink Hub: ${cmd} ch${ch} failed (${msg}) — channel skipped, polling continues", 600000L)
}

// --- Utils ---
def warnThrottled(String key, String msg, long minMs = 60000L) {
    // log.warn at most once per minMs per key, so a stuck condition is visible without flooding
    long last = safeLong(state."warnTs_${key}", 0L)
    if (now() - last < minMs) return
    state."warnTs_${key}" = now()
    log.warn msg
}

def safeInt(def v, int dflt) {
    try { if (v == null) return dflt; return (v as Integer) } catch (ignored) { }
    try { return v.toString().toInteger() } catch (ignored2) { }
    return dflt
}

def safeIntOrNull(def v) {
    try { if (v == null) return null; return (v as Integer) } catch (ignored) { }
    try { return v.toString().toInteger() } catch (ignored2) { }
    return null
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