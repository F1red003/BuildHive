import React from 'react';
import {
  AlertCircle,
  Ban,
  CheckCircle2,
  Clock,
  FastForward,
  Loader2,
  Lock,
} from 'lucide-react';
import type { JobState, PipelineStatus } from '../../types';
import { cn } from '../../utils';

interface StatusBadgeProps {
  status: JobState | PipelineStatus | string;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
  showIcon?: boolean;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({
  status,
  size = 'md',
  className,
  showIcon = true,
}) => {
  const normalized = status?.toUpperCase() || 'UNKNOWN';

  let colorClasses = 'bg-slate-100 text-slate-600 border-slate-200';
  let Icon = Clock;
  let animate = false;

  switch (normalized) {
    case 'READY':
      // Blue pill for READY
      colorClasses = 'bg-blue-50 text-blue-700 border-blue-200';
      Icon = Clock;
      break;

    case 'RUNNING':
      // Amber spinner for RUNNING
      colorClasses = 'bg-amber-50 text-amber-800 border-amber-200';
      Icon = Loader2;
      animate = true;
      break;

    case 'SUCCEEDED':
      // Green pill for SUCCEEDED
      colorClasses = 'bg-emerald-50 text-emerald-700 border-emerald-200';
      Icon = CheckCircle2;
      break;

    case 'FAILED':
      // Red pill for FAILED
      colorClasses = 'bg-rose-50 text-rose-700 border-rose-200';
      Icon = AlertCircle;
      break;

    case 'SKIPPED':
      // Gray pill for SKIPPED
      colorClasses = 'bg-slate-100 text-slate-600 border-slate-200';
      Icon = FastForward;
      break;

    case 'BLOCKED':
      colorClasses = 'bg-zinc-100 text-zinc-600 border-zinc-200';
      Icon = Lock;
      break;

    case 'CANCELLED':
      colorClasses = 'bg-rose-50 text-rose-700 border-rose-200';
      Icon = Ban;
      break;

    case 'CLAIMED':
      colorClasses = 'bg-sky-50 text-sky-700 border-sky-200';
      Icon = Loader2;
      animate = true;
      break;

    case 'PENDING':
    default:
      colorClasses = 'bg-indigo-50 text-indigo-700 border-indigo-200';
      Icon = Clock;
      break;
  }

  const sizeClasses = {
    sm: 'px-2 py-0.5 text-xs gap-1',
    md: 'px-2.5 py-1 text-xs gap-1.5',
    lg: 'px-3 py-1.5 text-sm gap-2',
  }[size];

  const iconSizes = {
    sm: 'w-3 h-3',
    md: 'w-3.5 h-3.5',
    lg: 'w-4 h-4',
  }[size];

  return (
    <span
      className={cn(
        'inline-flex items-center font-medium rounded-full border shadow-sm transition-all duration-150',
        colorClasses,
        sizeClasses,
        className
      )}
    >
      {showIcon && (
        <Icon
          className={cn(
            iconSizes,
            'shrink-0',
            animate && 'animate-spin'
          )}
        />
      )}
      <span>{normalized}</span>
    </span>
  );
};
