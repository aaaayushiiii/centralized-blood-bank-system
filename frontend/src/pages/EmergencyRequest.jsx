import { PREVIEW_ENABLED } from "../preview/config.js";
import { useRef, useState } from "react";
import { api } from "../services/api.js";
import {
  BloodSelect,
  Field,
  HospitalSelect,
  splitBloodGroup,
} from "../components/FormFields.jsx";
import Feedback from "../components/Feedback.jsx";
import PageHeading from "../components/PageHeading.jsx";
import useHospitals from "../components/useHospitals.js";
export default function EmergencyRequest({
  hospitalId,
  setHospitalId,
  assignedHospitalId,
  dataApi = api,
  preview: requestedPreview = false,
}) {
  const preview = PREVIEW_ENABLED && requestedPreview;
  const { hospitals, loading, error, retry } = useHospitals(
    assignedHospitalId,
    dataApi,
  );
  const [blood, setBlood] = useState("O+"),
    [priority, setPriority] = useState("Critical"),
    [result, setResult] = useState(null),
    [state, setState] = useState({ error: "", success: "", loading: false }),
    [allocated, setAllocated] = useState(false);
  const lock = useRef(false);
  async function submit(event) {
    event.preventDefault();
    if (lock.current) return;
    const units = Number(
      new FormData(event.currentTarget).get("units_requested"),
    );
    if (!hospitalId || !Number.isInteger(units) || units < 1) {
      setState({
        error: "Choose a hospital and enter a positive whole number of units.",
        success: "",
        loading: false,
      });
      return;
    }
    lock.current = true;
    setState({ error: "", success: "", loading: true });
    try {
      const data = await dataApi.createRequest({
        hospital_id: hospitalId,
        ...splitBloodGroup(blood),
        units_requested: units,
        priority,
      });
      setResult(data.request);
      setAllocated(false);
      setState({
        error: "",
        loading: false,
        success: preview
          ? "Preview request created. No real blood request was submitted."
          : "Request submitted. Blood has not been allocated yet.",
      });
    } catch (err) {
      setState({ error: err.message, success: "", loading: false });
    } finally {
      lock.current = false;
    }
  }
  async function allocate() {
    if (lock.current || !result) return;
    lock.current = true;
    setState({ error: "", success: "", loading: true });
    try {
      const data = await dataApi.allocateRequest(result.request_id);
      setResult(data.request);
      setAllocated(true);
      setState({
        error: "",
        loading: false,
        success: `${preview ? "Preview allocation only" : "Allocation response received"}: ${data.allocations.length} units. Request status: ${data.request.request_status}.`,
      });
    } catch (err) {
      setState({ error: err.message, success: "", loading: false });
    } finally {
      lock.current = false;
    }
  }
  return (
    <>
      <PageHeading
        eyebrow="Emergency request"
        title="Coordinate the next critical step."
        description="Submit a hospital blood requirement, then request allocation against its reference."
      />
      <div className="request-layout">
        <section className="surface request-form">
          <h2>New blood request</h2>
          <p className="section-description">All fields are required.</p>
          <Feedback error={error} loading={loading} />
          {error && (
            <button className="secondary" onClick={retry}>
              Retry hospital list
            </button>
          )}
          {!result && (
            <form onSubmit={submit}>
              <fieldset disabled={state.loading || loading || !!error}>
                <HospitalSelect
                  hospitals={hospitals}
                  value={hospitalId}
                  onChange={(e) => setHospitalId(e.target.value)}
                  name="request-hospital"
                />
                <div className="two-cols">
                  <BloodSelect
                    name="request-blood"
                    value={blood}
                    onChange={(e) => setBlood(e.target.value)}
                  />
                  <Field
                    name="units_requested"
                    label="Number of units"
                    type="number"
                    min={1}
                    step={1}
                    required
                    defaultValue={1}
                  />
                </div>
                <fieldset className="priority-field">
                  <legend>Priority</legend>
                  <div className="priority-options">
                    {["Critical", "Urgent", "Routine"].map((p) => (
                      <label
                        key={p}
                        className={priority === p ? "selected" : ""}
                      >
                        <input
                          type="radio"
                          name="priority"
                          value={p}
                          checked={priority === p}
                          onChange={() => setPriority(p)}
                        />
                        <span>{p}</span>
                      </label>
                    ))}
                  </div>
                </fieldset>
                <button className="primary wide" disabled={!hospitals.length}>
                  {state.loading ? "Submitting…" : "Submit blood request"}
                  <span aria-hidden="true">↗</span>
                </button>
              </fieldset>
            </form>
          )}
          <Feedback {...state} />
          {result && (
            <div className="request-receipt">
              <p className="eyebrow">Request reference</p>
              <h3>{result.request_id}</h3>
              <p>
                Status: <strong>{result.request_status}</strong>
              </p>
              <p>
                A submitted request does not guarantee inventory or dispatch.
              </p>
              <button
                className="primary wide"
                disabled={state.loading || allocated}
                onClick={allocate}
              >
                {state.loading
                  ? "Processing…"
                  : allocated
                    ? "Allocation processed"
                    : "Allocate available units"}
              </button>
              <button
                className="text-button"
                disabled={state.loading}
                onClick={() => {
                  setResult(null);
                  setState({ error: "", success: "", loading: false });
                }}
              >
                Create another request
              </button>
            </div>
          )}
        </section>
        <aside className="request-notes">
          <p className="eyebrow">Request workflow</p>
          <h2>Clarity at every handoff.</h2>
          <ol>
            <li>
              <strong>Record the requirement</strong>
              <p>
                Select the requesting hospital, blood group, units and priority.
              </p>
            </li>
            <li>
              <strong>Review allocation</strong>
              <p>
                The backend checks compatibility, available inventory and expiry
                before assigning units.
              </p>
            </li>
            <li>
              <strong>Track the reference</strong>
              <p>
                Use the request ID to coordinate with hospital staff. Allocation
                and dispatch are separate steps.
              </p>
            </li>
          </ol>
          <div className="note">
            This platform is not an emergency helpline or a clinical decision
            system.
          </div>
        </aside>
      </div>
    </>
  );
}
