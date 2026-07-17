import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { App } from "./pages/App";
import "./styles/global.css";

// 启动前应用持久化的主题，避免首屏闪烁。
// 浅色为默认，仅当显式保存过 dark 时才走深色。
const savedTheme = localStorage.getItem("sql-tuner-theme");
if (savedTheme === "dark") {
  document.documentElement.setAttribute("data-theme", "dark");
}

createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>
);
