(function(){
  'use strict';

  var BACKUP_VERSION=1;
  var PREFIXES=['argentas_'];
  var EXTRA_KEYS=['argentas_comandas_v2'];

  function collect(){
    var data={};
    for(var i=0;i<localStorage.length;i++){
      var k=localStorage.key(i);
      if(!k) continue;
      if(PREFIXES.some(function(p){return k.indexOf(p)===0})||EXTRA_KEYS.indexOf(k)>=0){
        try{data[k]=JSON.parse(localStorage.getItem(k));}
        catch(e){data[k]=localStorage.getItem(k);}
      }
    }
    return {
      format:'argentas-comandas-backup',
      version:BACKUP_VERSION,
      createdAt:new Date().toISOString(),
      data:data
    };
  }

  function download(){
    var payload=collect();
    var blob=new Blob([JSON.stringify(payload,null,2)],{type:'application/json;charset=utf-8'});
    var url=URL.createObjectURL(blob);
    var a=document.createElement('a');
    var d=new Date();
    var stamp=d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')+'_'+String(d.getHours()).padStart(2,'0')+'-'+String(d.getMinutes()).padStart(2,'0');
    a.href=url;
    a.download='Argentas-Comandas-respaldo-'+stamp+'.json';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function(){URL.revokeObjectURL(url)},1000);
  }

  function restore(file){
    if(!file) return;
    var reader=new FileReader();
    reader.onload=function(){
      try{
        var payload=JSON.parse(reader.result);
        if(!payload||payload.format!=='argentas-comandas-backup'||!payload.data||typeof payload.data!=='object'){
          throw new Error('Formato de respaldo no reconocido');
        }
        var keys=Object.keys(payload.data);
        if(!keys.length) throw new Error('El respaldo está vacío');
        if(!window.confirm('Se van a restaurar '+keys.length+' datos de Argentas-Comandas. La aplicación se reiniciará. ¿Continuar?')) return;
        keys.forEach(function(k){
          var v=payload.data[k];
          localStorage.setItem(k,typeof v==='string'?v:JSON.stringify(v));
        });
        alert('Respaldo restaurado correctamente. Argentas-Comandas se reiniciará.');
        location.reload();
      }catch(e){
        alert('No se pudo restaurar el respaldo: '+(e&&e.message?e.message:'archivo inválido'));
      }
    };
    reader.onerror=function(){alert('No se pudo leer el archivo de respaldo.')};
    reader.readAsText(file);
  }

  function addUi(){
    var section=document.getElementById('ac-bt');
    if(!section||document.getElementById('ac-backup-card')) return;
    var card=document.createElement('div');
    card.id='ac-backup-card';
    card.className='ac-card';
    card.style.marginTop='10px';
    card.innerHTML='<b>Respaldo y recuperación</b><div class="ac-muted" style="margin-top:8px">Guardá una copia de todos los datos locales de Argentas-Comandas para recuperarlos después de reinstalar o cambiar de equipo.</div><div class="ac-actions"><button type="button" class="ac-btn ac-primary" id="ac-backup-export">⬇️ GUARDAR RESPALDO</button><button type="button" class="ac-btn ac-dark" id="ac-backup-import">⬆️ RESTAURAR RESPALDO</button></div><input id="ac-backup-file" type="file" accept=".json,application/json" style="display:none">';
    section.appendChild(card);
    document.getElementById('ac-backup-export').onclick=download;
    document.getElementById('ac-backup-import').onclick=function(){document.getElementById('ac-backup-file').click()};
    document.getElementById('ac-backup-file').onchange=function(e){restore(e.target.files&&e.target.files[0]);e.target.value=''};
  }

  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',addUi,{once:true});
  else addUi();
  window.argentasBackup=collect;
  window.argentasRestore=restore;
})();