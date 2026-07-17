import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "./client";
import type { SqlTuningTask, TaskStatus } from "../types/api";

class FakeEventSource {
  static latest: FakeEventSource;
  readonly url: string;
  readonly withCredentials: boolean;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  close = vi.fn();
  private readonly listeners = new Map<string, EventListener[]>();

  constructor(url: string | URL, options?: EventSourceInit) {
    this.url = String(url);
    this.withCredentials = Boolean(options?.withCredentials);
    FakeEventSource.latest = this;
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject | null) {
    if (!listener) {
      return;
    }
    const callable: EventListener = typeof listener === "function"
      ? listener
      : (event) => listener.handleEvent(event);
    this.listeners.set(type, [...(this.listeners.get(type) || []), callable]);
  }

  emitPayload(type: string, payload: unknown) {
    const event = new MessageEvent(type, { data: JSON.stringify(payload) });
    for (const listener of this.listeners.get(type) || []) {
      listener(event);
    }
  }

  emitTransportError() {
    this.onerror?.(new Event("error"));
  }
}

function task(status: TaskStatus): SqlTuningTask {
  return {
    id: 42,
    userId: 1,
    conversationId: 7,
    dbDialect: "OceanBase MySQL",
    originalSql: "select 1",
    deepAnalysis: false,
    status,
    statusMessage: status,
    version: 3,
    ruleFindings: [],
    artifacts: [],
    createdAt: "2026-07-17T00:00:00",
    updatedAt: "2026-07-17T00:00:01"
  };
}

describe("task EventSource protocol", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("keeps failed-task payloads separate from native transport errors", () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    const onTask = vi.fn();
    const onError = vi.fn();
    const stop = api.streamTask(42, { onTask, onError });

    expect(FakeEventSource.latest.url).toBe("/api/tuning/tasks/42/events");
    expect(FakeEventSource.latest.withCredentials).toBe(true);

    FakeEventSource.latest.emitPayload("task-error", task("FAILED"));
    expect(onTask).toHaveBeenCalledWith(expect.objectContaining({ status: "FAILED" }));
    expect(onError).not.toHaveBeenCalled();

    expect(() => FakeEventSource.latest.emitTransportError()).not.toThrow();
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onTask).toHaveBeenCalledTimes(1);

    stop();
    expect(FakeEventSource.latest.close).toHaveBeenCalled();
  });
});
