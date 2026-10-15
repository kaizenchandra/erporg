"use client";
import { PositionCatalogue } from "./position-catalogue";
import { DepartmentReference } from "./department-reference";
import { OrganisationChart } from "./organisation-chart";
import type { Employee, Actor } from "@/lib/contracts";
export function Organisation({
  actor,
  onOpen,
}: {
  actor: Actor;
  onOpen: (e: Employee) => void;
}) {
  return (
    <section className="org-view">
      <DepartmentReference actor={actor} />
      <PositionCatalogue actor={actor} />
      <OrganisationChart actor={actor} onOpen={onOpen} />
    </section>
  );
}
