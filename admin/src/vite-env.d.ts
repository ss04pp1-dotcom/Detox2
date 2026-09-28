/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * API base override.
   * - unset/empty  -> same origin (`/api/v1` prefix, dev proxy -> wrangler :8787)
   * - full URL     -> e.g. https://api.maxleveldetox.com/api/v1
   * - "mock"       -> client-side demo mode with realistic fake data
   */
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
