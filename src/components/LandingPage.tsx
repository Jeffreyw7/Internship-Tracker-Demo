import { useState, type KeyboardEvent } from 'react';
import { ArrowDown, ArrowRight, ArrowUpRight, Check, ChevronDown, LockKeyhole, Menu, Plus, X } from 'lucide-react';

const previews = {
  applications: {
    label: 'Applications', title: 'Every possibility. One place.',
    description: 'Keep the company, role, status, and next step together. Find the application you need without retracing your tabs.',
    columns: ['Company / role', 'Status', 'Next step'],
    rows: [
      ['Northstar', 'Software Engineering Intern', 'Interviewing', 'Technical interview', 'Nov 06'],
      ['Forma', 'Product Design Intern', 'Applied', 'Follow up', 'Nov 09'],
      ['Orbit Labs', 'Data Engineering Intern', 'Saved', 'Application deadline', 'Nov 12'],
    ],
  },
  interviews: {
    label: 'Interviews', title: 'Walk in with the details.',
    description: 'Keep each interview stage, date, and note with its application. See what is coming up and mark completed stages as you go.',
    columns: ['Company / role', 'Stage', 'Scheduled'],
    rows: [
      ['Northstar', 'Software Engineering Intern', 'Technical', 'Coding conversation', 'Nov 06'],
      ['Forma', 'Product Design Intern', 'Portfolio', 'Portfolio walkthrough', 'Nov 10'],
      ['Orbit Labs', 'Data Engineering Intern', 'Recruiter', 'Introductory call', 'Nov 12'],
    ],
  },
  followups: {
    label: 'Follow-ups', title: 'Know when to check in.',
    description: 'Give the next conversation a date. Your workspace brings due follow-ups into view, so you can decide what to do next.',
    columns: ['Company / role', 'Action', 'Follow-up date'],
    rows: [
      ['Northstar', 'Software Engineering Intern', 'Check in', 'Send a thank-you note', 'Nov 07'],
      ['Forma', 'Product Design Intern', 'Check in', 'Ask about next steps', 'Nov 09'],
      ['Orbit Labs', 'Data Engineering Intern', 'Check in', 'Reconnect with recruiter', 'Nov 13'],
    ],
  },
} as const;
type Preview = keyof typeof previews;
const previewKeys = Object.keys(previews) as Preview[];

const questions = [
  ['What can I keep track of?', 'Company, role, location, application status, applied date, deadline, follow-up date, notes, and interview stages. Search, filter, and sort your applications as your search grows.'],
  ['Can I bring my existing spreadsheet?', 'Yes. Import a CSV, map its columns, and review the proposed changes before committing them. You can also export your applications as a CSV.'],
  ['Will Campus send me reminders?', 'Follow-ups and upcoming interviews appear inside your workspace. Campus does not currently send reminder emails or add events to your calendar.'],
  ['Who can see my applications?', 'Your applications belong to your account. Sign in to view and manage them; other accounts cannot access your records.'],
  ['How do I get started?', 'Create an account, confirm your email, and sign in. You can start with illustrative sample applications or add your own first application. Account registration must be enabled by the site operator.'],
];

export function Brand() {
  return <a className="landing-brand" href="#top" aria-label="Campus home">campus<span className="landing-brand-dot">.</span><span className="landing-brand-caption">INTERNSHIP<br/>TRACKER</span></a>;
}

// The artwork is decorative; the page explains its meaning in ordinary text.
export function JourneyArtwork() {
  return <div className="landing-art" aria-hidden="true">
    <svg className="landing-journey" viewBox="0 0 780 670" fill="none">
      <defs>
        <linearGradient id="journey-paper" x1="31" y1="27" x2="272" y2="224" gradientUnits="userSpaceOnUse"><stop stopColor="#ffffff"/><stop offset=".53" stopColor="#f1f1f3"/><stop offset="1" stopColor="#d9dbe2"/></linearGradient>
        <linearGradient id="journey-silver" x1="39" y1="20" x2="255" y2="226" gradientUnits="userSpaceOnUse"><stop stopColor="#f5f5f8"/><stop offset=".42" stopColor="#e0e2e9"/><stop offset="1" stopColor="#b9becb"/></linearGradient>
        <linearGradient id="journey-yellow" x1="0" y1="30" x2="290" y2="220" gradientUnits="userSpaceOnUse"><stop stopColor="#f8facb"/><stop offset="1" stopColor="#e3eb83"/></linearGradient>
        <filter id="journey-shadow" x="-30%" y="-30%" width="170%" height="190%"><feDropShadow dx="10" dy="20" stdDeviation="17" floodColor="#434b62" floodOpacity=".15"/></filter>
      </defs>
      <g className="journey-folder journey-folder-saved">
        <g className="journey-folder-surface">
        <g transform="translate(66 434) rotate(-13)">
          <path d="M0 14Q0 0 14 0H93Q102 0 108 8L124 26H292Q306 26 306 40V194Q306 208 292 208H14Q0 208 0 194Z" fill="#d9dce4" filter="url(#journey-shadow)"/>
          <rect x="119" y="20" width="176" height="150" rx="7" fill="#ffffff" transform="rotate(2 119 20)"/>
          <path d="M0 53Q0 39 14 39H292Q306 39 306 53V194Q306 208 292 208H14Q0 208 0 194Z" fill="url(#journey-paper)" stroke="#ffffff"/>
          <text x="17" y="19" className="journey-folder-tab">01 / THE POSSIBILITY</text>
          <text x="24" y="111" className="journey-folder-title">Saved.</text>
          <text x="25" y="139" className="journey-folder-detail">It starts with a possibility.</text>
          <path d="M25 174H281M25 182H218" stroke="#c6cbd4"/>
        </g>
        </g>
      </g>
      <g className="journey-folder journey-folder-applied">
        <g className="journey-folder-surface">
        <g transform="translate(206 270) rotate(-13)">
          <path d="M0 14Q0 0 14 0H93Q102 0 108 8L124 26H292Q306 26 306 40V194Q306 208 292 208H14Q0 208 0 194Z" fill="#b7bcc9" filter="url(#journey-shadow)"/>
          <rect x="119" y="20" width="176" height="150" rx="7" fill="#fcfcff" transform="rotate(2 119 20)"/>
          <path d="M0 53Q0 39 14 39H292Q306 39 306 53V194Q306 208 292 208H14Q0 208 0 194Z" fill="url(#journey-silver)" stroke="#f8f9fd"/>
          <text x="17" y="19" className="journey-folder-tab">02 / IN MOTION</text>
          <text x="24" y="111" className="journey-folder-title">Applied.</text>
          <text x="25" y="139" className="journey-folder-detail">A move, made.</text>
          <path d="M25 174H281M25 182H218" stroke="#a1a8b7" strokeOpacity=".5"/>
        </g>
        </g>
      </g>
      <g className="journey-folder journey-folder-next">
        <g className="journey-folder-surface">
        <g transform="translate(346 106) rotate(-13)">
          <path d="M0 14Q0 0 14 0H93Q102 0 108 8L124 26H292Q306 26 306 40V194Q306 208 292 208H14Q0 208 0 194Z" fill="#d4dd7b" filter="url(#journey-shadow)"/>
          <rect x="119" y="20" width="176" height="150" rx="7" fill="#fffef3" transform="rotate(2 119 20)"/>
          <path d="M0 53Q0 39 14 39H292Q306 39 306 53V194Q306 208 292 208H14Q0 208 0 194Z" fill="url(#journey-yellow)" stroke="#faffdf"/>
          <text x="17" y="19" className="journey-folder-tab">UP NEXT</text>
          <text x="25" y="87" className="journey-folder-eyebrow">ONE SMALL STEP.</text>
          <text x="24" y="128" className="journey-folder-title">Your next</text>
          <text x="24" y="165" className="journey-folder-title">conversation.</text>
          <path d="M249 84L275 58M251 58H275V82" stroke="#313923" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M25 187H281" stroke="#adba55" strokeOpacity=".4"/>
        </g>
        </g>
      </g>
      <text x="426" y="562" className="journey-art-caption">SMALL STEPS.</text>
      <text x="426" y="579" className="journey-art-caption">NEW POSSIBILITIES.</text>
    </svg>
  </div>;
}

export default function LandingPage() {
  const [menuOpen, setMenuOpen] = useState(false);
  const [preview, setPreview] = useState<Preview>('applications');
  const current = previews[preview];
  function switchPreview(event: KeyboardEvent<HTMLButtonElement>) {
    const index = previewKeys.indexOf(preview);
    const next = event.key === 'ArrowRight' ? (index + 1) % previewKeys.length
      : event.key === 'ArrowLeft' ? (index + previewKeys.length - 1) % previewKeys.length
      : event.key === 'Home' ? 0 : event.key === 'End' ? previewKeys.length - 1 : null;
    if (next === null) return;
    event.preventDefault(); setPreview(previewKeys[next]);
    document.getElementById(`preview-tab-${previewKeys[next]}`)?.focus();
  }
  return <div className="landing-page" id="top">
    <a className="landing-skip" href="#landing-content">Skip to content</a>
    <header className="landing-header landing-container">
      <Brand/>
      <nav className="landing-desktop-nav" aria-label="Main navigation"><a href="#workspace">The workspace</a><a href="#how-it-works">How it works</a><a href="#questions">Questions</a></nav>
      <div className="landing-header-actions"><a className="landing-sign-in" href="#sign-in">Sign in</a><a className="landing-button landing-button-small" href="#create-account">Get started<ArrowUpRight size={16}/></a><button className="landing-menu-toggle" aria-label={menuOpen ? 'Close navigation' : 'Open navigation'} aria-expanded={menuOpen} aria-controls="landing-mobile-nav" onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? <X size={22}/> : <Menu size={22}/>}</button></div>
      {menuOpen && <nav id="landing-mobile-nav" className="landing-mobile-nav" aria-label="Mobile navigation" onClick={() => setMenuOpen(false)} onKeyDown={event => { if (event.key === 'Escape') { setMenuOpen(false); document.querySelector<HTMLButtonElement>('.landing-menu-toggle')?.focus(); } }}><a href="#workspace">The workspace</a><a href="#how-it-works">How it works</a><a href="#questions">Questions</a><a href="#sign-in">Sign in</a></nav>}
    </header>
    <main id="landing-content" className="landing-main">
      <section className="landing-hero" aria-labelledby="landing-title">
        <div className="landing-container landing-hero-inner">
          <div className="landing-hero-heading">
            <span className="landing-paper-mark" aria-hidden="true"/>
            <h1 id="landing-title">Big ambitions.<br/>Clear next steps.</h1>
            <p className="landing-hero-description">From your first application to your next conversation. Keep your internship search moving, with everything in one personal workspace.</p>
            <div className="landing-hero-actions"><a className="landing-button" href="#create-account">Start your workspace<ArrowUpRight size={18}/></a><a className="landing-text-link" href="#workspace">Take a look<ArrowDown size={17}/></a></div>
            <p className="landing-hero-privacy"><LockKeyhole size={14}/>Your search. Your space.</p>
          </div>
          <JourneyArtwork/>
        </div>
      </section>
      <div className="landing-baseline landing-container"><span>Less to juggle. More room to focus.</span><div><span>Applications</span><span>Interviews</span><span>Follow-ups</span></div><a href="#workspace" aria-label="Explore the workspace"><ArrowDown size={19}/></a></div>

      <section id="workspace" className="landing-workspace landing-container" aria-labelledby="workspace-title">
        <div className="landing-section-intro"><h2 id="workspace-title">Your search has a lot<br/>of moving parts.<br/><span>Give them a home.</span></h2><p>The roles you saved. The conversation coming up. The follow-up you meant to send. Campus keeps the details connected.</p></div>
        <div className="landing-preview-layout">
          <div className="landing-preview-story"><div className="landing-preview-tabs" role="tablist" aria-label="Explore workspace features">{previewKeys.map(key => <button key={key} id={`preview-tab-${key}`} role="tab" aria-selected={preview === key} aria-controls="workspace-preview" tabIndex={preview === key ? 0 : -1} onClick={() => setPreview(key)} onKeyDown={switchPreview}>{previews[key].label}<ArrowUpRight size={17}/></button>)}</div><div className="landing-preview-copy" key={preview}><h3>{current.title}</h3><p>{current.description}</p></div><a className="landing-text-link" href="#create-account">Make it your own<ArrowRight size={17}/></a></div>
          <div className="landing-preview" id="workspace-preview" role="tabpanel" aria-labelledby={`preview-tab-${preview}`} tabIndex={0}>
            <div className="landing-preview-top"><span className="preview-wordmark">campus.</span><span className="preview-example">Illustrative preview</span><span className="preview-avatar">A</span></div>
            <div className="landing-preview-body" key={preview}><div className="landing-preview-heading"><h3>{current.label}</h3><span className="preview-add" aria-hidden="true"><Plus size={16}/></span></div>
              <div className="preview-table-heading">{current.columns.map(label => <span key={label}>{label}</span>)}</div>
              <div className="preview-rows">{current.rows.map((row, index) => <div className="preview-row" key={row[0]}><div className="preview-company"><span className={`preview-logo preview-logo-${index}`}>{row[0].slice(0,1)}</span><div><strong>{row[0]}</strong><span>{row[1]}</span></div></div><span className={`preview-status preview-status-${index}`}>{row[2]}</span><div className="preview-next"><strong>{row[3]}</strong><span>{row[4]}</span></div></div>)}</div>
              <div className="preview-table-footer"><span>3 sample applications</span><span><span/>All in one place</span></div>
            </div><div className="preview-note"><Check size={15}/><span>{preview === 'followups' ? 'Follow-ups appear here, inside your workspace.' : preview === 'interviews' ? 'Every conversation stays connected to its application.' : 'The details you need. Right where you left them.'}</span></div>
          </div>
        </div>
        <div className="landing-detail-strip"><p><Check size={16}/>Search, filter, find your focus</p><p><Check size={16}/>Import and export CSVs</p><p><Check size={16}/>Light, dark, or your system theme</p></div>
      </section>

      <section id="how-it-works" className="landing-how" aria-labelledby="how-title"><div className="landing-container landing-how-inner"><div className="landing-how-heading"><h2 id="how-title">A small start.<br/>A clearer direction.</h2><p>No perfect system required.<br/>Just your next opportunity.</p></div><ol className="landing-steps"><li><span className="landing-step-number">01</span><h3>Make space.</h3><p>Create your account, verify your email, and settle into your own workspace.</p></li><li><span className="landing-step-number">02</span><h3>Bring it together.</h3><p>Add an application or import your CSV. Keep the important details close.</p></li><li><span className="landing-step-number">03</span><h3>Take the next step.</h3><p>Track an interview, set a follow-up date, and keep your search moving.</p></li></ol></div></section>

      <section id="questions" className="landing-faq landing-container" aria-labelledby="faq-title"><div><h2 id="faq-title">A few things<br/>you might wonder.</h2><p>Before your next chapter.</p></div><div className="landing-questions">{questions.map(([question,answer]) => <details key={question}><summary>{question}<Plus size={19} className="faq-plus"/><ChevronDown size={19} className="faq-minus"/></summary><p>{answer}</p></details>)}</div></section>

      <section className="landing-close landing-container" aria-labelledby="close-title"><div className="landing-close-inner"><div><span className="landing-paper-mark" aria-hidden="true"/><h2 id="close-title">Your next chapter<br/>starts with one step.</h2><p>Make a little room for what comes next.</p><a className="landing-button" href="#create-account">Start your workspace<ArrowUpRight size={18}/></a></div><ArrowUpRight className="landing-close-arrow" strokeWidth={.8} aria-hidden="true"/></div></section>
    </main>
    <footer className="landing-footer landing-container"><Brand/><p>A little structure for your next big move.</p><a href="#top" className="landing-text-link">Back to top<ArrowUpRight size={16}/></a></footer>
  </div>;
}
