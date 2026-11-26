import React from 'react';
import { Outlet } from 'react-router-dom';
import { Navbar } from './Navbar';
export const AppLayout: React.FC = () => <div className="app-shell">
  <Navbar />
  <main className="app-main"><Outlet /></main>
</div>;
