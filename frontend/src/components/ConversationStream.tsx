import { Bot, UserRound } from "lucide-react";
import { useEffect, useRef } from "react";
import { TaskProgressMessage, TuningAdviceMessage } from "./TuningAdviceMessage";
import type { Message, SqlTuningTask } from "../types/api";

interface ConversationStreamProps {
  messages: Message[];
  tasksById: Record<number, SqlTuningTask>;
  pendingTask?: SqlTuningTask;
}

export function ConversationStream({ messages, tasksById, pendingTask }: ConversationStreamProps) {
  const streamRef = useRef<HTMLElement>(null);
  const hasAssistantForPending = pendingTask
    ? messages.some((message) => message.taskId === pendingTask.id && message.role === "ASSISTANT")
    : false;

  useEffect(() => {
    const stream = streamRef.current;
    if (stream) {
      stream.scrollTop = stream.scrollHeight;
    }
  }, [messages.length, pendingTask?.id, pendingTask?.status, pendingTask?.version]);

  if (messages.length === 0 && !pendingTask) {
    return (
      <section className="conversation-empty">
        <Bot size={24} />
        <strong>从一条 SQL 开始</strong>
        <span>也可以直接粘贴完整巡检报告。</span>
      </section>
    );
  }

  return (
    <section ref={streamRef} className="conversation-stream chat-conversation">
      {messages.map((message) => (
        <ConversationBubble
          key={message.id}
          message={message}
          task={message.taskId ? tasksById[message.taskId] : undefined}
        />
      ))}
      {pendingTask && !hasAssistantForPending && (
        <article className="message-row assistant">
          <div className="message-avatar"><Bot size={18} /></div>
          <div className="assistant-message-body">
            {pendingTask.result ? <TuningAdviceMessage task={pendingTask} /> : <TaskProgressMessage task={pendingTask} />}
          </div>
        </article>
      )}
    </section>
  );
}

function ConversationBubble({ message, task }: { message: Message; task?: SqlTuningTask }) {
  const isUser = message.role === "USER";

  if (!isUser) {
    return (
      <article className="message-row assistant">
        <div className="message-avatar"><Bot size={18} /></div>
        <div className="assistant-message-body">
          {task?.result ? <TuningAdviceMessage task={task} /> : task ? <TaskProgressMessage task={task} /> : <AssistantTextMessage message={message} />}
        </div>
      </article>
    );
  }

  return (
    <article className="message-row user">
      <div className="message-avatar"><UserRound size={17} /></div>
      <div className="message-card user-message-card">
        <div className="message-meta">
          <strong>你</strong>
          <span>{formatTime(message.createdAt)}</span>
        </div>
        {looksLikeSqlOrReport(message.content) ? <pre className="user-sql-message">{message.content}</pre> : <p>{message.content}</p>}
      </div>
    </article>
  );
}

function AssistantTextMessage({ message }: { message: Message }) {
  return (
    <article className="assistant-text-message">
      <div className="assistant-message-header">
        <div>
          <Bot size={17} />
          <strong>SQL 调优助手</strong>
        </div>
        <span>{formatTime(message.createdAt)}</span>
      </div>
      <p>{message.content}</p>
    </article>
  );
}

function looksLikeSqlOrReport(value: string) {
  return /^(select|insert|update|delete|with|sql\s*(id)?\s*[:：])/i.test(value.trim()) || value.includes("\n");
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
