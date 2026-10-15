import { useEffect, useState } from "react";
import { api } from "../services/api.js";
import { HospitalSelect } from "../components/FormFields.jsx";
import Feedback from "../components/Feedback.jsx";
import PageHeading from "../components/PageHeading.jsx";
import useHospitals from "../components/useHospitals.js";
export default function HospitalCommandCenter({
  hospitalId,
  setHospitalId,
  openRequest,
  assignedHospitalId,
  dataApi = api,
}) {
  const { hospitals, loading, error, retry } = useHospitals(
    assignedHospitalId,
    dataApi,
  );
  const [inventory, setInventory] = useState(null),
    [inventoryError, setInventoryError] = useState(""),
    [busy, setBusy] = useState(false),
    [attempt, setAttempt] = useState(0),
    [filter, setFilter] = useState("All");
  useEffect(() => {
    const controller = new AbortController();
    setInventory(null);
    setInventoryError("");
    setBusy(false);
    if (!hospitalId) return () => controller.abort();
    setBusy(true);
    dataApi
      .getInventory(hospitalId, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) {
          setInventory(data);
          setBusy(false);
        }
      })
      .catch((err) => {
        if (!controller.signal.aborted) {
          setInventoryError(err.message);
          setBusy(false);
        }
      });
    return () => controller.abort();
  }, [hospitalId, attempt, dataApi]);
  const bags = inventory?.bags || [];
  const isAvailable = (b) =>
    b.bag_status === "Available" && b.expiry_date >= inventory.as_of_date;
  const nearExpiry = inventory
    ? bags.filter(
        (b) =>
          isAvailable(b) &&
          (Date.parse(b.expiry_date) - Date.parse(inventory.as_of_date)) /
            86400000 <=
            inventory.near_expiry_days,
      )
    : [];
  const shown = bags.filter((b) => filter === "All" || b.bag_status === filter);
  const statuses = ["All", ...new Set(bags.map((b) => b.bag_status))];
  return (
    <>
      <PageHeading
        eyebrow="Hospital command center"
        title="A clear view of your inventory."
        description="Monitor blood units, review expiry dates and initiate emergency requests."
      >
        <button
          className="primary"
          disabled={!hospitalId || loading || !!error}
          onClick={() => openRequest(hospitalId)}
        >
          Emergency allocation <span aria-hidden="true">↗</span>
        </button>
      </PageHeading>
      <section className="context-bar">
        <div className="hospital-picker">
          <HospitalSelect
            hospitals={hospitals}
            value={hospitalId}
            onChange={(e) => {
              setHospitalId(e.target.value);
              setFilter("All");
            }}
          />
        </div>
        <p>
          Your hospital workspace
          <br />
          <strong>Approved staff access</strong>
        </p>
        <button
          className="secondary"
          disabled={!hospitalId || busy}
          onClick={() => setAttempt((n) => n + 1)}
        >
          Refresh inventory
        </button>
      </section>
      <Feedback error={error} loading={loading} />
      {error && (
        <button className="secondary" onClick={retry}>
          Retry hospital list
        </button>
      )}
      <Feedback error={inventoryError} loading={busy} />
      {inventoryError && (
        <button className="secondary" onClick={() => setAttempt((n) => n + 1)}>
          Retry inventory
        </button>
      )}
      {!hospitalId && !loading && !error && (
        <div className="empty">
          <h2>Select your hospital</h2>
          <p>Choose a hospital to view inventory and expiry warnings.</p>
        </div>
      )}
      {inventory && (
        <>
          <div className="stats">
            <div>
              <span>Total units</span>
              <strong>{bags.length}</strong>
              <small>Recorded in inventory</small>
            </div>
            <div>
              <span>Available</span>
              <strong>{bags.filter(isAvailable).length}</strong>
              <small>Available and unexpired</small>
            </div>
            <div>
              <span>Reserved</span>
              <strong>
                {bags.filter((b) => b.bag_status === "Reserved").length}
              </strong>
              <small>Assigned for requests</small>
            </div>
            <div>
              <span>Near expiry</span>
              <strong className="accent">{nearExpiry.length}</strong>
              <small>Within {inventory.near_expiry_days} days</small>
            </div>
          </div>
          <section className="expiry-section" aria-labelledby="expiry-heading">
            <div>
              <p className="eyebrow">Inventory attention</p>
              <h2 id="expiry-heading">Near-expiry units</h2>
              <p>
                Available units expiring within {inventory.near_expiry_days}{" "}
                days of {inventory.as_of_date}.
              </p>
            </div>
            <div>
              {nearExpiry.length ? (
                nearExpiry.map((b) => (
                  <div className="expiry-row" key={b.bag_id}>
                    <strong>
                      {b.blood_group}
                      {b.rh_factor}
                    </strong>
                    <span>{b.bag_id}</span>
                    <span>Expires {b.expiry_date}</span>
                  </div>
                ))
              ) : (
                <p>No available units nearing expiry.</p>
              )}
            </div>
          </section>
          <section className="surface inventory-section">
            <div className="table-heading">
              <div>
                <h2>Blood inventory</h2>
                <p>
                  {bags.length} units · As of {inventory.as_of_date}
                </p>
              </div>
              <label className="table-filter">
                Status
                <select
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                >
                  {statuses.map((s) => (
                    <option key={s}>{s}</option>
                  ))}
                </select>
              </label>
            </div>
            <div
              className="table-scroll"
              tabIndex={0}
              role="region"
              aria-label="Blood inventory, scroll horizontally on small screens"
            >
              <table>
                <thead>
                  <tr>
                    <th scope="col">Bag ID</th>
                    <th scope="col">Blood group</th>
                    <th scope="col">Collected</th>
                    <th scope="col">Expires</th>
                    <th scope="col">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((b) => (
                    <tr key={b.bag_id}>
                      <td className="mono">{b.bag_id}</td>
                      <td>
                        <strong>
                          {b.blood_group}
                          {b.rh_factor}
                        </strong>
                      </td>
                      <td>{b.collection_date}</td>
                      <td>{b.expiry_date}</td>
                      <td>
                        <span
                          className={`status ${b.bag_status === "Available" ? "available" : b.bag_status === "Expired" ? "expired" : ""}`}
                        >
                          {b.bag_status}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {shown.length === 0 && (
              <div className="empty">No units match this status.</div>
            )}
          </section>
        </>
      )}
    </>
  );
}
