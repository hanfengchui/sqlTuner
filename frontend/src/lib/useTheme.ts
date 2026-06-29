import { useCallback, useEffect, useState } from "react";

export type Theme = "light" | "dark";

const STORAGE_KEY = "sql-tuner-theme";

// 读取已应用的主题：优先 localStorage，否则默认浅色。
function readTheme(): Theme {
  if (typeof document !== "undefined" && document.documentElement.getAttribute("data-theme") === "dark") {
    return "dark";
  }
  if (typeof localStorage !== "undefined" && localStorage.getItem(STORAGE_KEY) === "dark") {
    return "dark";
  }
  return "light";
}

// 主题切换 hook：同步 <html data-theme> 与 localStorage，跨组件共享。
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(readTheme);

  useEffect(() => {
    const root = document.documentElement;
    if (theme === "dark") {
      root.setAttribute("data-theme", "dark");
    } else {
      root.removeAttribute("data-theme");
    }
    localStorage.setItem(STORAGE_KEY, theme);
  }, [theme]);

  const toggle = useCallback(() => {
    setTheme((current) => (current === "dark" ? "light" : "dark"));
  }, []);

  return { theme, toggle };
}
