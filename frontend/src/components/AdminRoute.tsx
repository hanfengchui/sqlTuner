import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import type { UserView } from "../types/api";

interface AdminRouteProps {
  user: UserView;
  children: ReactNode;
}

export function AdminRoute({ user, children }: AdminRouteProps) {
  if (user.role !== "ADMIN") {
    return <Navigate to="/chat" replace />;
  }
  return <>{children}</>;
}
