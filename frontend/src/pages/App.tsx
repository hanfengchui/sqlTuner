import { useEffect, useMemo, useState } from "react";
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
  const [route, setRoute] = useState(window.location.pathname === "/" ? "/chat" : window.location.pathname);
  const [returnTo, setReturnTo] = useState(window.location.pathname === "/login" ? "/chat" : window.location.pathname === "/" ? "/chat" : window.location.pathname);
  const [user, setUser] = useState<UserView | null>(null);
  const [loadingUser, setLoadingUser] = useState(true);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConversationId, setActiveConversationId] = useState<number | undefined>();
  const [activeTask, setActiveTask] = useState<SqlTuningTask | undefined>();
  const { theme, toggle: toggleTheme } = useTheme();

  useEffect(() => {
    api
      .me()
      .then((nextUser) => setUser(nextUser))
      .finally(() => setLoadingUser(false));
  }, []);

  useEffect(() => {
    if (!loadingUser && !user && route !== "/login") {
      setReturnTo(route === "/" ? "/chat" : route);
      navigate("/login");
    }
  }, [loadingUser, user, route]);

  useEffect(() => {
    if (user) {
      refreshConversations();
    }
  }, [user]);

  const taskIdFromRoute = useMemo(() => {
    const match = route.match(/^\/tasks\/(\d+)/);
    return match ? Number(match[1]) : undefined;
  }, [route]);

  function navigate(nextRoute: string) {
    window.history.pushState(null, "", nextRoute);
    window.scrollTo({ left: 0, top: 0 });
    setRoute(nextRoute);
  }

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
    if (activeConversationId === id) {
      setActiveConversationId(undefined);
      setActiveTask(undefined);
      navigate("/chat");
    }
    const list = await api.conversations();
    setConversations(list);
    if (activeConversationId === id && list.length > 0) {
      setActiveConversationId(list[0].id);
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
    return <div className="loading-screen">正在进入 SQL 调优助手...</div>;
  }

  if (!user || route === "/login") {
    return <LoginPage onLogin={(nextUser) => {
      setUser(nextUser);
      navigate(returnTo || "/chat");
    }} />;
  }

  return (
    <AppShell
      user={user}
      conversations={conversations}
      activeConversationId={activeConversationId}
      currentRoute={route}
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
      {route.startsWith("/admin/skills") && <SkillAdminPage />}
      {route.startsWith("/admin/model") && <ModelConfigPage />}
      {route.startsWith("/admin/rules") && <RuleAdminPage />}
      {route.startsWith("/tasks/") && taskIdFromRoute && <TaskDetailPage taskId={taskIdFromRoute} />}
      {route === "/chat" && (
        <ChatWorkspacePage
          activeConversationId={activeConversationId}
          activeTask={activeTask}
          onOpenTask={(taskId) => navigate(`/tasks/${taskId}`)}
          onTaskCreated={(task) => {
            setActiveTask(task);
            setActiveConversationId(task.conversationId);
            refreshConversations();
          }}
        />
      )}
    </AppShell>
  );
}
