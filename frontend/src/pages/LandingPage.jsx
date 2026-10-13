import PageHeading from "../components/PageHeading.jsx";
export default function LandingPage() {
  return (
    <>
      <PageHeading
        eyebrow="Healthcare coordination"
        title="Find blood. Connect with care."
        description="A shared workspace for blood availability enquiries, donor registration and hospital emergency requests."
      />
      <div className="citizen-layout">
        <section className="surface search-section">
          <div className="section-title">
            <span className="step">01</span>
            <div>
              <h2>For citizens</h2>
              <p>Make your next step clearer.</p>
            </div>
          </div>
          <p className="auth-description">
            Search hospital blood inventory by location and blood group, and
            register your interest in donating. Availability is confirmed only
            when hospital information is returned.
          </p>
          <a className="primary wide auth-link" href="#/citizen/sign-in">
            Citizen sign in <span aria-hidden="true">↗</span>
          </a>
          <p className="caption">
            New here? <a href="#/citizen/sign-up">Create a citizen account</a>
          </p>
        </section>
        <section className="surface donor-section">
          <div className="section-title">
            <span className="step">02</span>
            <div>
              <h2>For hospital staff</h2>
              <p>Coordinate inventory and requests.</p>
            </div>
          </div>
          <p className="auth-description">
            Review your hospital inventory, keep near-expiry units in view and
            submit blood requirements with a clear priority and request
            reference.
          </p>
          <a className="primary wide auth-link" href="#/hospital/sign-in">
            Hospital staff sign in <span aria-hidden="true">↗</span>
          </a>
          <p className="caption">
            Need staff access?{" "}
            <a href="#/hospital/request-access">
              Request access for your hospital
            </a>
          </p>
        </section>
      </div>
      <p className="caption">
        Inventory can change. Confirm availability and arrangements with the
        hospital before travelling.
      </p>
      <a className="text-button auth-link" href="#/roles">
        Choose your role
      </a>
    </>
  );
}
