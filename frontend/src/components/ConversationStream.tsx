import { Bot, FileSearch, Loader2, UserRound } from "lucide-react";
import { useEffect, useRef } from "react";
import type { Message, SqlTuningTask } from "../types/api";

interface ConversationStreamProps {
  messages: Message[];
  tasksById: Record<number, SqlTuningTask>;
  pendingTask?: SqlTuningTask;
  onInspectTask: (taskId: number) => void;
}

export function ConversationStream({ messages, tasksById, pendingTask, onInspectTask }: ConversationStreamProps) {
  const streamRef = useRef<HTMLElement>(null);
  const hasAssistantForPending = pendingTask
    ? messages.some((message) => message.taskId === pendingTask.id && message.role === "ASSISTANT")
    : false;

  useEffect(() => {
    const stream = streamRef.current;
    if (stream) {
      stream.scrollTop = stream.scrollHeight;
    }
  }, [messages.length, pendingTask?.id, pendingTask?.status]);

  if (messages.length === 0 && !pendingTask) {
    return (
      <section className="conversation-empty">
        <Bot size={28} />
        <strong>直接开始一次调优对话</strong>
        <span>可以先贴慢 SQL，也可以用自然语言描述现象；后续继续补充表结构、EXPLAIN、索引或业务约束。</span>
      </section>
    );
  }

  return (
    <section ref={streamRef} className="conversation-stream">
      {messages.map((message) => (
        <ConversationBubble
          key={message.id}
          message={message}
          task={message.taskId ? tasksById[message.taskId] : undefined}
          onInspectTask={onInspectTask}
        />
      ))}
      {pendingTask && !hasAssistantForPending && (
        <article className={isTerminal(pendingTask) ? "message-row assistant" : "message-row assistant pending"}>
          <div className="message-avatar">
            {isTerminal(pendingTask) ? <Bot size={18} /> : <Loader2 className="spin" size={18} />}
          </div>
          <div className="message-card">
            <div className="message-meta">
              <strong>SQL 调优助手</strong>
              <span>{pendingTask.statusMessage}</span>
            </div>
            <p>{assistantFallbackText(pendingTask)}</p>
            <TaskMiniCard task={pendingTask} onInspectTask={onInspectTask} />
          </div>
        </article>
      )}
    </section>
  );
}

function isTerminal(task: SqlTuningTask) {
  return task.status === "DONE" || task.status === "FAILED";
}

function ConversationBubble({ message, task, onInspectTask }: { message: Message; task?: SqlTuningTask; onInspectTask: (taskId: number) => void }) {
  const isUser = message.role === "USER";
  return (
    <article className={isUser ? "message-row user" : "message-row assistant"}>
      <div className="message-avatar">{isUser ? <UserRound size={18} /> : <Bot size={18} />}</div>
      <div className="message-card">
        <div className="message-meta">
          <strong>{isUser ? "你" : "SQL 调优助手"}</strong>
          <span>{formatTime(message.createdAt)}</span>
        </div>
        <p>{message.content}</p>
        {!isUser && task && <TaskMiniCard task={task} onInspectTask={onInspectTask} />}
      </div>
    </article>
  );
}

function TaskMiniCard({ task, onInspectTask }: { task: SqlTuningTask; onInspectTask: (taskId: number) => void }) {
  const result = task.result;
  return (
    <div className="task-mini-card">
      <div>
        <FileSearch size={16} />
        <strong>{task.status === "DONE" ? "SQL 调优报告已生成" : task.statusMessage}</strong>
      </div>
      <div className="task-mini-stats">
        <span>{task.dbDialect?.replace("OceanBase ", "OB ") || "OB MySQL"}</span>
        <span>{task.inputType === "natural_language" ? "自然语言" : "SQL"}</span>
        <span>{task.ruleFindings.length} 条规则</span>
        <span>{result?.indexSuggestions.length || 0} 个索引建议</span>
        <span>{result?.mockModel ? "mock" : result ? "real" : "分析中"}</span>
      </div>
      {result?.needMoreInfo.length ? <p>待补充：{result.needMoreInfo.slice(0, 3).join("、")}</p> : null}
      <button onClick={() => onInspectTask(task.id)}>打开右侧报告</button>
    </div>
  );
}

function assistantFallbackText(task: SqlTuningTask) {
  if (task.status === "DONE") {
    return task.result?.summary || "本轮分析已完成，右侧报告可以查看细节。";
  }
  if (task.status === "FAILED") {
    return "本轮分析失败，可以补充上下文后重新发送。";
  }
  return "我正在把本轮输入、会话上下文、规则命中和模型建议组合成可追溯的调优结论。";
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
