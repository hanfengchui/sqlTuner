import { useEffect, useRef, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { AdminRoute } from "../components/AdminRoute";
import { AppShell } from "../components/AppShell";
import { useTheme } from "../lib/useTheme";
import type { Conversation, SqlTuningTask, UserView } from "../types/api";
import { ChatWorkspacePage } from "./ChatWorkspacePage";
import { LoginPage } from "./LoginPage";
import { ModelConfigPage } from "./ModelConfigPage";
import { RuleAdminPage } from "./RuleAdminPage";
import { SkillAdminPage } from "./SkillAdminPage";

export function App() {
  const [user, setUser] = useState<UserView | null>(null);
  const [loadingUser, setLoadingUser] = useState(true);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationQuery, setConversationQuery] = useState("");
  const [nextConversationBefore, setNextConversationBefore] = useState<number | undefined>();
  const [hasMoreConversations, setHasMoreConversations] = useState(false);
  const [loadingMoreConversations, setLoadingMoreConversations] = useState(false);
  const [activeConversationId, setActiveConversationId] = useState<number | undefined>();
  const [activeTask, setActiveTask] = useState<SqlTuningTask | undefined>();
  const [returnTo, setReturnTo] = useState("/chat");
  const { theme, toggle: toggleTheme } = useTheme();
  const navigate = useNavigate();
  const location = useLocation();
  const conversationRequestVersion = useRef(0);

  useEffect(() => {
    let alive = true;
    api
      .me()
      .then((nextUser) => {
        if (alive) {
          setUser(nextUser);
        }
      })
      .finally(() => {
        if (alive) {
          setLoadingUser(false);
        }
      });
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    if (!loadingUser && !user && location.pathname !== "/login") {
      setReturnTo(location.pathname === "/" ? "/chat" : location.pathname);
      navigate("/login", { replace: true });
    }
  }, [loadingUser, user, location.pathname, navigate]);

  useEffect(() => {
    if (user) {
      void refreshConversations("");
    }
  }, [user]);

  async function refreshConversations(search = conversationQuery) {
    const requestVersion = ++conversationRequestVersion.current;
    const page = await api.conversationPage(search);
    if (requestVersion !== conversationRequestVersion.current) {
      return page.items;
    }
    setConversations(page.items);
    setNextConversationBefore(page.nextBefore);
    setHasMoreConversations(page.hasMore);
    if (!activeConversationId && page.items.length > 0) {
      setActiveConversationId(page.items[0].id);
    }
    return page.items;
  }

  async function loadMoreConversations() {
    if (!hasMoreConversations || !nextConversationBefore || loadingMoreConversations) {
      return;
    }
    setLoadingMoreConversations(true);
    try {
      const page = await api.conversationPage(conversationQuery, nextConversationBefore);
      setConversations((current) => mergeConversations(current, page.items));
      setNextConversationBefore(page.nextBefore);
      setHasMoreConversations(page.hasMore);
    } finally {
      setLoadingMoreConversations(false);
    }
  }

  function searchConversations(query: string) {
    setConversationQuery(query);
    void refreshConversations(query);
  }

  async function newConversation() {
    const conversation = await api.createConversation("新建调优");
    setConversationQuery("");
    await refreshConversations("");
    setActiveConversationId(conversation.id);
    setActiveTask(undefined);
    navigate("/chat");
  }

  async function deleteConversation(id: number) {
    await api.deleteConversation(id);
    const list = await refreshConversations(conversationQuery);
    if (activeConversationId === id) {
      setActiveTask(undefined);
      setActiveConversationId(list[0]?.id);
      navigate("/chat");
    }
  }

  async function logout() {
    await api.logout();
    setUser(null);
    setConversationQuery("");
    setActiveConversationId(undefined);
    setActiveTask(undefined);
    navigate("/login");
  }

  if (loadingUser) {
    return <div className="loading-screen">正在进入 OceanBase SQL 诊断工作台...</div>;
  }

  if (!user || location.pathname === "/login") {
    return (
      <LoginPage
        onLogin={(nextUser) => {
          setUser(nextUser);
          navigate(returnTo || "/chat", { replace: true });
        }}
      />
    );
  }

  return (
    <AppShell
      user={user}
      conversations={conversations}
      conversationQuery={conversationQuery}
      hasMoreConversations={hasMoreConversations}
      loadingMoreConversations={loadingMoreConversations}
      activeConversationId={activeConversationId}
      currentRoute={location.pathname}
      theme={theme}
      onToggleTheme={toggleTheme}
      onNewConversation={newConversation}
      onSelectConversation={(id) => {
        setActiveConversationId(id);
        setActiveTask(undefined);
        navigate("/chat");
      }}
      onDeleteConversation={deleteConversation}
      onSearchConversations={searchConversations}
      onLoadMoreConversations={loadMoreConversations}
      onNavigate={navigate}
      onLogout={logout}
    >
      <Routes>
        <Route path="/" element={<Navigate to="/chat" replace />} />
        <Route
          path="/chat"
          element={
            <ChatWorkspacePage
              activeConversationId={activeConversationId}
              activeTask={activeTask}
              onTaskCreated={(task) => {
                setActiveTask(task);
                setActiveConversationId(task.conversationId);
                void refreshConversations();
              }}
            />
          }
        />
        <Route
          path="/admin/skills"
          element={
            <AdminRoute user={user}>
              <SkillAdminPage />
            </AdminRoute>
          }
        />
        <Route
          path="/admin/model"
          element={
            <AdminRoute user={user}>
              <ModelConfigPage />
            </AdminRoute>
          }
        />
        <Route
          path="/admin/rules"
          element={
            <AdminRoute user={user}>
              <RuleAdminPage />
            </AdminRoute>
          }
        />
        <Route path="*" element={<Navigate to="/chat" replace />} />
      </Routes>
    </AppShell>
  );
}

function mergeConversations(current: Conversation[], incoming: Conversation[]) {
  const known = new Set(current.map((conversation) => conversation.id));
  return [...current, ...incoming.filter((conversation) => !known.has(conversation.id))];
}
