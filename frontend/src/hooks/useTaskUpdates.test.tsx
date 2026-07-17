import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../api/client";
import type { SqlTuningTask, TaskStatus } from "../types/api";
import { useTaskUpdates } from "./useTaskUpdates";

function task(status: TaskStatus, version: number): SqlTuningTask {
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

    act(() => handlers?.onError?.());
    await waitFor(() => expect(taskRequest.mock.calls.length).toBeGreaterThan(1));

    act(() => handlers?.onOpen?.());
    act(() => handlers?.onTask(task("DONE", 3)));
    expect(result.current.task?.status).toBe("DONE");
    expect(close).toHaveBeenCalled();
  });
});
