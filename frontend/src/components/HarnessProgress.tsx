import { CheckCircle2, Circle, Loader2, XCircle } from "lucide-react";
import { statusLabel, statusOrder } from "../lib/status";
import type { SqlTuningTask, TaskStatus } from "../types/api";

interface HarnessProgressProps {
  task?: SqlTuningTask;
}

export function HarnessProgress({ task }: HarnessProgressProps) {
  if (!task) {
    return null;
  }
  const currentIndex = statusOrder.indexOf(task.status);

  return (
    <div className="progress-card">
      <div className="progress-header">
        <strong>Harness 执行链路</strong>
        <span>{task.statusMessage}</span>
      </div>
      <div className="progress-steps">
        {statusOrder.map((status, index) => (
          <Step key={status} status={status} active={task.status === status} done={currentIndex >= index || task.status === "DONE"} failed={task.status === "FAILED"} />
        ))}
      </div>
    </div>
  );
}

function Step({ status, active, done, failed }: { status: TaskStatus; active: boolean; done: boolean; failed: boolean }) {
  let icon = <Circle size={16} />;
  if (failed && active) {
    icon = <XCircle size={16} />;
  } else if (done) {
    icon = <CheckCircle2 size={16} />;
  } else if (active) {
    icon = <Loader2 className="spin" size={16} />;
  }
  return (
    <div className={active ? "progress-step active" : done ? "progress-step done" : "progress-step"}>
      {icon}
      <span>{statusLabel[status]}</span>
    </div>
  );
}
