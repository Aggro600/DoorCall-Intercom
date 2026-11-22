# DoorCall Intercom – Guide

[Deutsch](ANLEITUNG.md) · [Back to overview](../README.md)

DoorCall turns a doorbell ring into a **real phone call** on Android phones and tablets and links the devices in your
home into an **intercom**. The logic lives in Home Assistant; the app "Türklingel" shows and reports back.

## Contents
1. [Requirements](#1-requirements)
2. [Setup](#2-setup)
3. [Approving devices](#3-approving-devices)
4. [Configuring a doorbell](#4-configuring-a-doorbell)
5. [When it rings](#5-when-it-rings)
6. [Call round: several devices](#6-call-round-several-devices)
7. [Speaking: fast or mixed](#7-speaking-fast-or-mixed)
8. [Hausruf: intercom inside the home](#8-hausruf-intercom-inside-the-home)
9. [Volume, silent mode, Do Not Disturb](#9-volume-silent-mode-do-not-disturb)
10. [Quiet zones](#10-quiet-zones)
11. [For automations](#11-for-automations)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Requirements
- **Home Assistant** 2026.9 or newer, DoorCall installed via HACS.
- On every device the **Home Assistant app** (it wakes the doorbell app) and the app **"Türklingel"**.
- For video and audio: **go2rtc** (e.g. the one built into Frigate) with a stream of the door camera. Two-way audio needs
  an audio backchannel (RTSP/ONVIF backchannel, e.g. Reolink Video Doorbell with PCMU).
- A **trigger** for the ring: a sensor/switch that turns on when someone rings, or a button/event.

## 2. Setup
1. HACS → Integrations → install **DoorCall Intercom** → restart Home Assistant.
2. Settings → Devices & services → Add integration → **DoorCall Intercom**.
3. **DoorCall** appears in the sidebar – everything is configured there (each change is saved immediately).
4. Install the app "Türklingel" on the devices and allow the permissions on first start
   (microphone, notifications, full-screen call; for Hausruf "show window right away").

## 3. Approving devices
Card **"Devices for DoorCall"**:
- **Only selected devices** (default): only ticked devices may use DoorCall. Others are never contacted and are
  rejected even with the app installed.
- **All devices automatically**: every device with the Home Assistant app.
- **Off**: nothing new is set up.

**Per-device settings** (same card):
- **Ring volume**: "as doorbell", "as on the device" (normal, silent stays silent) or a fixed value that is enforced –
  e.g. tablets 70 %, phones "as on the device".
- **Open ring window right away**: when it rings, the window with live video opens at once (good for kiosk tablets).
- **May be put through**: for Hausruf "put through directly" the device answers automatically.

Ticking a device is enough: DoorCall sends it a **silent greeting** (no ringing) with its addresses. If the app is
installed it answers and gets a **✓**; test call and Hausruf then work. "Search devices now" repeats this right away;
it also happens at start-up, within 10 minutes for new devices and every 6 hours.

## 4. Configuring a doorbell
One card per doorbell (**"Add doorbell"** for more):
- **Trigger** and **camera** (caller picture and photo for missed calls).
- **Devices** and **groups** that ring.
- **go2rtc** address and **stream**, **ring duration**.
- **Minimum ring volume** (see section 9).
- **Call round**: allow watching along, guests may operate, speaking mode (sections 6 and 7).
- **Open door with** + **enable the button** – deliberately two steps; in the call you tap twice.
- **Quick replies** (doorbell announcements, e.g. Reolink), optionally "open the door afterwards", with a
  **pre-selected** reply. The order of the replies in the app is freely sortable (arrows), single ones can be hidden.
- **Menu in the call**: order and visibility of the sections in the pulled-up sheet (quick replies, open door, call,
  switches, own buttons) – sort with arrows, show/hide with ticks.
- Own **buttons**, **switches with state** and **displays** (lock, door contact …).
- **Test call** rings on all configured devices.

Groups (e.g. "Phones", "Home") are created in the **"Groups"** card at the bottom.

## 5. When it rings
- All configured devices ring like a phone call (also on the lock screen); the caller picture is the door camera.
- **While ringing** you already see live video and – if enabled – can open the door.
- **Answer**: video and audio from the door; your own microphone starts **muted** (left dock button).
- Pull up **"⌃ Switches & replies"**: open door, switches, quick replies, own buttons.
- Volume keys: in the app settings choose whether they control call or media volume.
- Missed? You get a notification with a photo and the history **"Who was there"** in the app.

## 6. Call round: several devices
- **Whoever answers first leads.** The others stop ringing and get a quiet notification "call in progress – watch
  along". Tapping it joins as a **guest** (video, audio, mic muted).
- Only the **lead** sees "Call – you lead" in the pulled-up area:
  - **Watching allowed/locked** – locking removes all guests at once (they see who ended it).
  - **Bring in …** – ring devices or groups ("Living room tablet brings you in").
  - Switch the **speaking mode** and **lock microphones** (guests then only listen).
  - **Guests may co-lead** – unlocks this menu for everyone (default per doorbell).
- **Hanging up** as lead while others are in: "End for everyone" or "Only I leave – … takes over".

## 7. Speaking: fast or mixed
- **Fast – one at a time:** the mic goes straight to the door via WebRTC, almost no delay. Turning on your mic mutes the
  previous speaker.
- **Mixed – everyone at once:** every device sends its mic to Home Assistant, HA mixes and go2rtc plays the mix at the
  door. The voice arrives about 0.3–0.8 s later.
- The backchannel to the door takes a few seconds after answering; meanwhile the mic button shows yellow
  **"Startet …"**. In mixed mode it is set up already while ringing.
- Set per doorbell (DoorCall page), per device as a personal default (app → settings) and during the call by the lead.

## 8. Hausruf: intercom inside the home
No doorbell involved: one device calls other devices, a group or everyone. The caller is in right away, the others
ring ("Living room tablet calls you"). Everyone who answered can talk **at the same time** – HA mixes so that each
device hears all others.

**Start**
- In the app: **"📢 Hausruf …"**.
- **Dashboard card with selection inside Home Assistant** (recommended, works everywhere – also on work phones with a
  work profile):
  ```yaml
  type: custom:doorcall-hausruf-card
  name: Hausruf
  icon: mdi:account-voice
  # an: alle            # optional: fixed target, no selection
  ```
  The card **detects by itself** which device it is pressed on – the same card works on every device:
  1. device model in the Home Assistant app's browser (if only one device has that model),
  2. IP address of the connection (the address the app "Türklingel" reports from, or the Fully Kiosk address of a
     device with the same name) – tells e.g. two identical tablets apart,
  3. the logged-in user has only one device.
  Only if that is ambiguous it asks once "Which device is this?". The window shows "From: … (detected)"; "other device"
  pins a device, "↺ Detect automatically" resets. After that: pick the target – done.
- **Dashboard button via the app** – opens the selection of the app "Türklingel" on the device where it is pressed:
  ```yaml
  type: button
  name: Hausruf
  icon: mdi:account-voice
  tap_action:
    action: url
    url_path: doorcall://hausruf                          # opens the target selection
    # url_path: doorcall://hausruf?an=mobile_app_kitchen_tablet  # fixed target
    # url_path: doorcall://hausruf?an=alle                      # everyone
    # url_path: doorcall://hausruf?an=gruppe:Phones              # group
  ```
- **Automation/script**: action `doorcall.hausruf` with `von` (calling device) and `an` (list, default "alle").

**How should it ring?** After choosing the target (app, card, action with `art:`):
- **Normal** – as set on the device,
- **Loud** – force the volume,
- **Put through directly** – targets answer automatically (only devices with "may be put through", their mic starts
  muted; others ring loudly).

**Who should answer?** For "Everyone" or a group (not with "put through directly") a second question follows (action: `runde:`):
- **One** (`einer`) – whoever answers first talks to you; the others stop ringing and get a quiet "call in progress –
  tap to join".
- **All** (`alle`) – everyone keeps ringing, everyone who answers is in (action default).

**During the call** the middle of the screen shows who is in, or where it is still ringing. When everybody else has hung
up you see a yellow **"You are alone in the call"** with "Call again"; after 30 s the app hangs up by itself.

## 9. Volume, silent mode, Do Not Disturb
- **Minimum ring volume** (per doorbell, default 70 %): if a phone is silent, on vibrate or quieter, the app still
  rings – through the alarm channel – and restores the volume afterwards.
- The phone's **Do Not Disturb** is respected: while it is on, the app does not ring through. Tip: switch DND at work by
  **location** (Tasker/Routines) and use **"Priority"** – "Total silence" also mutes **alarm clocks**.
- **"Do not disturb" inside the app** (for 1 hour / until a time): rings only arrive as a silent notification.
- **Data saver** (app → settings: off / only on mobile data / always): a still image of the door with "Refresh" instead
  of live video; "Start live" turns on video and talking when needed.

## 10. Quiet zones
Optional in "Devices for DoorCall": while a **phone** is in one of its quiet zones (Home Assistant zone), DoorCall does
not ring it (doorbell, Hausruf, bringing in). Decided by Home Assistant from the Home Assistant app location. Tablets
without a location tracker cannot use this. Zones below ~150 m radius are often not detected reliably by phones.

## 11. For automations
Event **`doorcall_event`** with `aktion`, `geraet`, `anruf`, `klingel`, `name`:
`angenommen` (answered), `abgelehnt` (declined), `verpasst` (missed), `aufgelegt` (hung up), `oeffnen` (door opened),
`hausruf`, `neues_geraet` (new device), `ruhe` (quiet zone) and more.

## 12. Troubleshooting
| Problem | Fix |
|---|---|
| Test call/Hausruf missing in the app | Tick the device in "Devices for DoorCall" → "Search devices now"; wait for ✓ |
| Test call: "device not configured" | Tick the device at the doorbell (Hausruf works without) |
| Phone rings late or not at all | Disable battery optimisation for **Home Assistant** and **Türklingel** ("Unrestricted") |
| No video | Check go2rtc address and stream; the device must reach go2rtc (away from home: external HA URL + WebRTC port) |
| Nobody hears you outside | Camera needs a backchannel; mic button must not show yellow "Startet …"; turn the mic on |
| Hausruf window does not open by itself | App → "allow showing the window right away (over other apps)" |
| Dashboard button `doorcall://` does nothing / "no app available" | App ≥ 5.6 required; allow opening other apps in your kiosk browser; work phone with work profile: links cannot cross profiles → use the card `custom:doorcall-hausruf-card` instead |
| Chrome cannot reach Home Assistant (Android 17) | Allow Chrome the **"Local network"** permission |
| Popup cannot be tapped on a tablet | Use the **"Video ansehen"** button in the popup (some tablets do not open call popups when tapping the text) – or per device "open ring window right away" |
| Away from home nobody hears you (Hausruf/mixed) | App ≥ 5.9: via the external address (reverse proxy such as Nginx Proxy Manager) the app sends the mic in short packets |
