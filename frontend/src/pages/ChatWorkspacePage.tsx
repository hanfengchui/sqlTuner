import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";
import { ConversationStream } from "../components/ConversationStream";
import { SqlInputPanel, type SqlInputValue } from "../components/SqlInputPanel";
import { useTaskUpdates } from "../hooks/useTaskUpdates";
import type { ConversationTimeline, Message, SqlTuningTask } from "../types/api";
import styles from "./ChatWorkspacePage.module.css";

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
  const [nextBefore, setNextBefore] = useState<number | undefined>();
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [error, setError] = useState("");

  const applyTimeline = useCallback((timeline: ConversationTimeline, prepend = false) => {
    const nextMessages = timeline.items.map((item) => item.message);
    const nextTasks = timeline.items.reduce<Record<number, SqlTuningTask>>((current, item) => {
      if (item.task) {
        current[item.task.id] = item.task;
      }
      return current;
    }, {});
    setMessages((current) => prepend ? mergeMessages(nextMessages, current) : nextMessages);
    setTasksById((current) => prepend ? { ...current, ...nextTasks } : nextTasks);
    setNextBefore(timeline.nextBefore);
    setHasMoreHistory(timeline.hasMore);
  }, []);

  const reloadTimeline = useCallback(async (conversationId: number) => {
    const timeline = await api.timeline(conversationId);
    applyTimeline(timeline);
  }, [applyTimeline]);

  const { task, error: taskError, modelStream } = useTaskUpdates(taskSeed?.id, {
    initialTask: taskSeed,
    onTerminal: (terminalTask) => {
      if (terminalTask.conversationId !== activeConversationId) {
        return;
      }
      reloadTimeline(terminalTask.conversationId)
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
        setNextBefore(undefined);
        setHasMoreHistory(false);
        return;
      }
      try {
        const timeline = await api.timeline(activeConversationId);
        if (!alive) {
          return;
        }
        applyTimeline(timeline);
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
  }, [activeConversationId, applyTimeline]);

  async function loadEarlierHistory() {
    if (!activeConversationId || !nextBefore || loadingHistory) {
      return;
    }
    setLoadingHistory(true);
    try {
      const timeline = await api.timeline(activeConversationId, nextBefore);
      applyTimeline(timeline, true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "更早会话加载失败");
    } finally {
      setLoadingHistory(false);
    }
  }

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
      await reloadTimeline(created.conversationId);
      onTaskCreated(created);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "提交失败");
      throw cause;
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={`${styles.chatWorkspace} workspace chat-workspace`}>
      <div className="chat-column">
        <ConversationStream
          messages={messages}
          tasksById={tasksById}
          pendingTask={task}
          pendingStream={modelStream}
          hasMoreHistory={hasMoreHistory}
          loadingHistory={loadingHistory}
          onLoadMoreHistory={loadEarlierHistory}
        />
        {(error || taskError) && <div className="form-error chat-error">{error || taskError}</div>}
        <div className="composer-shell">
          <SqlInputPanel loading={submitting} onSubmit={submit} allowImageOnly={Boolean(activeConversationId)} />
        </div>
      </div>
    </div>
  );
}

function mergeMessages(older: Message[], current: Message[]) {
  const seen = new Set<number>();
  return [...older, ...current].filter((message) => {
    if (seen.has(message.id)) {
      return false;
    }
    seen.add(message.id);
    return true;
  });
}
