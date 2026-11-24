"""Einrichtung: keine Fragen - alles Weitere in der Seitenleiste "DoorCall".

Nur wenn es alte Klingel-Einstellungen aus ESPHomeRemote gibt: Häkchen "übernehmen" (ausdrückliche Wahl; geht nicht,
solange ESPHomeRemote geladen ist).
"""

from __future__ import annotations

import voluptuous as vol

from homeassistant import config_entries

from .core import DOMAIN


class DoorCallFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(self, user_input=None):
        from . import alt_vorhanden, esphomeremote_laeuft
        if self._async_current_entries():
            return self.async_abort(reason="single_instance_allowed")
        alt = await self.hass.async_add_executor_job(alt_vorhanden, self.hass.config.path(".storage", "esphomeremote"))
        errors = {}
        if user_input is not None:
            uebernehmen = bool(alt and user_input.get("uebernehmen"))
            if uebernehmen and esphomeremote_laeuft(self.hass):
                errors["base"] = "esphomeremote_loaded"
            else:
                return self.async_create_entry(title="DoorCall Intercom", data={"uebernehmen": uebernehmen})
        schema = vol.Schema({vol.Optional("uebernehmen", default=False): bool}) if alt else vol.Schema({})
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)
