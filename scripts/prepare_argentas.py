from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "source"
assets = root / "app" / "src" / "main" / "assets"
assets.mkdir(parents=True, exist_ok=True)

parts = sorted(source.glob("argentas-*.part"))
if not parts:
    raise SystemExit("No se encontraron partes de Argentas")

html = "".join(p.read_text(encoding="utf-8") for p in parts)
bridge = (source / "bluetooth-bridge.html").read_text(encoding="utf-8")

marker = "</body>"
if marker in html:
    html = html.replace(marker, bridge + "\n" + marker, 1)
else:
    html += "\n" + bridge

out = assets / "index.html"
out.write_text(html, encoding="utf-8")
print(f"Argentas reconstruido: {len(html)} caracteres desde {len(parts)} partes")
