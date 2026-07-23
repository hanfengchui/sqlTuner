import { Bot } from "lucide-react";
import { useCallback, useEffect, useRef } from "react";
import { TaskProgressMessage, TuningAdviceMessage } from "./TuningAdviceMessage";
import type { Message, ModelStreamUpdate, SqlTuningTask } from "../types/api";
import { formatRecognizedEvidence } from "../lib/recognizedEvidence";

interface ConversationStreamProps {
  messages: Message[];
  tasksById: Record<number, SqlTuningTask>;
  pendingTask?: SqlTuningTask;
  pendingStream?: ModelStreamUpdate;
  hasMoreHistory?: boolean;
  loadingHistory?: boolean;
  onLoadMoreHistory?: () => void;
}

export function ConversationStream({
  messages,
  tasksById,
  pendingTask,
  pendingStream,
  hasMoreHistory = false,
  loadingHistory = false,
  onLoadMoreHistory
}: ConversationStreamProps) {
  const streamRef = useRef<HTMLElement>(null);
  const stickToLatestRef = useRef(true);
  const hasAssistantForPending = pendingTask
    ? messages.some((message) => message.taskId === pendingTask.id && message.role === "ASSISTANT")
    : false;

  const scrollToLatest = useCallback(() => {
    const stream = streamRef.current;
    if (stream) {
      stream.scrollTop = stream.scrollHeight;
    }
  }, []);

  const followProgressiveContent = useCallback(() => {
    if (stickToLatestRef.current) {
      window.requestAnimationFrame(scrollToLatest);
    }
  }, [scrollToLatest]);

  useEffect(() => {
    stickToLatestRef.current = true;
    scrollToLatest();
  }, [messages.length, pendingTask?.id, pendingTask?.status, pendingTask?.version, scrollToLatest]);

  useEffect(() => {
    if (stickToLatestRef.current) {
      window.requestAnimationFrame(scrollToLatest);
    }
  }, [pendingStream?.sequence, scrollToLatest]);

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
    <section
      ref={streamRef}
      className="conversation-stream chat-conversation"
      onScroll={(event) => {
        const stream = event.currentTarget;
        stickToLatestRef.current = stream.scrollHeight - stream.scrollTop - stream.clientHeight < 72;
      }}
    >
      {hasMoreHistory && (
        <div className="conversation-history-control">
          <button type="button" onClick={onLoadMoreHistory} disabled={loadingHistory}>
            {loadingHistory ? "正在加载更早消息" : "加载更早消息"}
          </button>
        </div>
      )}
      {messages.map((message) => (
        <ConversationBubble
          key={message.id}
          message={message}
          task={message.taskId ? tasksById[message.taskId] : undefined}
          onContentChange={followProgressiveContent}
        />
      ))}
      {pendingTask && !hasAssistantForPending && (
        <article className="message-row assistant">
          <div className="assistant-message-body">
            {pendingTask.result
              ? <TuningAdviceMessage task={pendingTask} onContentChange={followProgressiveContent} />
              : <TaskProgressMessage task={pendingTask} stream={pendingStream} />}
          </div>
        </article>
      )}
    </section>
  );
}

function ConversationBubble({
  message,
  task,
  onContentChange
}: {
  message: Message;
  task?: SqlTuningTask;
  onContentChange: () => void;
}) {
  const isUser = message.role === "USER";
  const recognizedEvidence = isUser ? formatRecognizedEvidence(task) : "";

  if (!isUser) {
    return (
      <article className="message-row assistant">
        <div className="assistant-message-body">
          {task?.result ? <TuningAdviceMessage task={task} onContentChange={onContentChange} /> : task ? <TaskProgressMessage task={task} /> : <AssistantTextMessage message={message} />}
        </div>
      </article>
    );
  }

  return (
    <article className="message-row user" aria-label={`你的消息，${formatTime(message.createdAt)}`}>
      <div className="message-card user-message-card">
        {looksLikeSqlOrReport(message.content) ? <pre className="user-sql-message">{message.content}</pre> : <p>{message.content}</p>}
        {recognizedEvidence && (
          <p className="recognized-evidence">
            <strong>已识别证据：</strong>
            <span>{recognizedEvidence}</span>
          </p>
        )}
      </div>
    </article>
  );
}

function AssistantTextMessage({ message }: { message: Message }) {
  return (
    <article className="assistant-text-message" aria-label={`SQL 调优助手，${formatTime(message.createdAt)}`}>
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
