import { createAuthClient } from "./authClient.js";
export const auth = createAuthClient(import.meta.env.VITE_API_BASE_URL || "");
