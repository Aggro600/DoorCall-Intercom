# DoorCall Intercom – Anleitung

[English](GUIDE.md) · [Zurück zur Übersicht](../README.de.md)

DoorCall macht aus dem Klingeln an der Tür einen **echten Anruf** auf Android-Handys und -Tablets und verbindet die
Geräte in der Wohnung zu einer **Gegensprechanlage**. Die Logik steckt in Home Assistant, die App „Türklingel“ zeigt
an und meldet zurück.

## Inhalt
1. [Was man braucht](#1-was-man-braucht)
2. [Einrichten](#2-einrichten)
3. [Geräte freigeben](#3-geräte-freigeben)
4. [Eine Klingel einstellen](#4-eine-klingel-einstellen)
5. [Wenn es klingelt](#5-wenn-es-klingelt)
6. [Gesprächsrunde: mehrere Geräte](#6-gesprächsrunde-mehrere-geräte)
7. [Sprechen: schnell oder gemischt](#7-sprechen-schnell-oder-gemischt)
8. [Hausruf: Gegensprechen in der Wohnung](#8-hausruf-gegensprechen-in-der-wohnung)
9. [Lautstärke, lautlos, „Nicht stören“](#9-lautstärke-lautlos-nicht-stören)
10. [Ruhe-Zonen](#10-ruhe-zonen)
11. [Für Automationen](#11-für-automationen)
12. [Wenn etwas nicht geht](#12-wenn-etwas-nicht-geht)

---

## 1. Was man braucht
- **Home Assistant** 2026.9 oder neuer, DoorCall über HACS installiert.
- Auf jedem Gerät die **Home-Assistant-App** (sie weckt die Klingel-App) und die App **„Türklingel“**.
- Für Bild und Ton: **go2rtc** (z. B. das in Frigate eingebaute) mit einem Stream der Türkamera. Fürs Gegensprechen
  braucht die Kamera einen Ton-Rückkanal (RTSP/ONVIF-Backchannel, z. B. Reolink Video Doorbell mit PCMU).
- Ein **Auslöser** fürs Klingeln: ein Sensor/Schalter, der beim Klingeln auf „an“ geht, oder ein Knopf/Ereignis.

## 2. Einrichten
1. HACS → Integrationen → **DoorCall Intercom** installieren → Home Assistant neu starten.
2. Einstellungen → Geräte & Dienste → Integration hinzufügen → **DoorCall Intercom**.
3. In der Seitenleiste erscheint **DoorCall** – dort wird alles eingestellt (jede Änderung wird sofort gespeichert).
4. Die App „Türklingel“ auf den Geräten installieren und beim ersten Start die Rechte erlauben
   (Mikrofon, Benachrichtigungen, Anruf im Vollbild; für den Hausruf „Fenster sofort zeigen“).

## 3. Geräte freigeben
Karte **„Geräte für DoorCall“**:
- **Nur ausgewählte Geräte** (Standard): Nur angehakte Geräte dürfen DoorCall nutzen. Andere werden nie angesprochen und
  auch mit installierter App abgewiesen.
- **Alle Geräte automatisch**: jedes Gerät mit Home-Assistant-App.
- **Aus**: es wird nichts neu eingerichtet.

Ein Gerät **anhaken** genügt: DoorCall schickt ihm einen **stillen Gruß** (es klingelt nicht) mit den Adressen. Ist die
App installiert, meldet sie sich und bekommt ein **✓**. Ab dann gehen Probeanruf und Hausruf. „Jetzt Geräte suchen“
wiederholt das sofort; automatisch passiert es beim Start, bei neuen Geräten binnen 10 Minuten und alle 6 Stunden.

## 4. Eine Klingel einstellen
Karte je Klingel (**„Klingel hinzufügen“** für weitere):
- **Auslöser** und **Kamera** (für Anruferbild und Foto bei „verpasst“).
- **Geräte** und **Gruppen**, die klingeln sollen.
- **go2rtc**-Adresse und **Stream**, **Klingeldauer**.
- **Mindestlautstärke** (siehe Abschnitt 9).
- **Gesprächsrunde**: Mitschauen erlauben, Gäste dürfen bedienen, Sprech-Modus (Abschnitte 6 und 7).
- **Tür öffnen mit** + **Knopf freigeben** – bewusst zweistufig; im Anruf muss man zweimal tippen.
- **Schnellantworten** (Ansagen der Klingel, z. B. Reolink), optional „danach Tür öffnen“.
- Eigene **Knöpfe**, **Schalter mit Status** und **Anzeigen** (Schloss, Türkontakt …).
- **Probeanruf** klingelt auf allen eingestellten Geräten.

Gruppen (z. B. „Handys“, „Wohnung“) legt man unten in der Karte **„Gruppen“** an.

## 5. Wenn es klingelt
- Alle eingestellten Geräte klingeln wie bei einem Telefonanruf (auch im Sperrbildschirm), Anruferbild = Türkamera.
- Schon **während des Klingelns** sieht man das Live-Bild und kann – wenn freigegeben – die Tür öffnen.
- **Annehmen**: Bild und Ton von der Tür, das eigene Mikrofon ist zuerst **stumm** (Knopf links im Dock).
- Unten **„⌃ Schalter & Ansagen“** aufziehen: Tür öffnen, Schalter, Schnellantworten, eigene Knöpfe.
- Lautstärketasten: in den App-Einstellungen wählbar, ob sie die Anruf- oder die Medienlautstärke stellen.
- Verpasst? Es gibt eine Meldung mit Foto und den Verlauf **„Wer war da“** in der App.

## 6. Gesprächsrunde: mehrere Geräte
- **Wer zuerst annimmt, leitet.** Die anderen hören auf zu klingeln und bekommen eine stille Meldung
  „Gespräch läuft – mitschauen“. Wer sie antippt, ist als **Gast** dabei (Bild, Ton, Mikro stumm).
- Nur die **Leitung** sieht im aufgezogenen Bereich „Gespräch – du leitest“:
  - **Mitschauen erlaubt/gesperrt** – sperren nimmt alle Gäste sofort raus (sie sehen, wer es beendet hat).
  - **Dazuholen …** – Geräte oder Gruppen anklingeln („Tablet Wohnzimmer holt dich dazu“).
  - **Sprechen**-Modus umstellen und **Mikros sperren** (Gäste hören dann nur zu).
- **Auflegen** als Leitung, während andere dabei sind: „Für alle beenden“ oder „Nur ich gehe – … übernimmt“.

## 7. Sprechen: schnell oder gemischt
- **Schnell – immer nur einer:** Das Mikrofon geht direkt per WebRTC an die Tür, kaum Verzögerung. Wer sein Mikrofon
  anmacht, schaltet den vorigen stumm.
- **Gemischt – alle gleichzeitig:** Jedes Gerät schickt sein Mikrofon an Home Assistant, HA mischt und go2rtc spielt
  den Mix an der Tür ab. Die Stimme kommt etwa 0,3–0,8 s später an.
- Der Rückkanal zur Tür braucht nach dem Annehmen ein paar Sekunden; solange zeigt der Mikrofon-Knopf gelb
  **„Startet …“**. Im Modus „gemischt“ wird er schon beim Klingeln aufgebaut.
- Einstellbar je Klingel (DoorCall-Seite), je Gerät als eigener Standard (App → Einstellungen) und im Gespräch durch
  die Leitung.

## 8. Hausruf: Gegensprechen in der Wohnung
Ganz ohne Klingel: ein Gerät ruft andere Geräte, eine Gruppe oder alle. Der Anrufer ist sofort drin, die anderen
klingeln („Tablet Wohnzimmer ruft dich“). Alle, die angenommen haben, können **gleichzeitig** reden – HA mischt so,
dass jedes Gerät alle anderen hört.

**Starten**
- In der App: **„📢 Hausruf …“**.
- **Dashboard-Knopf** – der Anruf startet immer von dem Gerät, auf dem gedrückt wird:
  ```yaml
  type: button
  name: Hausruf
  icon: mdi:account-voice
  tap_action:
    action: url
    url_path: doorcall://hausruf                        # öffnet die Ziel-Auswahl
    # url_path: doorcall://hausruf?an=mobile_app_tablet_kuche   # festes Ziel
    # url_path: doorcall://hausruf?an=alle                     # alle
    # url_path: doorcall://hausruf?an=gruppe:Handys             # Gruppe
  ```
- **Automation/Skript**: Aktion `doorcall.hausruf` mit `von` (rufendes Gerät) und `an` (Liste, Standard „alle“).

**Im Gespräch** steht groß in der Mitte, wer dabei ist oder bei wem es noch klingelt. Haben alle anderen aufgelegt,
erscheint gelb **„Du bist allein im Gespräch“** mit „Nochmal rufen“; nach 30 s legt die App von selbst auf.

## 9. Lautstärke, lautlos, „Nicht stören“
- **Mindestlautstärke** (je Klingel, Standard 70 %): Ist ein Handy lautlos, auf Vibration oder leiser, klingelt die App
  trotzdem – über den Wecker-Kanal – und stellt die Lautstärke danach zurück.
- **„Nicht stören“ des Handys** wird respektiert: Ist es an, klingelt die App nicht laut durch. Tipp: auf der Arbeit
  „Nicht stören“ per Tasker/Routine nach **Ort** schalten und den Modus **„Priorität“** nehmen – „Keine
  Unterbrechungen“ schaltet auch **Wecker** stumm.
- **„Nicht stören“ in der App** (für 1 Stunde / bis Uhrzeit): Klingeln kommt nur still als Meldung.

## 10. Ruhe-Zonen
Optional in „Geräte für DoorCall“: Ist ein **Handy** in einer seiner Ruhe-Zonen (Home-Assistant-Zone), klingelt DoorCall
dort nicht (Haustür, Hausruf, Dazuholen). Entscheidet Home Assistant über den Standort der Home-Assistant-App. Tablets
ohne Standort-Tracker können das nicht. Zonen unter ~150 m Radius erkennt ein Handy oft nicht zuverlässig.

## 11. Für Automationen
Ereignis **`doorcall_event`** mit `aktion`, `geraet`, `anruf`, `klingel`, `name`:
`angenommen`, `abgelehnt`, `verpasst`, `aufgelegt`, `oeffnen`, `hausruf`, `neues_geraet`, `ruhe` u. a.

## 12. Wenn etwas nicht geht
| Problem | Lösung |
|---|---|
| In der App fehlen Probeanruf/Hausruf | Gerät in „Geräte für DoorCall“ anhaken → „Jetzt Geräte suchen“; bis ✓ erscheint |
| Probeanruf: „Gerät nicht eingetragen“ | Gerät bei der Klingel anhaken (Hausruf geht auch ohne) |
| Handy klingelt nicht / spät | In Android die Akku-Optimierung für **Home Assistant** und **Türklingel** ausschalten („Nicht eingeschränkt“) |
| Kein Bild | go2rtc-Adresse und Stream prüfen; das Gerät muss go2rtc erreichen (von unterwegs: externe HA-Adresse + WebRTC-Port) |
| Draußen hört man nichts | Kamera braucht Rückkanal; Mikrofon-Knopf darf nicht gelb „Startet …“ zeigen; im Gespräch Mikrofon an |
| Hausruf-Fenster geht nicht von selbst auf | App → „Fenster sofort zeigen erlauben (über anderen Apps)“ |
| Dashboard-Knopf `doorcall://` tut nichts | App ≥ 5.6 nötig; im Kiosk-Browser das Öffnen anderer Apps erlauben |
| Chrome erreicht Home Assistant nicht (Android 17) | Chrome die Berechtigung **„Lokales Netzwerk“** erlauben |
