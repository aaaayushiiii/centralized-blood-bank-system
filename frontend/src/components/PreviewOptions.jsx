import { PREVIEW_ENABLED } from "../preview/config.js";
export default function PreviewOptions({ staff, both = false }) {
  if (!PREVIEW_ENABLED) return null;
  return (
    <div className="feedback">
      <p className="eyebrow">Local development preview</p>
      <p className="caption">
        No sign-in or authenticated session. Fictional data only.
      </p>
      {(both || staff) && (
        <a className="secondary auth-link" href="#/preview/hospital">
          Preview Hospital Dashboard
        </a>
      )}
      {(both || !staff) && (
        <p className="caption">
          <a className="secondary auth-link" href="#/preview/citizen">
            Preview Citizen Portal
          </a>
        </p>
      )}
    </div>
  );
}
