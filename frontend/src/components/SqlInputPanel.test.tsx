import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SqlInputPanel, type SqlInputValue } from "./SqlInputPanel";

function imageFile(name: string, type = "image/png", body = "image-bytes") {
  return new File([body], name, { type });
}

function renderPanel(onSubmit = vi.fn<(value: SqlInputValue) => void>()) {
  render(<SqlInputPanel loading={false} onSubmit={onSubmit} />);
  return onSubmit;
}

describe("SqlInputPanel", () => {
  it("keeps the chat composer focused on pasted text instead of evidence fields", () => {
    renderPanel();

    expect(screen.getByLabelText("SQL 调优消息编辑器")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "SQL 或巡检报告文本" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "补充证据" })).not.toBeInTheDocument();
    expect(screen.queryByText("表结构")).not.toBeInTheDocument();
    expect(screen.queryByText("当前索引")).not.toBeInTheDocument();
  });

  it("recognizes pasted report text and compact clipboard screenshot attachments", async () => {
    const onSubmit = renderPanel();
    const editor = screen.getByRole("textbox", { name: "SQL 或巡检报告文本" });
    await userEvent.click(editor);
    await userEvent.paste("SQL ID: 260715-0038538\nSQL: select * from t");

    const clipboardImage = imageFile("", "image/png", "clipboard-image");
    fireEvent.paste(screen.getByLabelText("SQL 调优消息编辑器"), {
      clipboardData: {
        items: [{ kind: "file", type: "image/png", getAsFile: () => clipboardImage }]
      }
    });

    expect(await screen.findByText(/剪贴板图片-1\.png/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "提交分析" }));
    expect(onSubmit.mock.calls[0][0].inputType).toBe("report_text");
  });

  it("keeps report text when one clipboard paste also includes a screenshot", async () => {
    renderPanel();
    const editor = screen.getByRole("textbox", { name: "SQL 或巡检报告文本" });
    const report = "SQL ID: 260715-0038538\nSQL: select * from orders where status = 'PAID'";
    const clipboardImage = imageFile("plan.png", "image/png", "clipboard-image");

    fireEvent.paste(editor, {
      clipboardData: {
        getData: (type: string) => type === "text/plain" ? report : "",
        items: [{ kind: "string", type: "text/plain" }, { kind: "file", type: "image/png", getAsFile: () => clipboardImage }]
      }
    });

    await waitFor(() => expect(editor).toHaveValue(report));
    expect(await screen.findByText("plan.png")).toBeInTheDocument();
  });

  it("limits screenshots, rejects non-image files, and supports removal", async () => {
    renderPanel();
    const input = screen.getByLabelText("选择执行计划截图文件");
    const user = userEvent.setup({ applyAccept: false });

    await user.upload(input, [
      imageFile("one.png"),
      imageFile("two.jpg", "image/jpeg"),
      imageFile("three.webp", "image/webp"),
      imageFile("four.png"),
      imageFile("inspection-report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ]);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("最多添加 3 张截图");
    expect(alert).toHaveTextContent("仅支持 PNG、JPEG、WebP");
    expect(screen.queryByText("four.png")).not.toBeInTheDocument();
    expect(screen.queryByText("inspection-report.docx")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "删除 two.jpg" }));
    expect(screen.queryByText("two.jpg")).not.toBeInTheDocument();
    expect(screen.getByText("one.png")).toBeInTheDocument();
  });

  it("submits the text/image payload and clears the composer", async () => {
    const onSubmit = renderPanel();
    const input = screen.getByLabelText("选择执行计划截图文件");
    await userEvent.upload(input, imageFile("plan.png", "image/png", "plan-content"));
    await screen.findByText("plan.png");

    const editor = screen.getByRole("textbox", { name: "SQL 或巡检报告文本" });
    await userEvent.type(editor, "select * from orders where id = 1");
    await userEvent.click(screen.getByRole("button", { name: "提交分析" }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    const submitted = onSubmit.mock.calls[0][0];
    expect(submitted).toMatchObject({
      dbDialect: "OceanBase MySQL",
      inputType: "sql",
      deepAnalysis: false
    });
    expect(submitted.planImages).toHaveLength(1);
    expect(Object.keys(submitted.planImages[0]).sort()).toEqual(["dataUrl", "name"]);
    expect(submitted.planImages[0]).toMatchObject({ name: "plan.png" });
    expect(submitted.planImages[0].dataUrl).toMatch(/^data:image\/png;base64,/);
    await waitFor(() => expect(screen.queryByText("plan.png")).not.toBeInTheDocument());
    expect(editor).toHaveValue("");
  });

  it("keeps the draft when task creation is rejected", async () => {
    const onSubmit = vi.fn<(value: SqlInputValue) => Promise<void>>().mockRejectedValue(new Error("QUEUE_FULL"));
    render(<SqlInputPanel loading={false} onSubmit={onSubmit} />);
    const input = screen.getByLabelText("选择执行计划截图文件");
    await userEvent.upload(input, imageFile("retry-plan.png", "image/png", "plan-content"));
    const editor = screen.getByRole("textbox", { name: "SQL 或巡检报告文本" });
    await userEvent.type(editor, "select * from retry_orders");

    await userEvent.click(screen.getByRole("button", { name: "提交分析" }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(editor).toHaveValue("select * from retry_orders");
    expect(screen.getByText("retry-plan.png")).toBeInTheDocument();
  });

  it("uses the compact deep-review control without exposing more form fields", async () => {
    const onSubmit = renderPanel();
    await userEvent.click(screen.getByRole("button", { name: "标准分析" }));
    await userEvent.type(screen.getByRole("textbox", { name: "SQL 或巡检报告文本" }), "select id from orders");
    await userEvent.click(screen.getByRole("button", { name: "提交分析" }));

    expect(onSubmit.mock.calls[0][0].deepAnalysis).toBe(true);
    expect(screen.getByRole("button", { name: "深度复核" })).toHaveAttribute("aria-pressed", "true");
  });
});
