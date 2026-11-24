# DoorCall Intercom

**Your doorbell rings as a real phone call** on Android phones and tablets – with live video, two-way audio,
door opener, switches and quick replies. Several devices form one call: one leads, the others can watch along,
be brought in or locked out.

[Deutsch](README.de.md) · **[Step-by-step guide](docs/GUIDE.md)**

## What it does

- **Doorbell → call:** when the doorbell's trigger fires (e.g. `binary_sensor`/`input_boolean` turns on, or a
  `button`/`event` changes), every selected device gets a real Android call (lock screen, headset, car, watch –
  like a phone call). The caller picture is the doorbell camera.
- **In the call:** live video and two-way audio via [go2rtc](https://github.com/AlexxIT/go2rtc) (WebRTC, e.g. the
  go2rtc built into Frigate), "open door" with a safety double-tap, switches with state, status displays, quick
  replies (e.g. Reolink "play quick reply message"), own buttons.
- **Call round:** whoever answers first leads. The others stop ringing and get a quiet "call in progress –
  watch along" notification. The lead can lock watching (guests are removed and are told why), bring in more
  devices or groups ("anklingeln"), and decide when hanging up: end for everyone or hand over the lead.
- **Speaking with several devices** – your choice, explained in the settings:
  - *Fast:* the microphone goes straight to the door via WebRTC, almost no delay. Only one speaks at a time –
    whoever turns on their mic mutes the previous one.
  - *Mixed:* every device sends its microphone to Home Assistant, HA mixes all voices and go2rtc plays the mix
    at the door. Several people can talk at once; the voice arrives about 0.3–0.8 s later.
  - The lead can lock single microphones (listen only).
- **Intercom inside the home ("Hausruf")** – no doorbell involved: one device calls other devices, groups or
  everyone. The caller is in right away, the others ring with "Living room tablet calls". Everyone who answered can
  talk at the same time (Home Assistant mixes, each device hears all others). Start it from the app ("Hausruf …"),
  from a dashboard button or an automation with the action `doorcall.hausruf`:
  ```yaml
  action: doorcall.hausruf
  data:
    von: mobile_app_tablet_living_room   # caller
    an: ["alle"]                         # devices, "gruppe:<name>" or "alle"
  ```
- **Dashboard button for Hausruf:** a button with `tap_action: {action: url, url_path: "doorcall://hausruf"}` opens the target selection on the device where it was pressed (Fully, Home Assistant app) – the call always starts from that device. `doorcall://hausruf?an=mobile_app_kitchen_tablet` calls a fixed target.
- **Groups** (e.g. "Phones", "Flat") usable as call targets and for bringing people in.
- **Missed calls, history** ("who was there" with photos) and "do not disturb" times in the app.
- From outside your home: works through your Home Assistant external URL (signalling via HA; media needs
  go2rtc's WebRTC port reachable).

## Requirements

- Home Assistant 2026.9 or newer.
- On every device: the **Home Assistant Companion app** (it wakes the doorbell app) and the **DoorCall app
  "Türklingel"** (Android 10+). **Download the APK from the [latest release](https://github.com/Aggro600/DoorCall-Intercom/releases/latest)** (file
  `DoorCall-Tuerklingel-<version>.apk`, install it on the phone – allow "install unknown apps" once). A Google Play
  listing is in closed testing. Source: [`app/`](app).
- go2rtc with a stream of your doorbell camera. Two-way audio needs a backchannel (e.g. RTSP/ONVIF backchannel;
  a Reolink Video Doorbell accepts PCMU).

## Installation

1. HACS → search for **DoorCall Intercom** → download → restart Home Assistant.
   (Not listed yet? HACS → ⋮ → Custom repositories → `https://github.com/Aggro600/DoorCall-Intercom`, type *Integration*.)
2. Settings → Devices & services → Add integration → **DoorCall Intercom**.
3. Install the app „Türklingel“ ([APK from the latest release](https://github.com/Aggro600/DoorCall-Intercom/releases/latest)) on the phones/tablets and tick them under **DoorCall › Devices for DoorCall** – they set themselves up (silent greeting, no ringing). Default „Only selected devices“: other phones are never contacted and are rejected even with the app. Alternatively „All devices automatically“ or „Off“.
4. Open **DoorCall** in the sidebar: add a doorbell (trigger, camera), tick the devices, enter go2rtc address and
   stream, optionally door opener, switches, quick replies. Press **Test call**.

Coming from ESPHomeRemote (≤ 2026.11.20)? Disable or remove the ESPHomeRemote integration first; then the setup dialog
of DoorCall offers **"Import the doorbell calls from ESPHomeRemote"** (only if old settings exist). Nothing is imported
without that tick, and never while ESPHomeRemote is loaded.

## For automations

Every action of the app fires the event `doorcall_event` with `aktion` (e.g. `angenommen` = answered,
`abgelehnt`, `verpasst`, `aufgelegt`, `oeffnen`), `geraet`, `anruf`, `klingel`, `name`.

## Security

- The app talks to a Home Assistant webhook. **Every** message about a call (answer, decline, hang up, switch, open,
  talk …) needs the per-call, per-device key the device got with that call. Messages without a call (start an
  intercom call, test call, "hello") need a per-device key handed out with the silent setup – apps older than 7.6
  must be updated.
- "Open door" must be enabled explicitly and only works during a call (or while it rings) on that device.
- go2rtc stays inside your network; from outside, HA only forwards the WebRTC offer.

## License

MIT
