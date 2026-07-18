import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { HarnessArtifact, ModelStreamUpdate, SqlTuningTask } from "../types/api";

interface UseTaskUpdatesOptions {
  initialTask?: SqlTuningTask;
  onTerminal?: (task: SqlTuningTask) => void;
  pollIntervalMs?: number;
}

export function useTaskUpdates(taskId: number | undefined, options: UseTaskUpdatesOptions = {}) {
  const [task, setTask] = useState<SqlTuningTask | undefined>(options.initialTask);
  const [error, setError] = useState("");
  const [modelStream, setModelStream] = useState<ModelStreamUpdate | undefined>();
  const onTerminalRef = useRef(options.onTerminal);
  const currentVersionRef = useRef(options.initialTask?.version ?? -1);

  useEffect(() => {
    onTerminalRef.current = options.onTerminal;
  }, [options.onTerminal]);

  useEffect(() => {
    if (options.initialTask && options.initialTask.id === taskId) {
      currentVersionRef.current = options.initialTask.version ?? -1;
      setTask(options.initialTask);
    }
  }, [options.initialTask, taskId]);

  useEffect(() => {
    if (taskId === undefined || !Number.isFinite(taskId)) {
      setTask(undefined);
      setModelStream(undefined);
      setError("");
      return;
    }
    const activeTaskId = taskId;

    let alive = true;
    let streamOpen = false;
    let terminal = false;
    let terminalNotified = false;
    let pollTimer: number | undefined;
    let closeStream: () => void = () => undefined;
    const pollIntervalMs = options.pollIntervalMs ?? 2000;
    currentVersionRef.current = options.initialTask?.id === taskId ? options.initialTask.version ?? -1 : -1;
    let currentStreamSequence = -1;
    let currentStreamAttempt = options.initialTask?.id === taskId ? options.initialTask.attemptCount ?? -1 : -1;
    let taskAllowsModelStream = options.initialTask?.id === taskId
      ? !["QUEUED", "RECEIVED", "DONE", "FAILED"].includes(options.initialTask.status)
      : false;
    setModelStream(undefined);

    function clearPoll() {
      if (pollTimer !== undefined) {
        window.clearTimeout(pollTimer);
        pollTimer = undefined;
      }
    }

    function accept(next: SqlTuningTask) {
      if (!alive || next.id !== activeTaskId) {
        return;
      }
      const incomingVersion = next.version ?? 0;
      if (incomingVersion < currentVersionRef.current) {
        return;
      }
      currentVersionRef.current = incomingVersion;
      setTask(next);
      setError("");
      const incomingAttempt = next.attemptCount ?? currentStreamAttempt;
      if (incomingAttempt > currentStreamAttempt) {
        currentStreamAttempt = incomingAttempt;
        currentStreamSequence = -1;
        setModelStream(undefined);
      }
      taskAllowsModelStream = !["QUEUED", "RECEIVED", "DONE", "FAILED"].includes(next.status);
      if (next.status === "QUEUED" || next.status === "RECEIVED") {
        currentStreamSequence = -1;
        setModelStream(undefined);
      }
      if (next.status === "DONE" || next.status === "FAILED") {
        terminal = true;
        setModelStream(undefined);
        clearPoll();
        closeStream();
        if (!terminalNotified) {
          terminalNotified = true;
          onTerminalRef.current?.(next);
        }
      }
    }

    function acceptArtifact(artifact: HarnessArtifact) {
      if (!alive) {
        return;
      }
      setTask((current) => {
        if (!current || current.id !== activeTaskId) {
          return current;
        }
        const exists = current.artifacts.some((item) =>
          item.nodeName === artifact.nodeName && item.createdAt === artifact.createdAt);
        return exists ? current : { ...current, artifacts: [...current.artifacts, artifact] };
      });
    }

    function acceptModelStream(update: ModelStreamUpdate) {
      const incomingAttempt = update.attempt ?? currentStreamAttempt;
      if (!alive || terminal || incomingAttempt < currentStreamAttempt) {
        return;
      }
      if (incomingAttempt > currentStreamAttempt) {
        currentStreamAttempt = incomingAttempt;
        currentStreamSequence = -1;
      }
      if (update.sequence < currentStreamSequence) {
        return;
      }
      if (!taskAllowsModelStream) {
        return;
      }
      currentStreamSequence = update.sequence;
      if (update.phase === "RESET") {
        setModelStream(undefined);
        return;
      }
      setModelStream(update);
    }

    async function recoverFromDatabase() {
      try {
        accept(await api.task(activeTaskId));
      } catch (cause) {
        if (alive) {
          setError(cause instanceof Error ? cause.message : "任务恢复失败");
        }
      }
    }

    function schedulePoll(delayMs: number) {
      if (!alive || streamOpen || terminal || pollTimer !== undefined) {
        return;
      }
      pollTimer = window.setTimeout(async () => {
        pollTimer = undefined;
        await recoverFromDatabase();
        schedulePoll(pollIntervalMs);
      }, delayMs);
    }

    recoverFromDatabase();
    try {
      closeStream = api.streamTask(activeTaskId, {
        onOpen: () => {
          streamOpen = true;
          clearPoll();
        },
        onTask: accept,
        onArtifact: acceptArtifact,
        onModelStream: acceptModelStream,
        onError: () => {
          streamOpen = false;
          schedulePoll(0);
        }
      });
    } catch {
      schedulePoll(0);
    }

    return () => {
      alive = false;
      clearPoll();
      closeStream();
    };
  }, [taskId, options.pollIntervalMs]);

  return { task, error, modelStream };
}
