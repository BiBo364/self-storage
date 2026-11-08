import { MessageCircle, Send, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../../app/api";
import { errorMessage } from "../../app/http";

type ChatTurn = { role: "user" | "assistant"; content: string };
type ChatReply = { text: string; refused: boolean };

const STORAGE_KEY = "kho-moc-chat";
const MAX_TURNS = 20;
// Backend từ chối (400) tin nhắn dài hơn 2000 ký tự — câu trả lời dài vẫn hiển thị đủ, chỉ cắt khi gửi lại.
const MAX_CHARS = 2000;
const GREETING: ChatTurn = {
  role: "assistant",
  content:
    "Xin chào! Mình là trợ lý tư vấn của Lemar Self Storage. Bạn muốn hỏi về cơ sở, loại khoang, giá thuê hay quy trình thuê kho?",
};

function loadHistory(): ChatTurn[] {
  try {
    const stored = window.sessionStorage.getItem(STORAGE_KEY);
    return stored ? (JSON.parse(stored) as ChatTurn[]) : [];
  } catch {
    return [];
  }
}

/** Chatbot tư vấn (POST /api/chat) — nút nổi góc phải, hội thoại giữ trong phiên trình duyệt. */
export function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [turns, setTurns] = useState<ChatTurn[]>(loadHistory);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    try {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(turns));
    } catch {
      // Hội thoại vẫn dùng được trong tab hiện tại khi không lưu được.
    }
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [turns, open]);

  async function send() {
    const text = draft.trim();
    if (!text || pending) return;
    const next: ChatTurn[] = [...turns, { role: "user" as const, content: text }].slice(-MAX_TURNS);
    setTurns(next);
    setDraft("");
    setError(null);
    setPending(true);
    try {
      const reply = await apiRequest<ChatReply>("/api/chat", {
        method: "POST",
        body: {
          messages: next.map((turn) => ({ ...turn, content: turn.content.slice(0, MAX_CHARS) })),
        },
      });
      setTurns([...next, { role: "assistant" as const, content: reply.text }].slice(-MAX_TURNS));
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setPending(false);
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        aria-label="Mở trợ lý tư vấn"
        className="fixed right-5 bottom-5 z-40 grid h-14 w-14 place-items-center rounded-full bg-ink text-surface shadow-lg transition-transform hover:scale-105"
        onClick={() => setOpen(true)}
      >
        <MessageCircle size={24} />
      </button>
    );
  }

  const shown = turns.length === 0 ? [GREETING] : turns;

  return (
    <section
      aria-label="Trợ lý tư vấn"
      className="fixed right-5 bottom-5 z-40 flex h-[min(560px,calc(100vh-40px))] w-[min(380px,calc(100vw-40px))] flex-col overflow-hidden border border-border bg-surface shadow-2xl"
    >
      <header className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
        <div>
          <strong className="block text-small font-semibold text-ink">Trợ lý tư vấn</strong>
          <span className="text-label text-muted">Trả lời tự động bởi AI</span>
        </div>
        <div className="flex items-center gap-1">
          {turns.length > 0 && (
            <button
              type="button"
              className="border-0 bg-transparent px-2 py-1 text-label text-muted hover:text-ink"
              onClick={() => {
                setTurns([]);
                setError(null);
              }}
            >
              Hội thoại mới
            </button>
          )}
          <button
            type="button"
            aria-label="Đóng trợ lý tư vấn"
            className="grid h-8 w-8 place-items-center border-0 bg-transparent text-muted hover:text-ink"
            onClick={() => setOpen(false)}
          >
            <X size={18} />
          </button>
        </div>
      </header>

      <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-3">
        {shown.map((turn, index) => (
          <div
            key={index}
            className={`max-w-[85%] px-3 py-2 text-small whitespace-pre-wrap ${
              turn.role === "user"
                ? "ml-auto bg-ink text-surface"
                : "mr-auto bg-surface-subtle text-ink"
            }`}
          >
            {turn.content}
          </div>
        ))}
        {pending && (
          <div className="mr-auto bg-surface-subtle px-3 py-2 text-small text-muted" role="status">
            Đang trả lời…
          </div>
        )}
        {error && (
          <p className="m-0 text-label text-danger" role="alert">
            {error}
          </p>
        )}
      </div>

      <form
        className="flex items-end gap-2 border-t border-border p-3"
        onSubmit={(event) => {
          event.preventDefault();
          void send();
        }}
      >
        <textarea
          aria-label="Câu hỏi của bạn"
          className="max-h-28 min-h-10 flex-1 resize-none border border-border bg-surface px-3 py-2 text-small text-ink outline-none focus:border-ink"
          maxLength={2000}
          placeholder="Nhập câu hỏi…"
          rows={1}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              void send();
            }
          }}
        />
        <button
          type="submit"
          aria-label="Gửi"
          disabled={pending || !draft.trim()}
          className="grid h-10 w-10 place-items-center border-0 bg-ink text-surface disabled:opacity-40"
        >
          <Send size={16} />
        </button>
      </form>
    </section>
  );
}
