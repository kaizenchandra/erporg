"use client";
import { useState } from "react";
import { useDirectory } from "./use-directory";
import { Icon } from "@/components/ui/icon";
import { Avatar, Badge, Empty } from "@/components/ui/primitives";
import type { Employee } from "@/lib/contracts";
export function Directory({
  employees,
  onOpen,
  search,
  setSearch,
}: {
  employees: Employee[];
  onOpen: (e: Employee) => void;
  search: string;
  setSearch: (s: string) => void;
}) {
  const [department, setDepartment] = useState("All departments"),
    [status, setStatus] = useState("All statuses"),
    [page, setPage] = useState(0),
    [size, setSize] = useState(8),
    [ascending, setAscending] = useState(true),
    [columns, setColumns] = useState(false),
    [location, setLocation] = useState(true),
    [selected, setSelected] = useState<string[]>([]);
  const query = new URLSearchParams({
    search,
    department: department === "All departments" ? "" : department,
    status: status === "All statuses" ? "" : status,
    page: String(page),
    size: String(size),
    direction: ascending ? "asc" : "desc",
  }).toString();
  const { data, error, loading, retry } = useDirectory(query, employees);
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const current = page;
  const toggle = (id: string) =>
    setSelected((s) =>
      s.includes(id) ? s.filter((x) => x !== id) : [...s, id],
    );
  return (
    <section
      className="directory-card"
      aria-label="Employee directory"
      aria-busy={loading}
    >
      <div className="directory-toolbar">
        <label className="search-field">
          <Icon name="search" size={18} />
          <input
            aria-label="Search employees"
            placeholder="Search by name, role, or employee ID…"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
          <kbd>⌘ K</kbd>
        </label>
        <div className="filters">
          <label>
            <span className="sr-only">Department</span>
            <select
              aria-label="Department"
              value={department}
              onChange={(e) => {
                setDepartment(e.target.value);
                setPage(0);
              }}
            >
              <option>All departments</option>
              {(
                data?.departments ??
                [...new Set(employees.map((e) => e.department))].sort()
              ).map((d) => (
                <option key={d}>{d}</option>
              ))}
            </select>
          </label>
          <label>
            <span className="sr-only">Status</span>
            <select
              aria-label="Status"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option>All statuses</option>
              <option>Active</option>
              <option>On leave</option>
              <option>Onboarding</option>
              <option>Ended</option>
            </select>
          </label>
          <div className="column-control">
            <button
              className="button secondary compact"
              aria-expanded={columns}
              onClick={() => setColumns(!columns)}
            >
              <Icon name="columns" size={17} />
              <span>Columns</span>
            </button>
            {columns && (
              <div className="popover">
                <label>
                  <input
                    type="checkbox"
                    checked={location}
                    onChange={(e) => setLocation(e.target.checked)}
                  />{" "}
                  Show location
                </label>
              </div>
            )}
          </div>
        </div>
      </div>
      {selected.length > 0 && (
        <div className="selection-bar">
          {selected.length} explicitly selected employee
          {selected.length > 1 ? "s" : ""}.{" "}
          <button onClick={() => setSelected([])}>Clear selection</button>
        </div>
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th className="check-cell">
                <input
                  aria-label="Select visible employees"
                  type="checkbox"
                  checked={
                    rows.length > 0 &&
                    rows.every((e) => selected.includes(e.id))
                  }
                  onChange={(e) =>
                    setSelected(
                      e.target.checked
                        ? [...new Set([...selected, ...rows.map((r) => r.id)])]
                        : selected.filter(
                            (id) => !rows.some((r) => r.id === id),
                          ),
                    )
                  }
                />
              </th>
              <th aria-sort={ascending ? "ascending" : "descending"}>
                <button
                  className="sort-button"
                  onClick={() => {
                    setAscending(!ascending);
                    setPage(0);
                  }}
                >
                  Employee <span>{ascending ? "↑" : "↓"}</span>
                </button>
              </th>
              <th>Department</th>
              <th>Status</th>
              {location && <th>Location</th>}
              <th>Joined</th>
              <th>
                <span className="sr-only">View profile</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
              <tr
                key={e.id}
                className={selected.includes(e.id) ? "selected" : ""}
              >
                <td className="check-cell">
                  <input
                    type="checkbox"
                    aria-label={`Select ${e.name}`}
                    checked={selected.includes(e.id)}
                    onChange={() => toggle(e.id)}
                  />
                </td>
                <td>
                  <button className="employee-cell" onClick={() => onOpen(e)}>
                    <Avatar name={e.name} />
                    <span>
                      <strong>{e.name}</strong>
                      <small>{e.title}</small>
                    </span>
                  </button>
                </td>
                <td>
                  <span className="department-label">{e.department}</span>
                </td>
                <td>
                  <Badge status={e.status} />
                </td>
                {location && <td className="muted">{e.location}</td>}
                <td className="muted">
                  {new Intl.DateTimeFormat("en-GB", {
                    month: "short",
                    year: "numeric",
                    timeZone: "UTC",
                  }).format(new Date(e.startDate))}
                </td>
                <td>
                  <button
                    className="icon-button"
                    aria-label={`View ${e.name}`}
                    onClick={() => onOpen(e)}
                  >
                    <Icon name="chevron" size={16} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="employee-cards">
        {rows.map((e) => (
          <button
            className="mobile-employee"
            key={e.id}
            onClick={() => onOpen(e)}
          >
            <Avatar name={e.name} />
            <span>
              <strong>{e.name}</strong>
              <small>{e.title}</small>
              <Badge status={e.status} />
            </span>
            <Icon name="chevron" />
          </button>
        ))}
      </div>
      {loading && <p role="status">Loading employees…</p>}
      {error && (
        <div role="alert">
          {error} <button onClick={retry}>Retry directory</button>
        </div>
      )}
      {!loading && !error && !rows.length && (
        <Empty title="No employees found">
          Try another name or reset your department and status filters.
        </Empty>
      )}
      <footer className="table-footer">
        <span>
          Showing{" "}
          <strong>
            {total ? current * size + 1 : 0}–
            {Math.min((current + 1) * size, total)}
          </strong>{" "}
          of <strong>{total}</strong> employees
        </span>
        <div>
          <label className="page-size">
            Rows per page{" "}
            <select
              value={size}
              onChange={(e) => {
                setSize(Number(e.target.value));
                setPage(0);
              }}
            >
              <option>8</option>
              <option>12</option>
              <option>24</option>
            </select>
          </label>
          <button
            className="icon-button page-button"
            aria-label="Previous page"
            disabled={current === 0}
            onClick={() => setPage(current - 1)}
          >
            <Icon
              name="chevron"
              size={16}
              style={{ transform: "rotate(180deg)" }}
            />
          </button>
          <span className="page-number">{current + 1}</span>
          <button
            className="icon-button page-button"
            aria-label="Next page"
            disabled={loading || !!error || (current + 1) * size >= total}
            onClick={() => setPage(current + 1)}
          >
            <Icon name="chevron" size={16} />
          </button>
        </div>
      </footer>
    </section>
  );
}
