# Design system
Atlas: original enterprise workspace informed by public SAP Employee Central descriptions (https://www.sap.com/uk/products/hcm/employee-central-hris.html); no claim of inspecting authenticated SAP/Workday screens.

White and cool gray surfaces, slate navy sidebar, teal primary action. Tokens in app/globals.css: 4/8/12/16/24/32 spacing, 8/12/16 radii, fine gray borders, quiet shadows. System sans typography, 14px body, 32px page title. Consistent inline SVG stroke icons. Status labels include text plus color. Hover, focus-visible, selected, disabled, busy, error and empty states explicit. Reduced-motion respected.

Persistent global navigation, workspace context, directory search, department/status filters, sortable name, page size/pagination, column control. Selection applies only to explicitly selected records; no consequential bulk action in Phase 1. Profile and request panels use native modal dialog, trapped focus and return focus. Native labels, inline errors, preserved drafts, unsaved-change confirmation, request progress and live status. Consequential approval shows field, old/new value, effective date and reason. Responsive directory switches to cards; sidebar collapses on mobile.

Gate: inspect shell, directory, profile and inbox at desktop/mobile; keyboard-only dialog and form review; automated accessibility supplemented with screen-reader review before AA claim. Synthetic data only. Loading, empty, errors, long content and denied states must be exercised. No WCAG conformance claim from automated tests alone.
