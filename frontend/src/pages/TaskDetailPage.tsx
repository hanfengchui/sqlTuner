import { useEffect, useState } from "react";
import { api } from "../api/client";
import { HarnessProgress } from "../components/HarnessProgress";
import { ResultTabs } from "../components/ResultTabs";
import type { SqlTuningTask } from "../types/api";

interface TaskDetailPageProps {
  taskId: number;
}

export function TaskDetailPage({ taskId }: TaskDetailPageProps) {
  const [task, setTask] = useState<SqlTuningTask | undefined>();
  const [error, setError] = useState("");

  useEffect(() => {
    let alive = true;
    async function poll() {
      try {
        const next = await api.task(taskId);
        if (!alive) {
          return;
        }
        setTask(next);
        if (next.status !== "DONE" && next.status !== "FAILED") {
          window.setTimeout(poll, 1000);
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : "任务加载失败");
      }
    }
    poll();
    return () => {
      alive = false;
    };
  }, [taskId]);

  return (
    <div className="workspace detail-workspace">
      <section className="detail-header report-header">
        <div>
          <span>任务 #{taskId}</span>
          <h1>{task?.statusMessage || "正在加载调优任务"}</h1>
          {task?.sqlHash && <p>SQL Hash: {task.sqlHash}</p>}
        </div>
        <div className="report-stats">
          <article>
            <strong>{task?.status || "LOADING"}</strong>
            <span>状态</span>
          </article>
          <article>
            <strong>{task?.ruleFindings.length || 0}</strong>
            <span>规则命中</span>
          </article>
          <article>
            <strong>{task?.artifacts.length || 0}</strong>
            <span>链路节点</span>
          </article>
        </div>
      </section>
      {error && <div className="form-error wide">{error}</div>}
      <HarnessProgress task={task} />
      <ResultTabs task={task} />
    </div>
  );
}
