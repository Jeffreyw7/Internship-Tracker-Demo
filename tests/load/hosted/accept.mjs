import fs from 'node:fs';
import {acceptCapacity} from './config.mjs';

const inputs=process.argv.slice(2);
if(inputs.length!==3)throw new Error('Use accept.mjs sustained.json burst.json soak.json');
const reports=inputs.map(input=>{
  if(fs.statSync(input).size>1048576)throw new Error('Capacity report exceeds its bound.');
  return JSON.parse(fs.readFileSync(input,'utf8'));
});
const verdict=acceptCapacity(reports);
console.log(JSON.stringify(verdict,null,2));
if(!verdict.accepted)process.exitCode=1;
