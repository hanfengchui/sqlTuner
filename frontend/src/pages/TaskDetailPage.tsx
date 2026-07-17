import { useParams } from "react-router-dom";
import { TuningAdviceMessage } from "../components/TuningAdviceMessage";
import { TuningEvidenceDetails } from "../components/TuningEvidenceDetails";
import { useTaskUpdates } from "../hooks/useTaskUpdates";

export function TaskDetailPage() {
  const taskId = Number(useParams().taskId);
  const { task, error } = useTaskUpdates(Number.isFinite(taskId) ? taskId : undefined);

  return (
    <div className="workspace detail-workspace">
      <section className="detail-header task-detail-header">
        <div>
          <span>Task #{taskId}</span>
          <h1>{task?.statusMessage || "正在加载调优任务"}</h1>
          <p>{task?.dbDialect || "OceanBase SQL"}</p>
        </div>
        <div className="task-detail-stats">
          <article>
            <strong>{task?.status || "LOADING"}</strong>
            <span>状态</span>
          </article>
          <article>
            <strong>{task?.queuePosition ?? "-"}</strong>
            <span>队列</span>
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
      <div className="task-detail-advice">
        <TuningAdviceMessage task={task} progressive={false} showDetailLink={false} />
      </div>
      <TuningEvidenceDetails task={task} />
    </div>
  );
}
