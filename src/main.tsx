// Mounts the authentication gate in the HTML root and enables React development checks.
import React from 'react';
import ReactDOM from 'react-dom/client';
import AuthGate from './components/AuthGate';
import './styles.css';
import { ThemeProvider } from './theme/ThemeProvider';
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><ThemeProvider><AuthGate/></ThemeProvider></React.StrictMode>);
