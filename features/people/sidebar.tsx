"use client";
import Link from "next/link";
import { Icon } from "@/components/ui/icon";
import { nav, type View } from "./navigation";
export function Sidebar({
  menu,
  view,
  go,
  pending,
}: {
  menu: boolean;
  view: View;
  go: (v: View) => void;
  pending: number;
}) {
  return (
    <aside className={`sidebar ${menu ? "mobile-open" : ""}`}>
      <Link className="brand" href="/" aria-label="Atlas home">
        <span className="brand-mark">
          a<span />
        </span>
        <span>
          atlas<span className="brand-dot">.</span>
        </span>
      </Link>
      <div className="workspace-switch">
        <span className="company-mark">A</span>
        <div>
          <strong>Acme Group</strong>
          <span>People & operations</span>
        </div>
        <span className="demo-indicator" title="Synthetic workspace" />
      </div>
      <nav aria-label="Main navigation">
        {nav.map((item) => (
          <div key={item.label}>
            {item.section && <div className="nav-section">{item.section}</div>}
            <button
              className={`nav-item ${view === item.label ? "active" : ""}`}
              onClick={() => go(item.label)}
              aria-current={view === item.label ? "page" : undefined}
            >
              <Icon name={item.icon} size={19} />
              <span>{item.label}</span>
              {item.label === "Approvals" && pending > 0 && (
                <b className="nav-count">{pending}</b>
              )}
              {item.label === "People" && <span className="active-dot" />}
            </button>
          </div>
        ))}
      </nav>
      <div className="sidebar-bottom">
        <div className="workspace-note">
          <span className="small-orbit">
            <Icon name="shield" size={18} />
          </span>
          <strong>A place for your people.</strong>
          <p>
            Connected teams.
            <br />
            Thoughtful operations.
          </p>
          <span className="local-tag">SYNTHETIC WORKSPACE</span>
        </div>
        {(["Settings", "Help & resources"] as View[]).map((v) => (
          <button
            key={v}
            className={`nav-item ${view === v ? "active" : ""}`}
            onClick={() => go(v)}
          >
            <Icon name={v === "Settings" ? "settings" : "help"} size={18} />
            {v}
          </button>
        ))}
        <div className="sidebar-footer">
          <span className="connection-dot" />
          Local development<span>v0.1</span>
        </div>
      </div>
    </aside>
  );
}
