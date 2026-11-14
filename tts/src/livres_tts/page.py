"""Génération de la page d'écoute à l'aveugle (HTML autonome)."""

from __future__ import annotations

import html
import json

CRITERIA = [
    ("naturel", "Naturel"),
    ("rythme", "Rythme"),
    ("dialogues", "Intonation des dialogues"),
    ("noms", "Noms propres"),
    ("fatigue", "Fatigue estimée sur la durée (5 = aucune fatigue)"),
]

_TEMPLATE = """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Écoute à l'aveugle</title>
<style>
body{font-family:system-ui,sans-serif;max-width:48rem;margin:0 auto;padding:1rem;
background:#161616;color:#e8e8e8}
.voix{border:1px solid #444;border-radius:8px;padding:1rem;margin:1rem 0}
h2{margin-top:0}audio{width:100%}
.crit{display:flex;align-items:center;justify-content:space-between;gap:.5rem;margin:.4rem 0}
.crit label{flex:1}select,textarea,button{font:inherit;background:#222;color:inherit;
border:1px solid #555;border-radius:4px;padding:.3rem}
textarea{width:100%;box-sizing:border-box}
button{padding:.6rem 1rem;cursor:pointer}
</style>
</head>
<body>
<h1>Écoute à l'aveugle</h1>
<p>Notez chaque voix de 1 à 5. Les notes sont enregistrées dans ce navigateur.
Ne pas ouvrir <code>cle.json</code> avant d'avoir terminé.</p>
<div id="voix"></div>
<p><button id="export">Exporter les notes (JSON)</button>
<button id="reset">Tout effacer</button></p>
<details><summary>Texte de l'extrait</summary>
<pre style="white-space:pre-wrap">__TEXT__</pre></details>
<script>
const BENCH_ID = __ID__;
const FILES = __FILES__;
const CRITERIA = __CRITERIA__;
const KEY = "bench-" + BENCH_ID;
function load(){try{return JSON.parse(localStorage.getItem(KEY)||"{}")}catch(e){return {}}}
function save(d){try{localStorage.setItem(KEY,JSON.stringify(d))}catch(e){}}
let data = load();
const root = document.getElementById("voix");
FILES.forEach(f => {
  const label = f.replace(/^voix-/,"").replace(/\\.m4a$/,"");
  const box = document.createElement("section"); box.className="voix";
  let h = '<h2>Voix '+label+'</h2><audio controls preload="none" src="'+f+'"></audio>';
  CRITERIA.forEach(c => {
    h += '<div class="crit"><label for="'+label+'-'+c[0]+'">'+c[1]+'</label>' +
      '<select id="'+label+'-'+c[0]+'" data-v="'+label+'" data-c="'+c[0]+'">' +
      '<option value="">—</option>' +
      [1,2,3,4,5].map(n=>'<option>'+n+'</option>').join("") + '</select></div>';
  });
  h += '<textarea rows="2" placeholder="Remarques" data-v="'+label+'" data-c="note"></textarea>';
  box.innerHTML = h; root.appendChild(box);
});
document.querySelectorAll("[data-v]").forEach(el => {
  const v = el.dataset.v, c = el.dataset.c;
  if (data[v] && data[v][c] !== undefined) el.value = data[v][c];
  el.addEventListener("change", () => {
    data[v] = data[v] || {}; data[v][c] = el.value; save(data);
  });
  el.addEventListener("input", () => {
    data[v] = data[v] || {}; data[v][c] = el.value; save(data);
  });
});
document.getElementById("export").onclick = () => {
  const blob = new Blob([JSON.stringify({bench: BENCH_ID, notes: data}, null, 2)],
    {type: "application/json"});
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob); a.download = "notes-" + BENCH_ID + ".json"; a.click();
};
document.getElementById("reset").onclick = () => {
  if (confirm("Effacer toutes les notes ?")) {
    try{localStorage.removeItem(KEY)}catch(e){}
    location.reload();
  }
};
</script>
</body>
</html>
"""


def render_page(bench_id: str, files: list[str], text: str) -> str:
    def js(value: object) -> str:
        return json.dumps(value, ensure_ascii=False).replace("</", "<\\/")

    return (
        _TEMPLATE.replace("__ID__", js(bench_id))
        .replace("__FILES__", js(files))
        .replace("__CRITERIA__", js([list(c) for c in CRITERIA]))
        .replace("__TEXT__", html.escape(text))
    )
