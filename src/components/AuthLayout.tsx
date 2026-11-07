import { useEffect, useRef, type ReactNode } from 'react';
import { ArrowLeft, LockKeyhole } from 'lucide-react';
import { Brand, JourneyArtwork } from './LandingPage';
import { ThemeSelect } from './ThemeSelect';

type AuthLayoutProps = {
  title: string;
  description?: string;
  children: ReactNode;
  verification?: boolean;
  onHome?: () => void;
};

// The public account flow shares the landing identity; workspace appearance stays account-owned.
export default function AuthLayout({ title, description, children, verification, onHome }: AuthLayoutProps) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus({ preventScroll: true }); }, [title]);

  return <div className={`account-page${verification ? ' verification-shell' : ''}`}>
    <a className="landing-skip" href="#account-title" onClick={event => { event.preventDefault(); heading.current?.focus(); }}>Skip to form</a>
    <header className="account-header landing-container">
      <div onClick={onHome}><Brand/></div>
      <a className="account-home" href="#top" onClick={onHome}><ArrowLeft size={16} aria-hidden="true"/>Back to home</a>
    </header>
    <main id="account-content" className="account-main landing-container">
      <aside className="account-story" aria-label="Your Campus workspace">
        <div className="account-story-copy">
          <p className="account-story-title">Every next step,<br/>a little clearer.</p>
          <p>Your applications, interviews, and follow-ups.<br/>A little space for your big ambitions.</p>
        </div>
        <JourneyArtwork/>
        <p className="account-story-note"><LockKeyhole size={14} aria-hidden="true"/>Your search. Your space.</p>
      </aside>
      <section className="account-panel" aria-labelledby="account-title">
        <div className="account-heading">
          <span className="account-paper-mark" aria-hidden="true"/>
          <h1 id="account-title" ref={heading} tabIndex={-1}>{title}</h1>
          {description && <p>{description}</p>}
        </div>
        {children}
      </section>
    </main>
    <footer className="account-footer landing-container">
      <span><LockKeyhole size={13} aria-hidden="true"/>Your applications are private to your account.</span>
      <div className="account-theme"><span>Workspace appearance</span><ThemeSelect/></div>
    </footer>
  </div>;
}
