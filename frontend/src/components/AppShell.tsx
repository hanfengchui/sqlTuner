import * as Dialog from "@radix-ui/react-dialog";
import * as Tooltip from "@radix-ui/react-tooltip";
import {
  Bot,
  DatabaseZap,
  LogOut,
  Menu,
  MessageSquarePlus,
  Moon,
  Search,
  Settings,
  ShieldCheck,
  Sun,
  Trash2,
  X
} from "lucide-react";
import { useMemo, useState } from "react";
import type { Theme } from "../lib/useTheme";
import type { Conversation, UserView } from "../types/api";

interface AppShellProps {
  user: UserView;
  conversations: Conversation[];
  activeConversationId?: number;
  currentRoute: string;
  theme: Theme;
  onToggleTheme: () => void;
  onNewConversation: () => void;
  onSelectConversation: (id: number) => void;
  onDeleteConversation: (id: number) => void;
  onNavigate: (route: string) => void;
  onLogout: () => void;
  children: React.ReactNode;
}

export function AppShell(props: AppShellProps) {
  const [mobileOpen, setMobileOpen] = useState(false);

  function selectConversation(id: number) {
    props.onSelectConversation(id);
    setMobileOpen(false);
  }

  function navigate(route: string) {
    props.onNavigate(route);
    setMobileOpen(false);
  }

  return (
    <Tooltip.Provider delayDuration={250}>
      <div className="app-shell">
        <CommandRail {...props} onSelectConversation={selectConversation} onNavigate={navigate} />
        <main className="main-stage">
          <header className="topbar">
            <Dialog.Root open={mobileOpen} onOpenChange={setMobileOpen}>
              <Dialog.Trigger asChild>
                <button className="icon-button mobile-menu" aria-label="打开导航">
                  <Menu size={18} />
                </button>
              </Dialog.Trigger>
              <Dialog.Portal>
                <Dialog.Overlay className="dialog-overlay" />
                <Dialog.Content className="mobile-nav-sheet" aria-label="导航">
                  <Dialog.Title className="sr-only">导航</Dialog.Title>
                  <button className="icon-button sheet-close" aria-label="关闭导航" onClick={() => setMobileOpen(false)}>
                    <X size={18} />
                  </button>
                  <CommandRail {...props} onSelectConversation={selectConversation} onNavigate={navigate} />
                </Dialog.Content>
              </Dialog.Portal>
            </Dialog.Root>

            <button className={props.currentRoute === "/chat" ? "topbar-primary active" : "topbar-primary"} onClick={() => props.onNavigate("/chat")}>
              <MessageSquarePlus size={16} />
              调优工作台
            </button>
            <div className="topbar-actions">
              {props.user.role === "ADMIN" && (
                <>
                  <button className={props.currentRoute.startsWith("/admin/rules") ? "active" : ""} onClick={() => props.onNavigate("/admin/rules")}>
                    <ShieldCheck size={16} />
                    规则
                  </button>
                  <button className={props.currentRoute.startsWith("/admin/model") ? "active" : ""} onClick={() => props.onNavigate("/admin/model")}>
                    <Bot size={16} />
                    模型
                  </button>
                  <button className={props.currentRoute.startsWith("/admin/skills") ? "active" : ""} onClick={() => props.onNavigate("/admin/skills")}>
                    <Settings size={16} />
                    技能
                  </button>
                </>
              )}
              <Tooltip.Root>
                <Tooltip.Trigger asChild>
                  <button
                    className="theme-toggle"
                    aria-label={props.theme === "dark" ? "切换到浅色" : "切换到深色"}
                    onClick={props.onToggleTheme}
                  >
                    {props.theme === "dark" ? <Sun size={16} /> : <Moon size={16} />}
                  </button>
                </Tooltip.Trigger>
                <Tooltip.Content className="tooltip" sideOffset={8}>
                  {props.theme === "dark" ? "浅色主题" : "深色主题"}
                </Tooltip.Content>
              </Tooltip.Root>
            </div>
          </header>
          {props.children}
        </main>
      </div>
    </Tooltip.Provider>
  );
}

function CommandRail({
  user,
  conversations,
  activeConversationId,
  currentRoute,
  onNewConversation,
  onSelectConversation,
  onDeleteConversation,
  onNavigate,
  onLogout
}: AppShellProps) {
  const [query, setQuery] = useState("");
  const filtered = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) {
      return conversations;
    }
    return conversations.filter((conversation) => conversation.title.toLowerCase().includes(text));
  }, [conversations, query]);

  return (
    <aside className="sidebar">
      <div className="brand-row">
        <div className="brand-icon">
          <DatabaseZap size={22} />
        </div>
        <div>
          <strong>OceanBase SQL</strong>
          <span>Diagnostic Command Center</span>
        </div>
      </div>

      <div className="quick-panel">
        <div className="panel-heading">
          <span>工作入口</span>
          <em>Evidence-first</em>
        </div>
        <button className="new-chat-card" onClick={onNewConversation}>
          <span className="plus">
            <MessageSquarePlus size={18} />
          </span>
          <span>
            <strong>新建调优</strong>
            <small>SQL、结构、索引与 EXPLAIN</small>
          </span>
        </button>
        {user.role === "ADMIN" && (
          <div className="admin-shortcuts">
            <button className={currentRoute.startsWith("/admin/model") ? "active" : ""} onClick={() => onNavigate("/admin/model")}>
              <Bot size={15} />
              模型
            </button>
            <button className={currentRoute.startsWith("/admin/skills") ? "active" : ""} onClick={() => onNavigate("/admin/skills")}>
              <Settings size={15} />
              技能
            </button>
            <button className={currentRoute.startsWith("/admin/rules") ? "active" : ""} onClick={() => onNavigate("/admin/rules")}>
              <ShieldCheck size={15} />
              规则
            </button>
          </div>
        )}
      </div>

      <label className="search-card">
        <span>会话检索</span>
        <div className="search-input">
          <Search size={15} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索会话标题" />
        </div>
      </label>

      <div className="conversation-list">
        <span className="section-label">{query ? "匹配结果" : "最近会话"}</span>
        {filtered.map((conversation) => (
          <div key={conversation.id} className={conversation.id === activeConversationId ? "conversation-row active" : "conversation-row"}>
            <button className="conversation" onClick={() => onSelectConversation(conversation.id)}>
              <strong>{conversation.title}</strong>
              <span>{formatTime(conversation.updatedAt)}</span>
            </button>
            <button className="conversation-delete" aria-label={`删除 ${conversation.title}`} onClick={() => onDeleteConversation(conversation.id)}>
              <Trash2 size={14} />
            </button>
          </div>
        ))}
        {filtered.length === 0 && <p className="empty-note">{query ? "没有匹配的会话" : "还没有调优记录"}</p>}
      </div>

      <div className="sidebar-footer">
        <div className="avatar">{user.displayName.slice(0, 1)}</div>
        <div>
          <strong>{user.displayName}</strong>
          <span>{user.role}</span>
        </div>
        <button className="icon-button" aria-label="退出登录" onClick={onLogout}>
          <LogOut size={16} />
        </button>
      </div>
    </aside>
  );
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}
