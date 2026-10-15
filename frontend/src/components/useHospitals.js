import { useEffect, useState } from "react";
import { api } from "../services/api.js";
export default function useHospitals(assignedHospitalId, dataApi = api) {
  const [state, setState] = useState({
    hospitals: [],
    loading: true,
    error: "",
  });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setState({ hospitals: [], loading: true, error: "" });
    async function loadHospitals() {
      try {
        let hospitals = await dataApi.getHospitals(controller.signal);
        if (controller.signal.aborted) return;
        if (
          assignedHospitalId !== undefined &&
          assignedHospitalId !== null &&
          assignedHospitalId !== ""
        ) {
          hospitals = hospitals.filter(
            (h) => String(h.hospital_id) === String(assignedHospitalId),
          );
          if (!hospitals.length)
            throw new Error(
              "Your assigned hospital is unavailable. Please contact your administrator.",
            );
        }
        setState({ hospitals, loading: false, error: "" });
      } catch (error) {
        if (!controller.signal.aborted)
          setState({
            hospitals: [],
            loading: false,
            error:
              error?.message ||
              "Hospital records could not be loaded. Please retry.",
          });
      }
    }
    loadHospitals();
    return () => controller.abort();
  }, [attempt, assignedHospitalId, dataApi]);
  return { ...state, retry: () => setAttempt((n) => n + 1) };
}
