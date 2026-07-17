import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AppShell } from "./AppShell";

const conversations = [
  { id: 1, userId: 1, title: "订单慢 SQL", createdAt: "2026-07-17T09:00:00Z", updatedAt: "2026-07-17T09:00:00Z" },
  { id: 2, userId: 1, title: "库存分页查询", createdAt: "2026-07-17T10:00:00Z", updatedAt: "2026-07-17T10:00:00Z" }
];

describe("AppShell", () => {
  it("filters the conversation list with the real search input", async () => {
    render(
      <AppShell
        user={{ id: 1, username: "admin", displayName: "Admin", role: "ADMIN" }}
        conversations={conversations}
        activeConversationId={1}
        currentRoute="/chat"
        theme="light"
        onToggleTheme={vi.fn()}
        onNewConversation={vi.fn()}
        onSelectConversation={vi.fn()}
        onDeleteConversation={vi.fn()}
        onNavigate={vi.fn()}
        onLogout={vi.fn()}
      >
        <div>workspace</div>
      </AppShell>
    );

    await userEvent.type(screen.getByPlaceholderText("搜索会话标题"), "库存");

    expect(screen.getByText("库存分页查询")).toBeInTheDocument();
    expect(screen.queryByText("订单慢 SQL")).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "模型" })).toHaveLength(1);
  });
});
