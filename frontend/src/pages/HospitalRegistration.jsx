import { useEffect, useRef, useState } from "react";
import { Field } from "../components/FormFields.jsx";
import Feedback from "../components/Feedback.jsx";
import PageHeading from "../components/PageHeading.jsx";
import { hospitalRegistration, registrationFailure } from "../services/hospitalRegistration.js";
import { hospitalRegistrationPayload } from "../services/hospitalRegistrationValidation.js";

export default function HospitalRegistration({ registrationApi = hospitalRegistration }) {
  const [state, setState] = useState({ loading: false, error: "", reference: null, uncertain: false });
  const locked = useRef(false);
  const finished = useRef(false);
  const mounted = useRef(true);
  const feedback = useRef(null);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (state.error || state.reference) feedback.current?.focus();
  }, [state.error, state.reference]);

  async function submit(event) {
    event.preventDefault();
    if (locked.current || finished.current) return;
    const form = event.currentTarget;
    let body;
    try { body = hospitalRegistrationPayload(Object.fromEntries(new FormData(form))); }
    catch (error) {
      setState({ loading: false, reference: null, ...registrationFailure(error) });
      form.elements.namedItem(error.field)?.focus();
      return;
    }
    locked.current = true;
    setState({ loading: true, error: "", reference: null, uncertain: false });
    try {
      const result = await registrationApi.submit(body);
      finished.current = true;
      if (mounted.current) {
        form.reset();
        setState({ loading: false, error: "", reference: result.registration_request_id, uncertain: false });
      }
    } catch (error) {
      const failure = registrationFailure(error);
      if (failure.uncertain) finished.current = true;
      if (mounted.current) setState({ loading: false, reference: null, ...failure });
    } finally { locked.current = false; }
  }

  return (
    <>
      <PageHeading eyebrow="Join the hospital network" title="Register your hospital."
        description="Submit your institution for independent verification. Hospital registration and individual staff access are separate steps." />
      <div className="request-layout hospital-registration">
        <section className="surface request-form">
          {state.reference ? (
            <div ref={feedback} tabIndex={-1} className="hospital-registration-result" role="status">
              <p className="eyebrow">Application received</p>
              <h2>Pending administrator approval</h2>
              <p>Registration reference: <strong>{state.reference}</strong></p>
              <p>Keep this reference when contacting the network administrator. Your hospital is not active yet and no staff login credentials have been created.</p>
              <p>Once the hospital is approved, individual staff must request access and receive separate approval before signing in.</p>
            </div>
          ) : (
            <>
              <h2>Hospital application</h2>
              <p className="section-description" id="hospital-application-help">All fields are required except coordinates. Do not enter patient information or passwords.</p>
              <form onSubmit={submit} aria-describedby="hospital-application-help" aria-busy={state.loading}>
                <fieldset disabled={state.loading || state.uncertain}>
                  <fieldset className="hospital-registration-group">
                    <legend>Hospital details</legend>
                    <Field name="hospital_name" label="Hospital name" required maxLength={150} autoComplete="organization" />
                    <Field name="address" label="Address" required maxLength={300} autoComplete="street-address" />
                    <div className="two-cols">
                      <Field name="district" label="District" required maxLength={60} autoComplete="address-level2" />
                      <Field name="pincode" label="Pincode" required maxLength={6} inputMode="numeric" pattern="[1-9][0-9]{5}" autoComplete="postal-code" />
                    </div>
                    <div className="two-cols">
                      <Field name="latitude" label="Latitude (optional)" type="number" min={-90} max={90} step="0.000001" help="Provide both coordinates or leave both blank." />
                      <Field name="longitude" label="Longitude (optional)" type="number" min={-180} max={180} step="0.000001" help="Up to six decimal places." />
                    </div>
                  </fieldset>
                  <fieldset className="hospital-registration-group">
                    <legend>Official hospital contact</legend>
                    <Field name="official_phone" label="Official hospital phone" type="tel" required maxLength={20} autoComplete="section-hospital tel" />
                    <Field name="official_email" label="Official hospital email" type="email" required maxLength={254} autoComplete="section-hospital email" />
                  </fieldset>
                  <fieldset className="hospital-registration-group">
                    <legend>Official registration identity</legend>
                    <Field name="registration_authority" label="Registration authority" required maxLength={80} pattern="[A-Za-z0-9_]+" autoCapitalize="characters" spellCheck={false}
                      help="Use the network's canonical issuing-authority code. Ask the administrator if you do not know it; do not invent a code." />
                    <Field name="jurisdiction_code" label="Jurisdiction" required maxLength={30} pattern="[A-Za-z0-9_]+" autoCapitalize="characters" spellCheck={false}
                      help="Use the canonical jurisdiction code: letters, digits and underscores only." />
                    <Field name="registration_number" label="Registration number" required maxLength={100} spellCheck={false}
                      help="Your official registration number. Letters, digits, spaces, hyphens, slashes and dots are accepted." />
                  </fieldset>
                  <fieldset className="hospital-registration-group">
                    <legend>Hospital representative</legend>
                    <Field name="representative_name" label="Representative name" required maxLength={100} autoComplete="name" />
                    <Field name="representative_title" label="Representative designation" required maxLength={80} autoComplete="organization-title" />
                    <Field name="representative_email" label="Representative email" type="email" required maxLength={254} autoComplete="section-representative email" />
                    <Field name="representative_phone" label="Representative phone" type="tel" required maxLength={20} autoComplete="section-representative tel" />
                  </fieldset>
                  <p className="caption">Submitting requests verification only. It does not activate your hospital or create staff login credentials.</p>
                  <button className="primary wide" type="submit">{state.loading ? "Submitting application…" : "Submit hospital application"}</button>
                </fieldset>
              </form>
              <div ref={feedback} tabIndex={-1} className="hospital-registration-feedback">
                <Feedback error={state.error} loading={state.loading} />
              </div>
            </>
          )}
        </section>
        <aside className="request-notes">
          <p className="eyebrow">Before you apply</p>
          <h2>A verified place in the network.</h2>
          <p className="auth-description">An authorized administrator independently checks the hospital's official registration identity and your authority to represent it.</p>
          <div className="note">Approval is required before the hospital appears in the staff registration list. Staff accounts then require their own approval.</div>
          <p className="caption">Your hospital is already participating?</p>
          <p className="caption"><a href="#/hospital/request-access">Request hospital staff access</a></p>
          <p className="caption"><a href="#/hospital/sign-in">Hospital staff sign in</a></p>
          <a className="text-button auth-link" href="#/roles">Choose a different role</a>
        </aside>
      </div>
    </>
  );
}
