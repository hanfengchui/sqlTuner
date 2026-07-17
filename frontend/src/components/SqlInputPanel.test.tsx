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

describe("SqlInputPanel image evidence", () => {
  it("keeps optional evidence collapsed until requested", async () => {
    renderPanel();

    expect(screen.queryByText("表结构")).not.toBeInTheDocument();
    const trigger = screen.getByRole("button", { name: "补充证据" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await userEvent.click(trigger);
    expect(screen.getByText("表结构")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "收起证据" })).toHaveAttribute("aria-expanded", "true");
  });

  it("recognizes pasted report text and clipboard images", async () => {
    renderPanel();

    const editor = screen.getByRole("textbox", { name: "SQL 或巡检报告文本" });
    await userEvent.click(editor);
    await userEvent.paste("SQL ID: 260715-0038538\nSQL: select * from t");
    expect(screen.getByText("报告文本")).toBeInTheDocument();

    const clipboardImage = imageFile("", "image/png", "clipboard-image");
    fireEvent.paste(screen.getByLabelText("图片证据区"), {
      clipboardData: {
        items: [{ kind: "file", type: "image/png", getAsFile: () => clipboardImage }]
      }
    });

    expect(await screen.findByText(/剪贴板图片-1\.png/)).toBeInTheDocument();
    expect(screen.getByText(/不能代替文本 EXPLAIN/)).toBeInTheDocument();
  });

  it("limits images, reports unsupported types, and supports removal", async () => {
    renderPanel();
    const input = screen.getByLabelText("选择执行计划图片");
    const user = userEvent.setup({ applyAccept: false });

    await user.upload(input, [
      imageFile("one.png"),
      imageFile("two.jpg", "image/jpeg"),
      imageFile("three.webp", "image/webp"),
      imageFile("four.png"),
      imageFile("plan.gif", "image/gif")
    ]);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("最多添加 3 张图片");
    expect(alert).toHaveTextContent("仅支持 PNG、JPEG、WebP");
    expect(screen.queryByText("four.png")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "删除 two.jpg" }));
    expect(screen.queryByText("two.jpg")).not.toBeInTheDocument();
    expect(screen.getByText("one.png")).toBeInTheDocument();
    expect(screen.getByText("three.webp")).toBeInTheDocument();
  });

  it("keeps report input text-and-image only and rejects Word files", async () => {
    renderPanel();
    const input = screen.getByLabelText("选择执行计划图片");
    expect(input).toHaveAttribute("accept", "image/png,image/jpeg,image/webp");

    const user = userEvent.setup({ applyAccept: false });
    await user.upload(input, imageFile(
      "inspection-report.docx",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ));

    expect(await screen.findByRole("alert")).toHaveTextContent("仅支持 PNG、JPEG、WebP");
    expect(screen.queryByText("inspection-report.docx")).not.toBeInTheDocument();
  });

  it("submits image payload and clears attachments", async () => {
    const onSubmit = renderPanel();
    const input = screen.getByLabelText("选择执行计划图片");
    await userEvent.upload(input, imageFile("plan.png", "image/png", "plan-content"));
    await screen.findByText("plan.png");

    const editor = screen.getByRole("textbox", { name: "SQL 或巡检报告文本" });
    await userEvent.click(editor);
    await userEvent.paste("select * from orders where id = 1");
    await userEvent.click(screen.getByRole("button", { name: "提交分析" }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    const submitted = onSubmit.mock.calls[0][0];
    expect(submitted.inputType).toBe("sql");
    expect(submitted.planImages).toHaveLength(1);
    expect(Object.keys(submitted.planImages[0]).sort()).toEqual(["dataUrl", "name"]);
    expect(submitted.planImages[0]).toMatchObject({ name: "plan.png" });
    expect(submitted.planImages[0].dataUrl).toMatch(/^data:image\/png;base64,/);
    await waitFor(() => expect(screen.queryByText("plan.png")).not.toBeInTheDocument());
  });
});
