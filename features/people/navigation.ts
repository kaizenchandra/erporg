export type View =
  | "People"
  | "Approvals"
  | "Overview"
  | "Time & attendance"
  | "Payroll"
  | "Talent"
  | "Finance & operations"
  | "Reports"
  | "Settings"
  | "Help & resources";
export const nav: { label: View; icon: string; section?: string }[] = [
  { label: "Overview", icon: "grid" },
  { label: "Approvals", icon: "inbox" },
  { label: "People", icon: "people", section: "WORKSPACE" },
  { label: "Time & attendance", icon: "calendar" },
  { label: "Payroll", icon: "wallet" },
  { label: "Talent", icon: "star" },
  { label: "Finance & operations", icon: "building" },
  { label: "Reports", icon: "chart" },
];
