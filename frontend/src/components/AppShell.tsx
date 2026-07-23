import * as Tooltip from "@radix-ui/react-tooltip";
import {
  ArrowLeft,
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
import { useEffect, useMemo, useRef, useState } from "react";
import type { Theme } from "../lib/useTheme";
import type { Conversation, UserView } from "../types/api";
import styles from "./AppShell.module.css";

interface AppShellProps {
  user: UserView;
  conversations: Conversation[];
  conversationQuery?: string;
  hasMoreConversations?: boolean;
  loadingMoreConversations?: boolean;
  activeConversationId?: number;
  currentRoute: string;
  theme: Theme;
  onToggleTheme: () => void;
  onNewConversation: () => void;
  onSelectConversation: (id: number) => void;
  onDeleteConversation: (id: number) => void;
  onSearchConversations?: (query: string) => void;
  onLoadMoreConversations?: () => void;
  onNavigate: (route: string) => void;
  onLogout: () => void;
  children: React.ReactNode;
}

export function AppShell(props: AppShellProps) {
  const activeTitle = props.conversations.find((conversation) => conversation.id === props.activeConversationId)?.title;
  const adminRoute = props.currentRoute.startsWith("/admin/");

  return (
    <Tooltip.Provider delayDuration={250}>
      <div className={`${styles.appShell} app-shell`}>
        <CommandRail {...props} />
        <main className="main-stage">
          <header className="topbar">
            <div className="topbar-context">
              {adminRoute && (
                <TopbarIcon label="返回对话" onClick={() => props.onNavigate("/chat")}>
                  <ArrowLeft size={17} />
                </TopbarIcon>
              )}
              <span className="workspace-title">{routeTitle(props.currentRoute, activeTitle)}</span>
            </div>
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
  theme,
  onToggleTheme,
  onNewConversation,
  onSelectConversation,
  onDeleteConversation,
  conversationQuery = "",
  hasMoreConversations = false,
  loadingMoreConversations = false,
  onSearchConversations,
  onLoadMoreConversations,
  onLogout
}: AppShellProps) {
  const [query, setQuery] = useState(conversationQuery);
  const [searchOpen, setSearchOpen] = useState(false);
  const searchInputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    setQuery(conversationQuery);
  }, [conversationQuery]);
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
        <div>
          <strong>SQL Tuner</strong>
          <span>OceanBase</span>
        </div>
        <button
          className={searchOpen ? "rail-search-button active" : "rail-search-button"}
          type="button"
          aria-label="搜索会话"
          aria-pressed={searchOpen}
          title="搜索会话"
          onClick={() => {
            if (searchOpen) {
              setSearchOpen(false);
              setQuery("");
              onSearchConversations?.("");
              return;
            }
            setSearchOpen(true);
            window.setTimeout(() => searchInputRef.current?.focus(), 0);
          }}
        >
          <Search size={16} />
        </button>
      </div>

      <button className="new-chat-card" onClick={onNewConversation}>
        <MessageSquarePlus size={17} />
        <span>新建调优</span>
      </button>

      {searchOpen && (
        <label className="search-card">
          <span className="sr-only">会话检索</span>
          <div className="search-input">
            <Search size={15} />
            <input
              ref={searchInputRef}
              value={query}
              onChange={(event) => {
                const nextQuery = event.target.value;
                setQuery(nextQuery);
                onSearchConversations?.(nextQuery);
              }}
              onKeyDown={(event) => {
                if (event.key === "Escape") {
                  setSearchOpen(false);
                  setQuery("");
                  onSearchConversations?.("");
                }
              }}
              placeholder="搜索会话标题"
            />
          </div>
        </label>
      )}

      <div className="conversation-list">
        <span className="section-label">{query ? "匹配会话" : "最近会话"}</span>
        {filtered.map((conversation) => (
          <div key={conversation.id} className={conversation.id === activeConversationId ? "conversation-row active" : "conversation-row"}>
            <button className="conversation" onClick={() => onSelectConversation(conversation.id)}>
              <strong>{conversation.title}</strong>
            </button>
            <button className="conversation-delete" aria-label={`删除 ${conversation.title}`} title="删除会话" onClick={() => onDeleteConversation(conversation.id)}>
              <Trash2 size={14} />
            </button>
          </div>
        ))}
        {hasMoreConversations && !query && (
          <button className="conversation-load-more" type="button" onClick={onLoadMoreConversations} disabled={loadingMoreConversations}>
            {loadingMoreConversations ? "正在加载" : "加载更多"}
          </button>
        )}
        {filtered.length === 0 && <p className="empty-note">{query ? "没有匹配的会话" : "还没有调优记录"}</p>}
      </div>

      <div className="sidebar-footer">
        <div className="avatar">{user.displayName.slice(0, 1)}</div>
        <div>
          <strong>{user.displayName}</strong>
          <span>{user.role}</span>
        </div>
        <div className="sidebar-footer-actions">
          <button className="icon-button" aria-label={theme === "dark" ? "切换到浅色" : "切换到深色"} title={theme === "dark" ? "切换到浅色" : "切换到深色"} onClick={onToggleTheme}>
            {theme === "dark" ? <Sun size={16} /> : <Moon size={16} />}
          </button>
          <button className="icon-button" aria-label="退出登录" title="退出登录" onClick={onLogout}>
            <LogOut size={16} />
          </button>
        </div>
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

function routeTitle(route: string, conversationTitle?: string) {
  if (route.startsWith("/admin/model")) {
    return "模型配置";
  }
  if (route.startsWith("/admin/skills")) {
    return "技能约束";
  }
  if (route.startsWith("/admin/rules")) {
    return "规则目录";
  }
  return conversationTitle || "新建调优";
}
