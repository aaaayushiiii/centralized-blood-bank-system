import PreviewOptions from "../components/PreviewOptions.jsx";
import PageHeading from "../components/PageHeading.jsx";
export default function RoleSelection() {
  return (
    <>
      <PageHeading
        eyebrow="Welcome"
        title="Choose your workspace."
        description="Select how you will use the platform. Your account permissions are verified when you sign in."
      />
      <div className="citizen-layout">
        <section className="surface search-section">
          <div className="section-title">
            <span className="step">01</span>
            <div>
              <h2>Citizen</h2>
              <p>Search availability and register as a donor.</p>
            </div>
          </div>
          <a className="primary wide auth-link" href="#/citizen/sign-in">
            Sign in as a citizen <span aria-hidden="true">↗</span>
          </a>
          <p className="caption">
            <a href="#/citizen/sign-up">Create a citizen account</a>
          </p>
        </section>
        <section className="surface donor-section">
          <div className="section-title">
            <span className="step">02</span>
            <div>
              <h2>Hospital Staff</h2>
              <p>Manage inventory and emergency requirements.</p>
            </div>
          </div>
          <a className="primary wide auth-link" href="#/hospital/sign-in">
            Sign in as hospital staff <span aria-hidden="true">↗</span>
          </a>
          <p className="caption">
            <a href="#/hospital/request-access">
              Request hospital staff access
            </a>
          </p>
        </section>
      </div>
      <PreviewOptions both />
    </>
  );
}
