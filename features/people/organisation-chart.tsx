import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { Avatar } from "@/components/ui/primitives";
import type { Actor, Employee } from "@/lib/contracts";
export type OrganisationNode = {
  id: string;
  name: string;
  title: string;
  department: string;
  positionCode: string;
  status: string;
  visibleDirectReports: number;
};
export type OrganisationBranch = {
  parent: OrganisationNode | null;
  manager: OrganisationNode | null;
  managerOutsideScope: boolean;
  items: OrganisationNode[];
  total: number;
  page: number;
  size: number;
  asOf: string;
  mode: "ROOTS" | "REPORTS" | "SEARCH";
};
type Location = { parentId: string; search: string; page: number };
export function OrganisationChart({
  actor,
  onOpen,
}: {
  actor: Actor;
  onOpen: (e: Employee) => void;
}) {
  const [location, setLocation] = useState<Location>({
      parentId: "",
      search: "",
      page: 0,
    }),
    [input, setInput] = useState(""),
    [trail, setTrail] = useState<Location[]>([]),
    [revision, setRevision] = useState(0),
    [result, setResult] = useState<{
      key: string;
      data?: OrganisationBranch;
      error?: string;
    }>(),
    [opening, setOpening] = useState<string | null>(null),
    [profileError, setProfileError] = useState("");
  const profileRequest = useRef<AbortController | null>(null),
    heading = useRef<HTMLHeadingElement>(null);
  const key = JSON.stringify([
    actor.subject,
    actor.tenant,
    actor.role,
    location,
    revision,
  ]);
  const current = result?.key === key ? result : undefined;
  useEffect(
    () => () => profileRequest.current?.abort(),
    [actor.subject, actor.tenant, actor.role],
  );
  useEffect(() => {
    const c = new AbortController();
    const query = new URLSearchParams({
      ...location,
      page: String(location.page),
      size: "12",
    });
    api<OrganisationBranch>(`/api/v1/organisation/chart?${query}`, {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!c.signal.aborted) setResult({ key, data });
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Organisation view unavailable.",
          });
      });
    return () => c.abort();
  }, [key, location]);
  const cancelProfile = () => {
    profileRequest.current?.abort();
    setOpening(null);
    setProfileError("");
  };
  const navigate = (next: Location) => {
    cancelProfile();
    setTrail((t) => [...t.slice(-49), location]);
    setLocation(next);
    setInput(next.search);
    setProfileError("");
    heading.current?.focus();
  };
  const openProfile = async (node: OrganisationNode) => {
    profileRequest.current?.abort();
    const c = new AbortController();
    profileRequest.current = c;
    setOpening(node.id);
    setProfileError("");
    try {
      const employee = await api<Employee>(
        `/api/v1/employees/${encodeURIComponent(node.id)}`,
        { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
      );
      if (!c.signal.aborted) onOpen(employee);
    } catch (e) {
      if (!c.signal.aborted)
        setProfileError(
          e instanceof Error
            ? e.message
            : "Profile unavailable. Refresh the chart and retry.",
        );
    } finally {
      if (!c.signal.aborted) setOpening(null);
    }
  };
  const node = (n: OrganisationNode, anchor = false) => (
    <article
      className={`benefit-card organisation-node${anchor ? " organisation-anchor" : ""}`}
      aria-label={`Organisation member ${n.id}`}
    >
      <div className="organisation-person">
        <Avatar name={n.name} />
        <div>
          <h4>{n.name}</h4>
          <p>
            {n.id} · {n.title}
          </p>
        </div>
      </div>
      <p>
        {n.department} · {n.status}
      </p>
      <p>Position: {n.positionCode || "Unassigned"}</p>
      <p>{n.visibleDirectReports} visible direct reports</p>
      <div className="organisation-actions">
        {!anchor && (
          <button
            className="button secondary"
            disabled={n.visibleDirectReports === 0}
            onClick={() => navigate({ parentId: n.id, search: "", page: 0 })}
          >
            Browse reports of {n.name}
          </button>
        )}
        <button
          className="button secondary"
          disabled={opening === n.id}
          onClick={() => void openProfile(n)}
        >
          {opening === n.id ? "Opening profile…" : `View profile for ${n.name}`}
        </button>
      </div>
    </article>
  );
  return (
    <section aria-label="Organisation chart" className="organisation-chart">
      <div className="section-heading">
        <h3 tabIndex={-1} ref={heading}>
          Your organisational view
        </h3>
        <button
          className="button secondary"
          onClick={() => setRevision((n) => n + 1)}
        >
          Refresh chart
        </button>
      </div>
      <p>
        {actor.role === "HR"
          ? "Tenant hierarchy"
          : actor.role === "MANAGER"
            ? "Your record and current direct reports"
            : "Your record only"}
        . Counts include only visible direct reports. Search covers authorised
        records beyond the directory page. The chart shows current effective
        data, not future appointments. Back keeps 50 recent branches.
      </p>
      <form
        className="organisation-search"
        onSubmit={(e) => {
          e.preventDefault();
          navigate({ parentId: "", search: input.trim(), page: 0 });
        }}
      >
        <label>
          Search organisation members
          <input
            maxLength={120}
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />
        </label>
        <button className="button primary">Search chart</button>
      </form>
      <div className="organisation-actions">
        <button
          className="button secondary"
          onClick={() => {
            cancelProfile();
            setLocation({ parentId: "", search: "", page: 0 });
            setInput("");
            setTrail([]);
            setProfileError("");
            heading.current?.focus();
          }}
        >
          Organisation roots
        </button>
        <button
          className="button secondary"
          disabled={!trail.length}
          onClick={() => {
            cancelProfile();
            const last = trail.at(-1)!;
            setLocation(last);
            setInput(last.search);
            setTrail((t) => t.slice(0, -1));
            setProfileError("");
            heading.current?.focus();
          }}
        >
          Back to previous branch
        </button>
      </div>
      {profileError && (
        <p className="error" role="alert">
          {profileError} Refresh the chart if reporting access changed.
        </p>
      )}
      {!current ? (
        <p role="status">Loading organisation branch…</p>
      ) : current.error ? (
        <div className="error" role="alert">
          {current.error}{" "}
          <button onClick={() => setRevision((n) => n + 1)}>
            Retry organisation branch
          </button>
        </div>
      ) : (
        <>
          <p role="status">
            {current.data!.total}{" "}
            {current.data!.mode === "SEARCH"
              ? "matching members"
              : current.data!.mode === "REPORTS"
                ? "visible direct reports"
                : "visible roots"}{" "}
            · Page {location.page + 1} · Effective {current.data!.asOf} (UTC).
            Names A to Z.
          </p>
          {current.data!.manager && (
            <button
              className="button secondary"
              onClick={() =>
                navigate({
                  parentId: current.data!.manager!.id,
                  search: "",
                  page: 0,
                })
              }
            >
              Browse visible manager {current.data!.manager!.name}
            </button>
          )}
          {current.data!.managerOutsideScope && (
            <p>
              Manager is outside your authorised view. No manager identity is
              shown.
            </p>
          )}
          {current.data!.parent && (
            <div className="organisation-parent">
              {node(current.data!.parent, true)}
            </div>
          )}
          {current.data!.items.length ? (
            <ul
              className={`organisation-branches${current.data!.parent ? " organisation-children" : ""}`}
            >
              {current.data!.items.map((n) => (
                <li key={n.id}>{node(n)}</li>
              ))}
            </ul>
          ) : (
            <p>
              No visible members in this branch or search. Try another search or
              return to roots.
            </p>
          )}
          <div className="position-pagination">
            <button
              className="button secondary"
              disabled={!location.page}
              onClick={() => {
                cancelProfile();
                setLocation((s) => ({ ...s, page: s.page - 1 }));
              }}
            >
              Previous chart page
            </button>
            <button
              className="button secondary"
              disabled={(location.page + 1) * 12 >= current.data!.total}
              onClick={() => {
                cancelProfile();
                setLocation((s) => ({ ...s, page: s.page + 1 }));
              }}
            >
              Next chart page
            </button>
          </div>
        </>
      )}
    </section>
  );
}
