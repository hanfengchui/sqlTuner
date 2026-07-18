import { render, screen, within } from "@testing-library/react";
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

    await userEvent.click(screen.getByRole("button", { name: "搜索会话" }));
    await userEvent.type(screen.getByPlaceholderText("搜索会话标题"), "库存");

    const conversationList = document.querySelector(".conversation-list");
    expect(conversationList).not.toBeNull();
    expect(within(conversationList as HTMLElement).getByText("库存分页查询")).toBeInTheDocument();
    expect(within(conversationList as HTMLElement).queryByText("订单慢 SQL")).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "模型" })).toHaveLength(1);
  });

  it("uses an explicit back control on administrator routes", async () => {
    const navigate = vi.fn();
    render(
      <AppShell
        user={{ id: 1, username: "admin", displayName: "Admin", role: "ADMIN" }}
        conversations={conversations}
        currentRoute="/admin/model"
        theme="dark"
        onToggleTheme={vi.fn()}
        onNewConversation={vi.fn()}
        onSelectConversation={vi.fn()}
        onDeleteConversation={vi.fn()}
        onNavigate={navigate}
        onLogout={vi.fn()}
      >
        <div>model page</div>
      </AppShell>
    );

    expect(screen.getByText("模型配置")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "返回对话" }));
    expect(navigate).toHaveBeenCalledWith("/chat");
  });
});
