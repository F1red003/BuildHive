import React, { useCallback, useEffect, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { Plus, RefreshCw, ArrowRight } from 'lucide-react';
import { pipelinesApi } from '../api/client';
import { StatusBadge } from '../components/common/StatusBadge';
import type { DashboardMetrics, Pipeline } from '../types';
import { formatDateTime, formatDuration } from '../utils';
const empty: DashboardMetrics = { totalPipelines: 0, runningJobs: 0, readyJobs: 0, onlineWorkers: 0 };
export const DashboardPage: React.FC = () => {
  const [metrics, setMetrics] = useState(empty);
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(async (manual = false) => {
    if (manual) setRefreshing(true);
    try { const [nextMetrics, nextPipelines] = await Promise.all([pipelinesApi.getMetrics(), pipelinesApi.getAll()]);
      setMetrics(nextMetrics); setPipelines(nextPipelines.slice(0, 10)); setError(null);
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load dashboard'); }
    finally { setLoading(false); setRefreshing(false); }
  }, []);
  useEffect(() => {
    const initial = window.setTimeout(() => refresh(), 0);
    const interval = window.setInterval(() => refresh(), 5000);
    return () => {
      window.clearTimeout(initial);
      window.clearInterval(interval);
    };
  }, [refresh]);
  const stats = [
    ['Total Pipelines', metrics.totalPipelines], ['Active Running Jobs', metrics.runningJobs],
    ['Queued Ready Jobs', metrics.readyJobs], ['Online Workers', metrics.onlineWorkers],
  ] as const;
  return <div className="dashboard-page">
    <div className="page-heading dashboard-heading">
      <div><h1>Cluster Dashboard</h1><p>Real-time overview of your distributed Maven build cluster.</p></div>
      <div className="page-actions">
        <NavLink className="action-primary" to="/submit"><Plus size={18}/>New Submission</NavLink>
        <button className="action-secondary" onClick={() => refresh(true)} disabled={refreshing}><RefreshCw size={17} className={refreshing ? 'animate-spin' : ''}/>Refresh</button>
      </div>
    </div>
    {error && <div className="app-error" role="alert">{error} <button onClick={() => refresh(true)}>Retry</button></div>}
    <div className="metrics-strip" aria-label="Cluster metrics">{stats.map(([label,value]) => <div className="metric" key={label}>
      <span>{label}</span><strong>{loading ? '…' : value}</strong>
    </div>)}</div>
    <section className="recent-section" aria-labelledby="recent-title">
      <div className="section-heading"><h2 id="recent-title">Recent Pipelines</h2><NavLink to="/pipelines">View all <ArrowRight size={16}/></NavLink></div>
      {loading ? <p className="empty-state">Loading pipeline runs…</p> : pipelines.length === 0 ?
        <div className="empty-state">No pipeline executions yet. <NavLink to="/submit">Submit your first project</NavLink>.</div> : <>
        <div className="table-scroll"><table className="pipeline-table"><thead><tr><th>Project</th><th>Status</th><th>Duration</th><th>Submitted</th><th>Actions</th></tr></thead>
          <tbody>{pipelines.map(p => <tr key={p.id}><td><NavLink className="project-link" to={`/pipelines/${p.id}`}>{p.projectName || 'Maven Project'}</NavLink><small>ID: {p.id.slice(0, 8)}…</small></td>
            <td><StatusBadge status={p.status}/></td><td>{formatDuration(p.startedAt, p.completedAt)}</td><td>{formatDateTime(p.createdAt)}</td>
            <td><NavLink className="inspect-link" to={`/pipelines/${p.id}`}>Inspect <ArrowRight size={15}/></NavLink></td></tr>)}</tbody></table></div>
        <div className="mobile-pipeline-list">{pipelines.map(p => <NavLink to={`/pipelines/${p.id}`} className="mobile-pipeline" key={p.id}>
          <div><strong>{p.projectName || 'Maven Project'}</strong><small>ID: {p.id.slice(0, 8)}… · {formatDateTime(p.createdAt)}</small><span>Duration: {formatDuration(p.startedAt, p.completedAt)}</span></div>
          <div><StatusBadge status={p.status}/><b>Inspect <ArrowRight size={14}/></b></div>
        </NavLink>)}</div>
      </>}
    </section>
  </div>;
};
