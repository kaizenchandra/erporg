"use client";
import { useEffect, useState } from "react";

import { Icon } from "@/components/ui/icon";
import { Avatar, Empty, Modal } from "@/components/ui/primitives";
import { api, signOut } from "@/lib/api";
import type { Employee } from "@/lib/contracts";
import { useWorkspaceData } from "./use-workspace-data";
import { Directory } from "./directory";
import { Profile, ChangeForm } from "./profile";
import { Approvals } from "./approvals";
import { nav, type View } from "./navigation";
import { Sidebar } from "./sidebar";
import { Login } from "./login";
import { PeopleStats } from "./people-stats";
import { DocumentsWorkspace } from "@/features/documents/workspace";
import { BenefitsCatalogue } from "@/features/benefits/catalogue";
import { Hiring } from "./hiring";
import { Organisation } from "./organisation";
export function Workspace() {
  const {
    actor,
    employees,
    summary,
    self,
    notifications,
    loading,
    error,
    setError,
    refresh,
    reset: resetData,
  } = useWorkspaceData();
  const [view, setView] = useState<View>("People"),
    [tab, setTab] = useState("directory"),
    [search, setSearch] = useState(""),
    [profile, setProfile] = useState<Employee | null>(null),
    [change, setChange] = useState<Employee | null>(null),
    [notice, setNotice] = useState(""),
    [notificationPanel, setNotificationPanel] = useState(false),
    [menu, setMenu] = useState(false);
  const [authMode, setAuthMode] = useState("oidc");
  const reset = () => {
    resetData();
    setProfile(null);
    setChange(null);
    setSearch("");
    setView("People");
    setTab("directory");
  };
  useEffect(() => {
    const controller = new AbortController();
    api<{ mode: string }>("/api/auth-mode", { signal: controller.signal })
      .then((r) => setAuthMode(r.mode))
      .catch(() => {});
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        setView("People");
        setTab("directory");
        setTimeout(
          () =>
            document
              .querySelector<HTMLInputElement>(
                '[aria-label="Search employees"]',
              )
              ?.focus(),
          0,
        );
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);
  useEffect(() => {
    const expired = () => {
      setProfile(null);
      setChange(null);
      setSearch("");
      setView("People");
      setTab("directory");
    };
    window.addEventListener("atlas-session-expired", expired);
    return () => window.removeEventListener("atlas-session-expired", expired);
  }, []);
  const go = (v: View) => {
    setView(v);
    setMenu(false);
    setNotice("");
  };
  const pending = summary.pending;
  const saved = () => {
    setChange(null);
    setNotice("Request submitted. You can track it in Approvals.");
    refresh();
  };
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Sidebar menu={menu} view={view} go={go} pending={pending} />
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            <button
              className="icon-button mobile-menu"
              aria-label="Toggle navigation"
              onClick={() => setMenu(!menu)}
            >
              <Icon name="grid" />
            </button>
            <span>Workspace</span>
            <Icon name="chevron" size={14} />
            <strong>{view === "People" ? "Core HR" : view}</strong>
          </div>
          <div className="topbar-actions">
            <button
              className="global-search"
              onClick={() => {
                go("People");
                setTab("directory");
                setTimeout(
                  () =>
                    document
                      .querySelector<HTMLInputElement>(
                        '[aria-label="Search employees"]',
                      )
                      ?.focus(),
                  0,
                );
              }}
              aria-label="Search workspace"
            >
              <Icon name="search" size={19} />
              <span>Search anything</span>
              <kbd>⌘ K</kbd>
            </button>
            <span className="topbar-divider" />
            <button
              className="icon-button notification-button"
              aria-label={`Notifications (${notifications.length})`}
              onClick={() => setNotificationPanel(true)}
            >
              <Icon name="bell" />
              {notifications.length > 0 && <i />}
            </button>
            {actor && (
              <>
                <span className="topbar-divider" />
                <button
                  className="user-menu"
                  title="Open your profile"
                  onClick={() => {
                    const e = self;
                    if (e) setProfile(e);
                  }}
                >
                  <Avatar name={actor.name} />
                  <span>
                    <strong>{actor.name}</strong>
                    <small>
                      {actor.role === "HR"
                        ? "HR Administrator"
                        : actor.role === "MANAGER"
                          ? "Team manager"
                          : "Employee"}
                    </small>
                  </span>
                  <Icon name="down" size={14} />
                </button>
                <button
                  className="icon-button"
                  aria-label="Sign out"
                  onClick={async () => {
                    try {
                      await signOut(authMode);
                      reset();
                    } catch (e) {
                      setError(
                        e instanceof Error ? e.message : "Sign out failed.",
                      );
                    }
                  }}
                >
                  <Icon name="logout" size={18} />
                </button>
              </>
            )}
          </div>
        </header>
        <main id="main" tabIndex={-1}>
          <div className="page-heading">
            <div className="eyebrow">YOUR PEOPLE, CONNECTED</div>
            <div className="heading-line">
              <div>
                <h1>
                  {view === "People"
                    ? "People"
                    : view === "Approvals"
                      ? "Approval inbox"
                      : view}
                </h1>
                <p>
                  {view === "People"
                    ? "A shared home for your people and everything that moves them forward."
                    : view === "Approvals"
                      ? "Give every change the context and care it deserves."
                      : "Your people and operations, in one place."}
                </p>
              </div>
              {actor && view === "People" && (
                <button
                  className="button primary"
                  onClick={() => {
                    const e = self;
                    if (e) setChange(e);
                  }}
                >
                  <Icon name="plus" size={18} />
                  Request a change
                </button>
              )}
            </div>
          </div>
          {notice && (
            <div className="success-banner" role="status">
              <Icon name="check" size={18} />
              {notice}
              <button
                aria-label="Dismiss notification"
                onClick={() => setNotice("")}
              >
                <Icon name="close" size={16} />
              </button>
            </div>
          )}
          {error && (
            <div className="error" role="alert">
              {error}
              <button
                className="button secondary compact"
                onClick={() => refresh()}
              >
                Retry connection
              </button>
            </div>
          )}
          {loading ? (
            <div className="loading-state" role="status">
              <span className="spinner" />
              Opening your workspace…
            </div>
          ) : !actor ? (
            <Login authMode={authMode} refresh={refresh} onError={setError} />
          ) : (
            <>
              {(view === "People" || view === "Overview") && (
                <>
                  <PeopleStats summary={summary} pending={pending} />
                  <div className="people-tabs">
                    <button
                      className={tab === "directory" ? "active" : ""}
                      onClick={() => setTab("directory")}
                    >
                      <Icon name="people" size={17} />
                      Employee directory<span>{summary.total}</span>
                    </button>
                    <button
                      className={tab === "organization" ? "active" : ""}
                      onClick={() => setTab("organization")}
                    >
                      <Icon name="org" size={17} />
                      Organisation view
                    </button>
                    {actor.role === "HR" && (
                      <button
                        className={tab === "hiring" ? "active" : ""}
                        onClick={() => setTab("hiring")}
                      >
                        Hiring
                      </button>
                    )}
                    <button
                      className={tab === "benefits" ? "active" : ""}
                      onClick={() => setTab("benefits")}
                    >
                      Benefits
                    </button>
                    <button
                      className={tab === "documents" ? "active" : ""}
                      onClick={() => setTab("documents")}
                    >
                      Documents
                    </button>
                    <span className="tabs-note">
                      <span className="connection-dot" />
                      All changes saved securely
                    </span>
                  </div>
                  {tab === "directory" ? (
                    <Directory
                      employees={employees}
                      onOpen={setProfile}
                      search={search}
                      setSearch={setSearch}
                    />
                  ) : tab === "documents" ? (
                    <DocumentsWorkspace actor={actor} />
                  ) : tab === "benefits" ? (
                    <BenefitsCatalogue actor={actor} />
                  ) : tab === "hiring" && actor.role === "HR" ? (
                    <Hiring
                      actor={actor}
                      onRefresh={refresh}
                      onOpen={setProfile}
                    />
                  ) : (
                    <Organisation actor={actor} onOpen={setProfile} />
                  )}
                  <div className="bottom-note">
                    <span>
                      <Icon name="shield" size={15} />
                      People data is visible only within your authorised scope.
                    </span>
                    <span>Thoughtfully connected. Always human.</span>
                  </div>
                </>
              )}
              {view === "Approvals" && (
                <Approvals
                  revision={summary}
                  pending={pending}
                  actor={actor}
                  onSaved={(message) => {
                    setNotice(
                      message ??
                        "Decision recorded. The requester has been notified.",
                    );
                    refresh();
                  }}
                />
              )}
              {!["People", "Overview", "Approvals"].includes(view) && (
                <section className="planned-card">
                  <div className="planned-icon">
                    <Icon
                      name={nav.find((n) => n.label === view)?.icon ?? "help"}
                      size={30}
                    />
                  </div>
                  <div className="eyebrow">
                    {view === "Help & resources"
                      ? "WORKSPACE GUIDE"
                      : "DELIVERY ROADMAP"}
                  </div>
                  <h2>
                    {view === "Help & resources"
                      ? "A few things to get you started"
                      : `${view}, thoughtfully planned.`}
                  </h2>
                  <p>
                    {view === "Help & resources"
                      ? "Find a colleague in People, open their profile, and submit a change. An independent HR administrator or reporting manager reviews it in Approvals. Use ⌘ K to jump to search."
                      : view === "Settings"
                        ? "Country, legal entity, calendars, identity provider, hosting and residency are open implementation decisions. Local settings use synthetic data and UTC effective dates."
                        : `${view} is part of the committed implementation roadmap. This workspace currently supports employee profiles and employee-change approvals.`}
                  </p>
                  <div className="roadmap-steps">
                    <span className="complete">01 · Core HR foundation</span>
                    <span>02 · Lifecycle & documents</span>
                    <span>03 · Time & services</span>
                    <span>04 · Payroll & accounting</span>
                    <span>05 · Talent</span>
                    <span>06 · ERP operations</span>
                    <span>07 · Release verification</span>
                  </div>
                  <button
                    className="button secondary"
                    onClick={() => go("People")}
                  >
                    Back to your people
                    <Icon name="arrow" size={16} />
                  </button>
                </section>
              )}
            </>
          )}
        </main>
        <footer className="app-footer">
          <span>© 2026 Atlas</span>
          <span>People first. Possibilities ahead.</span>
          <span>Development workspace</span>
        </footer>
      </div>
      {profile && actor && (
        <Profile
          employee={profile}
          actor={actor}
          onClose={() => setProfile(null)}
          onChange={() => {
            setChange(profile);
            setProfile(null);
          }}
        />
      )}
      {change && actor && (
        <ChangeForm
          employee={change}
          actor={actor}
          onClose={() => setChange(null)}
          onSaved={saved}
        />
      )}
      {notificationPanel && (
        <Modal
          title="Notifications"
          onClose={() => setNotificationPanel(false)}
        >
          <div className="notification-list">
            {notifications.length ? (
              notifications.map((n) => (
                <article key={n.id}>
                  <Icon name="check" />
                  <div>
                    <strong>{n.message}</strong>
                    <small>{new Date(n.recordedAt).toLocaleString()}</small>
                  </div>
                </article>
              ))
            ) : (
              <Empty title="A quiet moment">
                Status updates on your requests will appear here.
              </Empty>
            )}
          </div>
        </Modal>
      )}
    </div>
  );
}
