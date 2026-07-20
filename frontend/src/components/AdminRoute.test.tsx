import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { UserView } from "../types/api";
import { AdminRoute } from "./AdminRoute";

const admin: UserView = { id: 1, username: "admin", displayName: "Admin", role: "ADMIN" };
const user: UserView = { id: 2, username: "user", displayName: "User", role: "USER" };

const adminPages = [
  ["/admin/model", "模型配置页"],
  ["/admin/skills", "技能配置页"],
  ["/admin/rules", "规则配置页"]
] as const;

describe("AdminRoute", () => {
  it.each(adminPages)("allows administrators to open %s", (path, pageText) => {
    renderAdminRoute(path, admin);

    expect(screen.getByText(pageText)).toBeInTheDocument();
    expect(screen.queryByText("调优工作区")).not.toBeInTheDocument();
  });

  it.each(adminPages)("redirects regular users away from %s", async (path, pageText) => {
    renderAdminRoute(path, user);

    expect(await screen.findByText("调优工作区")).toBeInTheDocument();
    expect(screen.getByTestId("current-path")).toHaveTextContent("/chat");
    expect(screen.queryByText(pageText)).not.toBeInTheDocument();
  });
});

function renderAdminRoute(initialPath: string, currentUser: UserView) {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/chat"
          element={
            <>
              <div>调优工作区</div>
              <CurrentPath />
            </>
          }
        />
        {adminPages.map(([path, pageText]) => (
          <Route
            key={path}
            path={path}
            element={
              <AdminRoute user={currentUser}>
                <div>{pageText}</div>
              </AdminRoute>
            }
          />
        ))}
      </Routes>
    </MemoryRouter>
  );
}

function CurrentPath() {
  return <span data-testid="current-path">{useLocation().pathname}</span>;
}
