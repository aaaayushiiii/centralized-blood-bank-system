// FICTIONAL DEVELOPMENT FIXTURES ONLY. No real hospitals, donors or inventory.
// Memory-only mutations disappear on reload. Never used after a live failure.
export const DEMO_DATE = "2026-10-13";
export const hospitals = [
  {
    hospital_id: "H-DEMO-01",
    name: "Sample North Hospital",
    address: "Fictional North District, Vellore",
    latitude: 12.94,
    longitude: 79.14,
    phone: "0000000000",
  },
  {
    hospital_id: "H-DEMO-02",
    name: "Sample Riverside Hospital",
    address: "Fictional Riverside District, Vellore",
    latitude: 12.92,
    longitude: 79.13,
    phone: "0000000000",
  },
  {
    hospital_id: "H-DEMO-03",
    name: "Sample South Hospital",
    address: "Fictional South District, Vellore",
    latitude: 12.9,
    longitude: 79.12,
    phone: "0000000000",
  },
];
const groups = ["O", "A", "B", "AB"];
const initialBags = Array.from({ length: 32 }, (_, i) => ({
  bag_id: `B-DEMO-${String(i + 1).padStart(3, "0")}`,
  donor_id: `D-DEMO-${i + 1}`,
  hospital_id: hospitals[i % 3].hospital_id,
  blood_group: groups[i % 4],
  rh_factor: i % 5 === 0 ? "-" : "+",
  collection_date: "2026-09-20",
  expiry_date:
    i % 7 === 0 ? "2026-10-12" : i % 3 === 0 ? "2026-10-15" : "2026-11-01",
  bag_status: i % 7 === 0 ? "Expired" : i % 6 === 0 ? "Reserved" : "Available",
}));
export function createMockApi() {
  const bags = structuredClone(initialBags);
  const requests = [];
  let donorCount = 0,
    allocationCount = 0;
  const available = (b) =>
    b.bag_status === "Available" && b.expiry_date >= DEMO_DATE;
  return {
    async getHospitals() {
      return structuredClone(hospitals);
    },
    async searchHospitals({ pincode, blood_group, rh_factor }) {
      // Fixed fixtures simulate geocoding. No browser distance calculation.
      if (!["632001", "632002", "632014"].includes(pincode))
        throw new Error(
          "Demo search supports 632001, 632002 or 632014 only. No location was found for this demo pincode.",
        );
      return {
        hospitals: hospitals.map((hospital, i) => ({
          hospital: structuredClone(hospital),
          distance_km: [2.4, 5.1, 8.6][i],
          available_units: bags.filter(
            (b) =>
              b.hospital_id === hospital.hospital_id &&
              b.blood_group === blood_group &&
              b.rh_factor === rh_factor &&
              available(b),
          ).length,
        })),
      };
    },
    async getInventory(id) {
      return {
        as_of_date: DEMO_DATE,
        near_expiry_days: 7,
        bags: structuredClone(bags.filter((b) => b.hospital_id === id)),
      };
    },
    async registerDonor(body) {
      return { donor: { ...body, donor_id: `D-DEMO-NEW-${++donorCount}` } };
    },
    async createRequest(body) {
      if (!hospitals.some((h) => h.hospital_id === body.hospital_id))
        throw new Error("Select a demo hospital.");
      if (!Number.isInteger(body.units_requested) || body.units_requested < 1)
        throw new Error("Units must be a positive whole number.");
      const request = {
        ...body,
        request_id: `R-DEMO-${requests.length + 1}`,
        request_status: "Pending",
        request_time: `${DEMO_DATE}T10:00:00+05:30`,
      };
      requests.push(request);
      return { request: structuredClone(request) };
    },
    async allocateRequest(id) {
      const request = requests.find((r) => r.request_id === id);
      if (!request)
        throw new Error("Demo request not found. Submit a new request.");
      if (request.request_status !== "Pending")
        throw new Error("This demo request has already been allocated.");
      // Exact groups only in demo; clinical compatibility belongs to backend.
      const candidates = bags
        .filter(
          (b) =>
            available(b) &&
            b.blood_group === request.blood_group &&
            b.rh_factor === request.rh_factor,
        )
        .sort((a, b) => a.expiry_date.localeCompare(b.expiry_date));
      if (candidates.length < request.units_requested)
        throw new Error(
          "Insufficient exact-match demo inventory. No bags were reserved.",
        );
      const allocations = candidates
        .slice(0, request.units_requested)
        .map((bag) => {
          bag.bag_status = "Reserved";
          return {
            allocation_id: `A-DEMO-${++allocationCount}`,
            request_id: id,
            bag_id: bag.bag_id,
            allocated_at: `${DEMO_DATE}T10:01:00+05:30`,
          };
        });
      request.request_status = "Allocated";
      return { request: structuredClone(request), allocations };
    },
  };
}
