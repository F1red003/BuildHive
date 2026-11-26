import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatDuration(startedAt?: string, completedAt?: string): string {
  if (!startedAt) return '-';
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const diffSec = Math.max(0, Math.floor((end - start) / 1000));
  
  if (diffSec < 60) return `${diffSec}s`;
  const mins = Math.floor(diffSec / 60);
  const secs = diffSec % 60;
  return `${mins}m ${secs}s`;
}

export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

export function formatRelativeTime(isoString?: string): string {
  if (!isoString) return 'never';
  const timestamp = new Date(isoString).getTime();
  const diffSec = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));

  if (diffSec < 5) return 'just now';
  if (diffSec < 60) return `${diffSec}s ago`;
  const mins = Math.floor(diffSec / 60);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

export function formatDateTime(isoString?: string): string {
  if (!isoString) return '-';
  const date = new Date(isoString);
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function getHeartbeatHealth(lastHeartbeat?: string): {
  status: 'healthy' | 'warning' | 'critical';
  label: string;
  diffSec: number;
} {
  if (!lastHeartbeat) {
    return { status: 'critical', label: 'No heartbeat', diffSec: Infinity };
  }
  const diffSec = Math.max(0, Math.floor((Date.now() - new Date(lastHeartbeat).getTime()) / 1000));
  if (diffSec < 15) {
    return { status: 'healthy', label: `${diffSec}s ago (Healthy)`, diffSec };
  }
  if (diffSec <= 30) {
    return { status: 'warning', label: `${diffSec}s ago (Warning)`, diffSec };
  }
  return { status: 'critical', label: `${diffSec}s ago (Unresponsive)`, diffSec };
}
