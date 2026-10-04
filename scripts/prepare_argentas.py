from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/"source"; assets=root/"app"/"src"/"main"/"assets"; assets.mkdir(parents=True,exist_ok=True)
parts=sorted(source.glob("argentas-original-*.part"))
if not parts: raise SystemExit("No se encontraron partes de Argentas original")
html="".join(p.read_text(encoding="utf-8") for p in parts)
bridge=(source/"bluetooth-bridge.html").read_text(encoding="utf-8")
overlay=(source/"comandas-overlay.html").read_text(encoding="utf-8")
marker="</body>"
if marker not in html: raise SystemExit("Argentas original no contiene </body>")
html=html.replace(marker,bridge+"\n"+overlay+"\n"+marker,1)
# Mostrar la navegación nativa de Argentas también en móvil para que CONEXIÓN sea un apartado normal.
html=html.replace('className:"hidden md:block border-t border-[#E8B84A]/10 bg-[#0F0F0F]/90 backdrop-blur"', 'className:"block border-t border-[#E8B84A]/10 bg-[#0F0F0F]/90 backdrop-blur"')
html=html.replace('className:"max-w-[1200px] mx-auto px-6 flex gap-2 h-[52px] items-center"', 'className:"max-w-[1200px] mx-auto px-3 md:px-6 flex gap-2 h-[52px] items-center overflow-x-auto"')
(assets/"index.html").write_text(html,encoding="utf-8")
print(f"Argentas-Comandas preparado: {len(html)} caracteres desde {len(parts)} partes")
