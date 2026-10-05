/** Optional real-browser checks share the HTTP smoke test's synthetic account and disposable database. */
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import path from 'node:path';

export async function verifyWorkspaceBrowser({base,identity}) {
  const module=process.env.CAMPUS_PLAYWRIGHT_MODULE;
  const {chromium}=await import(module?pathToFileURL(path.resolve(module)).href:'playwright');
  const browser=await chromium.launch({headless:true,...(process.env.CAMPUS_BROWSER_EXECUTABLE?{executablePath:process.env.CAMPUS_BROWSER_EXECUTABLE}:{})});
  try {
    const context=await browser.newContext({viewport:{width:1440,height:1080}});
    const page=await context.newPage();const errors=[],requests=[];
    page.on('pageerror',error=>errors.push(error.message));
    page.on('request',request=>{if(new URL(request.url()).pathname.startsWith('/api/'))requests.push({url:request.url(),method:request.method()});});
    await page.goto(base);
    await page.getByLabel('Email').fill(identity.email);
    await page.getByLabel('Password',{exact:true}).fill(identity.password);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();
    await page.getByText('Saved to your account',{exact:true}).waitFor();
    const workspace=page.getByRole('navigation',{name:'Workspace'});
    await workspace.getByRole('button',{name:'Applications 10',exact:true}).waitFor();
    assert.equal(await page.locator('tbody tr').count(),8);
    assert.equal(requests.filter(request=>new URL(request.url).pathname==='/api/v2/applications').length,1);
    assert.equal(requests.filter(request=>new URL(request.url).pathname==='/api/applications').length,0);
    await page.getByRole('button',{name:'Next',exact:true}).click();
    await page.getByText('2 on this page · 10 total applications',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Previous',exact:true}).click();
    await page.getByText('8 on this page · 10 total applications',{exact:true}).waitFor();
    const company=await page.locator('tbody .company-button strong').first().textContent();
    await page.keyboard.press('Control+k');
    const search=page.getByRole('textbox',{name:'Search applications'});
    assert.equal(await search.evaluate(element=>document.activeElement===element),true);
    await search.fill(company);
    await page.getByText('1 on this page · 10 total applications',{exact:true}).waitFor();
    await page.getByRole('button',{name:`Open ${company} application`,exact:true}).click();
    const drawer=page.getByRole('dialog');
    await drawer.getByRole('heading',{name:company,exact:true}).waitFor();
    assert.equal(requests.filter(request=>/^\/api\/applications\/[a-f0-9-]+$/.test(new URL(request.url).pathname)&&request.method==='GET').length,1);
    await drawer.getByRole('button',{name:'Edit application',exact:false}).click();
    await drawer.getByLabel('Company',{exact:false}).fill(company+' browser');
    await drawer.getByRole('button',{name:'Save changes',exact:true}).click();
    await drawer.waitFor({state:'hidden'});
    await page.getByRole('button',{name:`Open ${company} browser application`,exact:true}).click();
    await drawer.getByRole('heading',{name:company+' browser',exact:true}).waitFor();
    await drawer.getByRole('button',{name:'Delete',exact:true}).click();
    await drawer.getByRole('button',{name:'Delete application',exact:true}).click();
    await drawer.waitFor({state:'hidden'});
    await page.getByRole('button',{name:'Undo',exact:true}).click();
    await workspace.getByRole('button',{name:'Applications 10',exact:true}).waitFor();
    await page.getByRole('button',{name:`Open ${company} browser application`,exact:true}).waitFor();
    await workspace.getByRole('button',{name:'Interviews',exact:false}).click();
    await page.locator('.activity-row').first().waitFor();
    await page.locator('.complete-button').first().click();
    await page.getByText('Interview marked complete. Nice progress!',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Undo',exact:true}).click();
    await page.getByText('Change undone.',{exact:true}).waitFor();
    const csrf=await (await context.request.get(base+'/api/auth/csrf')).json();
    for(let index=0;index<106;index++) {
      const response=await context.request.post(base+'/api/applications',{headers:{[csrf.headerName]:csrf.token},data:{
        company:'Export fixture '+index,role:'Intern',location:'Remote',status:'Applied',appliedDate:'2026-10-05',deadline:null,followUpDate:null,
        notes:'=formula \"quoted\"\nnotes',stages:[{name:'Technical',state:'Scheduled',date:'2026-10-06'},{name:'Team',state:'Planned',date:null}],
      }});
      assert.equal(response.status(),201,await response.text());
    }
    await page.getByRole('button',{name:'Export CSV',exact:true}).click();
    await page.getByRole('button',{name:'Download CSV',exact:true}).waitFor({timeout:20000});
    const downloadEvent=page.waitForEvent('download');
    await page.getByRole('button',{name:'Download CSV',exact:true}).click();
    const download=await downloadEvent;
    const stream=await download.createReadStream();const chunks=[];for await(const chunk of stream)chunks.push(chunk);
    const csv=Buffer.concat(chunks).toString('utf8');
    assert.match(csv,/Export fixture 105/);assert.match(csv,/Technical \| Scheduled \| 2026-10-06; Team \| Planned/);
    assert.match(csv,/"'=formula ""quoted""\nnotes"/);
    assert.equal(csv.split('\r\n').length,118); // header + 116 records + trailing newline
    assert.equal(requests.filter(request=>new URL(request.url).pathname==='/api/applications'&&request.method==='GET').length,0);
    if(process.env.CAMPUS_BROWSER_SCREENSHOT)await page.screenshot({path:process.env.CAMPUS_BROWSER_SCREENSHOT,fullPage:true});
    assert.deepEqual(errors,[]);
    await page.getByRole('button',{name:'Sign out',exact:true}).click();
    await page.getByRole('button',{name:'Sign in',exact:true}).waitFor();
    console.log('PASS: browser bounded loading, global counts, cursor navigation, keyboard search, detail editing, delete/undo, activity completion/undo, and logout.');
  } finally {await browser.close();}
}
