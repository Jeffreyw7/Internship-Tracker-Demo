import { offsetDate, type Application, type Status } from '../../src/domain/application.ts';
export function sampleApplications():Application[] {
  const rows: [string,string,string,Status,number,number|null,number|null][] = [
    ['Figma','Software Engineer Intern','New York, NY','Interviewing',-8,21,2],
    ['Stripe','Backend Engineering Intern','New York, NY','Applied',-5,14,0],
    ['Bloomberg','Software Engineer Intern','New York, NY','Interviewing',-14,10,5],
    ['Notion','Software Engineer Intern','San Francisco, CA','Saved',0,3,null],
    ['Datadog','Backend Engineering Intern','New York, NY','Applied',-3,18,4],
    ['MongoDB','Software Engineer Intern','New York, NY','Interviewing',-18,12,null],
    ['Spotify','Backend Engineering Intern','New York, NY','Applied',-7,14,-1],
    ['Vercel','Engineering Intern','Remote','Offer',-26,null,6],
    ['Adobe','Software Engineer Intern','San Jose, CA','Rejected',-21,null,null],
    ['Ramp','Software Engineer Intern','New York, NY','Applied',-2,20,7]
  ];
  return rows.map(([company,role,location,status,applied,deadline,follow],i)=>({
    version:0,sample:true,deleted:false,id:'sample-'+i,company,role,location,status,appliedDate:status==='Saved'?'':offsetDate(applied),
    deadline:deadline===null?'':offsetDate(deadline),followUpDate:follow===null?'':offsetDate(follow),
    notes: company==='Figma'?'Review the project architecture and practice explaining the trade-offs in the Campus tracker. Prepare a question about the engineering team.':company==='Stripe'?'Tailor the project discussion toward APIs, reliability, and Java backend experience.':company==='Notion'?'Draft a concise introduction and highlight the application tracker project.':company==='Vercel'?'Sample offer received. Review team fit, mentorship, and internship dates before deciding.':'Sample application for exploring your workspace. Replace these notes with your own.',
    stages:status==='Interviewing'?[{id:'stage-'+i+'a',name:'Recruiter screen',state:'Completed' as const,date:offsetDate(-3)},{id:'stage-'+i+'b',name:i===0?'Technical interview':i===2?'Coding interview':'Team conversation',state:'Scheduled' as const,date:offsetDate(i===0?1:i===2?3:5)},{id:'stage-'+i+'c',name:'Final interview',state:'Planned' as const,date:''}]:[],
    createdAt:new Date(Date.now()+(applied-i)*86400000).toISOString(),updatedAt:new Date(Date.now()-i*60000).toISOString()
  }));
}
