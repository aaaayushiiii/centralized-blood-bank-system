import { useRef, useState } from "react";
import { Field, HospitalSelect } from "../components/FormFields.jsx";
import Feedback from "../components/Feedback.jsx";
import PageHeading from "../components/PageHeading.jsx";
import useHospitals from "../components/useHospitals.js";
import { auth } from "../services/auth.js";
import { validateAuthFields } from "../services/authValidation.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { navigate, roleHome } from "../auth/routes.js";
function StaffHospitalField({ hospitalId, setHospitalId }) {
  const { hospitals, loading, error, retry } = useHospitals();
  // The required empty select prevents submission while loading or unavailable.
  return (
    <>
      <HospitalSelect
        hospitals={hospitals}
        value={hospitalId}
        onChange={(e) => setHospitalId(e.target.value)}
        name="hospital_id"
      />
      <Feedback loading={loading} error={error} />
      {error && (
        <button type="button" className="secondary" onClick={retry}>
          Retry hospital list
        </button>
      )}
      {!loading && !error && !hospitals.length && (
        <p className="caption">
          No hospitals are available for access requests. Please contact your
          hospital administrator.
        </p>
      )}
    </>
  );
}
export default function AuthPage({ staff = false, registering = false, admin = false }) {
  const { login } = useAuth();
  const [hospitalId, setHospitalId] = useState("");
  const [state, setState] = useState({
    error: "",
    success: "",
    loading: false,
  });
  const lock = useRef(false);
  const title = admin ? "Administrator login." : registering
    ? staff
      ? "Request hospital staff access."
      : "Create your citizen account."
    : staff
      ? "Hospital staff sign in."
      : "Welcome back.";
  const action = registering
    ? staff
      ? "Request access"
      : "Create account"
    : "Sign in";
  const prefix = staff ? "/hospital" : "/citizen";
  async function submit(event) {
    event.preventDefault();
    if (lock.current) return;
    const form = event.currentTarget;
    const data = Object.fromEntries(new FormData(form));
    if (staff && registering) data.hospital_id = hospitalId;
    const error = validateAuthFields(data, { registering, staff });
    if (error) {
      setState({ error, success: "", loading: false });
      return;
    }
    lock.current = true;
    setState({ error: "", success: "", loading: true });
    try {
      // Password exists only in this form and request. It is never stored locally.
      const body = { email: data.email.trim(), password: data.password };
      if (registering) {
        body.full_name = data.full_name.trim();
        if (staff) body.hospital_id = hospitalId;
        if (staff) await auth.requestHospitalAccess(body);
        else await auth.registerCitizen(body);
        form.reset();
        setHospitalId("");
        setState({
          error: "",
          success: staff
            ? "Your access request was received. Hospital staff access requires approval; you are not signed in."
            : "Your account was created. Sign in to continue.",
          loading: false,
        });
      } else {
        const user = await login({
          ...body,
          role: admin ? "ADMIN" : staff ? "HOSPITAL_STAFF" : "CITIZEN",
        });
        form.reset();
        navigate(roleHome(user), true);
      }
    } catch (error) {
      setState({ error: admin
        ? error.status === 403 ? "Administrator access is unavailable. Local admin mode must be enabled on the loopback server."
          : error.status === 401 ? "Sign-in failed. Check your administrator credentials and active account."
          : error.status === 429 ? "Too many sign-in attempts. Please wait before trying again."
          : "Administrator sign-in could not be completed. Check the local service and try again."
        : error.message, success: "", loading: false });
    } finally {
      lock.current = false;
      if (form.elements.password) form.elements.password.value = "";
      if (form.elements.confirm_password)
        form.elements.confirm_password.value = "";
    }
  }
  return (
    <>
      <PageHeading
        eyebrow={admin ? "Restricted administrator access" : staff ? "Hospital staff" : "Citizen access"}
        title={title}
        description={
          admin ? "Authorized local administrators only. Sessions expire after 30 minutes. No public administrator signup is available." : registering
            ? staff
              ? "Request access to your hospital workspace. Approval is handled by your hospital administrator."
              : "Create an account to use the citizen portal and register your interest as a donor."
            : "Use your account email and password to continue to your workspace."
        }
      />
      <div className="request-layout">
        <section className="surface request-form">
          <h2>{action}</h2>
          <p className="section-description">All fields are required.</p>
          {!state.success && (
            <form onSubmit={submit}>
              <fieldset disabled={state.loading}>
                {registering && (
                  <Field
                    name="full_name"
                    label="Full name"
                    required
                    maxLength={100}
                    autoComplete="name"
                    placeholder="Your full name"
                  />
                )}
                <Field
                  name="email"
                  label={staff ? "Work email" : "Email address"}
                  type="email"
                  required
                  maxLength={254}
                  autoComplete="username"
                  placeholder={staff ? "you@hospital.org" : "you@example.com"}
                />
                {staff && registering && (
                  <StaffHospitalField
                    hospitalId={hospitalId}
                    setHospitalId={setHospitalId}
                  />
                )}
                <Field
                  name="password"
                  label="Password"
                  type="password"
                  required
                  maxLength={128}
                  minLength={registering ? 12 : undefined}
                  autoComplete={
                    registering ? "new-password" : "current-password"
                  }
                  help={
                    registering
                      ? "Use at least 12 characters. The service enforces its password policy."
                      : undefined
                  }
                />
                {registering && (
                  <Field
                    name="confirm_password"
                    label="Confirm password"
                    type="password"
                    required
                    maxLength={128}
                    autoComplete="new-password"
                  />
                )}
                <button className="primary wide" type="submit">
                  {state.loading ? "Please wait…" : action}
                  <span aria-hidden="true">↗</span>
                </button>
              </fieldset>
            </form>
          )}
          <Feedback {...state} />
          {!admin && <p className="caption">
            {registering ? (
              <a href={`#${prefix}/sign-in`}>Continue to sign in</a>
            ) : (
              <>
                Need an account?{" "}
                <a href={`#${prefix}/${staff ? "request-access" : "sign-up"}`}>
                  {staff ? "Request access" : "Sign up"}
                </a>
              </>
            )}
          </p>}
          <a className="text-button auth-link" href="#/roles">
            Choose a different role
          </a>
        </section>
        <aside className="request-notes">
          <p className="eyebrow">Your workspace</p>
          <h2>
            {admin ? "Hospital network review." : staff
              ? "Clear coordination at every handoff."
              : "A clearer path to care."}
          </h2>
          <p className="auth-description">
            {admin ? "Review official hospital identities and representative authority before making a decision. Government registry checks are manual, never automatic." : staff
              ? "Approved staff can review hospital blood inventory, monitor expiry and coordinate requests. Requesting access does not grant hospital permissions."
              : "Find hospital information by pincode and blood group, and maintain your donor registration. Account signup and donor registration are separate steps."}
          </p>
          <div className="note">
            {admin ? "Local academic demonstration only. Administrator access requires server-side authorization." : staff
              ? "Only an approved hospital staff session can open the command center."
              : "Availability is shown only after information is returned by the hospital service."}
          </div>
        </aside>
      </div>
    </>
  );
}
