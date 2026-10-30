// Public PKCE E2E against running services. Credentials and tokens stay in memory.
const fs=require('fs'), path=require('path'), crypto=require('crypto');
const newman=require('newman');
const root=path.resolve(__dirname,'..');
if(!process.env.OAUTH_USER1_PASSWORD){console.error('Missing OAUTH_USER1_PASSWORD');process.exit(2);}
const values={as:process.env.PKCE_AS_URL||'http://127.0.0.1:9000',rs:process.env.PKCE_RS_URL||'http://127.0.0.1:9001',
  client_id:'portage-public',redirect_uri:process.env.OAUTH_REDIRECT_URI||'http://127.0.0.1:8080/callback',
  username:'user1',password:process.env.OAUTH_USER1_PASSWORD,expected_subject:'subject-1',code_verifier:crypto.randomBytes(32).toString('base64url')};
newman.run({collection:require('../postman/pkce.collection.json'),folder:'Automated PKCE',
  environment:{values:Object.entries(values).map(([key,value])=>({key,value,enabled:true}))},
  ignoreRedirects:true,reporters:[],silent:true,timeout:120000,timeoutRequest:10000,bail:true},(err,summary)=>{
  const run=summary&&summary.run, stats=run&&run.stats||{};
  const pick=x=>({total:Number(x&&x.total||0),failed:Number(x&&x.failed||0)});
  const failures=(run&&run.failures||[]).length || (err?1:0);
  const status=err?'error':failures?'failed':'passed';
  const out=path.join(root,'.work/reports');fs.mkdirSync(out,{recursive:true});
  const report={report:'Newman PKCE sanitized aggregate',timestamp:new Date().toISOString(),status,
    counts:{requests:pick(stats.requests),assertions:pick(stats.assertions)},failures};
  fs.writeFileSync(path.join(out,'newman-pkce.json'),JSON.stringify(report,null,2)+'\n');
  fs.writeFileSync(path.join(out,'newman-pkce.xml'),`<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="Newman PKCE E2E" tests="1" failures="${failures?1:0}" errors="0"><testcase name="PKCE lifecycle">${failures?'<failure message="PKCE lifecycle failed"/>':''}</testcase></testsuite>\n`);
  // Report assertion names only: never dump request/response or sandbox error details.
  for(const f of run&&run.failures||[]) console.error('Failed step: '+(f.source&&f.source.name||'request')+'; assertion: '+(f.error&&f.error.test||'request/script error'));
  console.log(JSON.stringify(report));process.exitCode=failures?1:0;
});
