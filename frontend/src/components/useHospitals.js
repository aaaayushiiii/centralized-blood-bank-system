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
    dataApi
      .getHospitals(controller.signal)
      .then((hospitals) => {
        if (assignedHospitalId) {
          hospitals = hospitals.filter(
            (h) => String(h.hospital_id) === String(assignedHospitalId),
          );
          if (!hospitals.length)
            throw new Error(
              "Your assigned hospital is unavailable. Please contact your administrator.",
            );
        }
        if (!controller.signal.aborted)
          setState({ hospitals, loading: false, error: "" });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState({ hospitals: [], loading: false, error: error.message });
      });
    return () => controller.abort();
  }, [attempt, assignedHospitalId, dataApi]);
  return { ...state, retry: () => setAttempt((n) => n + 1) };
}
