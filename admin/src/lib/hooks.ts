/**
 * Data-fetching hooks.
 * - useApiData: single request with reload()
 * - usePaginatedQuery: cursor pagination with prev/next, refetch on key change
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, toApiError } from '../api/error';
import type { PaginatedResponse } from '../api/types';

export interface ApiDataResult<T> {
  data: T | null;
  loading: boolean;
  error: ApiError | null;
  reload: () => void;
}

/** Fetch a single resource. `deps` follow useEffect semantics (stable-length arrays). */
export function useApiData<T>(fetcher: () => Promise<T>, deps: readonly unknown[]): ApiDataResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetcherRef
      .current()
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(toApiError(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, loading, error, reload };
}

/** Debounced mirror of a value — delays search-driven refetches so typing
 *  does not fire one request per keystroke. */
export function useDebounced<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

export interface PaginatedQueryResult<T> {
  rows: T[];
  loading: boolean;
  error: ApiError | null;
  reload: () => void;
  page: number; // zero-based
  hasPrev: boolean;
  hasNext: boolean;
  prev: () => void;
  next: () => void;
}

/**
 * Cursor-paginated query. `queryKey` should serialize all filter params —
 * changing it resets to page 0. Prev/next navigate an in-memory cursor stack,
 * so the table never renders more than one page of rows.
 */
export function usePaginatedQuery<T>(
  queryKey: string,
  fetcher: (cursor: string | undefined) => Promise<PaginatedResponse<T>>,
): PaginatedQueryResult<T> {
  const [cursorStack, setCursorStack] = useState<Array<string | undefined>>([undefined]);
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<T[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  // Reset pagination whenever the query (filters) change. Clearing rows and
  // the stale nextCursor up front so a changed filter never briefly renders
  // (or paginates against) the previous query's results.
  useEffect(() => {
    setCursorStack([undefined]);
    setPage(0);
    setRows([]);
    setNextCursor(null);
  }, [queryKey]);

  const cursor = cursorStack[Math.min(page, cursorStack.length - 1)];

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetcherRef
      .current(cursor)
      .then((result) => {
        if (!cancelled) {
          setRows(result.items);
          setNextCursor(result.nextCursor);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(toApiError(err));
          setRows([]);
          setNextCursor(null);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [queryKey, cursor, page, tick]);

  const next = useCallback(() => {
    if (nextCursor === null || loading) return;
    setCursorStack((stack) => [...stack, nextCursor]);
    setPage((p) => p + 1);
  }, [nextCursor, loading]);

  const prev = useCallback(() => {
    setPage((p) => Math.max(0, p - 1));
  }, []);

  const reload = useCallback(() => setTick((t) => t + 1), []);

  return {
    rows,
    loading,
    error,
    reload,
    page,
    hasPrev: page > 0,
    hasNext: nextCursor !== null,
    prev,
    next,
  };
}
