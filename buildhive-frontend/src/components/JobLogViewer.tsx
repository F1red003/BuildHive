import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
  ArrowDown,
  Clock,
  Copy,
  Cpu,
  Download,
  Hash,
  Loader2,
  Maximize2,
  Minimize2,
  Radio,
  Search,
  Terminal,
  X,
} from 'lucide-react';
import { StatusBadge } from './common/StatusBadge';
import type { Job } from '../types';
import { useEventSource } from '../hooks/useEventSource';
import { cn, formatDateTime, formatDuration } from '../utils';

interface JobLogViewerProps {
  job: Job | null;
  onClose?: () => void;
  isModal?: boolean;
}

export const JobLogViewer: React.FC<JobLogViewerProps> = ({
  job,
  onClose,
  isModal = true,
}) => {
  const [selection, setSelection] = useState<{ jobId: string; attempt: number } | null>(null);
  const selectedAttempt = selection?.jobId === job?.id ? selection?.attempt ?? 0 : 0;
  const attempt = selectedAttempt || job?.retryCount || 0;
  const logPath = job && attempt ? `/api/jobs/${job.id}/logs` : null;
  const streamUrl = logPath ? `${logPath}/stream?attempt=${attempt}` : null;
  const downloadUrl = logPath ? `${logPath}/download?attempt=${attempt}` : null;
  const { lines: logs, status: connectionStatus } = useEventSource(streamUrl);
  const [download, setDownload] = useState<{ url: string; pending: boolean; error?: string } | null>(null);
  const currentDownload = download?.url === downloadUrl ? download : null;
  const downloadRequest = useRef<AbortController | null>(null);
  const [autoScroll, setAutoScroll] = useState<boolean>(true);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [copied, setCopied] = useState<boolean>(false);
  const [isFullScreen, setIsFullScreen] = useState<boolean>(false);

  const terminalEndRef = useRef<HTMLDivElement>(null);
  useEffect(() => () => downloadRequest.current?.abort(), [downloadUrl]);
  // Auto-scroll when new logs arrive if enabled
  useEffect(() => {
    if (autoScroll && terminalEndRef.current) {
      terminalEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [logs, autoScroll]);

  // Filter logs by search query
  const filteredLogs = useMemo(() => {
    if (!searchQuery.trim()) return logs;
    const q = searchQuery.toLowerCase();
    return logs.filter((line) => line.toLowerCase().includes(q));
  }, [logs, searchQuery]);

  // Download full accumulated logs as file
  const handleDownloadLogs = async () => {
    if (!downloadUrl) return;
    downloadRequest.current?.abort();
    const controller = new AbortController();
    downloadRequest.current = controller;
    setDownload({ url: downloadUrl, pending: true });
    try {
      const response = await fetch(downloadUrl, { method: 'HEAD', signal: controller.signal });
      if (controller.signal.aborted) return;
      if (response.status === 404) {
        setDownload({ url: downloadUrl, pending: false, error: 'No log file is available for this attempt yet.' });
      } else if (response.ok) {
        setDownload(null);
        window.location.assign(downloadUrl);
      } else {
        throw new Error(`Log lookup failed (${response.status})`);
      }
    } catch {
      if (!controller.signal.aborted) {
        setDownload({ url: downloadUrl, pending: false, error: 'Could not download the log. Try again.' });
      }
    }
  };

  // Copy logs to clipboard
  const handleCopyLogs = () => {
    navigator.clipboard.writeText(logs.join('\n'));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  if (!job) return null;

  const content = (
    <div
      className={cn(
        'flex flex-col bg-slate-950 border border-slate-800 shadow-2xl overflow-hidden transition-all duration-200',
        isFullScreen
          ? 'fixed inset-0 z-50 rounded-none'
          : isModal
          ? 'fixed inset-y-0 right-0 z-50 w-full sm:w-[650px] lg:w-[720px] rounded-l-2xl border-l'
          : 'w-full rounded-xl'
      )}
    >
      {/* 1. Header with Job Metadata */}
      <div className="p-4 sm:p-5 border-b border-slate-800 bg-slate-900/90 flex flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2.5 truncate">
            <div className="p-2 rounded-lg bg-amber-500/10 text-amber-400 shrink-0">
              <Terminal className="w-5 h-5" />
            </div>
            <div className="truncate">
              <div className="flex items-center gap-2">
                <h3 className="font-bold text-slate-100 text-base truncate">{job.jobType}</h3>
                <StatusBadge status={job.state} size="sm" />
              </div>
              <span className="text-xs font-mono text-slate-400">ID: {job.id}</span>
            </div>
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            <button
              type="button"
              onClick={() => setIsFullScreen((prev) => !prev)}
              className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
              title={isFullScreen ? 'Exit Full Screen' : 'Full Screen'}
            >
              {isFullScreen ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
            </button>
            {onClose && (
              <button
                type="button"
                onClick={onClose}
                className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
                title="Close Viewer"
              >
                <X className="w-4 h-4" />
              </button>
            )}
          </div>
        </div>

        {/* Metadata Badges Bar */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
          <div className="p-2 rounded bg-slate-950/60 border border-slate-800">
            <div className="text-[10px] text-slate-400 flex items-center gap-1">
              <Cpu className="w-3 h-3 text-slate-500" /> Assigned Worker
            </div>
            <div className="font-mono text-slate-200 text-xs font-medium truncate mt-0.5">
              {job.workerId || 'Unassigned'}
            </div>
          </div>

          <div className="p-2 rounded bg-slate-950/60 border border-slate-800">
            <div className="text-[10px] text-slate-400 flex items-center gap-1">
              <Hash className="w-3 h-3 text-slate-500" /> Attempt
            </div>
            <div className="font-mono text-slate-200 text-xs font-medium mt-0.5">
              <select aria-label="Log attempt" className="bg-slate-950" value={selectedAttempt}
                onChange={event => setSelection({ jobId: job.id, attempt: Number(event.target.value) })}>
                <option value={0}>{job.retryCount ? `Latest attempt (${job.retryCount})` : 'No attempts yet'}</option>
                {Array.from({ length: job.retryCount }, (_, i) => <option key={i + 1} value={i + 1}>Attempt {i + 1}</option>)}
              </select>
            </div>
          </div>

          <div className="p-2 rounded bg-slate-950/60 border border-slate-800">
            <div className="text-[10px] text-slate-400 flex items-center gap-1">
              <Clock className="w-3 h-3 text-slate-500" /> Duration
            </div>
            <div className="font-mono text-slate-200 text-xs font-medium mt-0.5">
              {formatDuration(job.claimTime, job.completedAt)}
            </div>
          </div>

          <div className="p-2 rounded bg-slate-950/60 border border-slate-800">
            <div className="text-[10px] text-slate-400 flex items-center gap-1">
              <Clock className="w-3 h-3 text-slate-500" /> Finished At
            </div>
            <div className="font-mono text-slate-200 text-xs font-medium truncate mt-0.5">
              {job.completedAt ? formatDateTime(job.completedAt) : 'Executing'}
            </div>
          </div>
        </div>

        {/* Failure banner if FAILED */}
        {job.state === 'FAILED' && (
          <div className="rounded-lg border border-rose-800/60 bg-rose-950/30 px-3 py-2 text-xs flex items-center gap-2 text-rose-300">
            <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
            <span>
              <strong className="font-semibold text-rose-200">Execution Error: </strong>
              Classification: {job.failureClassification || 'USER_CODE_ERROR'}
            </span>
          </div>
        )}
      </div>

      {/* 2. Terminal Toolbar */}
      <div className="px-4 py-2.5 border-b border-slate-800 bg-slate-900/60 flex flex-wrap items-center justify-between gap-3 text-xs">
        {/* Stream Status Indicator */}
        <div className="flex items-center gap-2">
          <span className="relative flex h-2.5 w-2.5">
            {connectionStatus === 'connected' && (
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
            )}
            <span
              className={cn(
                'relative inline-flex rounded-full h-2.5 w-2.5',
                connectionStatus === 'connected'
                  ? 'bg-emerald-500'
                  : connectionStatus === 'completed'
                  ? 'bg-blue-500'
                  : connectionStatus === 'connecting'
                  ? 'bg-amber-500'
                  : 'bg-rose-500'
              )}
            />
          </span>
          <span className="font-medium text-slate-300 capitalize">
            {connectionStatus === 'connected'
              ? 'Streaming Live Logs'
              : connectionStatus === 'completed'
              ? 'Stream Completed'
              : connectionStatus === 'connecting'
              ? 'Connecting to Stream...'
              : connectionStatus === 'error'
              ? 'Stream Disconnected'
              : 'Waiting for an attempt'}
          </span>
          <span className="text-slate-500 font-mono text-[11px]">
            ({filteredLogs.length} {filteredLogs.length === 1 ? 'line' : 'lines'})
          </span>
        </div>

        {/* Action Controls */}
        <div className="flex items-center gap-2 flex-wrap">
          {/* Search Input */}
          <div className="relative">
            <Search className="w-3.5 h-3.5 text-slate-500 absolute left-2.5 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Filter logs..."
              className="pl-8 pr-3 py-1 text-xs rounded-lg border border-slate-800 bg-slate-950 text-slate-200 placeholder:text-slate-500 focus:outline-none focus:border-amber-500 w-32 sm:w-44"
            />
          </div>

          {/* Auto-Scroll Toggle */}
          <button
            type="button"
            onClick={() => setAutoScroll((prev) => !prev)}
            className={cn(
              'inline-flex items-center gap-1 px-2.5 py-1 rounded-lg border text-xs font-medium transition-colors',
              autoScroll
                ? 'bg-amber-500/10 border-amber-500/40 text-amber-300'
                : 'bg-slate-900 border-slate-800 text-slate-400 hover:text-slate-200'
            )}
            title="Auto-scroll to bottom as new log chunks arrive"
          >
            <ArrowDown className="w-3 h-3" />
            <span>Auto-Scroll</span>
          </button>

          {/* Copy Button */}
          <button
            type="button"
            onClick={handleCopyLogs}
            disabled={logs.length === 0}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg border border-slate-800 bg-slate-900 hover:bg-slate-800 text-slate-300 text-xs transition-colors disabled:opacity-40"
            title="Copy visible log tail"
          >
            <Copy className="w-3 h-3" />
            <span>{copied ? 'Copied!' : 'Copy'}</span>
          </button>

          {/* Download Full Log Button */}
          <button
            type="button"
            onClick={handleDownloadLogs}
            disabled={!downloadUrl || currentDownload?.pending}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-amber-500 hover:bg-amber-400 text-slate-950 font-semibold text-xs transition-colors disabled:opacity-40 shadow-sm"
            title="Download full log file"
          >
            <Download className="w-3 h-3" />
            <span>{currentDownload?.pending ? 'Checking log…' : 'Download Log'}</span>
          </button>
        </div>
      </div>
      {currentDownload?.error && <p className="px-4 py-2 text-xs text-amber-300" role="status">{currentDownload.error}</p>}

      {/* 3. Terminal Console Emulator Box */}
      <div className="flex-1 overflow-y-auto p-4 font-mono text-xs text-slate-200 bg-black/95 select-text">
        {filteredLogs.length === 0 ? (
          <div className="h-64 flex flex-col items-center justify-center text-slate-500 space-y-2">
            {job.state === 'BLOCKED' ? (
              <span>Prerequisites not yet met. Job is waiting in BLOCKED state.</span>
            ) : job.state === 'READY' ? (
              <span>Job is queued in READY state. Waiting for an available worker to claim.</span>
            ) : connectionStatus === 'connecting' ? (
              <div className="flex items-center gap-2 text-amber-400">
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>Opening live Server-Sent Events stream...</span>
              </div>
            ) : searchQuery ? (
              <span>No lines matched filter "{searchQuery}"</span>
            ) : (
              <span>No console log lines emitted for this stage yet.</span>
            )}
          </div>
        ) : (
          <div className="space-y-0.5">
            {filteredLogs.map((line, idx) => {
              const isError = line.includes('[ERROR]') || line.includes('FAILURE') || line.includes('Build Failure');
              const isWarn = line.includes('[WARN]') || line.includes('WARNING');
              const isInfo = line.includes('[INFO]');
              const isSuccess = line.includes('SUCCESS') || line.includes('BUILD SUCCESS');

              return (
                <div
                  key={idx}
                  className="flex items-start gap-3 hover:bg-slate-900/50 px-1 py-0.5 rounded transition-colors group"
                >
                  <span className="text-slate-600 select-none text-[10px] w-8 text-right shrink-0 pt-0.5 font-mono">
                    {idx + 1}
                  </span>
                  <span
                    className={cn(
                      'whitespace-pre-wrap break-all leading-relaxed',
                      isError
                        ? 'text-rose-400 font-semibold'
                        : isWarn
                        ? 'text-amber-400'
                        : isSuccess
                        ? 'text-emerald-400 font-semibold'
                        : isInfo
                        ? 'text-sky-300'
                        : 'text-slate-300'
                    )}
                  >
                    {line}
                  </span>
                </div>
              );
            })}
            <div ref={terminalEndRef} />
          </div>
        )}
      </div>

      {/* Terminal Footer Status Bar */}
      <div className="px-4 py-2 border-t border-slate-800 bg-slate-950 text-[11px] font-mono text-slate-500 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Radio className={cn('w-3 h-3', connectionStatus === 'connected' ? 'text-emerald-400 animate-pulse' : 'text-slate-600')} />
          <span>Latest 2,000 lines · Download for the full attempt log</span>
        </div>
        <div>
          {job.state === 'RUNNING' ? (
            <span className="text-amber-400">Worker executing command...</span>
          ) : (
            <span>State: {job.state}</span>
          )}
        </div>
      </div>
    </div>
  );

  return content;
};

