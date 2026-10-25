export const hospitalRegistrationFields = Object.freeze([
  ["hospital_name", "Hospital name", 150],
  ["address", "Address", 300],
  ["district", "District", 60],
  ["pincode", "Pincode", 6],
  ["official_phone", "Official hospital phone", 20],
  ["official_email", "Official hospital email", 254],
  ["registration_authority", "Registration authority", 80],
  ["jurisdiction_code", "Jurisdiction", 30],
  ["registration_number", "Registration number", 100],
  ["representative_name", "Representative name", 100],
  ["representative_title", "Representative designation", 80],
  ["representative_email", "Representative email", 254],
  ["representative_phone", "Representative phone", 20],
]);

function invalid(field, message) {
  const error = new Error(message);
  error.field = field;
  throw error;
}

// Construct an allowlisted payload. No status, review metadata, IDs or credentials.
export function hospitalRegistrationPayload(values) {
  const body = {};
  for (const [name, label, maxBytes] of hospitalRegistrationFields) {
    if (typeof values[name] !== "string") invalid(name, `${label} is required.`);
    const value = values[name].trim();
    if (!value || /^[\s\u00a0]*$/u.test(value)) invalid(name, `${label} is required.`);
    if (/[\u0000-\u001f\u007f-\u009f]/u.test(value) || !value.isWellFormed())
      invalid(name, `${label} contains invalid characters.`);
    if (new TextEncoder().encode(value).length > maxBytes)
      invalid(name, `${label} must fit within ${maxBytes} UTF-8 bytes. Shorten the value.`);
    body[name] = value;
  }
  if (!/^[1-9][0-9]{5}$/.test(body.pincode))
    invalid("pincode", "Enter a six-digit pincode starting with 1–9.");
  for (const name of ["official_email", "representative_email"]) {
    body[name] = body[name].toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(body[name]) || new TextEncoder().encode(body[name]).length > 254)
      invalid(name, "Enter a valid email address within 254 UTF-8 bytes.");
  }
  for (const name of ["official_phone", "representative_phone"]) {
    const digits = body[name].replace(/[^0-9]/g, "").length;
    if (!/^\+?[0-9 ()-]{7,20}$/.test(body[name]) || digits < 7 || digits > 15)
      invalid(name, "Enter a phone number with 7–15 digits, using only a leading +, spaces, parentheses or hyphens.");
  }
  for (const name of ["registration_authority", "jurisdiction_code"]) {
    if (!/^[A-Za-z0-9_]+$/.test(body[name]))
      invalid(name, "Use the canonical authority or jurisdiction code: letters, digits and underscores only.");
    body[name] = body[name].toUpperCase();
  }
  if (!/^[A-Za-z0-9 ./-]+$/.test(body.registration_number))
    invalid("registration_number", "Use letters, digits, spaces, hyphens, slashes or dots for the registration number.");
  body.registration_number = body.registration_number.toUpperCase().replace(/[ ./-]/g, "");
  if (!body.registration_number)
    invalid("registration_number", "The registration number must contain a letter or digit.");

  const latitude = coordinate(values.latitude, "latitude", 90);
  const longitude = coordinate(values.longitude, "longitude", 180);
  if ((latitude === undefined) !== (longitude === undefined))
    invalid(latitude === undefined ? "latitude" : "longitude", "Provide both latitude and longitude, or leave both blank.");
  if (latitude !== undefined) {
    body.latitude = latitude;
    body.longitude = longitude;
  }
  return body;
}

function coordinate(value, field, bound) {
  if (value === undefined || value === null || value === "") return undefined;
  if (typeof value !== "string" && typeof value !== "number")
    invalid(field, `Enter a valid ${field}.`);
  const text = String(value).trim();
  if (!text) return undefined;
  // Plain decimals keep browser validation and Java's six-place precision aligned.
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(text))
    invalid(field, `Enter ${field} as a decimal number.`);
  const fraction = (text.split(".")[1] || "").replace(/0+$/, "");
  const number = Number(text);
  if (!Number.isFinite(number) || Math.abs(number) > bound || fraction.length > 6)
    invalid(field, `${field === "latitude" ? "Latitude" : "Longitude"} must be between -${bound} and ${bound}, with at most six decimal places.`);
  return number;
}
