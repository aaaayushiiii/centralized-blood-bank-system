import { PREVIEW_ENABLED } from "../preview/config.js";
import { useRef, useState } from "react";
import { api } from "../services/api.js";
import {
  Field,
  BloodSelect,
  splitBloodGroup,
} from "../components/FormFields.jsx";
import Feedback from "../components/Feedback.jsx";
import PageHeading from "../components/PageHeading.jsx";
export default function CitizenPortal({
  dataApi = api,
  preview: requestedPreview = false,
}) {
  const preview = PREVIEW_ENABLED && requestedPreview;
  const [blood, setBlood] = useState("O+");
  const [search, setSearch] = useState({
    rows: null,
    error: "",
    loading: false,
    label: "",
  });
  const [donor, setDonor] = useState({
    error: "",
    success: "",
    loading: false,
  });
  const [donorBlood, setDonorBlood] = useState("O+");
  const searchLock = useRef(false),
    donorLock = useRef(false);
  async function searchNearby(event) {
    event.preventDefault();
    if (searchLock.current) return;
    const pincode = new FormData(event.currentTarget).get("pincode").trim();
    if (!/^\d{6}$/.test(pincode)) {
      setSearch({
        rows: null,
        error: "Enter a six-digit pincode.",
        loading: false,
      });
      return;
    }
    searchLock.current = true;
    setSearch({ rows: null, error: "", loading: true, label: "" });
    try {
      const result = await dataApi.searchHospitals({
        pincode,
        ...splitBloodGroup(blood),
      });
      setSearch({
        rows: result.hospitals,
        loading: false,
        error: "",
        label: `${blood} · ${pincode}`,
      });
    } catch (error) {
      setSearch({ rows: null, loading: false, error: error.message });
    } finally {
      searchLock.current = false;
    }
  }
  async function register(event) {
    event.preventDefault();
    if (donorLock.current) return;
    const form = event.currentTarget,
      data = Object.fromEntries(new FormData(form)),
      age = Number(data.age);
    if (
      !data.full_name.trim() ||
      !data.gender.trim() ||
      !Number.isInteger(age) ||
      age < 1 ||
      age > 120
    ) {
      setDonor({
        error: "Enter a name, gender and valid whole-number age.",
        success: "",
        loading: false,
      });
      return;
    }
    if (!/^\+?[0-9 ()-]{7,20}$/.test(data.contact_number.trim())) {
      setDonor({
        error: "Enter a valid contact number (7-20 characters).",
        success: "",
        loading: false,
      });
      return;
    }
    const today = new Date();
    const date = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
    if (data.last_donation_date && data.last_donation_date > date) {
      setDonor({
        error: "Last donation date cannot be in the future.",
        success: "",
        loading: false,
      });
      return;
    }
    donorLock.current = true;
    setDonor({ error: "", success: "", loading: true });
    try {
      const result = await dataApi.registerDonor({
        full_name: data.full_name.trim(),
        age,
        gender: data.gender.trim(),
        contact_number: data.contact_number.trim(),
        last_donation_date: data.last_donation_date || null,
        ...splitBloodGroup(donorBlood),
      });
      setDonor({
        error: "",
        loading: false,
        success: preview
          ? `Preview registration: ${result.donor.donor_id}. No donor record was saved to a backend.`
          : `Donor registered: ${result.donor.donor_id}. Registration does not confirm donation eligibility.`,
      });
      form.reset();
      setDonorBlood("O+");
    } catch (error) {
      setDonor({ error: error.message, success: "", loading: false });
    } finally {
      donorLock.current = false;
    }
  }
  return (
    <>
      <PageHeading
        eyebrow="Citizen portal"
        title="Find blood. Connect with care."
        description="Look up nearby hospital inventory or register your interest as a donor."
      />
      <div className="citizen-layout">
        <div>
          <section
            className="surface search-section"
            aria-labelledby="find-heading"
          >
            <div className="section-title">
              <span className="step">01</span>
              <div>
                <h2 id="find-heading">Find available blood</h2>
                <p>Search by location and blood group.</p>
              </div>
            </div>
            <form onSubmit={searchNearby}>
              <fieldset disabled={search.loading}>
                <div className="search-fields">
                  <Field
                    name="pincode"
                    label="Pincode"
                    inputMode="numeric"
                    pattern="[0-9]{6}"
                    maxLength={6}
                    required
                    placeholder="e.g. 632001"
                    help={
                      preview
                        ? "Preview pincodes: 632001, 632002 or 632014. Distances are fictional."
                        : "Your pincode is used only to find nearby hospitals."
                    }
                  />
                  <BloodSelect
                    value={blood}
                    onChange={(e) => {
                      setBlood(e.target.value);
                      setSearch({ rows: null, error: "", loading: false });
                    }}
                  />
                </div>
                <button className="primary wide" type="submit">
                  {search.loading ? "Searching…" : "Search nearby hospitals"}
                  <span aria-hidden="true">↗</span>
                </button>
              </fieldset>
            </form>
            <Feedback error={search.error} loading={search.loading} />
          </section>
          <section
            className="results"
            aria-label="Hospital search results"
            aria-live="polite"
          >
            <div className="result-heading">
              <h2>Nearby availability</h2>
              {search.rows && <span>{search.label}</span>}
            </div>
            {search.rows === null && !search.loading && (
              <div className="empty">
                <span className="empty-mark" aria-hidden="true">
                  +
                </span>
                <h3>Your search starts here</h3>
                <p>Enter a pincode to see hospitals and available units.</p>
              </div>
            )}
            {search.rows?.length === 0 && (
              <div className="empty">
                <h3>No hospitals found</h3>
                <p>Try another pincode. No availability has been confirmed.</p>
              </div>
            )}
            {search.rows?.map(({ hospital, distance_km, available_units }) => (
              <article className="hospital-row" key={hospital.hospital_id}>
                <div className="hospital-mark" aria-hidden="true">
                  +
                </div>
                <div className="hospital-info">
                  <h3>{hospital.name}</h3>
                  <p>{hospital.address}</p>
                  <span>
                    {distance_km.toFixed(1)} km away · {hospital.phone}
                  </span>
                </div>
                <div className="units">
                  <strong>{available_units}</strong>
                  <span>
                    {available_units === 1
                      ? "unit available"
                      : "units available"}
                  </span>
                </div>
              </article>
            ))}
            <p className="caption">
              Inventory can change. Confirm availability with the hospital
              before travelling.
            </p>
          </section>
        </div>
        <section
          className="surface donor-section"
          aria-labelledby="donor-heading"
        >
          <div className="section-title">
            <span className="step">02</span>
            <div>
              <h2 id="donor-heading">Become a donor</h2>
              <p>A small step towards someone’s next chapter.</p>
            </div>
          </div>
          <form onSubmit={register}>
            <fieldset disabled={donor.loading}>
              <Field
                name="full_name"
                label="Full name"
                required
                maxLength={100}
                autoComplete="name"
                placeholder="Your full name"
              />
              <div className="two-cols">
                <Field
                  name="age"
                  label="Age"
                  type="number"
                  required
                  min={1}
                  max={120}
                  step={1}
                  placeholder="Age"
                />
                <Field
                  name="gender"
                  label="Gender"
                  required
                  maxLength={30}
                  placeholder="As recorded for donation"
                />
              </div>
              <BloodSelect
                name="donor-blood"
                value={donorBlood}
                onChange={(e) => setDonorBlood(e.target.value)}
              />
              <Field
                name="contact_number"
                label="Contact number"
                type="tel"
                inputMode="tel"
                required
                maxLength={20}
                autoComplete="tel"
                placeholder="Your contact number"
              />
              <Field
                name="last_donation_date"
                label="Last donation date (optional)"
                type="date"
              />
              <p className="caption">
                Registration records donor information. Eligibility and blood
                group must be verified by hospital staff.
              </p>
              <button className="primary wide">
                {donor.loading ? "Registering…" : "Register as a donor"}
                <span aria-hidden="true">↗</span>
              </button>
            </fieldset>
          </form>
          <Feedback {...donor} />
        </section>
      </div>
    </>
  );
}
