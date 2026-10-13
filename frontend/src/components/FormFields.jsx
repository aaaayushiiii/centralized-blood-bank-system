export function splitBloodGroup(value) {
  return { blood_group: value.slice(0, -1), rh_factor: value.slice(-1) };
}
export function Field({ label, name, help, children, ...props }) {
  return (
    <div className="field">
      <label htmlFor={name}>{label}</label>
      {children || (
        <input
          id={name}
          name={name}
          {...props}
          aria-describedby={help ? `${name}-help` : undefined}
        />
      )}
      {help && <small id={`${name}-help`}>{help}</small>}
    </div>
  );
}
export function BloodSelect({
  name = "blood",
  label = "Blood group",
  value,
  onChange,
}) {
  return (
    <Field label={label} name={name}>
      <select id={name} name={name} value={value} onChange={onChange} required>
        {["O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-"].map((g) => (
          <option key={g}>{g}</option>
        ))}
      </select>
    </Field>
  );
}
export function HospitalSelect({
  hospitals,
  value,
  onChange,
  name = "hospital",
}) {
  return (
    <Field label="Hospital" name={name}>
      <select
        id={name}
        name={name}
        value={value}
        onChange={onChange}
        required
        disabled={!hospitals.length}
      >
        <option value="">Select a hospital</option>
        {hospitals.map((h) => (
          <option key={h.hospital_id} value={h.hospital_id}>
            {h.name}
          </option>
        ))}
      </select>
    </Field>
  );
}
