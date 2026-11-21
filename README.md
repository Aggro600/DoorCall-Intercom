# DoorCall Intercom

**Your doorbell rings as a real phone call** on Android phones and tablets – with live video, two-way audio,
door opener, switches and quick replies. Several devices form one call: one leads, the others can watch along,
be brought in or locked out.

[Deutsch](README.de.md)

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
- **Groups** (e.g. "Phones", "Flat") usable as call targets and for bringing people in.
- **Missed calls, history** ("who was there" with photos) and "do not disturb" times in the app.
- From outside your home: works through your Home Assistant external URL (signalling via HA; media needs
  go2rtc's WebRTC port reachable).

## Requirements

- Home Assistant 2026.9 or newer.
- On every device: the **Home Assistant Companion app** (it wakes the doorbell app) and the **DoorCall app
  "Türklingel"** (Android; currently in closed testing on Google Play – the source is in [`app/`](app)).
- go2rtc with a stream of your doorbell camera. Two-way audio needs a backchannel (e.g. RTSP/ONVIF backchannel;
  a Reolink Video Doorbell accepts PCMU).

## Installation

1. HACS → Integrations → ⋮ → Custom repositories → `https://github.com/Aggro600/DoorCall-Intercom`, type
   *Integration* → install **DoorCall Intercom** → restart Home Assistant.
2. Settings → Devices & services → Add integration → **DoorCall Intercom**.
3. Open **DoorCall** in the sidebar: add a doorbell (trigger, camera), tick the devices, enter go2rtc address and
   stream, optionally door opener, switches, quick replies. Press **Test call**.

Coming from ESPHomeRemote (≤ 2026.11.20)? DoorCall takes over the doorbell call settings automatically on first
start (same webhook address – installed apps keep working).

## For automations

Every action of the app fires the event `doorcall_event` with `aktion` (e.g. `angenommen` = answered,
`abgelehnt`, `verpasst`, `aufgelegt`, `oeffnen`), `geraet`, `anruf`, `klingel`, `name`.

## Security

- The app talks to a Home Assistant webhook; switching, opening and talking need a per-call, per-device key.
- "Open door" must be enabled explicitly and only works during a call (or while it rings) on that device.
- go2rtc stays inside your network; from outside, HA only forwards the WebRTC offer.

## License

MIT
