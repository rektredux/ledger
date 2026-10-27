import { useState, useEffect, useCallback, useRef } from 'react';

interface ApiState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

/**
 * Simple data-fetch hook.
 *
 * Re-fetches when the dependency string changes. Cancels in-flight requests
 * when the component unmounts or the dep changes, so stale responses can't
 * overwrite newer data.
 */
export function useApi<T>(
  fetcher: () => Promise<T>,
  deps: string | number | boolean | null = null
): ApiState<T> & { refetch: () => void } {
  const [state, setState] = useState<ApiState<T>>({
    data: null,
    loading: true,
    error: null,
  });

  const cancelRef = useRef(false);

  const fetch = useCallback(async () => {
    cancelRef.current = false;
    setState(s => ({ ...s, loading: true, error: null }));
    try {
      const data = await fetcher();
      if (!cancelRef.current) {
        setState({ data, loading: false, error: null });
      }
    } catch (e) {
      if (!cancelRef.current) {
        setState({ data: null, loading: false, error: String(e) });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deps]);

  useEffect(() => {
    fetch();
    return () => { cancelRef.current = true; };
  }, [fetch]);

  return { ...state, refetch: fetch };
}
