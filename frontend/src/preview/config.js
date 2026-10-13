// Vite defines this constant from its command. It is always false for builds.
export const PREVIEW_ENABLED = import.meta.env.DEV && __ENABLE_LOCAL_PREVIEW__;
