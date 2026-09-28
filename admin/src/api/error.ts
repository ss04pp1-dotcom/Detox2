/**
 * ApiError — every failure surfaced by the client/mock transport.
 * Carries the frozen contract error code plus the requestId so
 * operators can correlate with server logs.
 */
export class ApiError extends Error {
  readonly code: string;
  readonly requestId: string;
  readonly status: number;

  constructor(code: string, message: string, requestId: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.requestId = requestId;
    this.status = status;
  }
}

/** Normalize any thrown value into an ApiError for consistent UI handling. */
export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err;
  if (err instanceof Error) return new ApiError('SERVER_ERROR', err.message, 'n/a', 500);
  return new ApiError('SERVER_ERROR', 'Unexpected error', 'n/a', 500);
}
