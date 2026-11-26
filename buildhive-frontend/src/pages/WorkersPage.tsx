import React, { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Cpu,
  HardDrive,
  Loader2,
  RefreshCw,
  Server,
} from 'lucide-react';
import { workersApi } from '../api/client';
import type { Worker } from '../types';
import { cn, formatDateTime, getHeartbeatHealth } from '../utils';

export const WorkersPage: React.FC = () => {
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [lastUpdated, setLastUpdated] = useState<Date>(new Date());
  const [error, setError] = useState<string | null>(null);

  const fetchWorkers = useCallback(async (isManual = false) => {
    if (isManual) setRefreshing(true);
    setError(null);
    try {
      const data = await workersApi.getAll();
      setWorkers(data);
      setLastUpdated(new Date());
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to fetch workers';
      setError(msg);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    const initial = setTimeout(fetchWorkers, 0);
    // Auto-refresh every 5 seconds as requested
    const interval = setInterval(() => {
      fetchWorkers();
    }, 5000);
    return () => {
      clearTimeout(initial);
      clearInterval(interval);
    };
  }, [fetchWorkers]);

  const activeCount = workers.filter((w) => w.status === 'ACTIVE').length;
  const unresponsiveCount = workers.filter((w) => w.status === 'UNRESPONSIVE').length;
  const busyCount = workers.filter((w) => !!w.currentJobId).length;

  return (
    <div className="space-y-8">
      {/* Header & Actions */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-950 sm:text-3xl flex items-center gap-2.5">
            <Cpu className="w-7 h-7 text-amber-400" />
            Distributed Worker Fleet
          </h1>
          <p className="text-xs sm:text-sm text-slate-600 mt-1">
            Real-time monitoring of registered execution nodes, heartbeat freshness, and active Maven processes.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-600 hidden sm:inline-block">
            Auto-refresh: <span className="font-mono text-amber-400">5s</span> (Updated {lastUpdated.toLocaleTimeString()})
          </span>
          <button
            type="button"
            onClick={() => fetchWorkers(true)}
            disabled={refreshing}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-slate-300 bg-slate-100/80 hover:bg-slate-200 text-slate-800 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={cn('w-3.5 h-3.5', refreshing && 'animate-spin text-amber-400')} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* Error Alert */}
      {error && (
        <div className="rounded-xl border border-rose-200 bg-rose-950/30 p-4 text-xs sm:text-sm text-rose-300 flex items-center justify-between">
          <div>
            <span className="font-semibold">Error: </span>
            {error}. Verify the backend daemon is running.
          </div>
          <button
            type="button"
            onClick={() => fetchWorkers(true)}
            className="underline hover:text-slate-950 ml-4 shrink-0"
          >
            Retry
          </button>
        </div>
      )}

      {/* Fleet Overview Metrics */}
      <div className="grid grid-cols-1 sm:grid-cols-4 gap-4">
        <div className="rounded-xl border border-slate-200 bg-white/50 p-4 flex items-center gap-4">
          <div className="p-3 rounded-lg bg-blue-500/10 text-blue-400">
            <Server className="w-6 h-6" />
          </div>
          <div>
            <div className="text-xs text-slate-600 font-medium">Total Registered</div>
            <div className="text-2xl font-bold text-slate-950">{loading ? '-' : workers.length}</div>
          </div>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white/50 p-4 flex items-center gap-4">
          <div className="p-3 rounded-lg bg-emerald-500/10 text-emerald-700">
            <CheckCircle2 className="w-6 h-6" />
          </div>
          <div>
            <div className="text-xs text-slate-600 font-medium">Healthy Nodes</div>
            <div className="text-2xl font-bold text-emerald-700">{loading ? '-' : activeCount}</div>
          </div>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white/50 p-4 flex items-center gap-4">
          <div className="p-3 rounded-lg bg-amber-500/10 text-amber-400">
            <Activity className="w-6 h-6" />
          </div>
          <div>
            <div className="text-xs text-slate-600 font-medium">Executing Jobs</div>
            <div className="text-2xl font-bold text-amber-400">{loading ? '-' : busyCount}</div>
          </div>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white/50 p-4 flex items-center gap-4">
          <div className="p-3 rounded-lg bg-rose-500/10 text-rose-700">
            <AlertTriangle className="w-6 h-6" />
          </div>
          <div>
            <div className="text-xs text-slate-600 font-medium">Unresponsive</div>
            <div className="text-2xl font-bold text-rose-700">{loading ? '-' : unresponsiveCount}</div>
          </div>
        </div>
      </div>

      {/* Worker Grid */}
      {loading ? (
        <div className="p-16 text-center text-slate-600 text-sm animate-pulse">
          Connecting to worker fleet registry...
        </div>
      ) : workers.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-200 bg-white/20 p-12 text-center space-y-3">
          <div className="inline-flex p-3 rounded-xl bg-slate-100/60 text-slate-600">
            <Cpu className="w-8 h-8" />
          </div>
          <h3 className="text-base font-semibold text-slate-800">No active worker nodes registered</h3>
          <p className="text-xs sm:text-sm text-slate-600 max-w-md mx-auto">
            Launch a <code className="text-amber-300 bg-slate-100 px-1 py-0.5 rounded font-mono">buildhive-worker</code> process
            or start the worker service to begin polling and claiming jobs.
          </p>
          <div className="pt-2 text-xs font-mono text-slate-500">
            mvn spring-boot:run -pl buildhive-worker
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {workers.map((worker) => {
            const health = getHeartbeatHealth(worker.lastHeartbeat);
            const isBusy = !!worker.currentJobId;

            return (
              <div
                key={worker.workerId}
                className={cn(
                  'relative rounded-xl border bg-white/60 p-5 shadow-lg backdrop-blur-sm transition-all duration-200 flex flex-col justify-between',
                  health.status === 'healthy'
                    ? 'border-slate-200 hover:border-slate-300'
                    : health.status === 'warning'
                    ? 'border-amber-800/60 bg-amber-950/10'
                    : 'border-rose-800/60 bg-rose-950/10'
                )}
              >
                <div>
                  {/* Top Bar: Name, Health Dot & Status */}
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2">
                      {/* Heartbeat health indicator: Green (<15s), Amber (15-30s), Red (>30s) */}
                      <span className="relative flex h-3 w-3">
                        {health.status === 'healthy' && (
                          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
                        )}
                        <span
                          className={cn(
                            'relative inline-flex rounded-full h-3 w-3',
                            health.status === 'healthy'
                              ? 'bg-emerald-500'
                              : health.status === 'warning'
                              ? 'bg-amber-500'
                              : 'bg-rose-500'
                          )}
                          title={`Heartbeat health: ${health.label}`}
                        />
                      </span>
                      <h3 className="font-semibold text-slate-900 truncate text-base">
                        {worker.workerName || 'Worker Node'}
                      </h3>
                    </div>

                    <span
                      className={cn(
                        'px-2 py-0.5 text-[10px] font-semibold rounded-full border uppercase tracking-wider',
                        worker.status === 'ACTIVE'
                          ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                          : 'bg-rose-50 text-rose-700 border-rose-200'
                      )}
                    >
                      {worker.status}
                    </span>
                  </div>

                  {/* Hostname & Worker ID */}
                  <div className="mt-3 space-y-1 text-xs text-slate-600">
                    <div className="flex items-center gap-1.5 truncate">
                      <HardDrive className="w-3.5 h-3.5 text-slate-500 shrink-0" />
                      <span className="font-mono text-slate-700">{worker.hostname}</span>
                    </div>
                    <div className="text-[11px] font-mono text-slate-600">
                      ID: {worker.workerId}
                    </div>
                  </div>

                  {/* Heartbeat Status */}
                  <div className="mt-4 pt-3 border-t border-slate-200/80 flex items-center justify-between text-xs">
                    <span className="text-slate-600">Last Heartbeat</span>
                    <span
                      className={cn(
                        'font-medium text-xs',
                        health.status === 'healthy'
                          ? 'text-emerald-700'
                          : health.status === 'warning'
                          ? 'text-amber-400'
                          : 'text-rose-700'
                      )}
                    >
                      {health.label}
                    </span>
                  </div>

                  {/* Current Execution State */}
                  <div className="mt-3 rounded-lg border border-slate-200/80 bg-slate-50/50 p-3">
                    <div className="text-[11px] font-medium text-slate-600 mb-1.5 flex items-center justify-between">
                      <span>Currently Executing Job</span>
                      {isBusy ? (
                        <span className="flex items-center gap-1 text-[10px] text-amber-400 font-mono">
                          <Activity className="w-3 h-3 animate-pulse" />
                          BUSY
                        </span>
                      ) : (
                        <span className="text-[10px] text-slate-500 font-mono">IDLE</span>
                      )}
                    </div>

                    {isBusy ? (
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-1.5">
                          <Loader2 className="w-3.5 h-3.5 text-amber-400 animate-spin" />
                          <span className="font-mono text-xs font-semibold text-amber-300">
                            {worker.currentJobType || 'JOB'}
                          </span>
                        </div>
                        {worker.currentJobId && (
                          <span className="text-[10px] font-mono text-slate-600 truncate max-w-[100px]">
                            {worker.currentJobId.substring(0, 8)}...
                          </span>
                        )}
                      </div>
                    ) : (
                      <div className="text-xs text-slate-600 italic">
                        Awaiting next ready task from queue...
                      </div>
                    )}
                  </div>
                </div>

                {/* Footer Registered At */}
                <div className="mt-4 pt-3 border-t border-slate-200/60 flex items-center justify-between text-[11px] text-slate-600">
                  <span>Registered:</span>
                  <span>{formatDateTime(worker.registeredAt)}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
