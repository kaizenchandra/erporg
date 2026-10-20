export const changeFieldLabels: Record<string, string> = {
  name: "Employee display name",
  legalName: "Recorded legal name",
  legalEntityCode: "Declared employing entity",
  calendarCode: "Company calendar assignment",
  personalEmail: "Personal email",
  birthDate: "Recorded birth date",
  phone: "Contact number",
  title: "Job title / promotion",
  department: "Department / transfer",
  location: "Work location",
  managerId: "Reporting manager / team change",
  positionCode: "Primary position / assignment",
  address: "Home address",
  emergencyContactName: "Emergency contact name",
  emergencyContactRelationship: "Emergency contact relationship",
  emergencyContactPhone: "Emergency contact phone",
};

// Presentation only; the backend owns address validation and serialization.
export function formatChangeValue(field: string, value: string | null) {
  if (field === "address" && value?.startsWith("@POSTAL/1\n"))
    return value.slice(10).split("\n").filter(Boolean).join(", ");
  return value;
}
