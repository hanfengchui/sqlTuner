import * as Tooltip from "@radix-ui/react-tooltip";
import {
  Bot,
  LogOut,
  MessageSquarePlus,
  Moon,
  Search,
  Settings,
  ShieldCheck,
  Sun,
  Trash2
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
  return (
    <Tooltip.Provider delayDuration={250}>
      <div className="app-shell">
        <CommandRail {...props} />
        <main className="main-stage">
          <header className="topbar">
            <button className="workspace-title" onClick={() => props.onNavigate("/chat")}>SQL 调优助手</button>
            <div className="topbar-actions">
              {props.user.role === "ADMIN" && (
                <>
                  <TopbarIcon label="规则" active={props.currentRoute.startsWith("/admin/rules")} onClick={() => props.onNavigate("/admin/rules")}>
                    <ShieldCheck size={17} />
                  </TopbarIcon>
                  <TopbarIcon label="模型" active={props.currentRoute.startsWith("/admin/model")} onClick={() => props.onNavigate("/admin/model")}>
                    <Bot size={17} />
                  </TopbarIcon>
                  <TopbarIcon label="技能" active={props.currentRoute.startsWith("/admin/skills")} onClick={() => props.onNavigate("/admin/skills")}>
                    <Settings size={17} />
                  </TopbarIcon>
                </>
              )}
              <TopbarIcon label={props.theme === "dark" ? "切换到浅色" : "切换到深色"} onClick={props.onToggleTheme}>
                {props.theme === "dark" ? <Sun size={17} /> : <Moon size={17} />}
              </TopbarIcon>
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
  onNewConversation,
  onSelectConversation,
  onDeleteConversation,
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
      <div className="rail-heading">
        <strong>SQL 调优助手</strong>
        <span>OceanBase</span>
      </div>

      <button className="new-chat-card" onClick={onNewConversation}>
        <MessageSquarePlus size={17} />
        <span>新建调优</span>
      </button>

      <label className="search-card">
        <span className="sr-only">会话检索</span>
        <div className="search-input">
          <Search size={15} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索会话标题" />
        </div>
      </label>

      <div className="conversation-list">
        <span className="section-label">{query ? "匹配会话" : "最近会话"}</span>
        {filtered.map((conversation) => (
          <div key={conversation.id} className={conversation.id === activeConversationId ? "conversation-row active" : "conversation-row"}>
            <button className="conversation" onClick={() => onSelectConversation(conversation.id)}>
              <strong>{conversation.title}</strong>
              <span>{formatTime(conversation.updatedAt)}</span>
            </button>
            <button className="conversation-delete" aria-label={`删除 ${conversation.title}`} title="删除会话" onClick={() => onDeleteConversation(conversation.id)}>
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
        <button className="icon-button" aria-label="退出登录" title="退出登录" onClick={onLogout}>
          <LogOut size={16} />
        </button>
      </div>
    </aside>
  );
}

function TopbarIcon({ label, active = false, onClick, children }: { label: string; active?: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <Tooltip.Root>
      <Tooltip.Trigger asChild>
        <button className={active ? "topbar-icon active" : "topbar-icon"} aria-label={label} onClick={onClick}>
          {children}
        </button>
      </Tooltip.Trigger>
      <Tooltip.Content className="tooltip" sideOffset={8}>{label}</Tooltip.Content>
    </Tooltip.Root>
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
