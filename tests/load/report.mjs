import fs from 'node:fs';
import {pathToFileURL} from 'node:url';

export function reportMarkdown(reports){
  const lines=['# Disposable scalability measurements','','These measurements describe the recorded local resource limits and dataset. They do not qualify hosted scale, zone failover, or multi-hour durability.','','| Scenario | Offered/s | Started/s | Completed | Dropped | Route | p95 ms | p99 ms | 429 | 5xx/transport |','| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: |'];
  for(const report of reports)for(const [route,stats] of Object.entries(report.routes)){
    lines.push(`| ${report.scenario} | ${report.rate} | ${report.achievedArrivalsPerSecond.toFixed(1)} | ${report.completed} | ${report.dropped} | ${route} | ${stats.p95?.toFixed(1)??'—'} | ${stats.p99?.toFixed(1)??'—'} | ${stats.rejections} | ${stats.errors} |`);
  }
  lines.push('','Dropped arrivals include scheduler lag and the explicit concurrency ceiling. Latencies are measured through response consumption; percentiles use a bounded reservoir when sample counts exceed the configured limit. HTTP 401/403/409 remain separate status counts in JSON, and are never hidden by automatic retries. Bootstrap authentication and fixture creation are outside timed foreground arrivals.','');
  return lines.join('\n');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [input,output]=process.argv.slice(2);if(!input||!output)throw new Error('Use report.mjs input.json output.md');
  const document=JSON.parse(fs.readFileSync(input,'utf8'));fs.writeFileSync(output,reportMarkdown(document.reports??[document]));
}
