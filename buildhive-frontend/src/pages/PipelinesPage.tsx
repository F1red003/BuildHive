import React, { useCallback, useEffect, useState } from 'react';
import { NavLink } from 'react-router-dom';
import {
  ArrowRight,
  GitFork,
  Layers,
  PlusCircle,
  RefreshCw,
  Search,
} from 'lucide-react';
import { pipelinesApi } from '../api/client';
import { StatusBadge } from '../components/common/StatusBadge';
import type { Pipeline, PipelineStatus } from '../types';
import { cn, formatDateTime, formatDuration } from '../utils';

export const PipelinesPage: React.FC = () => {
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [filter, setFilter] = useState<'ALL' | PipelineStatus>('ALL');
  const [searchQuery, setSearchQuery] = useState<string>('');

  const fetchPipelines = useCallback(async (isManual = false) => {
    if (isManual) setRefreshing(true);
    try {
      const data = await pipelinesApi.getAll();
      setPipelines(data);
    } catch {
      // Ignored
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    const initial = setTimeout(fetchPipelines, 0);
    const interval = setInterval(() => {
      fetchPipelines();
    }, 5000);
    return () => {
      clearTimeout(initial);
      clearInterval(interval);
    };
  }, [fetchPipelines]);

  const filteredPipelines = pipelines.filter((p) => {
    const matchesFilter = filter === 'ALL' || p.status === filter;
    const name = p.projectName || 'Maven Project';
    const matchesSearch =
      name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      p.id.toLowerCase().includes(searchQuery.toLowerCase());
    return matchesFilter && matchesSearch;
  });

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-950 sm:text-3xl flex items-center gap-2.5">
            <GitFork className="w-7 h-7 text-amber-400" />
            Build Pipelines
          </h1>
          <p className="text-xs sm:text-sm text-slate-600 mt-1">
            Historical and active DAG pipeline executions scheduled across worker nodes.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => fetchPipelines(true)}
            disabled={refreshing}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-slate-300 bg-slate-100/80 hover:bg-slate-200 text-slate-800 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={cn('w-3.5 h-3.5', refreshing && 'animate-spin text-amber-400')} />
            <span>Refresh</span>
          </button>
          <NavLink
            to="/submit"
            className="inline-flex items-center gap-1.5 px-3.5 py-1.5 text-xs font-semibold rounded-lg bg-amber-500 hover:bg-amber-400 text-slate-950 shadow-sm shadow-amber-500/20 transition-colors"
          >
            <PlusCircle className="w-3.5 h-3.5" />
            <span>New Submission</span>
          </NavLink>
        </div>
      </div>

      {/* Filters & Search */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0">
          {(['ALL', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'] as const).map((status) => (
            <button
              key={status}
              type="button"
              onClick={() => setFilter(status)}
              className={cn(
                'px-3 py-1.5 rounded-lg text-xs font-medium transition-colors whitespace-nowrap',
                filter === status
                  ? 'bg-amber-500 text-slate-950 font-semibold shadow-sm'
                  : 'bg-white/60 text-slate-600 hover:text-slate-800 border border-slate-200'
              )}
            >
              {status}
            </button>
          ))}
        </div>

        <div className="relative max-w-xs w-full">
          <Search className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search by project name or ID..."
            className="w-full pl-9 pr-4 py-1.5 rounded-lg border border-slate-200 bg-white/60 text-xs text-slate-900 placeholder:text-slate-500 focus:outline-none focus:border-amber-500"
          />
        </div>
      </div>

      {/* Table */}
      <div className="rounded-xl border border-slate-200/80 bg-white/50 shadow-xl overflow-hidden">
        {loading ? (
          <div className="p-16 text-center text-slate-600 text-sm animate-pulse">
            Loading pipelines...
          </div>
        ) : filteredPipelines.length === 0 ? (
          <div className="p-16 text-center space-y-3">
            <div className="inline-flex p-3 rounded-xl bg-slate-100/60 text-slate-600">
              <Layers className="w-8 h-8" />
            </div>
            <h3 className="text-base font-semibold text-slate-800">No pipelines match criteria</h3>
            <p className="text-xs text-slate-600">
              Try adjusting your search query or status filter.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto history-table">
            <table className="w-full text-left border-collapse text-xs sm:text-sm">
              <thead>
                <tr className="border-b border-slate-200/80 bg-slate-50/40 text-[11px] font-semibold text-slate-600 uppercase tracking-wider">
                  <th className="px-6 py-3">Project</th>
                  <th className="px-6 py-3">Status</th>
                  <th className="px-6 py-3">Duration</th>
                  <th className="px-6 py-3">Submitted</th>
                  <th className="px-6 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200/60 font-normal">
                {filteredPipelines.map((pipeline) => (
                  <tr key={pipeline.id} className="hover:bg-slate-100/40 transition-colors group">
                    <td className="px-6 py-4">
                      <div className="flex flex-col">
                        <NavLink
                          to={`/pipelines/${pipeline.id}`}
                          className="font-medium text-slate-900 group-hover:text-amber-400 transition-colors text-sm"
                        >
                          {pipeline.projectName || 'Maven Project'}
                        </NavLink>
                        <span className="text-[11px] font-mono text-slate-600">
                          ID: {pipeline.id.slice(0, 8)}…
                        </span>
                      </div>
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap">
                      <StatusBadge status={pipeline.status} size="sm" />
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap font-mono text-xs text-slate-700">
                      {formatDuration(pipeline.startedAt, pipeline.completedAt)}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap text-xs text-slate-600">
                      {formatDateTime(pipeline.createdAt)}
                    </td>

                    <td className="px-6 py-4 whitespace-nowrap text-right">
                      <NavLink
                        to={`/pipelines/${pipeline.id}`}
                        className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 text-slate-800 text-xs font-medium transition-colors"
                      >
                        <span>View DAG</span>
                        <ArrowRight className="w-3 h-3" />
                      </NavLink>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="history-mobile-list">
              {filteredPipelines.map((pipeline) => (
                <NavLink key={pipeline.id} to={`/pipelines/${pipeline.id}`} className="history-mobile-item">
                  <div className="history-mobile-top"><strong>{pipeline.projectName || 'Maven Project'}</strong><StatusBadge status={pipeline.status} size="sm" /></div>
                  <small>ID: {pipeline.id.slice(0, 8)}… · {formatDateTime(pipeline.createdAt)}</small>
                  <div className="history-mobile-bottom"><span>{formatDuration(pipeline.startedAt, pipeline.completedAt)}</span><b>View DAG <ArrowRight size={14} /></b></div>
                </NavLink>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
