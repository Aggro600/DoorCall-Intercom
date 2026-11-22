"""DoorCall Intercom - Kern: Klingeln als Anruf aufs Handy/Tablet (App "Türklingel", Paket de.esphomeremote.klingel).

Herausgelöst aus ESPHomeRemote (2026-11-21). Daten in .storage/doorcall (Klingeln, Gruppen, Webhook-ID).

Je Klingel (daten["klingeln"][i]: id, name, ausloeser, kamera, anruf{...}; Seitenleiste "DoorCall"):
  geraete       Liste der Home-Assistant-App-Geräte (notify-Dienst "mobile_app_<name>"), die klingeln
  go2rtc        Adresse von go2rtc (Video + Gegensprechen per WebRTC), z. B. http://192.168.1.10:1984
  src           go2rtc-Stream der Tür-Kamera
  dauer         Sekunden, wie lange es klingelt (danach "verpasst")
  oeffnen       Entität für "Tür öffnen" (switch/button/lock/script)
  oeffnen_frei  nur dann zeigt die App den Knopf und HA schaltet - und nur während eines angenommenen Anrufs

Ablauf: Auslöser der Klingel (Zustand -> "on" bzw. jede Änderung bei button/event) -> anrufen(): Weckruf über die Companion-App
(message command_broadcast_intent, Extras "d:<base64url JSON>"). Die App meldet per Webhook zurück:
angenommen (die anderen Geräte hören auf), abgelehnt, verpasst, aufgelegt, oeffnen. Jede Meldung geht auch als
Ereignis "doorcall_event" auf den Bus (für eigene Automationen).

Gesprächsrunde (2026-11-21, Nutzerwunsch): Die Logik liegt hier in HA, die App zeigt nur an.
  - Wer zuerst annimmt, leitet das Gespräch. Die anderen hören auf zu klingeln und bekommen (wenn "Mitschauen" frei ist)
    ein Angebot "Gespräch läuft – mitschauen"; wer annimmt, kommt als Gast dazu (Bild + Ton, Mikro stumm).
  - Sprechen: immer nur einer - wer sein Mikro anmacht, wird Sprecher, alle anderen schalten ihr Mikro aus.
  - Nur der Leiter: Mitschauen erlauben/sperren (sperren wirft alle Gäste raus) und Geräte/Gruppen dazuholen
    (die klingeln dann mit "<Leiter> holt dich dazu").
  - Legt der Leiter auf, entscheidet er: für alle beenden oder Leitung an einen Gast abgeben.
  - Gruppen (daten["gruppen"]) in der Seitenleiste; eine Klingel kann Gruppen statt einzelner Geräte anrufen.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import logging
import re
import secrets
import time

import voluptuous as vol
from aiohttp import ClientTimeout, web

from homeassistant.components.http import HomeAssistantView
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from homeassistant.components import webhook, websocket_api
from homeassistant.core import HomeAssistant, callback

_LOGGER = logging.getLogger(__name__)
DOMAIN = "doorcall"
API = "/api/doorcall"
EREIGNIS = "doorcall_event"
PAKET = "de.esphomeremote.klingel"
OEFFNEN_FRIST = 600          # Sekunden nach dem Annehmen, in denen "Tür öffnen" gilt
SIGNAL_FRIST = 1800          # so lange darf die App für einen Anruf über HA ein WebRTC-Angebot schicken (unterwegs)
OEFFNEN_DOMAINS = ("switch", "button", "input_button", "lock", "script", "input_boolean")

AKTION_DOMAINS = (*OEFFNEN_DOMAINS, "light", "scene")
DIENST = {"switch": ("switch", "turn_on"), "input_boolean": ("input_boolean", "turn_on"), "lock": ("lock", "unlock"),
          "button": ("button", "press"), "input_button": ("input_button", "press"), "script": ("script", "turn_on"),
          "light": ("light", "turn_on"), "scene": ("scene", "turn_on")}

SCHALTER_DOMAINS = ("input_boolean", "switch", "light", "fan")
ZUSTAND_TEXT = {"on": "an", "off": "aus", "locked": "verriegelt", "unlocked": "entriegelt", "locking": "verriegelt …",
                "unlocking": "entriegelt …", "open": "offen", "opening": "öffnet …", "closed": "zu", "closing": "schließt …",
                "jammed": "klemmt", "unavailable": "nicht erreichbar", "unknown": "unbekannt", "home": "zu Hause",
                "not_home": "unterwegs"}
OFFEN_KLASSEN = ("door", "window", "opening", "garage_door", "lock")

STANDARD = {"geraete": [], "go2rtc": "", "src": "", "dauer": 30, "oeffnen": "", "oeffnen_frei": False}
HAUS_RATE = 16000            # Hausruf (Gerät zu Gerät): 16 kHz mono, 16 bit little endian
HAUS_FRAME = 320             # 20 ms
MISCH_RATE = 8000            # Mischer: 8 kHz mono, 16 bit (die Klingel will PCMU = 8 kHz)
MISCH_FRAME = 160            # 20 ms
LEBENSZEICHEN = 25          # Sekunden ohne Status-Abfrage -> Teilnehmer gilt als weg (App fragt im Gespräch alle 2 s)


def validate_gruppen(roh) -> list[dict]:
    """Anruf-Gruppen: [{name, geraete}] - Name 1-24 Zeichen, eindeutig."""
    out, namen = [], set()
    for x in (roh or [])[:12]:
        n = str((x or {}).get("name") or "").strip()[:24]
        if not n or n.lower() in namen:
            continue
        namen.add(n.lower())
        out.append({"name": n, "geraete": [str(g) for g in ((x or {}).get("geraete") or [])
                                           if re.fullmatch(r"mobile_app_[a-z0-9_]+", str(g))][:12]})
    return out


def validate_anruf(a) -> dict:
    a = a if isinstance(a, dict) else {}
    geraete = [str(g) for g in (a.get("geraete") or []) if re.fullmatch(r"mobile_app_[a-z0-9_]+", str(g))][:8]
    go2rtc = str(a.get("go2rtc") or "").strip().rstrip("/")
    if go2rtc and not re.fullmatch(r"https?://[^\s/]+(/[^\s]*)?", go2rtc):
        raise vol.Invalid(f"Anruf: go2rtc-Adresse '{go2rtc}' ungültig")
    src = str(a.get("src") or "").strip()
    if src and not re.fullmatch(r"[A-Za-z0-9_.\-]{1,64}", src):
        raise vol.Invalid(f"Anruf: Stream '{src}' ungültig")
    oe = str(a.get("oeffnen") or "").strip()
    if oe and (not re.fullmatch(r"[a-z_]+\.[a-z0-9_]+", oe) or oe.split(".")[0] not in OEFFNEN_DOMAINS):
        raise vol.Invalid(f"Anruf: '{oe}' kann keine Tür öffnen")
    aktionen = []
    for x in (a.get("aktionen") or [])[:3]:   # eigene Knöpfe im Anruf (Licht, Schnellantwort ...), 2026-11-21
        e, n = str((x or {}).get("entity") or "").strip(), str((x or {}).get("name") or "").strip()[:16]
        if not e:
            continue
        if not re.fullmatch(r"[a-z_]+\.[a-z0-9_]+", e) or e.split(".")[0] not in AKTION_DOMAINS:
            raise vol.Invalid(f"Anruf: '{e}' geht nicht als Knopf")
        aktionen.append({"name": n or e.split(".")[1][:16], "entity": e})
    def liste(roh, n, doms, art):
        out = []
        for x in (roh or [])[:n]:
            e, nm = str((x or {}).get("entity") or "").strip(), str((x or {}).get("name") or "").strip()[:20]
            if not e:
                continue
            if not re.fullmatch(r"[a-z_]+\.[a-z0-9_]+", e) or (doms and e.split(".")[0] not in doms):
                raise vol.Invalid(f"Anruf: '{e}' geht nicht als {art}")
            out.append({"name": nm or e.split(".")[1][:20], "entity": e})
        return out
    an = str(a.get("antwort") or "").strip()   # Schnellantworten (2026-11-21): select mit den Ansagen der Klingel
    if an and (not re.fullmatch(r"[a-z_]+\.[a-z0-9_]+", an) or an.split(".")[0] not in ("select", "input_select")):
        raise vol.Invalid(f"Anruf: '{an}' ist keine Auswahl (select)")
    gruppen = [str(g).strip()[:24] for g in (a.get("gruppen") or []) if str(g).strip()][:6]
    return {"gruppen": gruppen, "mitschauen": a.get("mitschauen", True) is not False,
            "sprechen": "gemischt" if a.get("sprechen") == "gemischt" else "einer",
            # Mindestlautstärke beim Klingeln in % (2026-11-21): auch wenn das Handy lautlos ist (App klingelt dann über den
            # Wecker-Kanal); 0 = aus
            "laut": max(0, min(100, int(a.get("laut", 70) if a.get("laut") is not None else 70))),
            "gaeste_bedienen": a.get("gaeste_bedienen", True) is not False,
            "antwort": an, "antwort_oeffnen_s": max(0, min(60, int(a.get("antwort_oeffnen_s") or 8))),
            "geraete": geraete, "go2rtc": go2rtc, "src": src, "dauer": max(10, min(120, int(a.get("dauer") or 30))),
            "oeffnen": oe, "oeffnen_frei": bool(a.get("oeffnen_frei")) and bool(oe), "aktionen": aktionen,
            # 2026-11-21: Schalter mit Status (z. B. "Nach Klingeln öffnen") und reine Anzeigen (Schloss, Türkontakt)
            "schalter": liste(a.get("schalter"), 4, SCHALTER_DOMAINS, "Schalter"),
            "anzeigen": liste(a.get("anzeigen"), 3, None, "Anzeige")}


def _b64(d: dict) -> str:
    return base64.urlsafe_b64encode(json.dumps(d, ensure_ascii=False, separators=(",", ":")).encode()).decode().rstrip("=")


class Tuerklingel:
    def __init__(self, hass: HomeAssistant, store, daten: dict) -> None:
        self.hass = hass
        self.store = store
        self.daten = daten
        self.anrufe: dict[str, dict] = {}     # id -> {kid, start, geraete, angenommen, von}
        self._probe_zeit: dict[str, float] = {}   # Gerät -> letzter Probeanruf aus der App
        self._mischer: dict[str, Mischer] = {}
        self._haus: dict[str, HausMischer] = {}
        self._rk: dict[str, tuple[float, bool]] = {}   # Rückkanal-Zustand je go2rtc-Stream (Zwischenspeicher)
        wh = daten.get("webhook")
        if not wh:
            wh = daten["webhook"] = f"doorcall_{secrets.token_hex(16)}"
            self.speichern()
        self.webhook_id = wh
        webhook.async_unregister(hass, wh)   # nach Neuladen der Integration nicht doppelt
        webhook.async_register(hass, DOMAIN, "DoorCall Intercom (App)", wh, self._webhook, local_only=False,
                               allowed_methods=["POST"])

    def speichern(self) -> None:
        self.store.async_delay_save(lambda: self.daten, 2)

    def basis(self) -> str:
        """HA-Adresse im Heimnetz als "IP:Port" (für App und go2rtc): interne URL, bei Hostnamen die eigene IP."""
        import ipaddress
        from homeassistant.helpers.network import NoURLAvailableError, get_url
        from yarl import URL
        api = self.hass.config.api
        eigene = (f"{api.local_ip}:{api.port}" if api is not None and not api.use_ssl and api.local_ip
                  and not str(api.local_ip).startswith("127.") else "")
        try:
            u = URL(get_url(self.hass, allow_external=False, allow_cloud=False, prefer_external=False))
            if u.scheme == "http" and u.host:
                try:
                    ipaddress.ip_address(u.host)
                    return f"{u.host}:{u.port}"
                except ValueError:
                    return eigene or f"{u.host}:{u.port}"
        except NoURLAvailableError:
            pass
        return eigene

    def webhook_url(self) -> str:
        return f"http://{self.basis()}/api/webhook/{self.webhook_id}"

    def extern(self) -> str:
        """Öffentliche HA-Adresse (Einstellungen › Netzwerk) für unterwegs, leer wenn keine eingetragen."""
        return str(self.hass.config.external_url or "").rstrip("/")

    def klingel(self, kid: str) -> dict | None:
        return next((k for k in self.daten.get("klingeln") or [] if k.get("id") == kid), None)

    # ------------------------------------------------------------ Anrufen
    async def anrufen(self, kid: str, nur: list[str] | None = None, probe: bool = False) -> str | None:
        k = self.klingel(kid)
        a = (k or {}).get("anruf") or {}
        geraete = nur or self.ziele([*(a.get("geraete") or []), *(f"gruppe:{g}" for g in a.get("gruppen") or [])])
        if not nur:
            geraete = self.nicht_ruhig(geraete, "Klingeln")
        if not k or not geraete:
            return None
        jetzt = time.time()
        if not probe:   # erneutes Klingeln, während es noch klingelt: kein zweiter Anruf
            for aid, x in self.anrufe.items():
                if x["kid"] == kid and not x["angenommen"] and jetzt - x["start"] < a.get("dauer", 30):
                    return aid
        aid = f"{kid}_{int(jetzt)}"
        self.anrufe = {i: x for i, x in self.anrufe.items() if jetzt - x["start"] < 3600}
        schluessel = {g: secrets.token_urlsafe(18) for g in geraete}   # je Gerät, für Signal + Rückmeldung von unterwegs
        self.anrufe[aid] = {"kid": kid, "start": jetzt, "geraete": list(geraete), "angenommen": 0.0, "von": "",
                            "schluessel": schluessel, "teil": {}, "frei": a.get("mitschauen", True), "sprecher": "",
                            "eingeladen": [], "ende": 0.0, "modus": a.get("sprechen", "einer"), "gesperrt": [],
                            "sprecher_set": []}
        for g in geraete:
            await self._senden(g, "KLINGELN", self._payload(aid, g))
        if self.anrufe[aid]["modus"] == "gemischt":   # Rückkanal schon beim Klingeln öffnen (go2rtc braucht 6-8 s)
            self.mischer(aid)._play()
        _LOGGER.info("Türklingel %s: Anruf %s an %s", k["name"], aid, ", ".join(geraete))
        return aid

    def _payload(self, aid: str, g: str, **extra) -> dict:
        """Alles, was die App für einen Anruf braucht (Klingeln, Dazuholen, Mitschau-Angebot)."""
        x = self.anrufe[aid]
        k = self.klingel(x["kid"]) or {}
        a = k.get("anruf") or {}
        if g not in x["schluessel"]:
            x["schluessel"][g] = secrets.token_urlsafe(18)
        d = {"id": aid, "klingel": x["kid"], "name": k.get("name", "Tür"), "go2rtc": a.get("go2rtc", ""), "src": a.get("src", ""),
             "webhook": self.webhook_url(), "oeffnen": bool(a.get("oeffnen_frei") and a.get("oeffnen")),
             "dauer": a.get("dauer", 30), "geraet": g, "schluessel": x["schluessel"][g],
             "ha": f"http://{self.basis()}", "bild": bool(k.get("kamera")),
             "aktionen": [y["name"] for y in a.get("aktionen") or []], **self.status(k),
             "antworten": self._antworten(a), "antwort_oeffnen_s": a.get("antwort_oeffnen_s", 8),
             "modus": x.get("modus", "einer"), "api": API, "laut": a.get("laut", 70), **extra}
        if x.get("art") == "haus":   # Hausruf: keine Kamera, keine Tür - nur Gegensprechen zwischen den Geräten
            erste = ((self.daten.get("klingeln") or [{}])[0].get("anruf") or {})
            d.update({"laut": erste.get("laut", 70), "name": "Hausruf", "art": "haus", "go2rtc": "", "src": "", "oeffnen": False, "bild": False,
                      "aktionen": [], "schalter": [], "anzeigen": [], "antworten": [], "dauer": 45,
                      "von_name": self.name(x.get("starter", ""))})
        if ext := self.extern():   # unterwegs: Signal über HA ({API}/signal), Rückmeldung über den öffentlichen Webhook
            d["extern"] = ext
            d["webhook_extern"] = f"{ext}/api/webhook/{self.webhook_id}"
        return d

    # ------------------------------------------------------------ Geräte, Namen, Gruppen
    def namen(self) -> dict[str, str]:
        """notify-Dienst -> Gerätename aus der Home-Assistant-App (z. B. mobile_app_pixel_8 -> "Pixel 8")."""
        from homeassistant.util import slugify
        out = {}
        for e in self.hass.config_entries.async_entries("mobile_app"):
            n = str(e.data.get("device_name") or e.title or "")
            if n:
                out[f"mobile_app_{slugify(n)}"] = n
        return out

    def name(self, g: str) -> str:
        return self.namen().get(g) or g.removeprefix("mobile_app_").replace("_", " ").title()

    def gruppen(self) -> list[dict]:
        return self.daten.get("gruppen") or []

    def ziele(self, roh: list[str]) -> list[str]:
        """Geräte und "gruppe:<Name>" -> Geräteliste ohne Doppelte."""
        out: list[str] = []
        for z in roh:
            z = str(z)
            if z.startswith("gruppe:"):
                gr = next((x for x in self.gruppen() if x["name"].lower() == z[7:].lower()), None)
                out += (gr or {}).get("geraete") or []
            elif z == "alle":
                out += self.app_geraete()
            elif re.fullmatch(r"mobile_app_[a-z0-9_]+", z):
                out.append(z)
        return list(dict.fromkeys(out))[:16]

    # ------------------------------------------------------------ Neue Geräte automatisch einrichten
    def alle_handy_geraete(self) -> list[str]:
        return sorted(s for s in self.hass.services.async_services().get("notify", {}) if s.startswith("mobile_app_"))

    def freigabe(self) -> dict:
        """Welche Geräte DoorCall nutzen dürfen (Nutzer 2026-11-21: "mehr Kontrolle"):
        modus "auswahl" (nur angehakte), "alle" (jedes Gerät mit Home-Assistant-App), "aus" (keine automatische Einrichtung)."""
        f = self.daten.setdefault("freigabe", {"modus": "auswahl", "geraete": list(self.daten.get("app_geraete") or [])})
        f.setdefault("modus", "auswahl")
        f.setdefault("geraete", [])
        return f

    def ruhig(self, g: str) -> str:
        """Ruhe-Zonen (2026-11-21): ist das Gerät (device_tracker der Home-Assistant-App) in einer seiner Ruhe-Zonen,
        klingelt DoorCall dort nicht. Rückgabe: Name der Zone oder ""."""
        zonen = (self.daten.get("ruhe") or {}).get(g) or []
        if not zonen:
            return ""
        st = self.hass.states.get("device_tracker." + g.removeprefix("mobile_app_"))
        if st is None:
            return ""
        for z in zonen:
            zs = self.hass.states.get(z)
            name = (zs.attributes.get("friendly_name") if zs else "") or z.split(".")[-1]
            if st.state in (name, z.split(".")[-1]):
                return name
        return ""

    def nicht_ruhig(self, geraete: list[str], wofuer: str) -> list[str]:
        out = []
        for g in geraete:
            if z := self.ruhig(g):
                _LOGGER.info("DoorCall: %s ist in Ruhe-Zone %s - kein %s", g, z, wofuer)
                self.hass.bus.async_fire(EREIGNIS, {"aktion": "ruhe", "geraet": g, "anruf": "", "klingel": "", "name": z})
            else:
                out.append(g)
        return out

    def erlaubt(self, g: str) -> bool:
        f = self.freigabe()
        return f["modus"] == "alle" or g in f["geraete"]

    async def einrichten(self, nur: list[str] | None = None, alle: bool = False) -> list[str]:
        """Stiller Gruß an Geräte, deren Türklingel-App HA noch nicht kennt: Adressen, KEIN Klingeln. Ist die App da,
        speichert sie die Einstellungen und antwortet mit "hallo" -> Gerät ist bekannt (Probeanruf/Hausruf gehen)."""
        bekannt = set(self.daten.get("app_geraete") or [])
        f = self.freigabe()
        if f["modus"] == "aus" and not nur:
            return []
        ziele = [g for g in (nur or self.alle_handy_geraete()) if (alle or g not in bekannt) and self.erlaubt(g)]
        if not ziele:
            return []
        kl = self.daten.get("klingeln") or []
        ext = self.extern()
        for g in ziele:
            k = next((x for x in kl if g in ((x.get("anruf") or {}).get("geraete") or [])), kl[0] if kl else {})
            d = {"einrichten": True, "id": f"{k.get('id', 'tuer')}_0", "klingel": k.get("id", ""), "name": k.get("name", "Tür"),
                 "webhook": self.webhook_url(), "ha": f"http://{self.basis()}", "geraet": g, "api": API}
            if ext:
                d["extern"] = ext
                d["webhook_extern"] = f"{ext}/api/webhook/{self.webhook_id}"
            await self._senden(g, "EINRICHTEN", d)
        _LOGGER.info("DoorCall: Einrichtung angeboten an %s", ", ".join(ziele))
        return ziele

    # ------------------------------------------------------------ Hausruf (Gegensprechen ohne Klingel)
    async def hausruf(self, von: str, ziele: list[str] | None = None) -> tuple[str | None, str]:
        """von ruft ziele (Geräte, "gruppe:<Name>", "alle"). von nimmt sofort an (leitet), die anderen klingeln."""
        if not re.fullmatch(r"mobile_app_[a-z0-9_]+", von or ""):
            return None, "Gerät unbekannt"
        alle_an = [g for g in self.ziele(ziele or ["alle"]) if g != von]
        an = self.nicht_ruhig(alle_an, "Hausruf")
        if not an:
            return None, ("Alle Angerufenen sind gerade in einer Ruhe-Zone" if alle_an
                          else "Niemand zum Anrufen (Geräte mit App „Türklingel“)")
        jetzt = time.time()
        aid = f"hausruf_{int(jetzt * 1000)}"
        self.anrufe = {i: y for i, y in self.anrufe.items() if jetzt - y["start"] < 3600}
        self.anrufe[aid] = {"kid": "", "art": "haus", "starter": von, "start": jetzt, "geraete": [von, *an],
                            "angenommen": 0.0, "von": "", "schluessel": {}, "teil": {}, "frei": True, "sprecher": "",
                            "eingeladen": list(an), "ende": 0.0, "modus": "haus", "gesperrt": [], "sprecher_set": []}
        await self._senden(von, "KLINGELN", self._payload(aid, von, sofort=True, hinweis="Hausruf läuft …"))
        for g in an:
            await self._senden(g, "KLINGELN", self._payload(aid, g, hinweis=f"{self.name(von)} ruft"))
        _LOGGER.info("Hausruf %s: %s ruft %s", aid, von, ", ".join(an))
        self.hass.bus.async_fire(EREIGNIS, {"aktion": "hausruf", "geraet": von, "anruf": aid, "klingel": "",
                                            "name": "Hausruf", "an": an})
        return aid, "Ruft " + ", ".join(self.name(g) for g in an)

    def haus_mischer(self, aid: str) -> "HausMischer":
        m = self._haus.get(aid)
        if m is None or m.aus:
            m = self._haus[aid] = HausMischer(self, aid)
        return m

    def haus_stop(self, aid: str) -> None:
        if m := self._haus.pop(aid, None):
            m.stop()

    def app_geraete(self) -> list[str]:
        """Geräte, auf denen die Türklingel-App läuft: alle eingetragenen + alle, die sich je per Webhook gemeldet haben."""
        bekannt = set(self.daten.get("app_geraete") or [])
        for k in self.daten.get("klingeln") or []:
            bekannt.update(((k.get("anruf") or {}).get("geraete")) or [])
        for gr in self.gruppen():
            bekannt.update(gr["geraete"])
        alle = set(s for s in self.hass.services.async_services().get("notify", {}) if s.startswith("mobile_app_"))
        return sorted((g for g in bekannt & alle if self.erlaubt(g)), key=self.name)

    def _app_merken(self, g: str) -> None:
        l = self.daten.setdefault("app_geraete", [])
        if g and g not in l and re.fullmatch(r"mobile_app_[a-z0-9_]+", g):
            l.append(g)
            self.speichern()

    # ------------------------------------------------------------ Mischer (alle sprechen gleichzeitig)
    def mischer(self, aid: str) -> "Mischer":
        m = self._mischer.get(aid)
        if m is None or m.aus:
            m = self._mischer[aid] = Mischer(self, aid)
        return m

    def mischer_stop(self, aid: str) -> None:
        if m := self._mischer.pop(aid, None):
            m.stop()

    def darf_sprechen(self, x: dict, g: str) -> bool:
        return (x.get("modus") == "gemischt" and g in x["teil"] and g not in x.get("gesperrt", [])
                and g in x.get("sprecher_set", []) and not x.get("ende"))

    # ------------------------------------------------------------ Gesprächsrunde
    def _aufraeumen(self, x: dict) -> None:
        """Teilnehmer ohne Lebenszeichen entfernen; geht der Leiter verloren, übernimmt der dienstälteste Gast."""
        jetzt = time.time()
        for g in [g for g, t in x["teil"].items() if jetzt - t["zuletzt"] > LEBENSZEICHEN]:
            _LOGGER.info("Türklingel: %s meldet sich nicht mehr - aus dem Gespräch genommen", g)
            self._raus(x, g)

    def _raus(self, x: dict, g: str, nachfolger: str = "") -> None:
        if g in x["teil"] and g not in x.setdefault("weg", []):
            x["weg"].append(g)   # für "… hat aufgelegt" bei denen, die bleiben
        x["teil"].pop(g, None)
        if x["sprecher"] == g:
            x["sprecher"] = ""
        if x["von"] == g and x["teil"]:
            neu = nachfolger if nachfolger in x["teil"] else min(x["teil"], key=lambda y: x["teil"][y]["seit"])
            x["von"] = neu
            x["teil"][neu]["seit"] = time.time()   # Leiter-Frist (Tür öffnen) neu
            _LOGGER.info("Türklingel: Leitung geht an %s", neu)

    async def rueckkanal(self, k: dict | None) -> bool:
        """Steht der Ton-Rückkanal zur Tür? go2rtc zeigt ihn als Quelle mit "audio, sendonly" (z. B. PCMU).
        Ohne go2rtc-Antwort: True (lieber nichts anzeigen als fälschlich "nicht bereit"). Zwischenspeicher 1 s."""
        a = (k or {}).get("anruf") or {}
        if not a.get("go2rtc") or not a.get("src"):
            return True
        schl = a["go2rtc"] + "|" + a["src"]
        t, wert = self._rk.get(schl, (0.0, True))
        if time.time() - t < 1:
            return wert
        try:
            s = async_get_clientsession(self.hass)
            async with s.get(f"{a['go2rtc']}/api/streams", params={"src": a["src"]}, timeout=ClientTimeout(total=3)) as r:
                d = await r.json(content_type=None)
            wert = any("sendonly" in str(m) and str(m).startswith("audio")
                       for p in (d.get("producers") or []) for m in (p.get("medias") or []))
        except Exception:  # noqa: BLE001
            wert = True
        self._rk[schl] = (time.time(), wert)
        return wert

    def runde(self, x: dict, g: str) -> dict:
        """Gesprächsrunde für die App dieses Geräts (Status-Antwort)."""
        teil = sorted(x["teil"], key=lambda y: x["teil"][y]["seit"])
        r = {"leiter": self.name(x["von"]) if x["von"] else "", "ich_leite": g == x["von"], "frei": x["frei"],
             "sprecher": self.name(x["sprecher"]) if x["sprecher"] else "", "ich_spreche": x["sprecher"] == g,
             "teilnehmer": [{"g": y, "name": self.name(y), "ich": y == g, "leiter": y == x["von"]} for y in teil],
             "ende": bool(x["ende"]) or (bool(x["angenommen"]) and g not in x["teil"]),
             "grund": (x.get("grund") or {}).get(g, ""),
             "modus": x.get("modus", "einer"), "gesperrt": g in x.get("gesperrt", []),
             "mik_bereit": x.get("rk", True),
             "klingelt": self._klingelt(x), "weg": [self.name(y) for y in x.get("weg", []) if y not in x["teil"]],
             "nochmal": [y for y in x["geraete"] if y not in x["teil"] and y != g],
             "sprecher_liste": [self.name(y) for y in x.get("sprecher_set", []) if y in x["teil"]]}
        for t in r["teilnehmer"]:
            t["gesperrt"] = t["g"] in x.get("gesperrt", [])
        if g == x["von"]:
            weg = set(teil)
            r["einladbar"] = [{"id": f"gruppe:{gr['name']}", "name": f"Gruppe {gr['name']}"} for gr in self.gruppen()
                              if set(gr["geraete"]) - weg] + [{"id": y, "name": self.name(y)} for y in self.app_geraete() if y not in weg]
        return r

    def _klingelt(self, x: dict) -> list[str]:
        """Bei wem es (vermutlich) noch klingelt: eingeladen/angerufen, nicht drin, nicht abgelehnt, nicht gegangen."""
        jetzt, dauer = time.time(), (45 if x.get("art") == "haus" else 120) + 5
        seit = x.get("klingel_seit", {})
        return [self.name(g) for g in x["geraete"]
                if g not in x["teil"] and g not in x.get("abgesagt", []) and g not in x.get("weg", [])
                and (x.get("art") == "haus" or g in x.get("eingeladen", []))
                and jetzt - seit.get(g, x["start"]) < dauer]

    async def _angebot(self, x: dict, aid: str, an: list[str]) -> None:
        """Klingeln beenden und (wenn Mitschauen frei) "Gespräch läuft – mitschauen" anbieten."""
        for g in an:
            if g in x["teil"]:
                continue
            if x["frei"] and not x["ende"] and not self.ruhig(g):
                await self._senden(g, "BEENDEN", {"id": aid, "angebot": self._payload(
                    aid, g, hinweis=f"{self.name(x['von'])} spricht mit der Tür")})
            else:
                await self._senden(g, "BEENDEN", {"id": aid})

    async def _alle_raus(self, x: dict, aid: str, an: list[str], grund: str) -> None:
        """Gespräch auf diesen Geräten beenden (auch laufende) und Angebote zurückziehen."""
        for g in an:
            await self._senden(g, "BEENDEN", {"id": aid, "alle": True, "grund": grund})

    async def _einladen(self, x: dict, aid: str, ziele: list[str]) -> tuple[bool, str]:
        neu = self.nicht_ruhig([g for g in self.ziele(ziele) if g not in x["teil"]], "Dazuholen")
        if not neu:
            return False, "Niemand zum Dazuholen"
        von = self.name(x["von"])
        for g in neu:
            if g not in x["geraete"]:
                x["geraete"].append(g)
            if g not in x["eingeladen"]:
                x["eingeladen"].append(g)
            x.setdefault("klingel_seit", {})[g] = time.time()
            if g in x.get("abgesagt", []):
                x["abgesagt"].remove(g)
            if g in x.get("weg", []):
                x["weg"].remove(g)
            await self._senden(g, "KLINGELN", self._payload(aid, g, hinweis=f"{von} holt dich dazu", eingeladen=True))
        _LOGGER.info("Türklingel: %s holt %s dazu", x["von"], ", ".join(neu))
        return True, "Klingelt bei " + ", ".join(self.name(g) for g in neu)

    async def _senden(self, geraet: str, aktion: str, d: dict) -> None:
        if not self.hass.services.has_service("notify", geraet):
            _LOGGER.warning("Türklingel: notify.%s gibt es nicht (Home-Assistant-App)", geraet)
            return
        try:
            await self.hass.services.async_call("notify", geraet, {
                "message": "command_broadcast_intent",
                "data": {"intent_package_name": PAKET, "intent_action": f"{PAKET}.{aktion}", "intent_extras": f"d:{_b64(d)}",
                         "priority": "high", "ttl": 0}}, blocking=False)
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("Türklingel: %s an %s: %s", aktion, geraet, err)

    async def beenden(self, aid: str, ausser: str = "") -> None:
        x = self.anrufe.get(aid)
        if not x:
            return
        if x["angenommen"]:   # angenommen: die anderen hören auf und bekommen ggf. das Mitschau-Angebot
            await self._angebot(x, aid, [g for g in x["geraete"] if g != ausser])
            return
        for g in x["geraete"]:
            if g != ausser:
                await self._senden(g, "BEENDEN", {"id": aid})

    # ------------------------------------------------------------ Rückmeldungen der App
    async def _webhook(self, hass, webhook_id, request):
        try:
            j = await request.json()
        except Exception:  # noqa: BLE001
            return None
        aktion, aid, geraet = str(j.get("tuerklingel") or ""), str(j.get("id") or ""), str(j.get("geraet") or "")
        antwort = lambda ok, text: web.json_response({"ok": ok, "text": text})  # noqa: E731
        if aktion == "hallo":   # App hat die stille Einrichtung bekommen (oder startet neu) -> Gerät mit App
            if not self.erlaubt(geraet):
                _LOGGER.info("DoorCall: %s hat die App, ist aber nicht freigegeben", geraet)
                return antwort(False, "Gerät in DoorCall nicht freigegeben")
            if re.fullmatch(r"mobile_app_[a-z0-9_]+", geraet) and geraet in self.alle_handy_geraete():
                neu = geraet not in (self.daten.get("app_geraete") or [])
                self._app_merken(geraet)
                if neu:
                    _LOGGER.warning("DoorCall: neues Gerät mit Türklingel-App: %s (App %s)", self.name(geraet), j.get("app"))
                    hass.bus.async_fire(EREIGNIS, {"aktion": "neues_geraet", "geraet": geraet, "anruf": "", "klingel": "",
                                                   "name": self.name(geraet), "app": j.get("app")})
                return antwort(True, "")
            return antwort(False, "Gerät unbekannt")
        if aktion in ("hausruf_start", "hausruf_ziele"):   # Hausruf aus der App: nur Geräte, die die App schon kennt
            if geraet not in self.app_geraete():
                return antwort(False, "Gerät unbekannt")
            if aktion == "hausruf_ziele":
                return web.json_response({"ok": True, "ziele": [{"id": "alle", "name": "Alle"}] + [
                    {"id": f"gruppe:{gr['name']}", "name": f"Gruppe {gr['name']}"} for gr in self.gruppen()] + [
                    {"id": g, "name": self.name(g)} for g in self.app_geraete() if g != geraet]})
            aid, text = await self.hausruf(geraet, [str(z) for z in (j.get("ziele") or ["alle"])][:16])
            return antwort(bool(aid), text)
        if aktion == "probeanruf":   # Probeanruf-Knopf der App (2026-11-21): echter Anruf nur an dieses Gerät
            return antwort(*await self._probeanruf(str(j.get("klingel") or aid.rsplit("_", 1)[0]), geraet))
        x = self.anrufe.get(aid)
        k = self.klingel(x["kid"]) if x else None
        if aktion != "status":   # Status fragt die App alle paar Sekunden ab - nicht ins Log/auf den Bus
            _LOGGER.info("Türklingel: %s von %s (Anruf %s, App %s)", aktion, geraet, aid, j.get("app") or "vor 4.3")
            hass.bus.async_fire(EREIGNIS, {"aktion": aktion, "geraet": geraet, "anruf": aid,
                                                          "klingel": x["kid"] if x else "", "name": (k or {}).get("name", "")})
        if not x:
            return antwort(False, "Anruf unbekannt")
        x.setdefault("teil", {}); x.setdefault("frei", True); x.setdefault("sprecher", ""); x.setdefault("eingeladen", []); x.setdefault("ende", 0.0)
        x.setdefault("modus", "einer"); x.setdefault("gesperrt", []); x.setdefault("sprecher_set", [])
        schluessel_ok = secrets.compare_digest(x.get("schluessel", {}).get(geraet, "-"), str(j.get("schluessel") or ""))
        if schluessel_ok:
            self._app_merken(geraet)
        jetzt = time.time()
        if aktion == "angenommen":
            if x["ende"]:
                return antwort(False, "Gespräch ist schon vorbei")
            if geraet not in x["geraete"]:
                return antwort(False, "Gerät gehört nicht zu diesem Anruf")
            self._aufraeumen(x)
            if x.get("art") == "haus":
                if geraet == x.get("starter") and not x["von"]:
                    x["angenommen"], x["von"] = jetzt, geraet
                    x["teil"][geraet] = {"seit": jetzt, "zuletzt": jetzt}
                    return web.json_response({"ok": True, "text": "", "rolle": "leiter"})
                if geraet not in x["teil"]:
                    if not x["teil"] and x["angenommen"]:
                        return antwort(False, "Gespräch ist schon vorbei")
                    x["teil"][geraet] = {"seit": jetzt, "zuletzt": jetzt}
                    if not x["von"]:
                        x["angenommen"], x["von"] = jetzt, geraet
                return web.json_response({"ok": True, "text": "", "rolle": "leiter" if geraet == x["von"] else "gast"})
            if not x["angenommen"] or (not x["teil"] and not x["von"]):
                x["angenommen"], x["von"] = jetzt, geraet
                x["teil"][geraet] = {"seit": jetzt, "zuletzt": jetzt}
                await self.beenden(aid, ausser=geraet)
                if x.get("modus") == "gemischt":   # Rückkanal schon jetzt öffnen (go2rtc braucht dafür einige Sekunden)
                    self.mischer(aid)._play()
                return web.json_response({"ok": True, "text": "", "rolle": "leiter"})
            if geraet in x["teil"]:
                return web.json_response({"ok": True, "text": "", "rolle": "leiter" if geraet == x["von"] else "gast"})
            if geraet in x["geraete"] and (x["frei"] or geraet in x["eingeladen"]):
                x["teil"][geraet] = {"seit": jetzt, "zuletzt": jetzt}
                _LOGGER.info("Türklingel: %s kommt als Gast dazu (Leitung %s)", geraet, x["von"])
                return web.json_response({"ok": True, "text": f"Mit dabei – {self.name(x['von'])} leitet", "rolle": "gast"})
            return antwort(False, "Mitschauen ist gesperrt")
        elif aktion in ("abgelehnt", "verpasst"):
            if geraet not in x.setdefault("abgesagt", []):
                x["abgesagt"].append(geraet)
        elif aktion == "aufgelegt":
            if geraet in x["teil"]:
                if geraet == x["von"] and len(x["teil"]) > 1 and j.get("alle"):
                    andere = [g for g in x["teil"] if g != geraet]
                    x["teil"].clear(); x["ende"] = jetzt
                    self.mischer_stop(aid)
                    self.haus_stop(aid)
                    grund = f"{self.name(geraet)} hat das Gespräch für alle beendet"
                    for g in andere:
                        x.setdefault("grund", {})[g] = grund
                    await self._alle_raus(x, aid, list(dict.fromkeys([*andere, *x["geraete"]])), grund)
                else:
                    self._raus(x, geraet, str(j.get("nachfolger") or ""))
                    if not x["teil"]:
                        x["ende"] = jetzt
                        self.mischer_stop(aid)
                        self.haus_stop(aid)
                        await self._alle_raus(x, aid, [g for g in x["geraete"] if g != geraet], "Gespräch beendet")
        elif aktion in ("status", "schalten", "sprechen", "freigabe", "einladen", "modus", "mik_sperren"):
            if not schluessel_ok:
                return antwort(False, "nicht erlaubt")
            if geraet in x.get("raus", ()) and geraet not in x["teil"] and x.get("gemeldet_raus", {}).get(geraet, 0) < jetzt - 30:
                x.setdefault("gemeldet_raus", {})[geraet] = jetzt   # Gerät ist raus, fragt aber noch (alte App / Meldung nicht angekommen)
                _LOGGER.warning("Türklingel: %s ist nicht (mehr) im Gespräch, schaut aber noch (App %s)", geraet, j.get("app") or "vor 4.3")
            if geraet in x["teil"]:
                x["teil"][geraet]["zuletzt"] = jetzt
            self._aufraeumen(x)
            ok, text = True, ""
            if aktion == "schalten":
                ok, text = await self._schalten(x, k, geraet, j.get("nr"))
            elif aktion == "sprechen":
                if geraet not in x["teil"]:
                    ok, text = False, "nicht im Gespräch"
                elif j.get("an") and geraet in x["gesperrt"]:
                    ok, text = False, "Die Leitung hat dein Mikrofon gesperrt"
                elif x["modus"] == "gemischt":   # alle dürfen gleichzeitig - HA mischt
                    if j.get("an") and geraet not in x["sprecher_set"]:
                        x["sprecher_set"].append(geraet)
                    elif not j.get("an") and geraet in x["sprecher_set"]:
                        x["sprecher_set"].remove(geraet)
                elif j.get("an"):
                    if x["sprecher"] and x["sprecher"] != geraet:
                        text = f"Du sprichst jetzt – {self.name(x['sprecher'])} ist stumm"
                    x["sprecher"] = geraet
                elif x["sprecher"] == geraet:
                    x["sprecher"] = ""
            elif aktion in ("freigabe", "einladen", "modus", "mik_sperren"):
                if geraet != x["von"]:
                    ok, text = False, "Nur wer das Gespräch leitet"
                elif aktion == "einladen":
                    ok, text = await self._einladen(x, aid, [str(z) for z in (j.get("ziele") or [])][:16])
                elif aktion == "modus":
                    neu = "gemischt" if j.get("modus") == "gemischt" else "einer"
                    if neu != x["modus"]:
                        x["modus"], x["sprecher"], x["sprecher_set"] = neu, "", []
                        if neu == "einer":
                            self.mischer_stop(aid)
                        else:
                            self.mischer(aid)._play()
                        _LOGGER.info("Türklingel: %s stellt Sprechen auf %s", geraet, neu)
                    text = "Alle dürfen gleichzeitig sprechen (gemischt)" if neu == "gemischt" else "Immer nur einer spricht"
                elif aktion == "mik_sperren":
                    z, an = str(j.get("ziel") or ""), bool(j.get("an"))
                    ziele = [g for g in x["teil"] if g != geraet] if z == "alle" else [z] if z in x["teil"] and z != geraet else []
                    for g in ziele:
                        if an and g not in x["gesperrt"]:
                            x["gesperrt"].append(g)
                        elif not an and g in x["gesperrt"]:
                            x["gesperrt"].remove(g)
                        if an:
                            if x["sprecher"] == g:
                                x["sprecher"] = ""
                            if g in x["sprecher_set"]:
                                x["sprecher_set"].remove(g)
                    text = (("Mikro gesperrt: " if an else "Mikro frei: ") + ", ".join(self.name(g) for g in ziele)) if ziele else "Niemand betroffen"
                else:
                    x["frei"] = bool(j.get("an"))
                    _LOGGER.info("Türklingel: %s stellt Mitschauen %s", geraet, "frei" if x["frei"] else "gesperrt")
                    if x["frei"]:
                        text = "Mitschauen erlaubt"
                        await self._angebot(x, aid, x["geraete"])
                    else:
                        gaeste = [g for g in x["teil"] if g != geraet]
                        for g in gaeste:
                            x["teil"].pop(g, None)
                            x.setdefault("raus", []).append(g)
                        if x["sprecher"] in gaeste:
                            x["sprecher"] = ""
                        x["eingeladen"].clear()
                        text = "Mitschauen gesperrt" + (f" – {len(gaeste)} raus" if gaeste else "")
                        grund = f"{self.name(geraet)} hat das Mitschauen beendet"
                        for g in gaeste:
                            x.setdefault("grund", {})[g] = grund
                        await self._alle_raus(x, aid, [g for g in x["geraete"] if g != geraet], grund)
            if x["angenommen"]:
                x["rk"] = await self.rueckkanal(k)
            return web.json_response({"ok": ok, "text": text, **self.status(k), "runde": self.runde(x, geraet)})
        elif aktion == "antwort":
            if not secrets.compare_digest(x.get("schluessel", {}).get(geraet, "-"), str(j.get("schluessel") or "")):
                return antwort(False, "nicht erlaubt")
            return antwort(*await self._antwort(x, k, geraet, j.get("nr"), bool(j.get("oeffnen"))))
        elif aktion in ("oeffnen", "aktion"):
            if not secrets.compare_digest(x.get("schluessel", {}).get(geraet, "-"), str(j.get("schluessel") or "")):
                _LOGGER.warning("Türklingel: %s von %s ohne gültigen Schlüssel abgelehnt", aktion, geraet)
                return antwort(False, "nicht erlaubt")
            if aktion == "oeffnen":
                return antwort(*await self._oeffnen(x, k, geraet))
            return antwort(*await self._aktion(x, k, geraet, j.get("nr")))
        return antwort(True, "")

    def _antworten(self, a: dict) -> list[str]:
        st = self.hass.states.get(a.get("antwort") or "")
        return [o for o in (st.attributes.get("options") or []) if o not in ("off", "aus")][:20] if st else []

    async def _antwort(self, x: dict, k: dict | None, geraet: str, nr, oeffnen: bool) -> tuple[bool, str]:
        """Schnellantwort abspielen; auf Wunsch danach (antwort_oeffnen_s) die Tür öffnen - nur wenn freigegeben."""
        a = (k or {}).get("anruf") or {}
        liste = self._antworten(a)
        try:
            text = liste[int(nr)]
        except (TypeError, ValueError, IndexError):
            return False, "Ansage unbekannt"
        if not self._darf(x, geraet, k):
            return False, "Anruf vorbei"
        ent = a["antwort"]
        _LOGGER.info("Türklingel: %s spielt Schnellantwort '%s' (%s)", geraet, text, ent)
        await self.hass.services.async_call(ent.split(".")[0], "select_option", {"entity_id": ent, "option": text}, blocking=True)
        if not oeffnen:
            return True, "Ansage läuft"
        if not (a.get("oeffnen_frei") and a.get("oeffnen")):
            return False, "Ansage läuft – Tür öffnen nicht freigegeben"
        warte = a.get("antwort_oeffnen_s", 8)

        async def spaeter():
            await asyncio.sleep(warte)
            await self._oeffnen(x, k, geraet)
        self.hass.async_create_task(spaeter())
        return True, f"Ansage läuft – Tür öffnet in {warte} s"

    async def _probeanruf(self, kid: str, geraet: str) -> tuple[bool, str]:
        """Nur an ein Gerät, das bei dieser Klingel eingetragen ist; höchstens alle 20 s (Schlüssel bekommt es per Weckruf)."""
        def eingetragen(kk):
            a2 = (kk or {}).get("anruf") or {}
            return geraet in self.ziele([*(a2.get("geraete") or []), *(f"gruppe:{g}" for g in a2.get("gruppen") or [])])
        k = self.klingel(kid)
        if not k or not eingetragen(k):   # z. B. letzter Anruf war ein Hausruf (keine Klingel): erste passende Klingel
            k = next((kk for kk in self.daten.get("klingeln") or [] if eingetragen(kk)), None)
            kid = (k or {}).get("id", kid)
        an = (k or {}).get("anruf") or {}
        if not k or not eingetragen(k):
            return False, "Gerät nicht eingetragen"
        jetzt = time.time()
        if jetzt - self._probe_zeit.get(geraet, 0) < 20:
            return False, "Bitte kurz warten"
        self._probe_zeit[geraet] = jetzt
        _LOGGER.info("Türklingel: Probeanruf %s an %s (aus der App)", k["name"], geraet)
        await self.anrufen(kid, [geraet], probe=True)
        return True, "Probeanruf kommt"

    def status(self, k: dict | None) -> dict:
        """Schalter (Name + an/aus) und Anzeigen (Name + Text) für die App."""
        a = (k or {}).get("anruf") or {}
        sch = []
        for x in a.get("schalter") or []:
            st = self.hass.states.get(x["entity"])
            sch.append({"name": x["name"], "an": bool(st and st.state == "on"), "da": bool(st and st.state not in ("unavailable", "unknown"))})
        anz = []
        for x in a.get("anzeigen") or []:
            st = self.hass.states.get(x["entity"])
            if st is None:
                t = "?"
            elif st.domain == "binary_sensor" and st.attributes.get("device_class") in OFFEN_KLASSEN:
                t = {"on": "offen", "off": "zu"}.get(st.state, ZUSTAND_TEXT.get(st.state, st.state))
            else:
                t = ZUSTAND_TEXT.get(st.state, st.state)
                if (u := st.attributes.get("unit_of_measurement")) and t == st.state:
                    t = f"{t} {u}"
            anz.append({"name": x["name"], "text": t})
        return {"schalter": sch, "anzeigen": anz}

    async def _schalten(self, x: dict, k: dict | None, geraet: str, nr) -> tuple[bool, str]:
        l = ((k or {}).get("anruf") or {}).get("schalter") or []
        try:
            sch = l[int(nr)]
        except (TypeError, ValueError, IndexError):
            return False, "Schalter unbekannt"
        if not self._darf(x, geraet, k):
            return False, "Anruf vorbei"
        st = self.hass.states.get(sch["entity"])
        neu = "turn_off" if st and st.state == "on" else "turn_on"
        _LOGGER.info("Türklingel: %s schaltet %s %s", geraet, sch["entity"], neu)
        await self.hass.services.async_call(sch["entity"].split(".")[0], neu, {"entity_id": sch["entity"]}, blocking=True)
        return True, f"{sch['name']}: {'an' if neu == 'turn_on' else 'aus'}"

    def _darf(self, x: dict, geraet: str, k: dict | None) -> bool:
        """Schaltet nur das Gerät, das gerade angenommen hat (bis OEFFNEN_FRIST) - oder während es noch klingelt."""
        jetzt, a = time.time(), (k or {}).get("anruf") or {}
        if x["angenommen"]:   # Gesprächsrunde: Leiter immer, Gäste nur wenn im Konfigurator erlaubt
            t = (x.get("teil") or {}).get(geraet)
            if not t or (geraet != x["von"] and not a.get("gaeste_bedienen", True)):
                return False
            return jetzt - t["seit"] <= OEFFNEN_FRIST or geraet == x["von"] and jetzt - x["angenommen"] <= OEFFNEN_FRIST
        # klingelt noch: Dauer aus HA, in der App bis 120 s einstellbar (2026-11-21) -> Fenster bis 125 s
        return geraet in x["geraete"] and jetzt - x["start"] <= max(a.get("dauer", 30), 120) + 5

    async def _aktion(self, x: dict, k: dict | None, geraet: str, nr) -> tuple[bool, str]:
        l = ((k or {}).get("anruf") or {}).get("aktionen") or []
        try:
            akt = l[int(nr)]
        except (TypeError, ValueError, IndexError):
            return False, "Knopf unbekannt"
        if not self._darf(x, geraet, k):
            return False, "Anruf vorbei"
        _LOGGER.info("Türklingel: %s löst %s aus (%s)", geraet, akt["name"], akt["entity"])
        await self.hass.services.async_call(*DIENST[akt["entity"].split(".")[0]], {"entity_id": akt["entity"]}, blocking=False)
        return True, akt["name"] + " ✓"

    async def _oeffnen(self, x: dict, k: dict | None, geraet: str) -> tuple[bool, str]:
        a = (k or {}).get("anruf") or {}
        ent = a.get("oeffnen", "")
        if not (a.get("oeffnen_frei") and ent):
            _LOGGER.warning("Türklingel: Tür öffnen von %s abgelehnt - im Konfigurator nicht freigegeben", geraet)
            return False, "nicht freigegeben"
        if not self._darf(x, geraet, k):
            _LOGGER.warning("Türklingel: Tür öffnen von %s abgelehnt - kein laufender Anruf auf diesem Gerät", geraet)
            return False, "Anruf vorbei"
        _LOGGER.warning("Türklingel: %s öffnet %s (%s)", geraet, k["name"], ent)
        await self.hass.services.async_call(*DIENST[ent.split(".")[0]], {"entity_id": ent}, blocking=False)
        x["geoeffnet"] = time.time()
        return True, "Tür geöffnet"


class Mischer:
    """Mischt die Mikrofone aller Sprecher eines Anrufs (je 8 kHz/16 bit von der App) und gibt den Mix als
    endlosen AU-Strom aus; go2rtc spielt ihn per "Play" über den Rückkanal an der Tür ab (PCMU).
    Takt 20 ms, je Gerät kleiner Puffer (60 ms Vorlauf, max. 200 ms) - daher etwas Verzögerung."""

    def __init__(self, t: "Tuerklingel", aid: str) -> None:
        self.t, self.aid = t, aid
        self.token = secrets.token_urlsafe(18)
        self.puffer: dict[str, list[bytes]] = {}
        self.bereit: dict[str, bool] = {}
        self.rest: dict[str, bytes] = {}
        self.ausgaenge: list[web.StreamResponse] = []
        self.aus = False
        self._play_zeit = 0.0
        self.task = t.hass.async_create_background_task(self._takt(), f"tuerklingel_mischer_{aid}")

    def rein(self, g: str, daten: bytes) -> None:
        daten = self.rest.pop(g, b"") + daten
        n = len(daten) // (MISCH_FRAME * 2) * (MISCH_FRAME * 2)
        if len(daten) > n:
            self.rest[g] = daten[n:]
        q = self.puffer.setdefault(g, [])
        for i in range(0, n, MISCH_FRAME * 2):
            q.append(daten[i:i + MISCH_FRAME * 2])
        if len(q) > 10:   # mehr als 200 ms Rückstand: Ältestes weg (Verzögerung begrenzen)
            del q[:len(q) - 10]
        self._play()

    def _play(self) -> None:
        """go2rtc den Mix an der Tür abspielen lassen (einmal; neu, falls ffmpeg die Verbindung verloren hat)."""
        if self.ausgaenge or time.time() - self._play_zeit < 5:
            return
        self._play_zeit = time.time()
        x = self.t.anrufe.get(self.aid) or {}
        a = (self.t.klingel(x.get("kid", "")) or {}).get("anruf") or {}
        if not a.get("go2rtc") or not a.get("src"):
            return
        quelle = f"ffmpeg:http://{self.t.basis()}{API}/mix?id={self.aid}&t={self.token}#audio=pcmu"

        async def los():
            try:
                s = async_get_clientsession(self.t.hass)
                async with s.post(f"{a['go2rtc']}/api/streams", params={"dst": a["src"], "src": quelle},
                                  timeout=ClientTimeout(total=15)) as r:
                    if r.status >= 400:
                        _LOGGER.warning("Türklingel: Mischer an die Tür geht nicht (go2rtc HTTP %s: %s)", r.status, (await r.text())[:120])
                    else:
                        _LOGGER.info("Türklingel: Mischer spielt an der Tür (%s)", self.aid)
            except Exception as err:  # noqa: BLE001
                _LOGGER.warning("Türklingel: Mischer go2rtc: %s", err)
        self.t.hass.async_create_task(los())

    async def _takt(self) -> None:
        import array
        naechst = time.monotonic()
        try:
            while not self.aus:
                naechst += MISCH_FRAME / MISCH_RATE
                warte = naechst - time.monotonic()
                if warte > 0:
                    await asyncio.sleep(warte)
                elif warte < -0.5:   # weit hinterher (Pause): Takt neu
                    naechst = time.monotonic()
                x = self.t.anrufe.get(self.aid)
                self._zaehler = getattr(self, "_zaehler", 0) + 1
                if x and self._zaehler % 50 == 0:   # 1x je Sekunde: Geräte ohne Lebenszeichen raus (sonst liefe der Mischer ewig)
                    self.t._aufraeumen(x)
                if not x or x.get("ende") or (not x.get("teil") and time.time() - x["start"] > 130) \
                        or (x.get("angenommen") and not x.get("teil")):
                    break   # vorbei, keiner rangegangen (verpasst/abgelehnt) oder alle weg
                summe = [0] * MISCH_FRAME
                for g, q in self.puffer.items():
                    if not self.t.darf_sprechen(x, g):
                        q.clear(); self.bereit[g] = False
                        continue
                    if not self.bereit.get(g):
                        if len(q) < 3:
                            continue
                        self.bereit[g] = True
                    if not q:
                        self.bereit[g] = False
                        continue
                    f = array.array("h", q.pop(0))
                    for i in range(MISCH_FRAME):
                        summe[i] += f[i]
                out = array.array("h", (32767 if v > 32767 else -32768 if v < -32768 else v for v in summe))
                out.byteswap()   # AU = big endian
                b = out.tobytes()
                for r in list(self.ausgaenge):
                    try:
                        await r.write(b)
                    except Exception:  # noqa: BLE001
                        self.ausgaenge.remove(r)
        finally:
            self.stop()

    def stop(self) -> None:
        if self.aus:
            return
        self.aus = True
        for r in self.ausgaenge:
            with contextlib.suppress(Exception):
                r.force_close()
        self.ausgaenge.clear()
        if self.t._mischer.get(self.aid) is self:
            self.t._mischer.pop(self.aid, None)
        _LOGGER.info("Türklingel: Mischer beendet (%s)", self.aid)


class MikView(HomeAssistantView):
    """Mikrofon einer App im Modus "gemischt": endloser POST mit 16-bit-PCM, 8 kHz, mono (little endian)."""

    url = API + "/mik"
    name = "api:doorcall:mik"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        t: Tuerklingel = self.hass.data[DOMAIN + "_tuerklingel"]
        q = request.query
        aid, g = q.get("id", ""), q.get("geraet", "")
        x = t.anrufe.get(aid)
        if not x or not secrets.compare_digest(x.get("schluessel", {}).get(g, "-"), q.get("schluessel", "")):
            return web.Response(status=403)
        if x.get("modus") != "gemischt" or g not in x.get("teil", {}):
            return web.Response(status=409, text="nicht gemischt")
        m = t.mischer(aid)
        _LOGGER.info("Türklingel: Mikro von %s im Mischer", g)
        try:
            while not m.aus:
                d = await request.content.read(1280)
                if not d:
                    break
                m.rein(g, d)
        except Exception:  # noqa: BLE001
            pass
        return web.Response(status=200)


class HausMischer:
    """Hausruf: jedes Gerät schickt sein Mikrofon (16 kHz/16 bit LE), jedes Gerät hört die Summe aller ANDEREN
    (mix-minus). Takt 20 ms, je Gerät 60 ms Vorlauf, höchstens 200 ms Rückstand."""

    def __init__(self, t: "Tuerklingel", aid: str) -> None:
        self.t, self.aid = t, aid
        self.puffer: dict[str, list[bytes]] = {}
        self.bereit: dict[str, bool] = {}
        self.rest: dict[str, bytes] = {}
        self.hoerer: dict[str, web.StreamResponse] = {}
        self.aus = False
        self.task = t.hass.async_create_background_task(self._takt(), f"doorcall_hausruf_{aid}")

    def rein(self, g: str, daten: bytes) -> None:
        daten = self.rest.pop(g, b"") + daten
        n = len(daten) // (HAUS_FRAME * 2) * (HAUS_FRAME * 2)
        if len(daten) > n:
            self.rest[g] = daten[n:]
        q = self.puffer.setdefault(g, [])
        for i in range(0, n, HAUS_FRAME * 2):
            q.append(daten[i:i + HAUS_FRAME * 2])
        if len(q) > 10:
            del q[:len(q) - 10]

    async def _takt(self) -> None:
        import array
        naechst = time.monotonic()
        try:
            while not self.aus:
                naechst += HAUS_FRAME / HAUS_RATE
                warte = naechst - time.monotonic()
                if warte > 0:
                    await asyncio.sleep(warte)
                elif warte < -0.5:
                    naechst = time.monotonic()
                x = self.t.anrufe.get(self.aid)
                self._zaehler = getattr(self, "_zaehler", 0) + 1
                if x and self._zaehler % 50 == 0:   # 1x je Sekunde: Geräte ohne Lebenszeichen raus (sonst liefe der Mischer ewig)
                    self.t._aufraeumen(x)
                if not x or x.get("ende") or (x.get("angenommen") and not x.get("teil")):
                    break
                eigen: dict[str, array.array] = {}
                summe = [0] * HAUS_FRAME
                for g, q in self.puffer.items():
                    if g not in x["teil"] or g in x.get("gesperrt", []):
                        q.clear(); self.bereit[g] = False
                        continue
                    if not self.bereit.get(g):
                        if len(q) < 3:
                            continue
                        self.bereit[g] = True
                    if not q:
                        self.bereit[g] = False
                        continue
                    f = array.array("h", q.pop(0))
                    eigen[g] = f
                    for i in range(HAUS_FRAME):
                        summe[i] += f[i]
                for g, r in list(self.hoerer.items()):
                    e = eigen.get(g)
                    werte = summe if e is None else [summe[i] - e[i] for i in range(HAUS_FRAME)]
                    out = array.array("h", (32767 if v > 32767 else -32768 if v < -32768 else v for v in werte))
                    try:
                        await r.write(out.tobytes())
                    except Exception:  # noqa: BLE001
                        self.hoerer.pop(g, None)
        finally:
            self.stop()

    def stop(self) -> None:
        if self.aus:
            return
        self.aus = True
        for r in self.hoerer.values():
            with contextlib.suppress(Exception):
                r.force_close()
        self.hoerer.clear()
        if self.t._haus.get(self.aid) is self:
            self.t._haus.pop(self.aid, None)


def _haus_pruefen(t: "Tuerklingel", q) -> tuple[str, str, dict | None]:
    aid, g = q.get("id", ""), q.get("geraet", "")
    x = t.anrufe.get(aid)
    if not x or x.get("art") != "haus" or not secrets.compare_digest(x.get("schluessel", {}).get(g, "-"), q.get("schluessel", "")):
        return aid, g, None
    return aid, g, x


class HausMikView(HomeAssistantView):
    """Hausruf: Mikrofon eines Geräts, endloser POST mit 16-bit-PCM, 16 kHz, mono (little endian)."""

    url = API + "/haus_mik"
    name = "api:doorcall:haus_mik"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        t: Tuerklingel = self.hass.data[DOMAIN + "_tuerklingel"]
        aid, g, x = _haus_pruefen(t, request.query)
        if x is None or g not in x.get("teil", {}):
            return web.Response(status=403)
        m = t.haus_mischer(aid)
        _LOGGER.info("Hausruf %s: Mikro von %s verbunden", aid, g)
        try:
            while not m.aus:
                d = await request.content.read(1280)
                if not d:
                    break
                m.rein(g, d)
        except Exception:  # noqa: BLE001
            pass
        return web.Response(status=200)


class HausHoerView(HomeAssistantView):
    """Hausruf: was dieses Gerät hört (alle anderen gemischt) - endlose 16-bit-PCM, 16 kHz, mono (little endian)."""

    url = API + "/haus_hoeren"
    name = "api:doorcall:haus_hoeren"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.StreamResponse:
        t: Tuerklingel = self.hass.data[DOMAIN + "_tuerklingel"]
        aid, g, x = _haus_pruefen(t, request.query)
        if x is None or g not in x.get("teil", {}):
            return web.Response(status=403)
        m = t.haus_mischer(aid)
        r = web.StreamResponse(headers={"Content-Type": "application/octet-stream", "Cache-Control": "no-store"})
        await r.prepare(request)
        if (alt := m.hoerer.get(g)) is not None:
            with contextlib.suppress(Exception):
                alt.force_close()
        m.hoerer[g] = r
        _LOGGER.info("Hausruf %s: %s hört zu", aid, g)
        while not m.aus and m.hoerer.get(g) is r:
            await asyncio.sleep(0.5)
        return r


class MixView(HomeAssistantView):
    """Der Mix für go2rtc (ffmpeg liest ihn): AU-Datei ohne Ende, 16 bit big endian, 8 kHz, mono."""

    url = API + "/mix"
    name = "api:doorcall:mix"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.StreamResponse:
        import struct
        t: Tuerklingel = self.hass.data[DOMAIN + "_tuerklingel"]
        m = t._mischer.get(request.query.get("id", ""))
        if not m or m.aus or not secrets.compare_digest(m.token, request.query.get("t", "")):
            return web.Response(status=403)
        r = web.StreamResponse(headers={"Content-Type": "audio/basic", "Cache-Control": "no-store"})
        await r.prepare(request)
        await r.write(b".snd" + struct.pack(">IIIII", 24, 0xFFFFFFFF, 3, MISCH_RATE, 1))
        m.ausgaenge.append(r)
        while not m.aus and r in m.ausgaenge:
            await asyncio.sleep(0.5)
        return r


class SignalView(HomeAssistantView):
    """WebRTC-Angebot der App von unterwegs: HA reicht es an go2rtc im Heimnetz weiter (go2rtc selbst bleibt intern).
    Erlaubt nur mit dem Schlüssel des Geräts für einen bekannten, frischen Anruf. Bild/Ton laufen danach direkt über
    go2rtcs WebRTC-Port (Router-Freigabe 8555, Kandidat stun:8555 in go2rtc)."""

    url = API + "/signal"
    name = "api:doorcall:signal"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    @property
    def t(self) -> "Tuerklingel":   # immer die aktuelle Instanz (auch nach Neuladen der Integration)
        return self.hass.data[DOMAIN + "_tuerklingel"]

    async def post(self, request: web.Request) -> web.Response:
        try:
            j = await request.json()
        except Exception:  # noqa: BLE001
            return web.json_response({"fehler": "kein JSON"}, status=400)
        aid, g, sl, sdp = str(j.get("id") or ""), str(j.get("geraet") or ""), str(j.get("schluessel") or ""), str(j.get("sdp") or "")
        x = self.t.anrufe.get(aid)
        if not x or not sl or not secrets.compare_digest(x.get("schluessel", {}).get(g, ""), sl) \
                or time.time() - x["start"] > SIGNAL_FRIST or not sdp.startswith("v=0") or len(sdp) > 20000:
            _LOGGER.warning("Türklingel: Signal abgelehnt (%s, %s)", aid, g)
            return web.json_response({"fehler": "nicht erlaubt"}, status=403)
        a = (self.t.klingel(x["kid"]) or {}).get("anruf") or {}
        if not a.get("go2rtc") or not a.get("src"):
            return web.json_response({"fehler": "keine Kamera"}, status=404)
        try:
            s = async_get_clientsession(self.t.hass)
            async with s.post(f"{a['go2rtc']}/api/webrtc", params={"src": a["src"]}, json={"type": "offer", "sdp": sdp},
                              timeout=ClientTimeout(total=10)) as r:
                antwort = await r.json(content_type=None)
                if r.status >= 400:
                    raise ValueError(f"HTTP {r.status}")
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("Türklingel: go2rtc %s: %s", a.get("go2rtc"), err)
            return web.json_response({"fehler": "go2rtc"}, status=502)
        _LOGGER.info("Türklingel: Signal von unterwegs für %s (%s)", aid, g)
        return web.json_response({"type": "answer", "sdp": antwort.get("sdp", "")})


class BildView(HomeAssistantView):
    """Kamerabild der Klingel für das Anruferbild in der App (Schlüssel des Anrufs, 30 min gültig)."""

    url = API + "/bild"
    name = "api:doorcall:bild"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        t: Tuerklingel = self.hass.data[DOMAIN + "_tuerklingel"]
        q = request.query
        x = t.anrufe.get(q.get("id", ""))
        if not x or not secrets.compare_digest(x.get("schluessel", {}).get(q.get("geraet", ""), "-"), q.get("schluessel", "")) \
                or time.time() - x["start"] > SIGNAL_FRIST:
            return web.Response(status=403)
        kamera = (t.klingel(x["kid"]) or {}).get("kamera")
        if not kamera:
            return web.Response(status=404)
        from homeassistant.components.camera import async_get_image
        try:
            img = await async_get_image(self.hass, kamera, timeout=8, width=640)
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("Türklingel: Bild %s: %s", kamera, err)
            return web.Response(status=502)
        return web.Response(body=img.content, content_type=img.content_type, headers={"Cache-Control": "no-store"})


# Alte Adressen der App bis Version 4.5 (vor DoorCall): gleiche Ansichten unter /api/esphomeremote/klingel_*
class AltSignalView(SignalView):
    url = "/api/esphomeremote/klingel_signal"
    name = "api:doorcall:alt_signal"


class AltBildView(BildView):
    url = "/api/esphomeremote/klingel_bild"
    name = "api:doorcall:alt_bild"


class AltMikView(MikView):
    url = "/api/esphomeremote/klingel_mik"
    name = "api:doorcall:alt_mik"


def ansichten(hass: HomeAssistant) -> None:
    """HTTP-Ansichten einmal je HA-Lauf (lassen sich nicht abmelden; holen sich die aktuelle Instanz selbst)."""
    if hass.data.get(DOMAIN + "_ansichten"):
        return
    hass.data[DOMAIN + "_ansichten"] = True
    for v in (SignalView, BildView, MikView, MixView, HausMikView, HausHoerView, AltSignalView, AltBildView, AltMikView):
        hass.http.register_view(v(hass))


def _go2rtc_vorschlag(hass) -> str:
    for dom in ("webrtc", "frigate"):
        for e in hass.config_entries.async_entries(dom):
            u = str(e.data.get("url") or "")
            if dom == "webrtc" and u:
                return u.rstrip("/")
            if dom == "frigate" and u:
                return re.sub(r":\d+$", ":1984", u.rstrip("/"))
    return ""
