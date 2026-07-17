import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { HarnessArtifact, SqlTuningTask } from "../types/api";

interface UseTaskUpdatesOptions {
  initialTask?: SqlTuningTask;
  onTerminal?: (task: SqlTuningTask) => void;
  pollIntervalMs?: number;
}

export function useTaskUpdates(taskId: number | undefined, options: UseTaskUpdatesOptions = {}) {
  const [task, setTask] = useState<SqlTuningTask | undefined>(options.initialTask);
  const [error, setError] = useState("");
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
      if (next.status === "DONE" || next.status === "FAILED") {
        terminal = true;
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

  return { task, error };
}
