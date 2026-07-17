import { FileCode2, Save, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { SkillVersion } from "../types/api";

const MAX_SKILL_CONTENT_CHARS = 16 * 1024;

export function SkillAdminPage() {
  const [skills, setSkills] = useState<SkillVersion[]>([]);
  const [active, setActive] = useState<SkillVersion | undefined>();
  const [content, setContent] = useState("");
  const [message, setMessage] = useState("");

  useEffect(() => {
    api.skills().then((list) => {
      setSkills(list);
      setActive(list[0]);
      setContent(list[0]?.content || "");
    });
  }, []);

  async function save() {
    if (!active || !content.trim() || content.length > MAX_SKILL_CONTENT_CHARS) {
      setMessage("技能内容必须为 1–16384 字符");
      return;
    }
    const next = await api.saveSkill(active.name, content);
    setMessage(`已发布 ${next.name} v${next.version}`);
    const list = await api.skills();
    setSkills(list);
    setActive(next);
  }

  return (
    <div className="admin-page skill-admin-page">
      <header>
        <span>Skills</span>
        <h1>SQL 调优技能</h1>
        <p>技能内容以 Markdown 版本化管理。每次任务都会绑定执行时的技能版本，便于回溯模型行为。</p>
      </header>
      <div className="skill-admin-grid">
        <aside className="admin-list">
          <div className="skill-list-summary">
            <strong>{skills.length}</strong>
            <span>技能条目</span>
            <p>左侧选择技能版本模板，右侧编辑系统提示词。发布后，新任务会使用新版本，历史任务继续引用旧版本。</p>
          </div>
          {skills.map((skill) => (
            <button
              key={skill.name}
              className={active?.name === skill.name ? "active" : ""}
              onClick={() => {
                setActive(skill);
                setContent(skill.content);
              }}
            >
              <div className="skill-list-head">
                <strong>{skill.name}</strong>
                <span>v{skill.version}</span>
              </div>
              <div className="skill-list-meta">
                <span>{skill.enabled ? "已启用" : "未启用"}</span>
                <span>{formatTime(skill.updatedAt)}</span>
              </div>
            </button>
          ))}
        </aside>
        <section className="editor-panel">
          <div className="skill-editor-head">
            <div className="skill-editor-title">
              <div className="skill-editor-icon">
                <FileCode2 size={18} />
              </div>
              <div>
                <strong>{active?.name || "未选择技能"}</strong>
                <span>{active ? `版本 v${active.version} · Markdown Prompt` : "请选择左侧技能进行编辑"}</span>
              </div>
            </div>
            <div className="skill-editor-status">
              <span>{active?.enabled ? "当前启用" : "未启用"}</span>
              <span>{content.length} / {MAX_SKILL_CONTENT_CHARS} chars</span>
            </div>
          </div>
          <div className="skill-editor-callout">
            <Sparkles size={16} />
            <span>建议把技能写成稳定的系统提示词：先约束事实边界，再定义输出结构，最后补数据库场景的专有规则和禁止项。</span>
          </div>
          <textarea maxLength={MAX_SKILL_CONTENT_CHARS} value={content} onChange={(event) => setContent(event.target.value)} />
          <div className="editor-actions">
            <span>{message}</span>
            <button className="primary-button" onClick={save} disabled={!content.trim() || content.length > MAX_SKILL_CONTENT_CHARS}>
              <Save size={16} />
              发布新版本
            </button>
          </div>
        </section>
      </div>
    </div>
  );
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
