import { useId, useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';

type PasswordFieldProps = {
  label?: string;
  value: string;
  onChange: (value: string) => void;
  autoComplete: 'new-password' | 'current-password';
  disabled?: boolean;
  minLength?: number;
  describedBy?: string;
};

export default function PasswordField({ label = 'Password', value, onChange, autoComplete, disabled, minLength = 1, describedBy }: PasswordFieldProps) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  return <div className="account-field">
    <label htmlFor={id}>{label}</label>
    <div className="account-password">
      <input id={id} name="password" type={visible ? 'text' : 'password'} required minLength={minLength} maxLength={72}
        autoComplete={autoComplete} aria-describedby={describedBy} disabled={disabled} value={value}
        onChange={event => onChange(event.target.value)}/>
      <button type="button" className="account-password-toggle" aria-label={visible ? 'Hide password' : 'Show password'}
        aria-pressed={visible} aria-controls={id} disabled={disabled} onClick={() => setVisible(!visible)}>
        {visible ? <EyeOff size={18} aria-hidden="true"/> : <Eye size={18} aria-hidden="true"/>}
      </button>
    </div>
  </div>;
}
