// The Vite command gate is stronger than DEV alone: NODE_ENV can be overridden.
export function allowLocalPreview({ command, isPreview = false, enabled }) {
  return command === "serve" && !isPreview && enabled === "true";
}
export const previewPaths = [
  "/preview/citizen",
  "/preview/hospital",
  "/preview/request",
];
