"""DoorCall Intercom: Klingeln an der Tür als echter Anruf auf Handys und Tablets (App "Türklingel").

Eigenständige Integration (2026-11-21, vorher Teil von ESPHomeRemote). Sie
  - hört auf die Auslöser der eingestellten Klingeln (binary_sensor/input_boolean/switch -> "on",
    button/input_button/event -> jede Änderung) und ruft die Geräte über die Home-Assistant-App an,
  - führt die Gesprächsrunde (Leitung, Mitschauen, Sprechen, Dazuholen) und den Mischer,
  - hat eine eigene Seite in der Seitenleiste ("DoorCall") für Klingeln, Geräte und Gruppen.
Beim ersten Start übernimmt sie die Einstellungen aus ESPHomeRemote (gleiche Webhook-Adresse, die App merkt nichts).
"""

from __future__ import annotations

import json
import logging
import os
import re

import voluptuous as vol

from homeassistant.components import websocket_api
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Event, HomeAssistant, ServiceCall, callback
from homeassistant.exceptions import HomeAssistantError
import homeassistant.helpers.config_validation as cv
from homeassistant.helpers.event import async_track_state_change_event
from homeassistant.helpers.storage import Store

from .core import DOMAIN, Tuerklingel, ansichten, validate_anruf, validate_gruppen, _go2rtc_vorschlag

_LOGGER = logging.getLogger(__name__)
STORE_VERSION = 1
IMPULS = ("button", "input_button", "event")   # Auslöser ohne "on": jede Änderung zählt
VERSION_JS = "2026.11.22.11"


def validate_klingeln(roh) -> list[dict]:
    """Klingeln: [{id, name, ausloeser, kamera, anruf}] - höchstens 8."""
    out, ids = [], set()
    for k in (roh or [])[:8]:
        k = k if isinstance(k, dict) else {}
        name = str(k.get("name") or "").strip()[:30] or "Tür"
        kid = str(k.get("id") or "").strip()
        if not re.fullmatch(r"[a-z0-9_]{1,20}", kid):
            kid = re.sub(r"[^a-z0-9]+", "_", name.lower().replace("ü", "ue").replace("ä", "ae").replace("ö", "oe")
                         .replace("ß", "ss")).strip("_")[:20] or "tuer"
        basis, n = kid, 2
        while kid in ids:
            kid = f"{basis[:17]}_{n}"
            n += 1
        ids.add(kid)
        a = str(k.get("ausloeser") or "").strip()
        if a and not re.fullmatch(r"[a-z_]+\.[a-z0-9_]+", a):
            raise vol.Invalid(f"Auslöser '{a}' ungültig")
        c = str(k.get("kamera") or "").strip()
        if c and not re.fullmatch(r"camera\.[a-z0-9_]+", c):
            raise vol.Invalid(f"Kamera '{c}' ungültig")
        out.append({"id": kid, "name": name, "ausloeser": a, "kamera": c, "anruf": validate_anruf(k.get("anruf"))})
    return out


def _alt_lesen(pfad: str) -> dict:
    try:
        with open(pfad, encoding="utf-8") as f:
            return json.load(f).get("data") or {}
    except (OSError, ValueError):
        return {}


async def _uebernehmen(hass: HomeAssistant) -> dict:
    """Einmalig: Klingel-Anrufe aus ESPHomeRemote (.storage/esphomeremote) übernehmen."""
    alt = await hass.async_add_executor_job(_alt_lesen, hass.config.path(".storage", "esphomeremote"))
    if not alt.get("klingel_webhook"):
        return {}
    klingeln = []
    for k in alt.get("klingel") or []:
        a = k.get("anruf") or {}
        klingeln.append({"id": k.get("id"), "name": k.get("name"), "ausloeser": k.get("ausloeser", ""),
                         "kamera": k.get("kamera", ""), "anruf": a})
    try:
        klingeln = validate_klingeln(klingeln)
    except vol.Invalid as err:
        _LOGGER.warning("DoorCall: Übernahme aus ESPHomeRemote teilweise ungültig (%s) - ohne Klingeln", err)
        klingeln = []
    _LOGGER.warning("DoorCall: Einstellungen aus ESPHomeRemote übernommen (%d Klingeln, %d Gruppen)",
                    len(klingeln), len(alt.get("anruf_gruppen") or []))
    return {"webhook": alt["klingel_webhook"], "klingeln": klingeln,
            "gruppen": validate_gruppen(alt.get("anruf_gruppen")), "app_geraete": list(alt.get("klingel_app") or []),
            "uebernommen": True}


class Ausloeser:
    """Hört auf die Auslöser aller Klingeln."""

    def __init__(self, hass: HomeAssistant, t: Tuerklingel) -> None:
        self.hass, self.t = hass, t
        self._abo = None

    @callback
    def starten(self) -> None:
        self.stoppen()
        quellen = [k["ausloeser"] for k in self.t.daten.get("klingeln") or [] if k.get("ausloeser")]
        if quellen:
            self._abo = async_track_state_change_event(self.hass, quellen, self._geklingelt)

    @callback
    def stoppen(self) -> None:
        if self._abo:
            self._abo()
            self._abo = None

    @callback
    def _geklingelt(self, ev: Event) -> None:
        alt, neu = ev.data.get("old_state"), ev.data.get("new_state")
        if neu is None or alt is None or neu.state in ("unavailable", "unknown"):
            return
        if ev.data["entity_id"].split(".")[0] in IMPULS:
            if neu.state == alt.state:
                return
        elif neu.state != "on" or alt.state == "on":
            return
        for k in self.t.daten.get("klingeln") or []:
            if k.get("ausloeser") == ev.data["entity_id"]:
                self.hass.async_create_task(self.t.anrufen(k["id"]))


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    store = Store(hass, STORE_VERSION, DOMAIN)
    daten = await store.async_load() or {}
    if not daten:
        daten = await _uebernehmen(hass)
        await store.async_save(daten)
    daten.setdefault("klingeln", [])
    daten.setdefault("gruppen", [])
    t = Tuerklingel(hass, store, daten)
    if "freigabe" not in daten:   # 2026-11-21: Freigabe einführen - bisher verbundene Geräte bleiben freigegeben
        t.freigabe()
        await store.async_save(daten)
    hass.data[DOMAIN + "_tuerklingel"] = t
    aus = Ausloeser(hass, t)
    aus.starten()
    hass.data[DOMAIN + "_ausloeser"] = aus
    ansichten(hass)
    if not hass.data.get(DOMAIN + "_ws"):
        hass.data[DOMAIN + "_ws"] = True
        for fn in (ws_get, ws_save, ws_gruppen, ws_info, ws_test, ws_suchen, ws_freigabe, ws_ruhe, ws_ziele):
            websocket_api.async_register_command(hass, fn)
    await _panel(hass)
    _einrichten_planen(hass, entry, t)
    if not hass.services.has_service(DOMAIN, "hausruf"):
        async def _hausruf(call: ServiceCall) -> None:
            aid, text = await _t(hass).hausruf(call.data["von"], call.data.get("an") or ["alle"])
            if not aid:
                raise HomeAssistantError(text)
        hass.services.async_register(DOMAIN, "hausruf", _hausruf, schema=vol.Schema({
            vol.Required("von"): vol.Match(r"^mobile_app_[a-z0-9_]+$"),
            vol.Optional("an"): vol.All(cv.ensure_list, [cv.string])}))
    return True


def _einrichten_planen(hass: HomeAssistant, entry: ConfigEntry, t: Tuerklingel) -> None:
    """Neue Geräte automatisch einrichten: 1 min nach dem Start, neue Handys binnen 10 min, sonst alle 6 h."""
    from datetime import timedelta
    from homeassistant.helpers.event import async_call_later, async_track_time_interval
    gesehen: set[str] = set(t.alle_handy_geraete())

    async def start(_now=None) -> None:
        await t.einrichten()

    async def neue(_now=None) -> None:
        jetzt = set(t.alle_handy_geraete())
        if neu := sorted(jetzt - gesehen):
            await t.einrichten(neu)
        gesehen.update(jetzt)

    entry.async_on_unload(async_call_later(hass, 60, start))
    entry.async_on_unload(async_track_time_interval(hass, neue, timedelta(minutes=10)))
    entry.async_on_unload(async_track_time_interval(hass, start, timedelta(hours=6)))


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    from homeassistant.components import frontend, webhook
    if aus := hass.data.pop(DOMAIN + "_ausloeser", None):
        aus.stoppen()
    if t := hass.data.pop(DOMAIN + "_tuerklingel", None):
        webhook.async_unregister(hass, t.webhook_id)
        for aid in list(t._mischer):
            t.mischer_stop(aid)
    frontend.async_remove_panel(hass, "doorcall")
    hass.services.async_remove(DOMAIN, "hausruf")
    return True


async def _panel(hass: HomeAssistant) -> None:
    from homeassistant.components import panel_custom
    from homeassistant.components.http import StaticPathConfig
    if not hass.data.get(DOMAIN + "_static"):
        hass.data[DOMAIN + "_static"] = True
        await hass.http.async_register_static_paths([StaticPathConfig(
            "/doorcall_static", os.path.join(os.path.dirname(__file__), "frontend"), cache_headers=False)])
        # Dashboard-Karte custom:doorcall-hausruf-card überall verfügbar (ohne Ressource von Hand)
        from homeassistant.components.frontend import add_extra_js_url
        add_extra_js_url(hass, f"/doorcall_static/doorcall-hausruf-card.js?v={VERSION_JS}")
    await panel_custom.async_register_panel(
        hass, frontend_url_path="doorcall", webcomponent_name="doorcall-panel", sidebar_title="DoorCall",
        sidebar_icon="mdi:doorbell-video", module_url=f"/doorcall_static/doorcall-panel.js?v={VERSION_JS}",
        require_admin=True, config={})


def _t(hass) -> Tuerklingel:
    return hass.data[DOMAIN + "_tuerklingel"]


@websocket_api.websocket_command({vol.Required("type"): "doorcall/get"})
@websocket_api.require_admin
@callback
def ws_get(hass, connection, msg) -> None:
    t = _t(hass)
    connection.send_result(msg["id"], {"klingeln": t.daten.get("klingeln") or [], "gruppen": t.gruppen(),
                                       "uebernommen": bool(t.daten.get("uebernommen"))})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/save", vol.Required("klingeln"): list})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_save(hass, connection, msg) -> None:
    t = _t(hass)
    try:
        t.daten["klingeln"] = validate_klingeln(msg["klingeln"])
    except (vol.Invalid, ValueError, TypeError) as err:
        connection.send_error(msg["id"], "ungueltig", str(err))
        return
    await t.store.async_save(t.daten)
    hass.data[DOMAIN + "_ausloeser"].starten()
    connection.send_result(msg["id"], {"klingeln": t.daten["klingeln"]})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/gruppen", vol.Required("gruppen"): list})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_gruppen(hass, connection, msg) -> None:
    t = _t(hass)
    t.daten["gruppen"] = validate_gruppen(msg["gruppen"])
    await t.store.async_save(t.daten)
    connection.send_result(msg["id"], {"gruppen": t.gruppen()})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/info"})
@websocket_api.require_admin
@callback
def ws_info(hass, connection, msg) -> None:
    t = _t(hass)
    geraete = sorted(s for s in hass.services.async_services().get("notify", {}) if s.startswith("mobile_app_"))
    connection.send_result(msg["id"], {"geraete": geraete, "go2rtc": _go2rtc_vorschlag(hass), "namen": t.namen(),
                                       "gruppen": t.gruppen(), "app": t.app_geraete(), "freigabe": t.freigabe(),
                                       "verbunden": list(t.daten.get("app_geraete") or []),
                                       "ruhe": t.daten.get("ruhe") or {},
                                       "zonen": [{"id": z.entity_id, "name": z.attributes.get("friendly_name") or z.entity_id}
                                                 for z in hass.states.async_all("zone") if z.attributes.get("radius")]})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/test", vol.Required("kid"): str,
                                  vol.Optional("geraet"): str})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_test(hass, connection, msg) -> None:
    aid = await _t(hass).anrufen(msg["kid"], [msg["geraet"]] if msg.get("geraet") else None, probe=True)
    if not aid:
        connection.send_error(msg["id"], "ungueltig", "Keine Geräte für den Anruf eingestellt")
        return
    connection.send_result(msg["id"], {"anruf": aid})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/suchen", vol.Optional("alle"): bool,
                                  vol.Optional("geraet"): str})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_suchen(hass, connection, msg) -> None:
    """Knopf "Geräte suchen": allen Handys ohne bekannte App die Einrichtung schicken."""
    nur = [msg["geraet"]] if msg.get("geraet") else None
    connection.send_result(msg["id"], {"gefragt": await _t(hass).einrichten(nur, alle=bool(msg.get("alle")))})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/freigabe", vol.Required("modus"): vol.In(["auswahl", "alle", "aus"]),
                                  vol.Required("geraete"): [vol.Match(r"^mobile_app_[a-z0-9_]+$")]})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_freigabe(hass, connection, msg) -> None:
    """Freigabe speichern; neu angehakte Geräte gleich einrichten."""
    t = _t(hass)
    alt = set(t.freigabe()["geraete"])
    t.daten["freigabe"] = {"modus": msg["modus"], "geraete": list(dict.fromkeys(msg["geraete"]))[:32]}
    await t.store.async_save(t.daten)
    neu = [g for g in t.daten["freigabe"]["geraete"] if g not in alt]
    gefragt = await t.einrichten(neu, alle=True) if neu else []
    if msg["modus"] == "alle":
        gefragt += await t.einrichten()
    connection.send_result(msg["id"], {"freigabe": t.freigabe(), "gefragt": gefragt})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/ruhe", vol.Required("ruhe"): {
    vol.Match(r"^mobile_app_[a-z0-9_]+$"): [vol.Match(r"^zone\.[a-z0-9_]+$")]}})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_ruhe(hass, connection, msg) -> None:
    """Ruhe-Zonen je Gerät speichern: dort klingelt DoorCall nicht."""
    t = _t(hass)
    t.daten["ruhe"] = {g: list(dict.fromkeys(z))[:8] for g, z in msg["ruhe"].items() if z}
    await t.store.async_save(t.daten)
    connection.send_result(msg["id"], {"ruhe": t.daten["ruhe"]})


@websocket_api.websocket_command({vol.Required("type"): "doorcall/ziele"})
@callback
def ws_ziele(hass, connection, msg) -> None:
    """Für die Dashboard-Karte (auch ohne Admin): Geräte mit App (Name) und Gruppen."""
    t = _t(hass)
    connection.send_result(msg["id"], {
        "geraete": [{"id": g, "name": t.name(g)} for g in t.app_geraete()],
        "gruppen": [{"id": f"gruppe:{gr['name']}", "name": gr["name"]} for gr in t.gruppen()]})
