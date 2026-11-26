import React, { useEffect, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { LayoutDashboard, Upload, GitBranch, Server, Hexagon } from 'lucide-react';
import { workersApi } from '../../api/client';

const links = [
  { label: 'Dashboard', to: '/', icon: LayoutDashboard },
  { label: 'Submit Project', to: '/submit', icon: Upload },
  { label: 'Pipelines', to: '/pipelines', icon: GitBranch },
  { label: 'Workers', to: '/workers', icon: Server },
];
export const Navbar: React.FC = () => {
  const [connected, setConnected] = useState<boolean | null>(null);
  useEffect(() => {
    const check = () => workersApi.getAll().then(() => setConnected(true)).catch(() => setConnected(false));
    check(); const id = window.setInterval(check, 15000); return () => window.clearInterval(id);
  }, []);
  return <>
    <aside className="app-sidebar" aria-label="Main navigation">
      <NavLink to="/" className="app-brand"><Hexagon size={31} strokeWidth={2.3}/><strong>BuildHive</strong></NavLink>
      <nav className="app-nav" aria-label="Primary">
        {links.map(({label,to,icon:Icon}) => <NavLink key={to} end={to === '/'} to={to}
          className={({isActive}) => `app-nav-link ${isActive ? 'is-active' : ''}`}>
          <Icon size={19} strokeWidth={1.9}/><span>{label}</span>
        </NavLink>)}
      </nav>
      <div className="sidebar-foot">Distributed Maven builds</div>
    </aside>
    <header className="app-topbar">
      <NavLink to="/" className="mobile-brand"><Hexagon size={24}/><strong>BuildHive</strong></NavLink>
      <span className={`connection ${connected === true ? 'online' : connected === false ? 'offline' : ''}`}>
        <i/>{connected === true ? 'Server Connected' : connected === false ? 'Server Offline' : 'Connecting'}
      </span>
      <span className="topbar-caption">BuildHive · Distributed Maven Builds</span>
    </header>
    <nav className="mobile-nav" aria-label="Mobile navigation">
      {links.map(({label,to,icon:Icon}) => <NavLink key={to} end={to === '/'} to={to}
        className={({isActive}) => `mobile-nav-link ${isActive ? 'is-active' : ''}`}>
        <Icon size={20}/><span>{label}</span>
      </NavLink>)}
    </nav>
  </>;
};
