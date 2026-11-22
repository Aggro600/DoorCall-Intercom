// DoorCall Intercom - Seite in der Seitenleiste (2026-11-21): Klingeln, Geräte, Gruppen, Gesprächsrunde, Sprechen.
// Speichert jede Änderung sofort (doorcall/save bzw. doorcall/gruppen).
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const AUSLOESER = ["binary_sensor", "input_boolean", "switch", "button", "input_button", "event"];
const TUEREN = ["switch", "button", "input_button", "lock", "script", "input_boolean"];
const LISTEN = {
  aktionen: ["Eigene Knöpfe im Anruf (z. B. Licht Treppenhaus)", 3, ["switch", "button", "input_button", "lock", "script", "input_boolean", "light", "scene"], 16],
  schalter: ["Schalter mit Status im Anruf, antippen schaltet um (z. B. „Nach Klingeln immer öffnen“)", 4, ["input_boolean", "switch", "light", "fan"], 20],
  anzeigen: ["Anzeigen im Anruf (z. B. Schloss, Türkontakt)", 3, null, 20],
};
const MENUE_NAMEN = { antwort: "Schnellantworten", tuer: "Tür öffnen", leitung: "Gespräch (Leitung: dazuholen, mitschauen, sprechen)", schalter: "Schalter", knoepfe: "Eigene Knöpfe" };
const MENUE_STD = [{ id: "tuer", an: true }, { id: "leitung", an: true }, { id: "schalter", an: true }, { id: "antwort", an: true }, { id: "knoepfe", an: true }];
const LEER = () => ({ geraete: [], gruppen: [], go2rtc: "", src: "", dauer: 30, oeffnen: "", oeffnen_frei: false, mitschauen: true,
  gaeste_bedienen: true, sprechen: "einer", laut: 70, antwort: "", antwort_oeffnen_s: 8, aktionen: [], schalter: [], anzeigen: [] });

class DoorCallPanel extends HTMLElement {
  set hass(h) { const erst = !this._hass; this._hass = h; if (erst) this._laden(); }
  set narrow(n) { this._narrow = n; }

  async _laden() {
    this.innerHTML = `<div style="padding:24px">Lade DoorCall …</div>`;
    try {
      const [d, info] = await Promise.all([this._hass.callWS({ type: "doorcall/get" }), this._hass.callWS({ type: "doorcall/info" })]);
      this.d = d; this.info = info; this.msg = d.uebernommen ? "Einstellungen wurden aus ESPHomeRemote übernommen." : "";
    } catch (e) { this.innerHTML = `<div style="padding:24px">DoorCall nicht erreichbar: ${esc(e.message || e)}</div>`; return; }
    if (!this._an) {
      this._an = true;
      this.addEventListener("change", (e) => this._aenderung(e));
      this.addEventListener("click", (e) => this._klick(e));
    }
    this._zeigen();
  }

  _name(e) { return this._hass.states[e]?.attributes.friendly_name || e; }
  _gname(g) { return (this.info.namen || {})[g] || g.replace(/^mobile_app_/, "").split("_").map((w) => w[0]?.toUpperCase() + w.slice(1)).join(" "); }
  _ents(doms, wert) {
    const e = Object.keys(this._hass.states).filter((x) => !doms || doms.includes(x.split(".")[0])).sort((a, b) => this._name(a).localeCompare(this._name(b), "de"));
    if (wert && !e.includes(wert)) e.unshift(wert);
    return e;
  }
  _sel(attr, wert, doms, leer = "— wählen —") {
    return `<select ${attr}><option value="">${esc(leer)}</option>${this._ents(doms, wert).map((x) => `<option value="${esc(x)}" ${x === wert ? "selected" : ""}>${esc(this._name(x))}</option>`).join("")}</select>`;
  }

  _zeigen() {
    const k = this.d.klingeln, grs = this.info.gruppen || [];
    const fg = this.info.freigabe || { modus: "auswahl", geraete: [] };
    const geraete = [...new Set([...(this.info.geraete || []), ...k.flatMap((x) => x.anruf?.geraete || [])])];
    this.innerHTML = `<style>
      :host, doorcall-panel { display:block; }
      .dc { padding:16px; max-width:1000px; margin:0 auto; color:var(--primary-text-color); font-family:var(--paper-font-body1_-_font-family, sans-serif); }
      .kopf { display:flex; align-items:center; gap:12px; margin:4px 0 12px; }
      .kopf h1 { font-size:24px; margin:0; font-weight:500; }
      .karte { background:var(--card-background-color); border-radius:12px; padding:16px; margin:0 0 16px; box-shadow:var(--ha-card-box-shadow, 0 1px 3px rgba(0,0,0,.2)); }
      .karte h2 { font-size:18px; margin:0 0 8px; font-weight:500; display:flex; align-items:center; gap:8px; }
      .zeile { display:flex; flex-wrap:wrap; gap:8px 16px; align-items:center; margin:8px 0; }
      label { display:inline-flex; align-items:center; gap:6px; }
      .note { color:var(--secondary-text-color); font-size:13px; line-height:1.4; }
      .erkl { background:var(--secondary-background-color); border-radius:8px; padding:8px 12px; margin:6px 0; }
      input, select { background:var(--input-fill-color, var(--secondary-background-color)); color:var(--primary-text-color); border:1px solid var(--divider-color); border-radius:6px; padding:6px 8px; font-size:14px; max-width:100%; }
      input[type=checkbox] { width:18px; height:18px; }
      button { background:var(--primary-color); color:var(--text-primary-color, #fff); border:0; border-radius:8px; padding:8px 14px; font-size:14px; cursor:pointer; display:inline-flex; align-items:center; gap:6px; }
      button.ghost { background:transparent; color:var(--primary-color); border:1px solid var(--divider-color); }
      button.rot { background:transparent; color:var(--error-color); border:1px solid var(--divider-color); }
      button:disabled { opacity:.4; cursor:default; }
      .gruppe { border:1px solid var(--divider-color); border-radius:8px; padding:8px; display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin:6px 0; }
      .msg { padding:8px 12px; border-radius:8px; background:var(--secondary-background-color); margin-bottom:12px; }
      .msg.err { color:var(--error-color); }
      hr { border:0; border-top:1px solid var(--divider-color); margin:12px 0; }
    </style>
    <div class="dc">
      <div class="kopf"><ha-icon icon="mdi:doorbell-video"></ha-icon><h1>DoorCall Intercom</h1><span style="flex:1"></span>
        <a href="https://github.com/Aggro600/DoorCall-Intercom/blob/main/docs/ANLEITUNG.md" target="_blank" rel="noopener" style="color:var(--primary-color);text-decoration:none;display:inline-flex;align-items:center;gap:6px"><ha-icon icon="mdi:book-open-variant"></ha-icon>Anleitung</a></div>
      <div class="note" style="margin-bottom:12px">Klingeln an der Tür kommt als echter Anruf auf Handys und Tablets (App „Türklingel“ und Home-Assistant-App nötig).
        Nach dem Annehmen: Live-Bild, Gegensprechen über go2rtc, Tür öffnen, Schalter und Ansagen. Mehrere Geräte bilden eine Gesprächsrunde.</div>
      ${this.msg ? `<div class="msg ${this.msgErr ? "err" : ""}">${esc(this.msg)}</div>` : ""}
      <div class="karte"><h2><ha-icon icon="mdi:cellphone-check"></ha-icon>Geräte für DoorCall</h2>
        <div class="zeile"><label>Einrichtung <select data-fg="modus">
          <option value="auswahl" ${fg.modus === "auswahl" ? "selected" : ""}>Nur ausgewählte Geräte</option>
          <option value="alle" ${fg.modus === "alle" ? "selected" : ""}>Alle Geräte automatisch</option>
          <option value="aus" ${fg.modus === "aus" ? "selected" : ""}>Aus</option></select></label></div>
        <div class="note erkl">${fg.modus === "alle"
          ? "<b>Alle automatisch:</b> Jedes Handy/Tablet mit Home-Assistant-App bekommt einen stillen Gruß (es klingelt nicht). Hat es die App „Türklingel“, ist es danach dabei (Probeanruf, Hausruf, Dazuholen)."
          : fg.modus === "aus"
          ? "<b>Aus:</b> Es wird kein Gerät mehr eingerichtet und keins nimmt neu teil – nur die angehakten Geräte bleiben nutzbar."
          : "<b>Nur ausgewählte:</b> Nur angehakte Geräte bekommen den stillen Gruß und dürfen DoorCall nutzen. Andere werden abgewiesen, auch wenn sie die App haben, und tauchen nirgends auf (auch nicht beim Hausruf)."}
          ✓ = App verbunden.</div>
        <div class="zeile">${geraete.map((g) => `<label><input type="checkbox" data-fg="geraet" data-g="${esc(g)}" ${fg.modus === "alle" || (fg.geraete || []).includes(g) ? "checked" : ""} ${fg.modus === "alle" ? "disabled" : ""}>${esc(this._gname(g))}${(this.info.verbunden || []).includes(g) ? " ✓" : ""}</label>`).join("")}</div>
        <div class="zeile"><button class="ghost" data-a="suchen" ${fg.modus === "aus" ? "disabled" : ""}><ha-icon icon="mdi:magnify"></ha-icon>Jetzt Geräte suchen</button></div>
        <hr><div class="note">Einstellungen je Gerät</div>
        <div class="note erkl"><b>Lautstärke beim Klingeln:</b> „wie Klingel“ nimmt die Mindestlautstärke der Klingel, „wie am Gerät“ klingelt normal (lautlos bleibt lautlos), ein Wert erzwingt diese Lautstärke (z. B. für Tablets).
          <b>Klingelfenster sofort:</b> beim Klingeln geht gleich das Fenster mit Live-Bild auf statt nur die Meldung (für Tablets; braucht „Über anderen Apps anzeigen“).
          <b>Darf durchgestellt werden:</b> beim Hausruf „Direkt durchstellen“ nimmt dieses Gerät automatisch an (Mikro dort zuerst stumm).</div>
        ${(fg.modus === "alle" ? geraete : (fg.geraete || [])).map((g) => { const e = (this.info.geraete_einst || {})[g] || {}; return `<div class="zeile"><span style="min-width:150px">${esc(this._gname(g))}</span>
          <label>Lautstärke <select data-ge="laut" data-g="${esc(g)}"><option value="" ${e.laut == null ? "selected" : ""}>wie Klingel</option><option value="0" ${e.laut === 0 ? "selected" : ""}>wie am Gerät</option>
            ${[30, 40, 50, 60, 70, 80, 90, 100].map((v) => `<option value="${v}" ${e.laut === v ? "selected" : ""}>${v} %</option>`).join("")}</select></label>
          <label><input type="checkbox" data-ge="fenster" data-g="${esc(g)}" ${e.fenster ? "checked" : ""}>Klingelfenster sofort</label>
          <label><input type="checkbox" data-ge="auto" data-g="${esc(g)}" ${e.auto ? "checked" : ""}>Darf durchgestellt werden</label></div>`; }).join("")}
        <hr><div class="note">Ruhe-Zonen</div>
        <div class="note erkl">Ist ein Gerät in einer seiner Ruhe-Zonen (z. B. auf der Arbeit), klingelt DoorCall dort nicht – weder Haustür noch Hausruf, Dazuholen oder Mitschauen. Entscheidet Home Assistant über den Standort der Home-Assistant-App (device_tracker), unabhängig vom „Nicht stören“ des Handys. Ein Probeanruf an genau dieses Gerät klingelt trotzdem. Tipp: Zonen unter ~150 m Radius erkennt das Handy oft nicht zuverlässig.</div>
        ${(fg.modus === "alle" ? geraete : (fg.geraete || [])).map((g) => `<div class="zeile"><span style="min-width:150px">${esc(this._gname(g))}</span>
          ${(this.info.zonen || []).map((z) => `<label><input type="checkbox" data-ruhe="${esc(g)}" data-z="${esc(z.id)}" ${((this.info.ruhe || {})[g] || []).includes(z.id) ? "checked" : ""}>${esc(z.name)}</label>`).join("")}</div>`).join("")}
      </div>
      ${k.map((x, ki) => this._klingel(x, ki, geraete, grs)).join("")}
      ${k.length < 8 ? `<button class="ghost" data-a="add"><ha-icon icon="mdi:plus"></ha-icon>Klingel hinzufügen</button>` : ""}
      <div class="karte" style="margin-top:16px"><h2><ha-icon icon="mdi:account-group"></ha-icon>Gruppen</h2>
        <div class="note">Gruppen gelten für alle Klingeln. Eine Klingel kann eine Gruppe anrufen; im Gespräch kann die Leitung Gruppen „dazuholen“.</div>
        ${grs.map((g, gi) => `<div class="gruppe">
          <input data-gr="name" data-gi="${gi}" value="${esc(g.name || "")}" maxlength="24" placeholder="Name, z. B. Handys" style="width:140px">
          ${geraete.map((d) => `<label><input type="checkbox" data-gr="geraet" data-gi="${gi}" data-g="${esc(d)}" ${(g.geraete || []).includes(d) ? "checked" : ""}>${esc(this._gname(d))}</label>`).join("")}
          <button class="rot" data-a="gr_del" data-gi="${gi}" title="Gruppe löschen"><ha-icon icon="mdi:close"></ha-icon></button></div>`).join("")}
        ${grs.length < 12 ? `<button class="ghost" data-a="gr_add"><ha-icon icon="mdi:plus"></ha-icon>Gruppe</button>` : ""}
      </div>
    </div>`;
  }

  _klingel(k, ki, geraete, grs) {
    const a = k.anruf || LEER();
    const lst = (key) => { const [titel, max, doms, nmax] = LISTEN[key], l = a[key] || [];
      return `<div class="zeile"><span class="note" style="width:100%">${esc(titel)} – bis ${max}:</span>
        ${l.map((x, ai) => `<span style="display:inline-flex;gap:6px;align-items:center"><input data-li="${key}" data-f="name" data-ki="${ki}" data-ai="${ai}" value="${esc(x.name || "")}" maxlength="${nmax}" placeholder="Name" style="width:120px">
          ${this._sel(`data-li="${key}" data-f="entity" data-ki="${ki}" data-ai="${ai}"`, x.entity, doms)}
          <button class="rot" data-a="li_del" data-li="${key}" data-ki="${ki}" data-ai="${ai}" title="Entfernen"><ha-icon icon="mdi:close"></ha-icon></button></span>`).join("")}
        ${l.length < max ? `<button class="ghost" data-a="li_add" data-li="${key}" data-ki="${ki}"><ha-icon icon="mdi:plus"></ha-icon>Hinzufügen</button>` : ""}</div>`; };
    const gem = a.sprechen === "gemischt";
    return `<div class="karte"><h2><ha-icon icon="mdi:doorbell"></ha-icon>
        <input data-k="name" data-ki="${ki}" value="${esc(k.name)}" maxlength="30" style="font-size:16px;width:200px">
        <span style="flex:1"></span><button class="rot" data-a="del" data-ki="${ki}" title="Klingel löschen"><ha-icon icon="mdi:delete-outline"></ha-icon></button></h2>
      <div class="zeile">
        <label title="Wenn dieser Zustand auf „an“ geht (bei Knopf/Ereignis: bei jeder Änderung), wird angerufen">Auslöser ${this._sel(`data-k="ausloeser" data-ki="${ki}"`, k.ausloeser, AUSLOESER, "— keiner —")}</label>
        <label title="Für das Anruferbild und das Foto bei „verpasst“">Kamera ${this._sel(`data-k="kamera" data-ki="${ki}"`, k.kamera, ["camera"], "— keine —")}</label>
      </div>
      <hr><div class="note">Geräte, die klingeln (Home-Assistant-App + App „Türklingel“):</div>
      <div class="zeile">${geraete.length ? geraete.map((g) => `<label><input type="checkbox" data-a2="geraet" data-ki="${ki}" data-g="${esc(g)}" ${(a.geraete || []).includes(g) ? "checked" : ""}>${esc(this._gname(g))}${(this.info.app || []).includes(g) ? " ✓" : ""}</label>`).join("")
        : `<span class="note">Kein Gerät mit Home-Assistant-App gefunden.</span>`}</div>
      ${grs.filter((g) => g.name).length ? `<div class="zeile"><span class="note">Gruppen:</span>${grs.filter((g) => g.name).map((g) => `<label><input type="checkbox" data-a2="gruppe" data-ki="${ki}" data-g="${esc(g.name)}" ${(a.gruppen || []).includes(g.name) ? "checked" : ""}>${esc(g.name)} <span class="note">(${(g.geraete || []).map((d) => esc(this._gname(d))).join(", ") || "leer"})</span></label>`).join("")}</div>` : ""}
      <div class="zeile">
        <label title="Adresse von go2rtc (z. B. aus Frigate)">go2rtc <input data-a2="go2rtc" data-ki="${ki}" value="${esc(a.go2rtc || "")}" placeholder="${esc(this.info.go2rtc || "http://…:1984")}" style="width:210px"></label>
        <label title="Name des Streams in go2rtc">Stream <input data-a2="src" data-ki="${ki}" value="${esc(a.src || "")}" placeholder="z. B. haustur" style="width:120px"></label>
        <label>Klingeln <input type="number" data-a2="dauer" data-ki="${ki}" min="10" max="120" value="${a.dauer || 30}" style="width:64px"> s</label>
      </div>
      <div class="zeile"><label title="0 = aus">Mindestlautstärke beim Klingeln <input type="number" data-a2="laut" data-ki="${ki}" min="0" max="100" step="10" value="${a.laut ?? 70}" style="width:64px"> %</label></div>
      <div class="note erkl">Ist ein Handy lautlos, auf Vibration oder leiser als dieser Wert, klingelt die App trotzdem – über den Wecker-Kanal (wie ein Wecker, auch bei „lautlos“) mit dieser Lautstärke. Nach dem Klingeln wird die Lautstärke zurückgestellt. 0 = aus (dann gilt die Lautstärke des Handys). „Nicht stören“ in der App Türklingel bleibt still.</div>
      <hr><div class="note">Gesprächsrunde</div>
      <div class="zeile">
        <label title="Wer zuerst annimmt, leitet. Die anderen Geräte können als Gast mitschauen; die Leitung kann das sperren und Geräte dazuholen."><input type="checkbox" data-a2="mitschauen" data-ki="${ki}" ${a.mitschauen !== false ? "checked" : ""}>Mitschauen auf den anderen Geräten erlauben</label>
        <label title="Sonst darf nur die Leitung Tür öffnen, Schalter, Ansagen und eigene Knöpfe benutzen"><input type="checkbox" data-a2="gaeste_bedienen" data-ki="${ki}" ${a.gaeste_bedienen !== false ? "checked" : ""}>Gäste dürfen bedienen (Tür, Schalter, Ansagen)</label>
      </div>
      <div class="zeile"><label>Sprechen mehrerer Geräte <select data-a2="sprechen" data-ki="${ki}">
          <option value="einer" ${!gem ? "selected" : ""}>Schnell – immer nur einer spricht</option>
          <option value="gemischt" ${gem ? "selected" : ""}>Gemischt – alle gleichzeitig (mit Verzögerung)</option></select></label></div>
      <div class="note erkl">${gem
        ? "<b>Gemischt:</b> Jedes Gerät schickt sein Mikrofon an Home Assistant, HA mischt alle Stimmen und go2rtc spielt das Ergebnis an der Tür ab. Mehrere können gleichzeitig reden, die Stimme kommt aber etwa 0,3–0,8 s später draußen an (Puffer und Umwandlung). Bild und Ton von draußen sind nicht verzögert."
        : "<b>Schnell:</b> Das Mikrofon geht direkt per WebRTC an die Tür – kaum Verzögerung. Macht jemand anderes sein Mikrofon an, wird das vorige automatisch stumm (zwei Mikrofone direkt würden sich draußen zerhacken)."}
        Die Leitung kann im Gespräch umstellen und einzelne Mikrofone sperren.</div>
      <hr><div class="note">Tür und Bedienung im Anruf</div>
      <div class="zeile">
        <label title="Wird im Anruf mit „Tür öffnen“ (zweimal tippen) geschaltet">Tür öffnen mit ${this._sel(`data-a2="oeffnen" data-ki="${ki}"`, a.oeffnen, TUEREN, "— nicht —")}</label>
        ${a.oeffnen ? `<label><input type="checkbox" data-a2="oeffnen_frei" data-ki="${ki}" ${a.oeffnen_frei ? "checked" : ""}>Knopf „Tür öffnen“ im Anruf freigeben</label>` : ""}
      </div>
      <div class="zeile">
        <label title="Auswahl, die beim Wählen eine Ansage abspielt (z. B. Reolink „Schnellantwort-Nachricht abspielen“)">Schnellantworten ${this._sel(`data-a2="antwort" data-ki="${ki}"`, a.antwort, ["select", "input_select"], "— keine —")}</label>
        ${a.antwort && a.oeffnen ? `<label title="Wenn in der App „danach Tür öffnen“ angehakt ist">Tür öffnen nach Ansage <input type="number" data-a2="antwort_oeffnen_s" data-ki="${ki}" min="0" max="60" value="${a.antwort_oeffnen_s ?? 8}" style="width:60px"> s</label>` : ""}
      </div>
      ${a.antwort ? `<div class="zeile"><label title="Ist in der App schon ausgewählt">Vorausgewählte Schnellantwort <select data-a2="antwort_standard" data-ki="${ki}">
        <option value="">— erste —</option>${(this._hass.states[a.antwort]?.attributes.options || []).filter((o) => !["off", "aus"].includes(o)).map((o) => `<option value="${esc(o)}" ${o === a.antwort_standard ? "selected" : ""}>${esc(o)}</option>`).join("")}</select></label></div>` : ""}
      ${a.antwort ? `<div class="note" style="margin-top:6px">Reihenfolge der Schnellantworten in der App (Pfeile), Haken = anzeigen:</div>
      <div class="zeile" style="flex-direction:column;align-items:flex-start;gap:4px">${this._reihe(a).map((r, ri, l) => `<span style="display:inline-flex;gap:6px;align-items:center">
        <button class="ghost" data-a="r_hoch" data-ki="${ki}" data-ri="${ri}" ${ri === 0 ? "disabled" : ""}><ha-icon icon="mdi:arrow-up"></ha-icon></button>
        <button class="ghost" data-a="r_runter" data-ki="${ki}" data-ri="${ri}" ${ri === l.length - 1 ? "disabled" : ""}><ha-icon icon="mdi:arrow-down"></ha-icon></button>
        <label><input type="checkbox" data-reihe="${ki}" data-ri="${ri}" ${r.an !== false ? "checked" : ""}>${esc(r.text)}</label></span>`).join("")}</div>` : ""}
      ${lst("aktionen")}${lst("schalter")}${lst("anzeigen")}
      <hr><div class="note">Menü im Anruf (aufgezogenes Blatt): Reihenfolge und was sichtbar ist</div>
      <div class="zeile" style="flex-direction:column;align-items:flex-start;gap:4px">${(a.menue && a.menue.length ? a.menue : MENUE_STD).map((m, mi, l) => `<span style="display:inline-flex;gap:6px;align-items:center">
        <button class="ghost" data-a="m_hoch" data-ki="${ki}" data-mi="${mi}" ${mi === 0 ? "disabled" : ""} title="nach oben"><ha-icon icon="mdi:arrow-up"></ha-icon></button>
        <button class="ghost" data-a="m_runter" data-ki="${ki}" data-mi="${mi}" ${mi === l.length - 1 ? "disabled" : ""} title="nach unten"><ha-icon icon="mdi:arrow-down"></ha-icon></button>
        <label><input type="checkbox" data-menue="${ki}" data-mi="${mi}" ${m.an !== false ? "checked" : ""}>${esc(MENUE_NAMEN[m.id] || m.id)}</label></span>`).join("")}</div>
      <div class="zeile"><label title="Die Leitung kann es im Gespräch jederzeit umschalten"><input type="checkbox" data-a2="gaeste_leiten" data-ki="${ki}" ${a.gaeste_leiten ? "checked" : ""}>Gäste dürfen mitleiten (dazuholen, Mitschauen sperren, Sprechen umstellen, Mikros sperren)</label></div>
      <div class="zeile"><button data-a="test" data-ki="${ki}" ${((a.geraete || []).length || (a.gruppen || []).length) && a.src ? "" : "disabled"}><ha-icon icon="mdi:phone-ring-outline"></ha-icon>Probeanruf</button>
        <span class="note">klingelt auf allen eingestellten Geräten</span></div>
    </div>`;
  }

  /** Schnellantworten der Klingel in gespeicherter Reihenfolge, neue Optionen hinten (wie in HA). */
  _reihe(a) {
    const alle = (this._hass.states[a.antwort]?.attributes.options || []).filter((o) => !["off", "aus"].includes(o));
    const r = (a.antworten_reihe || []).filter((x) => alle.includes(x.text)).map((x) => ({ text: x.text, an: x.an !== false }));
    return [...r, ...alle.filter((o) => !r.some((x) => x.text === o)).map((o) => ({ text: o, an: true }))];
  }

  _melden(t, err) { this.msg = t; this.msgErr = !!err; this._zeigen(); }

  async _speichern() {
    try { const r = await this._hass.callWS({ type: "doorcall/save", klingeln: this.d.klingeln }); this.d.klingeln = r.klingeln; this.msg = ""; this.msgErr = false; }
    catch (e) { this.msg = "Nicht gespeichert: " + (e.message || e); this.msgErr = true; }
    this._zeigen();
  }

  async _gruppenSpeichern() {
    try { const r = await this._hass.callWS({ type: "doorcall/gruppen", gruppen: (this.info.gruppen || []).filter((g) => g.name) });
      this.info.gruppen = [...r.gruppen, ...(this.info.gruppen || []).filter((g) => !g.name)]; }
    catch (e) { this.msg = "Gruppen: " + (e.message || e); this.msgErr = true; }
  }

  async _aenderung(e) {
    const t = e.target, ki = Number(t.dataset.ki), k = this.d.klingeln[ki];
    if (t.dataset.reihe !== undefined) {   // Schnellantwort ein-/ausblenden
      const kk = this.d.klingeln[Number(t.dataset.reihe)], an = (kk.anruf ||= LEER());
      an.antworten_reihe = this._reihe(an); an.antworten_reihe[Number(t.dataset.ri)].an = t.checked; return this._speichern();
    }
    if (t.dataset.menue !== undefined) {   // Menü-Bereich sichtbar/unsichtbar
      const kk = this.d.klingeln[Number(t.dataset.menue)], an = (kk.anruf ||= LEER());
      an.menue = (an.menue && an.menue.length ? an.menue : MENUE_STD).map((m) => ({ ...m }));
      an.menue[Number(t.dataset.mi)].an = t.checked; return this._speichern();
    }
    if (t.dataset.ge) {   // Einstellungen je Gerät (doorcall/geraet_einst)
      const e = this.info.geraete_einst ||= {}, g = t.dataset.g, x = (e[g] ||= {});
      if (t.dataset.ge === "laut") x.laut = t.value === "" ? null : Number(t.value); else x[t.dataset.ge] = t.checked;
      try { this.info.geraete_einst = (await this._hass.callWS({ type: "doorcall/geraet_einst", einst: e })).geraete_einst; this.msg = ""; this.msgErr = false; }
      catch (err) { this.msg = "Gerät: " + (err.message || err); this.msgErr = true; }
      return this._zeigen();
    }
    if (t.dataset.ruhe) {   // Ruhe-Zonen je Gerät (doorcall/ruhe)
      const r = this.info.ruhe ||= {}, g = t.dataset.ruhe;
      r[g] = (r[g] || []).filter((z) => z !== t.dataset.z); if (t.checked) r[g].push(t.dataset.z);
      try { this.info.ruhe = (await this._hass.callWS({ type: "doorcall/ruhe", ruhe: r })).ruhe; this.msg = ""; this.msgErr = false; }
      catch (err) { this.msg = "Ruhe-Zonen: " + (err.message || err); this.msgErr = true; }
      return this._zeigen();
    }
    if (t.dataset.fg) {   // Freigabe der Geräte (doorcall/freigabe)
      const fg = this.info.freigabe ||= { modus: "auswahl", geraete: [] };
      if (t.dataset.fg === "modus") fg.modus = t.value;
      else { fg.geraete = (fg.geraete || []).filter((x) => x !== t.dataset.g); if (t.checked) fg.geraete.push(t.dataset.g); }
      try { const r = await this._hass.callWS({ type: "doorcall/freigabe", modus: fg.modus, geraete: fg.geraete });
        this.info.freigabe = r.freigabe;
        this.msg = r.gefragt.length ? `Eingerichtet wird: ${r.gefragt.map((g) => this._gname(g)).join(", ")}` : ""; this.msgErr = false;
        setTimeout(async () => { try { this.info = await this._hass.callWS({ type: "doorcall/info" }); this._zeigen(); } catch (x) { /* egal */ } }, 8000); }
      catch (err) { this.msg = "Freigabe: " + (err.message || err); this.msgErr = true; }
      return this._zeigen();
    }
    if (t.dataset.k) { k[t.dataset.k] = t.value.trim(); return this._speichern(); }
    if (t.dataset.gr) {
      const gr = (this.info.gruppen || [])[Number(t.dataset.gi)]; if (!gr) return;
      if (t.dataset.gr === "name") { const alt = gr.name; gr.name = t.value.trim().slice(0, 24);
        for (const x of this.d.klingeln) if (x.anruf?.gruppen) x.anruf.gruppen = x.anruf.gruppen.map((n) => n === alt ? gr.name : n);
        await this._gruppenSpeichern(); return this._speichern(); }
      gr.geraete = (gr.geraete || []).filter((x) => x !== t.dataset.g); if (t.checked) gr.geraete.push(t.dataset.g);
      await this._gruppenSpeichern(); return this._zeigen();
    }
    if (!k) return;
    const a = (k.anruf ||= LEER());
    if (t.dataset.li) {
      const x = (a[t.dataset.li] ||= [])[Number(t.dataset.ai)]; if (!x) return;
      const nmax = LISTEN[t.dataset.li][3];
      if (t.dataset.f === "name") x.name = t.value.trim().slice(0, nmax);
      else { x.entity = t.value; if (!x.name && t.value) x.name = this._name(t.value).slice(0, nmax); }
      if (!x.entity) return this._zeigen();
      return this._speichern();
    }
    const f = t.dataset.a2; if (!f) return;
    if (f === "geraet" || f === "gruppe") { const key = f === "geraet" ? "geraete" : "gruppen";
      a[key] = (a[key] || []).filter((x) => x !== t.dataset.g); if (t.checked) a[key].push(t.dataset.g); }
    else if (f === "oeffnen_frei") {
      if (t.checked && !confirm("„Tür öffnen“ im Anruf freigeben? Wer den Anruf angenommen hat – oder bei wem es gerade klingelt –, kann dann die Tür öffnen (zweimal tippen).")) { t.checked = false; return; }
      a.oeffnen_frei = t.checked;
    } else if (f === "mitschauen" || f === "gaeste_bedienen" || f === "gaeste_leiten") a[f] = t.checked;
    else if (f === "dauer") a.dauer = Math.max(10, Math.min(120, Number(t.value) || 30));
    else if (f === "laut") a.laut = Math.max(0, Math.min(100, Number(t.value) || 0));
    else if (f === "antwort_oeffnen_s") a.antwort_oeffnen_s = Math.max(0, Math.min(60, Number(t.value) || 0));
    else { a[f] = t.value.trim(); if (f === "oeffnen" && !a.oeffnen) a.oeffnen_frei = false; }
    if (f === "src" && a.src && !a.go2rtc && this.info.go2rtc) a.go2rtc = this.info.go2rtc;
    return this._speichern();
  }

  async _klick(e) {
    const b = e.target.closest("button[data-a]"); if (!b) return;
    const a = b.dataset.a, ki = Number(b.dataset.ki), k = this.d.klingeln[ki];
    if (a === "add") { this.d.klingeln.push({ id: "", name: "Tür " + (this.d.klingeln.length + 1), ausloeser: "", kamera: "", anruf: LEER() }); return this._speichern(); }
    if (a === "del") { if (!confirm(`Klingel „${k.name}“ löschen?`)) return; this.d.klingeln.splice(ki, 1); return this._speichern(); }
    if (a === "r_hoch" || a === "r_runter") {
      const an = (k.anruf ||= LEER()), l = this._reihe(an), i = Number(b.dataset.ri), j = a === "r_hoch" ? i - 1 : i + 1;
      [l[i], l[j]] = [l[j], l[i]]; an.antworten_reihe = l; return this._speichern();
    }
    if (a === "m_hoch" || a === "m_runter") {
      const an = (k.anruf ||= LEER()), l = (an.menue && an.menue.length ? an.menue : MENUE_STD).map((m) => ({ ...m }));
      const i = Number(b.dataset.mi), j = a === "m_hoch" ? i - 1 : i + 1;
      [l[i], l[j]] = [l[j], l[i]]; an.menue = l; return this._speichern();
    }
    if (a === "li_add") { (k.anruf ||= LEER())[b.dataset.li] ||= []; k.anruf[b.dataset.li].push({ name: "", entity: "" }); return this._zeigen(); }
    if (a === "li_del") { k.anruf[b.dataset.li].splice(Number(b.dataset.ai), 1); return this._speichern(); }
    if (a === "gr_add") { (this.info.gruppen ||= []).push({ name: "", geraete: [] }); return this._zeigen(); }
    if (a === "gr_del") {
      const gr = this.info.gruppen[Number(b.dataset.gi)]; if (!gr) return;
      if (gr.name && !confirm(`Gruppe „${gr.name}“ löschen?`)) return;
      this.info.gruppen.splice(Number(b.dataset.gi), 1);
      for (const x of this.d.klingeln) if (x.anruf?.gruppen) x.anruf.gruppen = x.anruf.gruppen.filter((n) => n !== gr.name);
      await this._gruppenSpeichern(); return this._speichern();
    }
    if (a === "suchen") {
      try { const r = await this._hass.callWS({ type: "doorcall/suchen" });
        this._melden(r.gefragt.length ? `Gefragt: ${r.gefragt.map((g) => this._gname(g)).join(", ")} – Geräte mit App melden sich in ein paar Sekunden.` : "Alle Geräte sind schon bekannt.");
        setTimeout(async () => { try { this.info = await this._hass.callWS({ type: "doorcall/info" }); this._zeigen(); } catch (e) { /* egal */ } }, 8000); }
      catch (err) { this._melden("Suchen: " + (err.message || err), true); }
      return;
    }
    if (a === "test") {
      try { await this._hass.callWS({ type: "doorcall/test", kid: k.id }); this._melden(`Probeanruf „${k.name}“ geschickt.`); }
      catch (err) { this._melden("Probeanruf: " + (err.message || err), true); }
    }
  }
}
customElements.define("doorcall-panel", DoorCallPanel);
