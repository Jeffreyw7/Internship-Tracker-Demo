import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const css=readFileSync(new URL('../src/theme/tokens.css',import.meta.url),'utf8');
const palettes=[...css.matchAll(/(?:^|\n):root(?:\[data-theme="dark"\])?\s*\{([^}]+)\}/g)].map(m=>Object.fromEntries([...m[1].matchAll(/--([\w-]+):\s*(#[\da-f]+)/g)].map(m=>[m[1],m[2]])));
function luminance(hex){return hex.slice(1).match(/../g).slice(0,3).map(x=>parseInt(x,16)/255).map(x=>x<=.04045?x/12.92:((x+.055)/1.055)**2.4).reduce((s,x,i)=>s+x*[.2126,.7152,.0722][i],0);}
function contrast(a,b){const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
test('both semantic palettes have matching roles and readable text/control pairs',()=>{
  assert.equal(palettes.length,2);assert.deepEqual(Object.keys(palettes[0]),Object.keys(palettes[1]));
  for(const [i,p] of palettes.entries()){
    const pairs=[];
    for(const fg of ['text-primary','text-secondary','text-muted','link-text'])for(const bg of ['bg-page','bg-surface','bg-sidebar','bg-subtle','bg-hover','bg-selected'])pairs.push([fg,bg,4.5]);
    for(const tone of ['error','warning','success','blue','plum','rose','neutral','danger','action','toast'])pairs.push([tone+'-text',tone+'-bg',4.5]);
    for(const bg of ['bg-surface','bg-page'])pairs.push(['border-control',bg,3],['focus-ring',bg,3]);
    for(const tone of ['blue','plum','rose','success','neutral','warning'])pairs.push([tone+'-text','bg-sidebar',3]);
    for(const [fg,bg,min] of pairs)assert.ok(contrast(p[fg],p[bg])>=min,`palette ${i} ${fg}/${bg}: ${contrast(p[fg],p[bg]).toFixed(2)} < ${min}`);
  }
});
test('component stylesheet only consumes defined semantic colors',()=>{
  const styles=readFileSync(new URL('../src/styles.css',import.meta.url),'utf8');
  assert.doesNotMatch(styles, /#[\da-fA-F]{3,8}\b/);
  const defined=new Set([...css.matchAll(/--([\w-]+):/g),...styles.matchAll(/--([\w-]+):/g)].map(m=>m[1]));
  for(const match of styles.matchAll(/var\(--([\w-]+)\)/g))assert.ok(defined.has(match[1]),match[1]);
  assert.match(styles,/\.tabs>button\.active:after\{[^}]*background:var\(--link-text\)/);
});
