// Client checks improve form feedback; Java must repeat validation and enforce policy.
export function validateAuthFields(
  data,
  { registering = false, staff = false } = {},
) {
  if (
    registering &&
    (!data.full_name?.trim() || data.full_name.trim().length > 100)
  )
    return "Enter your full name (up to 100 characters).";
  if (
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(data.email?.trim() || "") ||
    data.email.trim().length > 254
  )
    return "Enter a valid email address.";
  if (
    typeof data.password !== "string" ||
    !data.password ||
    data.password.length > 128
  )
    return "Enter your password (up to 128 characters).";
  if (registering && data.password.length < 12)
    return "Use a password with at least 12 characters.";
  if (registering && data.password !== data.confirm_password)
    return "The passwords do not match.";
  if (registering && staff && !data.hospital_id)
    return "Select the hospital where you work.";
  return "";
}
