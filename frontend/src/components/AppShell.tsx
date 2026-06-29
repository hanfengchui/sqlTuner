import { BarChart3, Bot, DatabaseZap, LogOut, MessageSquarePlus, Moon, Search, Settings, ShieldCheck, Sun, Trash2 } from "lucide-react";
import type { Conversation, UserView } from "../types/api";
import type { Theme } from "../lib/useTheme";

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

export function AppShell({
  user,
  conversations,
  activeConversationId,
  currentRoute,
  theme,
  onToggleTheme,
  onNewConversation,
  onSelectConversation,
  onDeleteConversation,
  onNavigate,
  onLogout,
  children
}: AppShellProps) {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-row">
          <div className="brand-icon">
            <DatabaseZap size={22} />
          </div>
          <div>
            <strong>SQL 调优助手</strong>
            <span>Slow SQL Copilot</span>
          </div>
        </div>

        <div className="quick-panel">
          <div className="panel-heading">
            <span>工作入口</span>
            <em>Harness</em>
          </div>
          <button className="new-chat-card" onClick={onNewConversation}>
            <span className="plus">
              <MessageSquarePlus size={18} />
            </span>
            <span>
              <strong>新建调优</strong>
              <small>粘贴 SQL 与执行计划</small>
            </span>
          </button>
          {user.role === "ADMIN" && (
            <button className="admin-link" onClick={() => onNavigate("/admin/skills")}>
              <Settings size={15} />
              管理后台
            </button>
          )}
        </div>

        <div className="search-card">
          <span>会话检索</span>
          <div className="search-input">
            <Search size={15} />
            <input placeholder="搜索 SQL、系统、问题..." />
          </div>
        </div>

        <div className="conversation-list">
          <span className="section-label">更早</span>
          {conversations.map((conversation) => (
            <div key={conversation.id} className={conversation.id === activeConversationId ? "conversation-row active" : "conversation-row"}>
              <button className="conversation" onClick={() => onSelectConversation(conversation.id)}>
                {conversation.title}
              </button>
              <button className="conversation-delete" title="删除会话" onClick={() => onDeleteConversation(conversation.id)}>
                <Trash2 size={14} />
              </button>
            </div>
          ))}
          {conversations.length === 0 && <p className="empty-note">还没有调优记录</p>}
        </div>

        <div className="sidebar-footer">
          <div className="avatar">{user.displayName.slice(0, 1)}</div>
          <div>
            <strong>{user.displayName}</strong>
            <span>{user.role}</span>
          </div>
          <button className="icon-button" title="退出登录" onClick={onLogout}>
            <LogOut size={16} />
          </button>
        </div>
      </aside>

      <main className="main-stage">
        <header className="topbar">
          <button className={currentRoute === "/chat" ? "topbar-primary active" : "topbar-primary"} onClick={() => onNavigate("/chat")}>
            <MessageSquarePlus size={16} />
            调优工作台
          </button>
          <div className="topbar-actions">
            <button className={currentRoute.startsWith("/admin/rules") ? "active" : ""} onClick={() => onNavigate("/admin/rules")}>
              <ShieldCheck size={16} />
              规则
            </button>
            <button className={currentRoute.startsWith("/admin/model") ? "active" : ""} onClick={() => onNavigate("/admin/model")}>
              <Bot size={16} />
              模型
            </button>
            <button>
              <BarChart3 size={16} />
              案例库
            </button>
            <button
              className="theme-toggle"
              title={theme === "dark" ? "切换到浅色" : "切换到深色"}
              aria-label={theme === "dark" ? "切换到浅色" : "切换到深色"}
              onClick={onToggleTheme}
            >
              {theme === "dark" ? <Sun size={16} /> : <Moon size={16} />}
            </button>
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}
