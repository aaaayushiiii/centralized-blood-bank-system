import { useState } from "react";
import CitizenPortal from "../pages/CitizenPortal.jsx";
import HospitalCommandCenter from "../pages/HospitalCommandCenter.jsx";
import EmergencyRequest from "../pages/EmergencyRequest.jsx";
import { createMockApi } from "../services/mock.js";
import { navigate } from "../auth/routes.js";
import { PREVIEW_ENABLED } from "./config.js";
export default function PreviewWorkspace({ path }) {
  const [dataApi] = useState(createMockApi);
  const [hospitalId, setHospitalId] = useState("H-DEMO-01");
  // Defense in depth: this workspace never updates AuthContext or creates a user.
  if (!PREVIEW_ENABLED) return null;
  if (path === "/preview/citizen")
    return <CitizenPortal dataApi={dataApi} preview />;
  if (path === "/preview/hospital")
    return (
      <HospitalCommandCenter
        hospitalId={hospitalId}
        setHospitalId={setHospitalId}
        dataApi={dataApi}
        preview
        openRequest={(id) => {
          setHospitalId(id);
          navigate("/preview/request");
        }}
      />
    );
  if (path === "/preview/request")
    return (
      <EmergencyRequest
        hospitalId={hospitalId}
        setHospitalId={setHospitalId}
        dataApi={dataApi}
        preview
      />
    );
  return null;
}
