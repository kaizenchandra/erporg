"use client";
import { Icon } from "@/components/ui/icon";
import { Avatar } from "@/components/ui/primitives";
import { DepartmentReference } from "./department-reference";
import type { Employee, Actor } from "@/lib/contracts";
export function Organisation({
  employees,
  actor,
  total,
  onOpen,
}: {
  employees: Employee[];
  actor: Actor;
  total: number;
  onOpen: (e: Employee) => void;
}) {
  return (
    <section className="org-view">
      <DepartmentReference actor={actor} />
      <div className="section-heading">
        <h3>Your organisational view</h3>
        <span>
          Preview: {employees.length} of {total} authorised employees. Use the
          directory to find everyone.
        </span>
      </div>
      {[...new Set(employees.map((e) => e.department))].sort().map((d) => (
        <div className="org-department" key={d}>
          <h3>
            <Icon name="building" />
            {d}
            <span>{employees.filter((e) => e.department === d).length}</span>
          </h3>
          <div>
            {employees
              .filter((e) => e.department === d)
              .map((e) => (
                <button key={e.id} onClick={() => onOpen(e)}>
                  <Avatar name={e.name} />
                  <span>
                    <strong>{e.name}</strong>
                    <small>{e.title}</small>
                    <small>
                      Reports to{" "}
                      {employees.find((m) => m.id === e.managerId)?.name ??
                        e.managerId ??
                        "—"}
                    </small>
                  </span>
                </button>
              ))}
          </div>
        </div>
      ))}
    </section>
  );
}
