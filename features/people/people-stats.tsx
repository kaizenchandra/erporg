import { Icon } from "@/components/ui/icon";
import type { PeopleSummary } from "@/lib/contracts";
export function PeopleStats({
  summary,
  pending,
}: {
  summary: PeopleSummary;
  pending: number;
}) {
  return (
    <div className="stats-grid">
      {[
        {
          label: "Total employees",
          value: summary.total,
          icon: "people",
          note: "Across your authorised workspace",
          color: "teal",
        },
        {
          label: "Active employees",
          value: summary.active,
          icon: "check",
          note: "Building great things together",
          color: "green",
        },
        {
          label: "On leave",
          value: summary.onLeave,
          icon: "calendar",
          note: "Taking time to recharge",
          color: "amber",
        },
        {
          label: "Pending requests",
          value: pending,
          icon: "clock",
          note: "A little attention goes a long way",
          color: "purple",
        },
      ].map((s) => (
        <article className="stat-card" key={s.label}>
          <div>
            <span>{s.label}</span>
            <span className={`stat-icon ${s.color}`}>
              <Icon name={s.icon} size={19} />
            </span>
          </div>
          <strong>{s.value.toString().padStart(2, "0")}</strong>
          <p>{s.note}</p>
        </article>
      ))}
    </div>
  );
}
