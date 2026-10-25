import { useEffect, useRef, useState } from "react";
import PageHeading from "../components/PageHeading.jsx";
import Feedback from "../components/Feedback.jsx";
import { Field } from "../components/FormFields.jsx";
import { adminReview, REVIEW_STATUSES, approvalPayload, rejectionPayload, reviewError } from "../services/adminReview.js";

function Status({ value }) { return <span className={`review-status review-status-${value.toLowerCase()}`}>{value}</span>; }
function Details({ application: a }) {
  const groups = [
    ["Hospital & location", [["Hospital name", a.hospital_name], ["Address", a.address], ["District", a.district], ["Pincode", a.pincode], ["Latitude", a.latitude], ["Longitude", a.longitude], ["Official phone", a.official_phone], ["Official email", a.official_email]]],
    ["Official registration identity", [["Authority", a.registration_authority], ["Jurisdiction", a.jurisdiction_code], ["Registration number", a.registration_number]]],
    ["Representative", [["Name", a.representative_name], ["Designation", a.representative_title], ["Email", a.representative_email], ["Phone", a.representative_phone]]],
    ["Review record", [["Submitted (database time)", a.submitted_at], ["Reviewed (database time)", a.reviewed_at], ["Reviewer", a.reviewed_by], ["Review notes", a.verification_notes], ["Rejection reason", a.rejection_reason], ["Approved hospital ID", a.approved_hospital_id]]],
  ];
  return <div className="review-detail-groups">{groups.map(([title, fields]) => <section key={title}><h3>{title}</h3><dl className="review-facts">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? "Not provided"}</dd></div>)}</dl></section>)}</div>;
}

export function ReviewForm({ detail, api, onSuccess, onBusy }) {
  const { application: a, review_token: token } = detail;
  const [action, setAction] = useState("approve");
  const [preview, setPreview] = useState(null);
  const [state, setState] = useState({ busy: false, error: "", reload: false });
  const lock = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  function prepare(event) {
    event.preventDefault();
    if (lock.current || state.reload) return;
    const values = Object.fromEntries(new FormData(event.currentTarget));
    try {
      const body = action === "approve" ? approvalPayload(a, token) : rejectionPayload(a, token, values);
      setPreview({ action, values: body });
      setState({ busy: false, error: "", reload: false });
    } catch (error) { setState({ busy: false, error: error.message, reload: false }); }
  }
  async function confirm() {
    if (!preview || lock.current || state.reload) return;
    lock.current = true; onBusy(true);
    setState({ busy: true, error: "", reload: false });
    try {
      const result = await api.decide(a, token, preview.action, preview.values);
      if (mounted.current) { setPreview(null); onSuccess(result); }
    } catch (error) {
      if (mounted.current) { setPreview(null); setState({ busy: false, error: reviewError(error, true), reload: true }); }
    } finally { lock.current = false; if (mounted.current) onBusy(false); }
  }
  if (a.status !== "PENDING") return <p className="note">This application has already been reviewed. No further decision is available.</p>;
  return <section className="review-controls">
    <h3>Review decision</h3>
    <p className="note">Local academic demonstration only. Official government verification is not performed. Review the submitted details before deciding.</p>
    <p className="caption"><a href="https://facility.abdm.gov.in/" target="_blank" rel="noopener noreferrer">Official ABDM Health Facility Registry (HFR) - optional information, opens in a new tab</a>. HFR identity alone does not verify the representative's authorization or establish blood-bank licensing.</p>
    <Feedback error={state.error} />
    {state.reload ? <p role="status">Reload application details above before making another decision.</p> : preview ?
      <section className="review-confirm" role="region" aria-label="Confirm review decision">
        <h3>{preview.action === "approve" ? "Approve this hospital?" : "Reject this application?"}</h3>
        <p>Request #{a.registration_request_id}: {a.hospital_name}</p>
        <p>{preview.action === "approve" ? "This creates one active hospital. It does not create staff login credentials." : "This rejects the application without creating a hospital."} This decision cannot be undone in this dashboard.</p>
        <p>Local academic demonstration; official government verification not performed.</p>
        {preview.action === "reject" && <p>Reason: {preview.values.rejection_reason}</p>}
        <div className="review-actions"><button className="secondary" disabled={state.busy} onClick={() => setPreview(null)}>Cancel</button><button className="primary" disabled={state.busy} onClick={confirm}>{state.busy ? "Saving decision..." : "Confirm final decision"}</button></div>
      </section> :
      <form onSubmit={prepare}>
        <fieldset disabled={state.busy}>
          {action === "reject" && <Field label="Rejection reason (maximum 1000 UTF-8 bytes)" name="rejection_reason"><textarea id="rejection_reason" name="rejection_reason" required maxLength={1000} rows={3} /></Field>}
          <div className="review-actions">
            <button className="primary" type="submit">{action === "approve" ? "Approve" : "Reject"}</button>
            <button className="secondary" type="button" onClick={() => { setAction(action === "approve" ? "reject" : "approve"); setState({ busy: false, error: "", reload: false }); }}>{action === "approve" ? "Reject" : "Cancel rejection"}</button>
          </div>
        </fieldset>
      </form>}
  </section>;
}

export default function AdminDashboard({ reviewApi = adminReview }) {
  const [status, setStatus] = useState("PENDING"), [revision, setRevision] = useState(0);
  const [list, setList] = useState({ rows: [], next: null, loading: true, error: "" });
  const [selected, setSelected] = useState({ detail: null, id: null, loading: false, error: "" });
  const [busy, setBusy] = useState(false), [success, setSuccess] = useState("");
  const listRequest = useRef(null), detailRequest = useRef(null), generation = useRef(0), mounted = useRef(true), pageLock = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; listRequest.current?.abort(); detailRequest.current?.abort(); };
  }, []);
  useEffect(() => {
    const controller = new AbortController(); listRequest.current?.abort(); listRequest.current = controller;
    const current = ++generation.current; pageLock.current = true;
    setList({ rows: [], next: null, loading: true, error: "" });
    detailRequest.current?.abort(); setSelected({ detail: null, id: null, loading: false, error: "" });
    (async () => {
      try {
        await reviewApi.session(controller.signal);
        const data = await reviewApi.list(status, 0, controller.signal);
        if (!controller.signal.aborted && mounted.current && generation.current === current) setList({ rows: data.applications, next: data.next_after_id, loading: false, error: "" });
      } catch (error) {
        if (!controller.signal.aborted && mounted.current && generation.current === current) setList({ rows: [], next: null, loading: false, error: reviewError(error) });
      } finally { if (generation.current === current) pageLock.current = false; }
    })();
    return () => controller.abort();
  }, [status, revision, reviewApi]);
  useEffect(() => {
    const controller = new AbortController();
    const check = async () => {
      try { await reviewApi.session(controller.signal); }
      catch (error) {
        if (controller.signal.aborted || !mounted.current) return;
        // Remove all review data on policy, role or session failure. No POST replay.
        detailRequest.current?.abort(); listRequest.current?.abort(); ++generation.current;
        setSelected({ detail: null, id: null, loading: false, error: "" });
        setBusy(false);
        setList({ rows: [], next: null, loading: false, error: reviewError(error) });
      }
    };
    const interval = setInterval(check, 60000);
    window.addEventListener("focus", check);
    return () => { controller.abort(); clearInterval(interval); window.removeEventListener("focus", check); };
  }, [reviewApi]);
  async function more() {
    if (pageLock.current || list.next === null || busy) return;
    pageLock.current = true; const current = generation.current;
    const controller = new AbortController(); listRequest.current = controller;
    setList((old) => ({ ...old, loading: true, error: "" }));
    try {
      const data = await reviewApi.list(status, list.next, controller.signal);
      if (!controller.signal.aborted && mounted.current && generation.current === current) setList((old) => ({ rows: [...old.rows, ...data.applications.filter((row) => !old.rows.some((v) => v.registration_request_id === row.registration_request_id))], next: data.next_after_id, loading: false, error: "" }));
    } catch (error) { if (!controller.signal.aborted && mounted.current && generation.current === current) setList((old) => ({ ...old, loading: false, error: reviewError(error) })); }
    finally { if (generation.current === current) pageLock.current = false; }
  }
  async function open(reference) {
    if (busy) return;
    detailRequest.current?.abort(); const controller = new AbortController(); detailRequest.current = controller;
    setSelected({ detail: null, id: reference, loading: true, error: "" }); setSuccess("");
    try {
      await reviewApi.session(controller.signal);
      const detail = await reviewApi.detail(reference, controller.signal);
      if (!controller.signal.aborted && mounted.current) setSelected({ detail, id: reference, loading: false, error: "" });
    } catch (error) { if (!controller.signal.aborted && mounted.current) setSelected({ detail: null, id: reference, loading: false, error: reviewError(error) }); }
  }
  function decided(result) {
    setBusy(false); setSelected({ detail: null, id: null, loading: false, error: "" });
    setSuccess(`Request #${result.registration_request_id} ${result.status.toLowerCase()}${result.approved_hospital_id ? `; active hospital #${result.approved_hospital_id} created` : ""}.`);
    setRevision((n) => n + 1);
  }
  return <>
    <PageHeading eyebrow="Administrator workspace" title="Hospital application review." description="Review submitted hospital identities and representative authority. Decisions are recorded against your authenticated administrator account." />
    <p className="note">Local academic demonstration. Sessions expire after 30 minutes. Sign out using the header when finished.</p>
    <Feedback success={success} />
    <div className="admin-review-layout">
      <section className="surface review-queue" aria-label="Hospital application queue">
        <h2>Applications</h2>
        <div className="review-filters" aria-label="Application status filters">{REVIEW_STATUSES.map((value) => <button key={value} className={status === value ? "primary" : "secondary"} aria-pressed={status === value} disabled={busy} onClick={() => { setSuccess(""); setStatus(value); }}>{value[0] + value.slice(1).toLowerCase()}</button>)}</div>
        <button className="text-button" disabled={busy || list.loading} onClick={() => setRevision((n) => n + 1)}>Refresh applications</button>
        <Feedback loading={list.loading} error={list.error} />
        {!list.loading && !list.error && !list.rows.length && <p className="empty">No {status.toLowerCase()} applications.</p>}
        <ul className="review-list">{list.rows.map((row) => <li key={row.registration_request_id}><button disabled={busy} aria-pressed={selected.id === row.registration_request_id} onClick={() => open(row.registration_request_id)}><span className="review-reference">#{row.registration_request_id} · {row.district}</span><strong>{row.hospital_name}</strong><Status value={row.status} /><small>Submitted {row.submitted_at}</small></button></li>)}</ul>
        {list.next !== null && <button className="secondary wide" disabled={busy || list.loading} onClick={more}>Load more applications</button>}
      </section>
      <section className="surface review-detail" aria-label="Selected hospital application">
        <Feedback loading={selected.loading} error={selected.error} />
        {selected.id !== null && <button className="secondary" disabled={busy || selected.loading} onClick={() => open(selected.id)}>Reload application details</button>}
        {selected.detail ? <><div className="review-detail-heading"><div><p className="eyebrow">Request #{selected.id}</p><h2>{selected.detail.application.hospital_name}</h2></div><Status value={selected.detail.application.status} /></div><Details application={selected.detail.application} /><ReviewForm key={`${selected.id}:${selected.detail.review_token}`} detail={selected.detail} api={reviewApi} onSuccess={decided} onBusy={setBusy} /></> : !selected.loading && <div className="empty"><h2>Select an application</h2><p>Open a request from the queue to inspect the submitted details.</p></div>}
      </section>
    </div>
  </>;
}
