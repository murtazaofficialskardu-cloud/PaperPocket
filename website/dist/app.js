'use strict';
const screenshots = {
  home: {file:'01-home.png',alt:'The Home screen in PaperPocket, with Import photos and Scan',heading:'Start with what you have.',caption:'Bring photos from your gallery or scan a document. The Home screen keeps the next step close by.'},
  pages: {file:'02-pages.png',alt:'The Pages screen in PaperPocket, before adding photos',heading:'Prepare your next PDF.',caption:'Add photos, arrange them and choose your export settings. ID-card mode puts front and back on one A4 page.'},
  library: {file:'03-library.png',alt:'The My PDFs screen in PaperPocket, with folders, merge and Trash controls',heading:'Find it when you need it.',caption:'Your generated PDFs, in one library. Search, organize into folders, mark Favorites, share or restore from Trash.'}
};
const tabs = Array.from(document.querySelectorAll('[data-screen]'));
function selectScreen(tab,focus){
  const screen=screenshots[tab.dataset.screen];
  tabs.forEach(item=>{const selected=item===tab;item.setAttribute('aria-selected',String(selected));item.tabIndex=selected?0:-1;});
  const img=document.getElementById('screen-image');img.src='assets/'+screen.file;img.alt=screen.alt;
  document.getElementById('screen-heading').textContent=screen.heading;
  document.getElementById('screen-caption').textContent=screen.caption;
  document.getElementById('screen-panel').setAttribute('aria-labelledby',tab.id);
  if(focus)tab.focus();
}
tabs.forEach((tab,index)=>{
  tab.addEventListener('click',()=>selectScreen(tab,false));
  tab.addEventListener('keydown',event=>{
    let next=null;
    if(event.key==='ArrowRight')next=(index+1)%tabs.length;
    if(event.key==='ArrowLeft')next=(index+tabs.length-1)%tabs.length;
    if(event.key==='Home')next=0;
    if(event.key==='End')next=tabs.length-1;
    if(next!==null){event.preventDefault();selectScreen(tabs[next],true);}
  });
});
let apkChecksum='';
fetch('assets/release-manifest-1.3.0.json').then(response=>{if(!response.ok)throw Error('Manifest unavailable');return response.json();}).then(manifest=>{
  const artifact=manifest.artifacts&&manifest.artifacts['PaperPocket-1.3.0.apk'];
  if(!artifact||!/^https:\/\/github\.com\/murtazaofficialskardu-cloud\/PaperPocket\/raw\//.test(artifact.url)||!/^[a-f0-9]{64}$/.test(artifact.sha256))throw Error('Invalid release data');
  apkChecksum=artifact.sha256;document.getElementById('apk-hash').textContent=apkChecksum;
}).catch(()=>{document.getElementById('copy-hash').disabled=true;document.getElementById('checksum-status').textContent='Use the release manifest link to check the checksum.';});
async function copyText(value){
  if(!navigator.clipboard||!window.isSecureContext)throw Error('Clipboard unavailable');
  await navigator.clipboard.writeText(value);
}
document.getElementById('copy-hash').addEventListener('click',async()=>{
  const status=document.getElementById('checksum-status');
  if(!apkChecksum){status.textContent='Checksum is loading. You can also use the release manifest link.';return;}
  try{await copyText(apkChecksum);status.textContent='Checksum copied.';}catch{status.textContent='Select the checksum above to copy it manually.';}
});
const feedback=document.getElementById('feedback-form');
function report(){
  const values=new FormData(feedback);
  return {topic:String(values.get('topic')||'Feedback'),body:'PaperPocket Android feedback\nApp version: 1.3.0\nPhone: '+String(values.get('phone')||'Not provided').trim()+'\nAndroid: '+String(values.get('android')||'').trim()+'\n\n'+String(values.get('note')||'').trim()};
}
feedback.addEventListener('submit',event=>{
  event.preventDefault();
  if(!feedback.reportValidity())return;
  const value=report();
  const destination='mailto:murtazaofficialskardu@gmail.com?subject='+encodeURIComponent('PaperPocket feedback — '+value.topic)+'&body='+encodeURIComponent(value.body);
  window.location.href=destination;
  document.getElementById('feedback-status').textContent='Your email app will open a draft. Review and send it there. If it does not open, copy the report or use the GitHub link.';
});
document.getElementById('copy-report').addEventListener('click',async()=>{
  if(!feedback.reportValidity())return;
  const value=report();const text=value.topic+'\n\n'+value.body;
  const status=document.getElementById('feedback-status');
  try{await copyText(text);status.textContent='Report copied. Paste it into an email or a GitHub issue.';}
  catch{const fallback=document.getElementById('report-fallback');fallback.value=text;fallback.hidden=false;fallback.focus();fallback.select();status.textContent='Your browser could not copy automatically. Copy the selected report below.';}
});
