import { useEffect, useState } from "react";
import { api } from "../api/client";
import { ConversationStream } from "../components/ConversationStream";
import { SqlInputPanel, type SqlInputValue } from "../components/SqlInputPanel";
import { useTaskUpdates } from "../hooks/useTaskUpdates";
import type { Message, SqlTuningTask } from "../types/api";

interface ChatWorkspacePageProps {
  activeConversationId?: number;
  activeTask?: SqlTuningTask;
  onTaskCreated: (task: SqlTuningTask) => void;
}

export function ChatWorkspacePage({ activeConversationId, activeTask, onTaskCreated }: ChatWorkspacePageProps) {
  const [submitting, setSubmitting] = useState(false);
  const [taskSeed, setTaskSeed] = useState<SqlTuningTask | undefined>(activeTask);
  const [messages, setMessages] = useState<Message[]>([]);
  const [tasksById, setTasksById] = useState<Record<number, SqlTuningTask>>({});
  const [error, setError] = useState("");
  const { task, error: taskError, modelStream } = useTaskUpdates(taskSeed?.id, {
    initialTask: taskSeed,
    onTerminal: (terminalTask) => {
      api.messages(terminalTask.conversationId)
        .then(setMessages)
        .catch((cause) => setError(cause instanceof Error ? cause.message : "会话刷新失败"));
    }
  });

  useEffect(() => {
    setTaskSeed(activeTask);
  }, [activeTask]);

  useEffect(() => {
    if (task) {
      setTasksById((current) => ({ ...current, [task.id]: task }));
    }
  }, [task]);

  useEffect(() => {
    let alive = true;
    async function loadMessages() {
      if (!activeConversationId) {
        setMessages([]);
        setTasksById({});
        return;
      }
      try {
        const nextMessages = await api.messages(activeConversationId);
        if (!alive) {
          return;
        }
        setMessages(nextMessages);
        const taskIds = Array.from(new Set(nextMessages.map((message) => message.taskId).filter((id): id is number => Boolean(id))));
        const loadedTasks = await Promise.all(taskIds.map((id) => api.task(id).catch(() => undefined)));
        if (!alive) {
          return;
        }
        setTasksById(Object.fromEntries(loadedTasks.filter((item): item is SqlTuningTask => Boolean(item)).map((item) => [item.id, item])));
      } catch (cause) {
        if (alive) {
          setError(cause instanceof Error ? cause.message : "会话加载失败");
        }
      }
    }
    void loadMessages();
    return () => {
      alive = false;
    };
  }, [activeConversationId]);

  async function submit(value: SqlInputValue) {
    setSubmitting(true);
    setError("");
    try {
      const created = await api.createTask({
        conversationId: activeConversationId,
        dbDialect: value.dbDialect,
        sqlText: value.sqlText,
        inputType: value.inputType,
        planImages: value.planImages,
        deepAnalysis: value.deepAnalysis
      }, crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`);
      setTaskSeed(created);
      setTasksById((current) => ({ ...current, [created.id]: created }));
      const nextMessages = await api.messages(created.conversationId);
      setMessages(nextMessages);
      onTaskCreated(created);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "提交失败");
      throw cause;
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="workspace chat-workspace">
      <div className="chat-column">
        <ConversationStream messages={messages} tasksById={tasksById} pendingTask={task} pendingStream={modelStream} />
        {(error || taskError) && <div className="form-error chat-error">{error || taskError}</div>}
        <div className="composer-shell">
          <SqlInputPanel loading={submitting} onSubmit={submit} />
        </div>
      </div>
    </div>
  );
}
