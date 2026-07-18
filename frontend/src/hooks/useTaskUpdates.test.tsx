import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../api/client";
import type { SqlTuningTask, TaskStatus } from "../types/api";
import { useTaskUpdates } from "./useTaskUpdates";

function task(status: TaskStatus, version: number, attemptCount = 0): SqlTuningTask {
  return {
    id: 42,
    userId: 1,
    conversationId: 7,
    dbDialect: "OceanBase MySQL",
    originalSql: "select 1",
    deepAnalysis: false,
    status,
    statusMessage: status,
    version,
    attemptCount,
    ruleFindings: [],
    artifacts: [],
    createdAt: "2026-07-17T00:00:00",
    updatedAt: "2026-07-17T00:00:00"
  };
}

describe("useTaskUpdates", () => {
  afterEach(() => vi.restoreAllMocks());

  it("uses SSE updates, ignores stale versions, and polls while the stream is disconnected", async () => {
    let handlers: Parameters<typeof api.streamTask>[1] | undefined;
    const close = vi.fn();
    const taskRequest = vi.spyOn(api, "task").mockResolvedValue(task("QUEUED", 1));
    vi.spyOn(api, "streamTask").mockImplementation((_taskId, nextHandlers) => {
      handlers = nextHandlers;
      return close;
    });

    const { result } = renderHook(() => useTaskUpdates(42, { pollIntervalMs: 10 }));
    await waitFor(() => expect(result.current.task?.status).toBe("QUEUED"));

    act(() => handlers?.onTask(task("RECEIVED", 2)));
    expect(result.current.task?.status).toBe("RECEIVED");
    act(() => handlers?.onTask(task("QUEUED", 1)));
    expect(result.current.task?.status).toBe("RECEIVED");
    act(() => handlers?.onArtifact?.({
      nodeName: "rules",
      summary: "rule scan",
      payload: {},
      createdAt: "2026-07-17T00:00:01"
    }));
    expect(result.current.task?.artifacts).toHaveLength(1);

    act(() => handlers?.onModelStream?.({
      phase: "THINKING",
      receivedChars: 64,
      sequence: 1
    }));
    expect(result.current.modelStream).toEqual(expect.objectContaining({ sequence: 1, receivedChars: 64 }));
    act(() => handlers?.onModelStream?.({
      phase: "ANSWER",
      draftText: "过期草稿",
      receivedChars: 32,
      sequence: 0
    }));
    expect(result.current.modelStream?.sequence).toBe(1);

    act(() => handlers?.onTask(task("QUEUED", 3, 1)));
    expect(result.current.modelStream).toBeUndefined();
    act(() => handlers?.onTask(task("RECEIVED", 4, 2)));
    act(() => handlers?.onModelStream?.({
      phase: "ANSWER",
      draftText: "新一轮草稿",
      receivedChars: 96,
      sequence: 2,
      attempt: 2
    }));
    expect(result.current.modelStream?.draftText).toBe("新一轮草稿");
    act(() => handlers?.onModelStream?.({
      phase: "RESET",
      receivedChars: 0,
      sequence: 3,
      attempt: 2
    }));
    expect(result.current.modelStream).toBeUndefined();
    act(() => handlers?.onModelStream?.({
      phase: "ANSWER",
      draftText: "旧 attempt 草稿",
      receivedChars: 120,
      sequence: 99,
      attempt: 1
    }));
    expect(result.current.modelStream).toBeUndefined();

    act(() => handlers?.onError?.());
    await waitFor(() => expect(taskRequest.mock.calls.length).toBeGreaterThan(1));

    act(() => handlers?.onOpen?.());
    act(() => handlers?.onTask(task("DONE", 5)));
    expect(result.current.task?.status).toBe("DONE");
    expect(result.current.modelStream).toBeUndefined();
    expect(close).toHaveBeenCalled();
  });
});
