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
    this._fenster("Welches Gerät ist das?", `<p>Einmal festlegen – der Hausruf startet dann immer von diesem Gerät. Wird hier im Browser gemerkt.</p>`,
      [...(lesen(FEST) && erkannt ? [{ text: "↺  Automatisch erkennen", los: () => { try { localStorage.removeItem(FEST); } catch (e) {} this._c.an ? this._rufen(this._c.an) : this._ziel(geraete, d); } }] : []),
      ...geraete.map((g) => ({ text: g.name, los: () => { ichSetzen(g.id); this._c.an ? this._rufen(this._c.an) : this._ziel(geraete, d); } }))]);
  }

  _ziel(geraete, d) {
    const von = geraete.find((g) => g.id === ich());
    const ziele = [...((d.tueren || {})[ich()] || []), { id: "alle", name: "Alle" }, ...(d.gruppen || []).map((g) => ({ id: g.id, name: "Gruppe " + g.name })),
      ...geraete.filter((g) => g.id !== ich())];
    const wie = lesen(FEST) ? "festgelegt" : erkannt === ich() ? "erkannt" : "gemerkt";
    this._fenster("Hausruf an …", `<p>Von: <b>${esc(von ? von.name : ich())}</b> (${wie})
        · <a href="#" data-wer>anderes Gerät</a></p>`,
      ziele.map((z) => ({ text: z.name, los: () => z.id.startsWith("klingel:") ? this._rufen(z.id) : this._art(z, geraete) })),
      (box) => { box.querySelector("[data-wer]").onclick = (e) => { e.preventDefault(); this._wer(geraete, d); }; });
  }

  _art(z, geraete) {
    const auto = z.id === "alle" || z.id.startsWith("gruppe:") ? geraete.some((g) => g.auto && g.id !== ich()) : geraete.some((g) => g.id === z.id && g.auto);
    this._fenster("Hausruf an " + z.name, `<p>Wie soll es klingeln?</p>`, [
      { text: "🔔  Normal – wie am Gerät eingestellt", los: () => this._runde(z, "normal") },
      { text: "🔊  Laut – Lautstärke erzwingen", los: () => this._runde(z, "laut") },
      { text: "🔕  Lautlos – nur anzeigen, kein Ton", los: () => this._runde(z, "lautlos") },
      ...(auto ? [{ text: "⚡  Direkt durchstellen – nimmt automatisch an", los: () => this._rufen(z.id, "durchstellen") }] : []),
    ]);
  }

  _runde(z, art) {   // mehrere Ziele: wer darf rangehen? (2026-11-22)
    if (this._c.runde || !(z.id === "alle" || z.id.startsWith("gruppe:"))) return this._rufen(z.id, art);
    this._fenster("Hausruf an " + z.name, `<p>Wer soll rangehen?</p>`, [
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

  // Fenster über den Dialog-Manager von Home Assistant (2026-11-22): sieht aus wie die HA-eigenen Dialoge (ha-dialog),
  // Zurück-Taste/Wischgeste schließt es, Verlauf wird wie bei Mehr-Infos gepflegt.
  _zu() { if (offen) offen.closeDialog(); }

  _fenster(titel, html, knoepfe, nachher) {
    this.dispatchEvent(new CustomEvent("show-dialog", { bubbles: true, composed: true, detail: {
      dialogTag: DIALOG, dialogImport: () => Promise.resolve(), addHistory: true,
      dialogParams: { titel, html, knoepfe, nachher } } }));
  }
}

const DIALOG = "doorcall-hausruf-dialog";
let offen = null;
const STIL = `
  .inhalt{font-family:var(--ha-font-family-body,Roboto,sans-serif);color:var(--primary-text-color)}
  .inhalt p{margin:0 0 12px;color:var(--secondary-text-color);font-size:var(--ha-font-size-m,14px);line-height:1.5}
  .inhalt b{color:var(--primary-text-color)}
  .inhalt a{color:var(--primary-color)}
  .liste{display:flex;flex-direction:column;gap:8px}
  .liste button{all:unset;box-sizing:border-box;display:flex;align-items:center;min-height:56px;padding:12px 16px;
    border-radius:var(--ha-border-radius-lg,12px);background:var(--ha-color-fill-neutral-quiet-resting,var(--secondary-background-color));
    color:var(--primary-text-color);font-size:var(--ha-font-size-l,16px);cursor:pointer;-webkit-tap-highlight-color:transparent}
  .liste button:hover{background:var(--ha-color-fill-neutral-quiet-hover,var(--divider-color))}
  .liste button:focus-visible{outline:2px solid var(--primary-color);outline-offset:2px}
  /* Ersatz, falls ha-dialog noch nicht geladen ist: gleiche Maße/Farben wie die HA-Dialoge */
  .scrim{position:fixed;inset:0;z-index:9999;display:flex;align-items:center;justify-content:center;
    background:var(--mdc-dialog-scrim-color,rgba(0,0,0,.32));opacity:0;transition:opacity .2s}
  .scrim.da{opacity:1}
  .flaeche{box-sizing:border-box;width:min(580px,95vw);max-height:calc(100vh - 80px);display:flex;flex-direction:column;overflow:hidden;
    background:var(--ha-dialog-surface-background,var(--card-background-color,#1c1c1c));color:var(--primary-text-color);
    border-radius:var(--ha-dialog-border-radius,28px);box-shadow:var(--dialog-box-shadow,0 8px 30px rgba(0,0,0,.35));
    transform:scale(.95);transition:transform .2s}
  .scrim.da .flaeche{transform:none}
  .kopf{display:flex;align-items:center;gap:4px;padding:0 8px;min-height:64px}
  .kopf h2{margin:0;flex:1;font-size:var(--ha-font-size-xl,22px);font-weight:var(--ha-font-weight-medium,500);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .x{all:unset;width:48px;height:48px;display:flex;align-items:center;justify-content:center;border-radius:50%;cursor:pointer;color:var(--primary-text-color)}
  .x:hover{background:rgba(127,127,127,.12)}
  .koerper{padding:0 24px 24px;overflow:auto}
  @media (max-width:450px),(max-height:500px){.flaeche{width:100vw;min-height:100dvh;max-height:100dvh;border-radius:0}}`;

class DoorCallHausrufDialog extends HTMLElement {
  constructor() { super(); this.attachShadow({ mode: "open" }); }

  showDialog(p) {
    offen = this;
    const echt = !!customElements.get("ha-dialog");
    if (!this._rahmen || this._echt !== echt) this._bauen(echt);
    this._titelSetzen(p.titel);
    const k = this._koerper;
    k.innerHTML = `<div class="inhalt">${p.html || ""}<div class="liste"></div></div>`;
    const liste = k.querySelector(".liste");
    for (const b of p.knoepfe || []) {
      const e = document.createElement("button");
      e.textContent = b.text;
      e.onclick = b.los;
      liste.appendChild(e);
    }
    if (p.nachher) p.nachher(k);
    this._oeffnen();
  }

  closeDialog() {
    if (!this._auf) return true;
    this._auf = false;
    if (this._echt) this._rahmen.open = false;   // ha-dialog meldet danach "closed"
    else { this._rahmen.classList.remove("da"); setTimeout(() => this._geschlossen(), 200); }
    return true;
  }

  _geschlossen() {
    this._auf = false;
    if (offen === this) offen = null;
    this.dispatchEvent(new CustomEvent("dialog-closed", { bubbles: true, composed: true, detail: { dialog: DIALOG } }));
  }

  _bauen(echt) {
    this._echt = echt;
    this.shadowRoot.innerHTML = `<style>${STIL}</style>`;
    if (echt) {
      const d = document.createElement("ha-dialog");
      d.addEventListener("closed", (e) => { if (e.target === d) this._geschlossen(); });
      if (!customElements.get("ha-icon-button")) {   // HA-Schließen-Knopf noch nicht geladen: gleiches X selbst
        const x = document.createElement("button");
        x.className = "x"; x.slot = "headerNavigationIcon"; x.setAttribute("aria-label", "Schließen");
        x.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12L19,6.41Z"/></svg>';
        x.onclick = () => this.closeDialog();
        d.appendChild(x);
      }
      this._koerper = document.createElement("div");
      d.appendChild(this._koerper);
      this.shadowRoot.appendChild(d);
      this._rahmen = d;
    } else {
      const s = document.createElement("div");
      s.className = "scrim";
      s.innerHTML = `<div class="flaeche" role="dialog" aria-modal="true"><div class="kopf"><button class="x" aria-label="Schließen">
        <svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12L19,6.41Z"/></svg>
        </button><h2></h2></div><div class="koerper"></div></div>`;
      s.onclick = (e) => { if (e.target === s) this.closeDialog(); };
      s.querySelector(".x").onclick = () => this.closeDialog();
      this._taste = (e) => { if (e.key === "Escape" && this._auf) this.closeDialog(); };
      document.addEventListener("keydown", this._taste);
      this._koerper = s.querySelector(".koerper");
      this.shadowRoot.appendChild(s);
      this._rahmen = s;
    }
  }

  _titelSetzen(t) {
    if (this._echt) { this._rahmen.headerTitle = t; this._rahmen.setAttribute("header-title", t); }
    else this._rahmen.querySelector("h2").textContent = t;
  }

  _oeffnen() {
    if (this._auf) return;
    this._auf = true;
    if (this._echt) this._rahmen.open = true;
    else requestAnimationFrame(() => this._rahmen.classList.add("da"));
  }

  disconnectedCallback() { if (this._taste) document.removeEventListener("keydown", this._taste); }
}
if (!customElements.get(DIALOG)) customElements.define(DIALOG, DoorCallHausrufDialog);

if (!customElements.get("doorcall-hausruf-card")) {
  customElements.define("doorcall-hausruf-card", DoorCallHausrufCard);
  (window.customCards ||= []).push({ type: "doorcall-hausruf-card", name: "DoorCall Hausruf", description: "Hausruf mit Auswahl direkt in Home Assistant" });
}
