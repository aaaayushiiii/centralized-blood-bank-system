import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { allowLocalPreview } from "./src/preview/policy.js";
export default defineConfig(({ command, mode, isPreview }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  return {
    plugins: [react()],
    define: {
      __ENABLE_LOCAL_PREVIEW__: JSON.stringify(
        allowLocalPreview({
          command,
          isPreview,
          enabled: env.VITE_ENABLE_PREVIEW,
        }),
      ),
    },
  };
});
