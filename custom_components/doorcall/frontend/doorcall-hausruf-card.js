// DoorCall – Dashboard-Karte "Hausruf" (2026-11-21): Auswahlfenster direkt in Home Assistant.
// type: custom:doorcall-hausruf-card   name: Hausruf   icon: mdi:account-voice   an: (optional) "alle" | Gerät | "gruppe:Name"
// Welches Gerät das ist, erkennt DoorCall selbst (2026-11-22: Gerätemodell im User-Agent, IP der Verbindung, Benutzer mit
// nur einem Gerät) - ein und dieselbe Karte geht so auf jedem Gerät. Nur wenn das nicht eindeutig ist, wird einmal gefragt
// (localStorage "doorcall_geraet"); "anderes Gerät" legt es fest und hat dann Vorrang ("doorcall_geraet_fest").
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const SCHLUESSEL = "doorcall_geraet", FEST = "doorcall_geraet_fest";
const lesen = (k) => { try { return localStorage.getItem(k) || ""; } catch (e) { return ""; } };
const schreiben = (k, g) => { try { localStorage.setItem(k, g); } catch (e) { /* privat */ } };
let erkannt = "";
const ich = () => lesen(FEST) || erkannt || lesen(SCHLUESSEL);
const ichSetzen = (g) => { schreiben(FEST, g); schreiben(SCHLUESSEL, g); };

class DoorCallHausrufCard extends HTMLElement {
  setConfig(c) { this._c = c || {}; this._zeigen(); }
  set hass(h) { this._hass = h; if (!this._da) this._zeigen(); }
  getCardSize() { return 2; }
  static getStubConfig() { return { name: "Hausruf", icon: "mdi:account-voice" }; }

  _zeigen() {
    if (!this._c) return;
    this._da = true;
    this.innerHTML = `<ha-card style="cursor:pointer;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:16px 8px;min-height:110px;gap:8px">
      <ha-icon icon="${esc(this._c.icon || "mdi:account-voice")}" style="--mdc-icon-size:40px;color:var(--state-icon-color, var(--primary-color))"></ha-icon>
      <div style="font-size:16px">${esc(this._c.name || "Hausruf")}</div></ha-card>`;
    this.querySelector("ha-card").onclick = () => this._los();
  }

  async _los() {
    let d;
    try { d = await this._hass.callWS({ type: "doorcall/ziele", ua: navigator.userAgent || "" }); erkannt = d.ich || ""; }
    catch (e) { this._fenster("Hausruf", `<p>DoorCall nicht erreichbar: ${esc(e.message || e)}</p>`, []); return; }
    const geraete = d.geraete || [];
    if (!ich() || !geraete.some((g) => g.id === ich())) return this._wer(geraete, d);
    if (this._c.an) return this._rufen(this._c.an);
    this._ziel(geraete, d);
  }

  _wer(geraete, d) {
    this._fenster("Welches Gerät ist das?", `<p style="margin:0 0 8px;color:var(--secondary-text-color)">Einmal festlegen – der Hausruf startet dann immer von diesem Gerät. Wird hier im Browser gemerkt.</p>`,
      [...(lesen(FEST) && erkannt ? [{ text: "↺  Automatisch erkennen", los: () => { try { localStorage.removeItem(FEST); } catch (e) {} this._c.an ? this._rufen(this._c.an) : this._ziel(geraete, d); } }] : []),
      ...geraete.map((g) => ({ text: g.name, los: () => { ichSetzen(g.id); this._c.an ? this._rufen(this._c.an) : this._ziel(geraete, d); } }))]);
  }

  _ziel(geraete, d) {
    const von = geraete.find((g) => g.id === ich());
    const ziele = [...((d.tueren || {})[ich()] || []), { id: "alle", name: "Alle" }, ...(d.gruppen || []).map((g) => ({ id: g.id, name: "Gruppe " + g.name })),
      ...geraete.filter((g) => g.id !== ich())];
    const wie = lesen(FEST) ? "festgelegt" : erkannt === ich() ? "erkannt" : "gemerkt";
    this._fenster("Hausruf an …", `<p style="margin:0 0 8px;color:var(--secondary-text-color)">Von: <b>${esc(von ? von.name : ich())}</b> (${wie})
        · <a href="#" data-wer style="color:var(--primary-color)">anderes Gerät</a></p>`,
      ziele.map((z) => ({ text: z.name, los: () => z.id.startsWith("klingel:") ? this._rufen(z.id) : this._art(z, geraete) })),
      (box) => { box.querySelector("[data-wer]").onclick = (e) => { e.preventDefault(); this._wer(geraete, d); }; });
  }

  _art(z, geraete) {
    const auto = z.id === "alle" || z.id.startsWith("gruppe:") ? geraete.some((g) => g.auto && g.id !== ich()) : geraete.some((g) => g.id === z.id && g.auto);
    this._fenster("Hausruf an " + z.name, `<p style="margin:0 0 8px;color:var(--secondary-text-color)">Wie soll es klingeln?</p>`, [
      { text: "🔔  Normal – wie am Gerät eingestellt", los: () => this._runde(z, "normal") },
      { text: "🔊  Laut – Lautstärke erzwingen", los: () => this._runde(z, "laut") },
      { text: "🔕  Lautlos – nur anzeigen, kein Ton", los: () => this._runde(z, "lautlos") },
      ...(auto ? [{ text: "⚡  Direkt durchstellen – nimmt automatisch an", los: () => this._rufen(z.id, "durchstellen") }] : []),
    ]);
  }

  _runde(z, art) {   // mehrere Ziele: wer darf rangehen? (2026-11-22)
    if (this._c.runde || !(z.id === "alle" || z.id.startsWith("gruppe:"))) return this._rufen(z.id, art);
    this._fenster("Hausruf an " + z.name, `<p style="margin:0 0 8px;color:var(--secondary-text-color)">Wer soll rangehen?</p>`, [
      { text: "☝️  Einer – wer zuerst annimmt, bei den anderen hört es auf", los: () => this._rufen(z.id, art, "einer") },
      { text: "👥  Alle – alle klingeln weiter, jeder kann dazukommen", los: () => this._rufen(z.id, art, "alle") },
    ]);
  }

  async _rufen(an, art = this._c.art || "normal", runde = this._c.runde || "alle") {
    try {
      await this._hass.callService("doorcall", "hausruf", { von: ich(), an: [an], art, runde });
      this._fenster("Hausruf", "<p>Ruft an … Das Gesprächsfenster öffnet sich in der App „Türklingel“.</p>", []);
      setTimeout(() => this._zu(), 2500);
    } catch (e) {
      this._fenster("Hausruf", `<p>${esc(e.message || e)}</p>`, []);
    }
  }

  _zu() { if (this._box) { this._box.remove(); this._box = null; } }

  _fenster(titel, html, knoepfe, nachher) {
    this._zu();
    const box = document.createElement("div");
    box.style.cssText = "position:fixed;inset:0;z-index:9999;background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;padding:16px";
    box.innerHTML = `<div style="background:var(--card-background-color,#1c1c1c);color:var(--primary-text-color);border-radius:16px;padding:20px;min-width:280px;max-width:520px;width:100%;max-height:85vh;overflow:auto;box-shadow:0 8px 30px rgba(0,0,0,.5);font-family:var(--paper-font-body1_-_font-family,sans-serif)">
      <div style="font-size:20px;margin-bottom:8px">${esc(titel)}</div>${html}
      <div data-liste style="display:flex;flex-direction:column;gap:8px;margin-top:8px"></div>
      <div style="text-align:right;margin-top:14px"><button data-zu style="background:transparent;color:var(--primary-color);border:0;font-size:15px;padding:8px 12px;cursor:pointer">Abbrechen</button></div></div>`;
    const liste = box.querySelector("[data-liste]");
    for (const k of knoepfe) {
      const b = document.createElement("button");
      b.textContent = k.text;
      b.style.cssText = "text-align:left;font-size:17px;padding:14px 16px;border-radius:10px;border:1px solid var(--divider-color);background:var(--secondary-background-color);color:var(--primary-text-color);cursor:pointer";
      b.onclick = k.los;
      liste.appendChild(b);
    }
    box.querySelector("[data-zu]").onclick = () => this._zu();
    box.onclick = (e) => { if (e.target === box) this._zu(); };
    document.body.appendChild(box);
    this._box = box;
    if (nachher) nachher(box);
  }
}
if (!customElements.get("doorcall-hausruf-card")) {
  customElements.define("doorcall-hausruf-card", DoorCallHausrufCard);
  (window.customCards ||= []).push({ type: "doorcall-hausruf-card", name: "DoorCall Hausruf", description: "Hausruf mit Auswahl direkt in Home Assistant" });
}
