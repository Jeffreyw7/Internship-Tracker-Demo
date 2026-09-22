// Provides reusable company and status displays for tables and detail views.
import { ArrowUpRight } from 'lucide-react';
import type { Application, Status } from '../domain/application';
// Select the status-specific styling while keeping the readable label visible.
export function StatusBadge({status}:{status:Status}) {return <span className={'status status-'+status.toLowerCase()}><i/>{status}</span>;}
// Derive a stable decorative color and initial from the company name.
export function CompanyMark({company,large=false}:{company:string;large?:boolean}) { const tones=['plum','blue','amber','teal','rose'];const color=tones[[...company].reduce((n,c)=>n+c.charCodeAt(0),0)%tones.length];return <span aria-hidden="true" className={`company-mark ${color} ${large?'large':''}`}>{company.slice(0,1).toUpperCase()}</span>;}
// Make the company summary an accessible button that opens its application.
export function CompanyButton({app,onOpen}:{app:Application;onOpen:(a:Application)=>void}) {return <button className="company-button" onClick={()=>onOpen(app)} aria-label={`Open ${app.company} application`}><CompanyMark company={app.company}/><span><strong>{app.company}</strong><small>{app.location || 'Location not set'}</small></span><ArrowUpRight className="company-arrow" size={14}/></button>;}
