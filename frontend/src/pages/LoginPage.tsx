import { DatabaseZap, LogIn } from "lucide-react";
import { useState } from "react";
import { api } from "../api/client";
import type { UserView } from "../types/api";

interface LoginPageProps {
  onLogin: (user: UserView) => void;
}

export function LoginPage({ onLogin }: LoginPageProps) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const user = await api.login(username, password);
      onLogin(user);
    } catch (e) {
      setError(e instanceof Error ? e.message : "登录失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-card">
        <div className="brand-icon large">
          <DatabaseZap size={30} />
        </div>
        <h1>OceanBase SQL 诊断工作台</h1>
        <p>用证据、规则和模型复核生成可审阅的 SQL 调优建议。</p>
        <form onSubmit={submit}>
          <label>
            用户名
            <input value={username} autoComplete="username" onChange={(event) => setUsername(event.target.value)} />
          </label>
          <label>
            密码
            <input type="password" value={password} autoComplete="current-password" onChange={(event) => setPassword(event.target.value)} />
          </label>
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button" disabled={loading}>
            <LogIn size={16} />
            {loading ? "登录中..." : "登录"}
          </button>
        </form>
      </section>
    </main>
  );
}
