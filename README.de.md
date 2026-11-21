# DoorCall Intercom

**Klingeln an der Tür kommt als echter Anruf** auf Android-Handys und -Tablets – mit Live-Bild, Gegensprechen,
Türöffner, Schaltern und Schnellantworten. Mehrere Geräte bilden ein Gespräch: einer leitet, die anderen schauen
mit, werden dazugeholt oder ausgesperrt.

[English](README.md) · **[Anleitung Schritt für Schritt](docs/ANLEITUNG.md)**

## Was es kann

- **Klingeln → Anruf:** Löst der Auslöser der Klingel aus (z. B. `binary_sensor`/`input_boolean` geht auf „an“,
  oder ein `button`/`event` ändert sich), bekommt jedes gewählte Gerät einen echten Android-Anruf (Sperrbildschirm,
  Headset, Auto, Uhr – wie ein Telefonat). Anruferbild ist die Klingelkamera.
- **Im Gespräch:** Live-Bild und Gegensprechen über [go2rtc](https://github.com/AlexxIT/go2rtc) (WebRTC, z. B. das
  go2rtc in Frigate), „Tür öffnen“ mit Sicherheitsabfrage, Schalter mit Status, Anzeigen, Schnellantworten
  (z. B. Reolink „Schnellantwort abspielen“), eigene Knöpfe.
- **Gesprächsrunde:** Wer zuerst annimmt, leitet. Die anderen hören auf zu klingeln und bekommen eine stille
  Meldung „Gespräch läuft – mitschauen“. Die Leitung kann Mitschauen sperren (Gäste fliegen raus und sehen warum),
  Geräte oder Gruppen dazuholen (anklingeln) und beim Auflegen entscheiden: für alle beenden oder Leitung abgeben.
- **Sprechen mit mehreren Geräten** – wählbar, mit Erklärung in den Einstellungen:
  - *Schnell:* Das Mikrofon geht direkt per WebRTC an die Tür, kaum Verzögerung. Es spricht immer nur einer –
    wer sein Mikrofon anmacht, schaltet den vorigen stumm.
  - *Gemischt:* Jedes Gerät schickt sein Mikrofon an Home Assistant, HA mischt alle Stimmen und go2rtc spielt den
    Mix an der Tür ab. Mehrere können gleichzeitig reden; die Stimme kommt etwa 0,3–0,8 s später an.
  - Die Leitung kann einzelne Mikrofone sperren (nur zuhören).
- **Hausruf – Gegensprechen in der Wohnung**, ganz ohne Klingel: Ein Gerät ruft andere Geräte, Gruppen oder alle.
  Der Anrufer ist sofort drin, bei den anderen klingelt es mit „Tablet Wohnzimmer ruft“. Alle, die angenommen haben,
  können gleichzeitig reden (Home Assistant mischt, jedes Gerät hört alle anderen). Starten aus der App
  („Hausruf …“), per Dashboard-Knopf oder Automation mit der Aktion `doorcall.hausruf`:
  ```yaml
  action: doorcall.hausruf
  data:
    von: mobile_app_tablet_wohnzimmer   # wer ruft
    an: ["alle"]                        # Geräte, "gruppe:<Name>" oder "alle"
  ```
  Damit das Gesprächsfenster sofort aufgeht, in der App „Fenster sofort zeigen erlauben“ antippen.
- **Dashboard-Knopf für den Hausruf:** ein Knopf mit `tap_action: {action: url, url_path: "doorcall://hausruf"}` öffnet die Ziel-Auswahl auf dem Gerät, auf dem gedrückt wurde (Fully, Home-Assistant-App) – der Anruf startet immer von diesem Gerät. `doorcall://hausruf?an=mobile_app_tablet_kuche` ruft ein festes Ziel.
- **Gruppen** (z. B. „Handys“, „Wohnung“) als Anrufziel und zum Dazuholen.
- **Verpasste Anrufe, Verlauf** („Wer war da“ mit Fotos) und „Nicht stören“-Zeiten in der App.
- Von unterwegs: über die externe Adresse von Home Assistant (Signal über HA; Bild/Ton brauchen den
  WebRTC-Port von go2rtc).

## Voraussetzungen

- Home Assistant 2026.9 oder neuer.
- Auf jedem Gerät: die **Home-Assistant-App** (sie weckt die Klingel-App) und die **App „Türklingel“**
  (Android; derzeit im geschlossenen Test bei Google Play – Quellcode in [`app/`](app)).
- go2rtc mit einem Stream der Klingelkamera. Gegensprechen braucht einen Rückkanal (z. B. RTSP/ONVIF-Backchannel;
  eine Reolink Video Doorbell nimmt PCMU an).

## Installation

1. HACS → Integrationen → ⋮ → Benutzerdefinierte Repositories → `https://github.com/Aggro600/DoorCall-Intercom`,
   Typ *Integration* → **DoorCall Intercom** installieren → Home Assistant neu starten.
2. Einstellungen → Geräte & Dienste → Integration hinzufügen → **DoorCall Intercom**.
3. Die App „Türklingel“ auf den Handys/Tablets installieren und sie unter **DoorCall › Geräte für DoorCall** anhaken – sie richten sich dann von selbst ein (stiller Gruß, es klingelt nicht). Standard „Nur ausgewählte Geräte“: andere Handys werden nie angesprochen und auch mit App abgewiesen. Alternativ „Alle Geräte automatisch“ oder „Aus“.
4. In der Seitenleiste **DoorCall** öffnen: Klingel anlegen (Auslöser, Kamera), Geräte anhaken, go2rtc-Adresse und
   Stream eintragen, optional Türöffner, Schalter, Schnellantworten. **Probeanruf** drücken.

Vorher mit ESPHomeRemote (bis 2026.11.20) genutzt? DoorCall übernimmt die Anruf-Einstellungen beim ersten Start
automatisch (gleiche Webhook-Adresse – installierte Apps funktionieren weiter).

## Für Automationen

Jede Aktion der App löst das Ereignis `doorcall_event` aus, mit `aktion` (z. B. `angenommen`, `abgelehnt`,
`verpasst`, `aufgelegt`, `oeffnen`), `geraet`, `anruf`, `klingel`, `name`.

## Sicherheit

- Die App spricht mit einem Webhook von Home Assistant; Schalten, Öffnen und Sprechen brauchen einen Schlüssel je
  Anruf und Gerät.
- „Tür öffnen“ muss ausdrücklich freigegeben werden und geht nur während eines Anrufs (oder solange es klingelt)
  auf diesem Gerät.
- go2rtc bleibt im Heimnetz; von unterwegs reicht HA nur das WebRTC-Angebot weiter.

## Lizenz

MIT
