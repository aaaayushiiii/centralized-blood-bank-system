import { requestJson } from "./transport.js";
export const API_MODE = import.meta.env.VITE_API_MODE || "live";
const baseUrl = import.meta.env.VITE_API_BASE_URL || "";

function checkConfig() {
  // Operational screens must never present fictional inventory as live data.
  if (API_MODE !== "live" || !/^https?:\/\//.test(baseUrl)) {
    throw new Error(
      "The hospital service is not configured for connected access. Please contact support.",
    );
  }
}
function requireObject(value, fields, label) {
  if (
    !value ||
    typeof value !== "object" ||
    fields.some((field) => value[field] === undefined)
  )
    throw new Error(
      `Unexpected API response: invalid ${label}. Check the agreed API contract.`,
    );
}
function requireHospital(hospital) {
  requireObject(
    hospital,
    ["hospital_id", "name", "address", "phone"],
    "hospital",
  );
  if (
    typeof hospital.name !== "string" ||
    typeof hospital.address !== "string"
  ) {
    throw new Error("Unexpected API response: invalid hospital text.");
  }
}
function validate(data, type) {
  if (type === "hospitals") {
    if (!Array.isArray(data))
      throw new Error("Unexpected API response: hospitals must be an array.");
    data.forEach(requireHospital);
  }
  if (type === "search") {
    if (!Array.isArray(data?.hospitals))
      throw new Error("Unexpected API response: missing search results.");
    data.hospitals.forEach((row) => {
      requireHospital(row.hospital);
      if (
        !Number.isFinite(row.distance_km) ||
        row.distance_km < 0 ||
        !Number.isInteger(row.available_units) ||
        row.available_units < 0
      )
        throw new Error(
          "Unexpected API response: invalid distance or unit count.",
        );
    });
  }
  if (type === "inventory") {
    requireObject(
      data,
      ["as_of_date", "near_expiry_days", "bags"],
      "inventory",
    );
    if (
      !/^\d{4}-\d{2}-\d{2}$/.test(data.as_of_date) ||
      !Number.isInteger(data.near_expiry_days) ||
      data.near_expiry_days < 0 ||
      !Array.isArray(data.bags)
    )
      throw new Error("Unexpected API response: invalid inventory metadata.");
    data.bags.forEach((b) =>
      requireObject(
        b,
        ["bag_id", "blood_group", "rh_factor", "expiry_date", "bag_status"],
        "blood bag",
      ),
    );
  }
  if (type === "donor") requireObject(data?.donor, ["donor_id"], "donor");
  if (type === "request" || type === "allocation")
    requireObject(data?.request, ["request_id", "request_status"], "request");
  if (type === "allocation" && !Array.isArray(data.allocations))
    throw new Error("Unexpected API response: missing allocations.");
  return data;
}
async function call(path, options, type) {
  checkConfig();
  const data = await requestJson(baseUrl, path, options);
  return validate(data, type);
}
export const api = {
  getHospitals: (signal) => call("/hospitals", { signal }, "hospitals"),
  searchHospitals: (query, signal) =>
    call(
      `/hospitals/nearby?${new URLSearchParams(query)}`,
      { signal },
      "search",
    ),
  getInventory: (id, signal) =>
    call(
      `/hospitals/${encodeURIComponent(id)}/inventory`,
      { signal },
      "inventory",
    ),
  registerDonor: (body) => call("/donors", { method: "POST", body }, "donor"),
  createRequest: (body) =>
    call("/requests", { method: "POST", body }, "request"),
  allocateRequest: (id) =>
    call(
      `/requests/${encodeURIComponent(id)}/allocations`,
      { method: "POST", body: {} },
      "allocation",
    ),
};
