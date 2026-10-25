# Reolink Home Hub Pro – Hubitat drivers

Hubitat Elevation drivers for Reolink cameras connected through a **Reolink Home Hub Pro**. Everything runs over the hub's local HTTP API; no Reolink cloud account or internet connection is needed.

A parent driver logs into the Home Hub Pro and polls it; each camera channel becomes a child device with its own driver.

## Drivers

| Driver (Type name in Hubitat) | Version | Use for | Import URL |
|---|---|---|---|
| Reolink Hub Parent | v25 | The Home Hub Pro itself: login, polling, creating child devices | [raw](https://raw.githubusercontent.com/mrfy8/reolink/main/Reolink%20Home%20Hub%20Pro%20-%20Parent.groovy) |
| Reolink Altas 2K Camera Child | v14 | Atlas 2K battery camera: spotlight or status LED, PIR, battery | [raw](https://raw.githubusercontent.com/mrfy8/reolink/main/Reolink%20Altas%202K%20Camera%20-%20Child.groovy) |
| Reolink Argus MagiCam Camera Child | v1 | Argus MagiCam (AA lithium battery): status LED, PIR, battery | [raw](https://raw.githubusercontent.com/mrfy8/reolink/main/Reolink%20Argus%20MagiCam%20Camera%20-%20Child.groovy) |
| Reolink CX-820 Camera Child | v8 | CX-820 PoE camera: spotlight (no battery, no PIR) | [raw](https://raw.githubusercontent.com/mrfy8/reolink/main/Reolink%20CX-820%20Camera%20-%20Child.groovy) |
| Reolink Video Doorbell Child | v3 | Battery video doorbell: press detection, light, auto reply, PIR | [raw](https://raw.githubusercontent.com/mrfy8/reolink/main/Reolink%20Video%20Doorbell%20-%20Child.groovy) |
| Reolink Camera Child — Stub | v1 | Placeholder given to new child devices until you pick a camera driver | [raw](https://raw.githubusercontent.com/mrfy8/reolink/main/Reolink%20Camera%20Child%20-%20Stub.groovy) |

Camera drivers require **Reolink Hub Parent v22 or later**.

## Features

- **AI motion** – person, vehicle, animal (plus package and visitor on the doorbell), exposed as both `AI Motion` and the standard `Motion` capability so Rule Machine and other apps can use the cameras as motion sensors. A configurable hold time keeps motion active briefly after detection stops.
- **Detection details** – last AI detection type and time per camera, and daily AI counts per type on the parent (`AI Counts Today` / `AI Counts Yesterday`).
- **Push notifications, recording and PIR on/off** – per camera, or for all cameras at once from the parent. About 10 seconds after a change, the parent reads the hub's real settings back so each device shows what the hub is actually doing (`All On`, `All Off` or `Mixed` on the parent).
- **Recording detection** – `Recording Active`, `Last Recording Start/End/Length/Name/Size` from the hub's Search API.
- **Lights** – the `Switch` capability controls the spotlight, status LED or doorbell light, with an optional auto-off safety timer.
- **Snapshots** – single photos or bursts saved to Hubitat's File Manager, with retention by age plus minimum and maximum file counts.
- **Battery cameras** – battery percentage, inferred standby/wake tracking, and a light "heartbeat" to keep a camera awake while its light is on.
- **Doorbell** – `Doorbell Pressed` with a configurable reset time, and a fast press poll independent of the main poll.
- **Resilient** – asynchronous, queued requests to keep hub load low; automatic re-login on token expiry; a watchdog that restarts polling if it stalls.

## Requirements

- Reolink Home Hub Pro on your local network, with the cameras already added to it in the Reolink app.
- The hub's local admin username and password. (The Home Hub Pro does not let you create extra users.)
- For CX-820 recording on/off: Home Hub Pro firmware v3.3.0.466 or later.

## Installation

1. In Hubitat, go to **Drivers Code → New Driver → Import**, paste the parent's raw URL from the table above, then **Save**. Repeat for the Stub and for each camera driver you need.
2. Go to **Devices → Add Device → Virtual**, give it a name (e.g. `Reolink Home Hub Pro`) and set **Type** to `Reolink Hub Parent`.
3. In the device's **Preferences**, enter the hub's IP address, username, password and the number of channels (cameras), then **Save Preferences**.
4. Click **Initialize** to log in. `Connection Status` should show the hub as connected.
5. Click **createChildDevices**. One child device is created per channel, each using the Stub driver.
6. For each child, open the device, change **Type** to the driver for that camera model, and **Save Device**.

## Notes

- Push notification changes made through the API apply globally but are **not shown in the Reolink app**.
- PIR on/off applies to battery cameras only; PoE cameras have no PIR sensor.
- The Atlas driver is named **"Altas"** in Hubitat. The spelling is kept deliberately, because renaming a driver breaks devices already using it.
- Each driver can clear old states for troubleshooting: uncomment the three `Clear …` command lines in its metadata.
- Debug logging turns itself off after 2 hours.

## Changelog

Each driver's full version history is in the comment block at the top of its file.

## Disclaimer

Community drivers, not affiliated with or supported by Reolink. Use at your own risk.
