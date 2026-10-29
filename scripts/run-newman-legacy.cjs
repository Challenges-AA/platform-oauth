// Compatibilité historique uniquement : secrets injectés par l'environnement, jamais enregistrés.
const newman = require('newman');
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const required = ['OAUTH_CLIENT_SECRET','OAUTH_USER1_PASSWORD'];
// Échoue avant d'envoyer des requêtes si les valeurs requises ne sont pas provisionnées.
for (const k of required) if (!process.env[k]) { console.error(`Missing ${k}`); process.exit(2); }
const env = {values:[
  {key:'client_secret',value:process.env.OAUTH_CLIENT_SECRET,enabled:true},
  {key:'username',value:'user1',enabled:true},{key:'password',value:process.env.OAUTH_USER1_PASSWORD,enabled:true},
  {key:'subject',value:'subject-1',enabled:true},{key:'_access_token',value:'',enabled:true}
]};
// Aucun reporter brut : les requêtes contiennent l'authentification Basic et un jeton Bearer.
newman.run({collection:require('../postman/legacy.collection.json'), environment:env, reporters:[], silent:true, timeout:120000},(err,summary)=>{
  // Le rapport JSON brut de Newman contient les requêtes/réponses. Ne conserver que
  // des compteurs agrégés afin de ne pas écrire les secrets ou jetons sur disque.
  const run=summary && summary.run;
  const stats=run && run.stats || {};
  const pick=(group)=>({total:Number(group && group.total || 0),failed:Number(group && group.failed || 0),pending:Number(group && group.pending || 0)});
  const counts={requests:pick(stats.requests),assertions:pick(stats.assertions),prerequestScripts:pick(stats.prerequestScripts),testScripts:pick(stats.testScripts)};
  const failures=(run && Array.isArray(run.failures) ? run.failures.length : 0)+(err?1:0);
  const status=err?'error':failures?'failed':'passed';
  const out=path.join(root,'.work/reports'); fs.mkdirSync(out,{recursive:true});
  // Ne persiste que des compteurs globaux, jamais les détails de requête/réponse.
  fs.writeFileSync(path.join(out,'newman.json'),JSON.stringify({report:'Newman sanitized aggregate',status,counts,failures},null,2)+'\n','utf8');
  const failed=failures>0?1:0, errors=status==='error'?1:0;
  const testcase=failed?`  <testcase classname="Newman" name="collection-run"><failure message="Newman collection failed"/></testcase>`:'  <testcase classname="Newman" name="collection-run"/>';
  const xml=`<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="Newman E2E aggregate" tests="1" failures="${failed}" errors="${errors}" skipped="0" timestamp="${new Date().toISOString()}">\n${testcase}\n</testsuite>\n`;
  fs.writeFileSync(path.join(out,'newman.xml'),xml,'utf8');
  if(status!=='passed') { console.error('Newman failed; sanitized aggregate reports written to .work/reports.'); process.exit(1); }
  console.log('Newman passed; sanitized aggregate reports written to .work/reports.');
});
