import { FileText, KeyRound, LayoutDashboard, LogOut, Menu, ScrollText, Settings, Users2, X } from "lucide-react";
import { useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/utils";

/** Only implemented modules appear, and only when the user may read them. */
const NAV = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard, permission: "DASHBOARD_READ", end: true },
  { to: "/clients", label: "Clients", icon: Users2, permission: "CLIENT_READ" },
  { to: "/documents", label: "Documents", icon: FileText, permission: "DOCUMENT_READ" },
  { to: "/audit", label: "Audit Log", icon: ScrollText, permission: "AUDIT_READ" },
  { to: "/settings", label: "Settings", icon: Settings, permission: "USER_READ" },
];

export function AppLayout() {
  const { user, can, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const items = NAV.filter((n) => can(n.permission));

  const sidebar = (
    <nav aria-label="Main" className="flex h-full flex-col bg-primary text-white">
      <div className="flex items-center gap-2.5 px-5 py-5">
        <div className="flex h-8 w-8 items-center justify-center rounded-md bg-emerald font-bold">T</div>
        <div>
          <p className="text-sm leading-tight font-semibold tracking-wide">TAXPILOT AI</p>
          <p className="text-[11px] leading-tight text-white/60">Accounting &amp; Tax Department</p>
        </div>
      </div>
      <ul className="flex-1 space-y-0.5 px-3">
        {items.map(({ to, label, icon: Icon, end }) => (
          <li key={to}>
            <NavLink
              to={to}
              end={end}
              onClick={() => setOpen(false)}
              className={({ isActive }) =>
                cn(
                  "flex items-center gap-3 rounded-md px-3 py-2 text-sm text-white/80 hover:bg-white/10 hover:text-white",
                  isActive && "bg-white/12 font-medium text-white",
                )
              }
            >
              <Icon className="h-4 w-4" aria-hidden />
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
      <div className="border-t border-white/10 p-3">
        <div className="px-3 pb-2">
          <p className="truncate text-sm font-medium">{user?.fullName}</p>
          <p className="truncate text-xs text-white/60">{user?.roles.join(", ")}</p>
        </div>
        <NavLink
          to="/change-password"
          className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm text-white/80 hover:bg-white/10 hover:text-white"
        >
          <KeyRound className="h-4 w-4" aria-hidden /> Change password
        </NavLink>
        <button
          type="button"
          onClick={async () => {
            await logout();
            navigate("/login");
          }}
          className="flex w-full cursor-pointer items-center gap-3 rounded-md px-3 py-2 text-sm text-white/80 hover:bg-white/10 hover:text-white"
        >
          <LogOut className="h-4 w-4" aria-hidden /> Sign out
        </button>
      </div>
    </nav>
  );

  return (
    <div className="min-h-screen lg:pl-60">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 lg:block">{sidebar}</aside>
      {open && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-ink/40" onClick={() => setOpen(false)} aria-hidden />
          <aside className="absolute inset-y-0 left-0 w-64">{sidebar}</aside>
          <button
            type="button"
            className="absolute top-4 left-[17rem] rounded bg-white p-1.5"
            aria-label="Close menu"
            onClick={() => setOpen(false)}
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      )}
      <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-line bg-white/90 px-4 backdrop-blur lg:hidden">
        <button type="button" className="rounded p-1.5 hover:bg-mint" aria-label="Open menu" onClick={() => setOpen(true)}>
          <Menu className="h-5 w-5" />
        </button>
        <span className="font-semibold text-primary">TAXPILOT AI</span>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
        <Outlet />
      </main>
    </div>
  );
}
