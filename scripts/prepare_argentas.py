from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/"source"; assets=root/"app"/"src"/"main"/"assets"; assets.mkdir(parents=True,exist_ok=True)
parts=sorted(source.glob("argentas-original-*.part"))
if not parts: raise SystemExit("No se encontraron partes de Argentas original")
html="".join(p.read_text(encoding="utf-8") for p in parts)
bridge=(source/"bluetooth-bridge.html").read_text(encoding="utf-8")
overlay=(source/"comandas-overlay.html").read_text(encoding="utf-8")
backup=(source/"backup-recovery.js").read_text(encoding="utf-8")
marker="</body>"
if marker not in html: raise SystemExit("Argentas original no contiene </body>")
html=html.replace(marker,bridge+"\n"+overlay+"\n<script>"+backup+"<\/script>\n"+marker,1)
# En móvil dejamos la navegación inferior original (incluye CONEXIÓN) y la hacemos
# verdaderamente desplazable con el dedo mediante CSS del overlay. En escritorio
# mantenemos la barra superior original.
# COMANDAS es un apartado propio en móvil; no ocupa un lugar en la barra inferior.
html=html.replace('onClick:()=>e(d.k),className:', 'onClick:()=>d.k==="comandas"?window.dispatchEvent(new Event("open-argentas-comandas")):e(d.k),className:')

(assets/"index.html").write_text(html,encoding="utf-8")
print(f"Argentas-Comandas preparado: {len(html)} caracteres desde {len(parts)} partes")
