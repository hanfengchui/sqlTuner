import { useEffect, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { AppShell } from "../components/AppShell";
import { useTheme } from "../lib/useTheme";
import type { Conversation, SqlTuningTask, UserView } from "../types/api";
import { ChatWorkspacePage } from "./ChatWorkspacePage";
import { LoginPage } from "./LoginPage";
import { ModelConfigPage } from "./ModelConfigPage";
import { RuleAdminPage } from "./RuleAdminPage";
import { SkillAdminPage } from "./SkillAdminPage";
import { TaskDetailPage } from "./TaskDetailPage";

export function App() {
  const [user, setUser] = useState<UserView | null>(null);
  const [loadingUser, setLoadingUser] = useState(true);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConversationId, setActiveConversationId] = useState<number | undefined>();
  const [activeTask, setActiveTask] = useState<SqlTuningTask | undefined>();
  const [returnTo, setReturnTo] = useState("/chat");
  const { theme, toggle: toggleTheme } = useTheme();
  const navigate = useNavigate();
  const location = useLocation();

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
      refreshConversations();
    }
  }, [user]);

  async function refreshConversations() {
    const list = await api.conversations();
    setConversations(list);
    if (!activeConversationId && list.length > 0) {
      setActiveConversationId(list[0].id);
    }
  }

  async function newConversation() {
    const conversation = await api.createConversation("新建调优");
    await refreshConversations();
    setActiveConversationId(conversation.id);
    setActiveTask(undefined);
    navigate("/chat");
  }

  async function deleteConversation(id: number) {
    await api.deleteConversation(id);
    const list = await api.conversations();
    setConversations(list);
    if (activeConversationId === id) {
      setActiveTask(undefined);
      setActiveConversationId(list[0]?.id);
      navigate("/chat");
    }
  }

  async function logout() {
    await api.logout();
    setUser(null);
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
                refreshConversations();
              }}
            />
          }
        />
        <Route path="/tasks/:taskId" element={<TaskDetailPage />} />
        <Route path="/admin/skills" element={<SkillAdminPage />} />
        <Route path="/admin/model" element={<ModelConfigPage />} />
        <Route path="/admin/rules" element={<RuleAdminPage />} />
        <Route path="*" element={<Navigate to="/chat" replace />} />
      </Routes>
    </AppShell>
  );
}
