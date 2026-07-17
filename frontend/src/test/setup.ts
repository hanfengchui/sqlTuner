import "@testing-library/jest-dom/vitest";
import { vi } from "vitest";

Object.assign(navigator, {
  clipboard: {
    writeText: vi.fn()
  }
});

if (typeof Range !== "undefined" && !Range.prototype.getClientRects) {
  Range.prototype.getClientRects = vi.fn(() => [] as unknown as DOMRectList);
}

if (typeof Range !== "undefined" && !Range.prototype.getBoundingClientRect) {
  Range.prototype.getBoundingClientRect = vi.fn(() => ({
    bottom: 0,
    height: 0,
    left: 0,
    right: 0,
    top: 0,
    width: 0,
    x: 0,
    y: 0,
    toJSON: () => ({})
  }));
}
