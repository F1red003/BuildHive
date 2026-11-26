import { useEffect, useState } from 'react';

type EventSourceStatus = 'idle' | 'connecting' | 'connected' | 'completed' | 'error';

interface LogStream {
  url: string | null;
  status: EventSourceStatus;
  lines: string[];
}

function emptyStream(url: string | null): LogStream {
  return { url, status: url ? 'connecting' : 'idle', lines: [] };
}

export function useEventSource(url: string | null) {
  const [stream, setStream] = useState(() => emptyStream(url));
  const current = stream.url === url ? stream : emptyStream(url);
  if (current !== stream) {
    setStream(current);
  }

  useEffect(() => {
    if (!url) return;

    const source = new EventSource(url);
    let active = true;
    const setStatus = (status: EventSourceStatus) => {
      if (active) setStream((previous) => previous.url === url ? { ...previous, status } : previous);
    };
    source.onopen = () => setStatus('connected');
    source.onmessage = (event) => {
      if (!active || !event.data) return;
      const received = event.data.replace(/\n$/, '').split('\n');
      setStream((previous) => previous.url === url ? ({
        ...previous,
        lines: [...previous.lines, ...received].slice(-2000),
      }) : previous);
    };
    source.addEventListener('complete', () => {
      setStatus('completed');
      source.close();
    });
    // EventSource reconnects with Last-Event-ID using the same stream instance.
    source.onerror = () => setStatus('error');

    return () => {
      active = false;
      source.close();
    };
  }, [url]);

  return current;
}
