import { useEffect, useRef, useState } from "react";
import { ExternalLink, X } from "lucide-react";
import { api } from "../api/client";
import { ConversationStream } from "../components/ConversationStream";
import { ResultTabs } from "../components/ResultTabs";
import { SqlInputPanel, type SqlInputValue } from "../components/SqlInputPanel";
import { useTaskUpdates } from "../hooks/useTaskUpdates";
import type { Message, SqlTuningTask } from "../types/api";

interface ChatWorkspacePageProps {
  activeConversationId?: number;
  activeTask?: SqlTuningTask;
  onTaskCreated: (task: SqlTuningTask) => void;
  onOpenTask: (taskId: number) => void;
}

export function ChatWorkspacePage({ activeConversationId, activeTask, onTaskCreated, onOpenTask }: ChatWorkspacePageProps) {
  const [submitting, setSubmitting] = useState(false);
  const [taskSeed, setTaskSeed] = useState<SqlTuningTask | undefined>(activeTask);
  const [messages, setMessages] = useState<Message[]>([]);
  const [tasksById, setTasksById] = useState<Record<number, SqlTuningTask>>({});
  const [inspectorTaskId, setInspectorTaskId] = useState<number | undefined>();
  const [error, setError] = useState("");
  const inspectorBodyRef = useRef<HTMLDivElement>(null);
  const { task, error: taskError } = useTaskUpdates(taskSeed?.id, {
    initialTask: taskSeed,
    onTerminal: (terminalTask) => {
      api.messages(terminalTask.conversationId)
        .then(setMessages)
        .catch((cause) => setError(cause instanceof Error ? cause.message : "会话刷新失败"));
    }
  });

  const inspectorTask = inspectorTaskId ? tasksById[inspectorTaskId] : undefined;

  useEffect(() => {
    inspectorBodyRef.current?.scrollTo({ top: 0 });
  }, [inspectorTaskId, inspectorTask?.status]);

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
      } catch (e) {
        if (alive) {
          setError(e instanceof Error ? e.message : "会话加载失败");
        }
      }
    }
    loadMessages();
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
        schemaText: value.schemaText,
        indexText: value.indexText,
        explainText: value.explainText,
        businessContext: value.businessContext,
        obVersion: value.obVersion,
        tableStatsText: value.tableStatsText,
        runtimeMetricsText: value.runtimeMetricsText,
        businessInvariants: value.businessInvariants,
        allowedActions: value.allowedActions,
        planImages: value.planImages,
        deepAnalysis: value.deepAnalysis
      }, crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`);
      setTaskSeed(created);
      setTasksById((current) => ({ ...current, [created.id]: created }));
      setInspectorTaskId(created.id);
      const nextMessages = await api.messages(created.conversationId);
      setMessages(nextMessages);
      onTaskCreated(created);
    } catch (e) {
      setError(e instanceof Error ? e.message : "提交失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={inspectorTaskId ? "workspace workbench chat-inspector-open" : "workspace workbench"}>
      <div className="chat-workbench-grid">
        <div className="chat-column">
          <ConversationStream
            messages={messages}
            tasksById={tasksById}
            pendingTask={task}
            onInspectTask={(taskId) => setInspectorTaskId(taskId)}
          />
          {(error || taskError) && <div className="form-error wide">{error || taskError}</div>}
          <div className="composer-shell">
            <SqlInputPanel compact loading={submitting} onSubmit={submit} />
          </div>
        </div>
        {inspectorTaskId && (
          <aside className="report-inspector" aria-label="SQL 调优报告侧栏">
            <div className="report-inspector-head">
              <div>
                <span>SQL Report</span>
                <strong>{inspectorTask ? `任务 #${inspectorTask.id} · ${inspectorTask.status}` : "报告加载中"}</strong>
                {inspectorTask?.queuePosition !== undefined && <small>队列位置 {inspectorTask.queuePosition}</small>}
              </div>
              <div className="report-inspector-actions">
                {inspectorTask && (
                  <button onClick={() => onOpenTask(inspectorTask.id)} title="打开完整报告页">
                    <ExternalLink size={16} />
                  </button>
                )}
                <button onClick={() => setInspectorTaskId(undefined)} title="关闭报告侧栏">
                  <X size={16} />
                </button>
              </div>
            </div>
            <div ref={inspectorBodyRef} className="report-inspector-body">
              <ResultTabs task={inspectorTask} />
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}
