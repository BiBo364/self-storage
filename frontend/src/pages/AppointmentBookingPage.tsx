import { Calendar, Check, Clock3, IdCard, UserRound } from "lucide-react";
import { useMemo, useState } from "react";
import type { Navigate } from "../app/types";
import { facilities } from "../mocks/catalog";
import { findDepositInvoiceByOrderId, isInvoicePaid } from "../mocks/invoices";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { SurfaceState } from "../components/ui/SurfaceState";

const MAX_RESCHEDULES = 2;
const TIME_SLOTS = ["08:00 – 10:00", "13:00 – 15:00", "17:00 – 19:00"];

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function addDaysIso(days: number) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

export function AppointmentBookingPage({ navigate }: { navigate: Navigate }) {
  const orderId = useMemo(
    () => new URLSearchParams(window.location.search).get("orderId") ?? "",
    [],
  );
  const invoice = findDepositInvoiceByOrderId(orderId);
  const facility = facilities.find((item) => item.id === invoice?.facilityId);

  const [date, setDate] = useState("");
  const [slot, setSlot] = useState<string | null>(null);
  const [appointment, setAppointment] = useState<{ date: string; slot: string } | null>(null);
  const [assigned, setAssigned] = useState(false);
  const [rescheduleCount, setRescheduleCount] = useState(0);
  const [rescheduling, setRescheduling] = useState(false);

  if (!invoice || !isInvoicePaid(invoice.id)) {
    return (
      <SurfaceState
        variant="forbidden"
        title="Chưa thể đặt lịch check-in"
        description="Bạn cần thanh toán hoá đơn đặt cọc trước khi chọn lịch hẹn check-in."
        action={{ label: "Đến trang hoá đơn", onClick: () => navigate("/invoices") }}
      />
    );
  }

  function confirm() {
    if (!date || !slot) return;
    setAppointment({ date, slot });
    setRescheduling(false);
    setAssigned(false);
    window.setTimeout(() => setAssigned(true), 1400);
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Chọn lịch hẹn check-in</h1>
        <p className="mt-1.5 text-small text-muted">
          Đặt lịch đến cơ sở để kiểm tra khoang, ký hợp đồng và nhận bàn giao cho khoang{" "}
          {invoice.unitCode}.
        </p>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_320px] items-start gap-6 max-[980px]:grid-cols-1">
        <div>
          {appointment && !rescheduling ? (
            <div className="bg-surface p-5">
              <span className="text-label text-muted">Lịch hẹn của bạn</span>
              <h2 className="mt-1 text-section text-ink">
                {appointment.date} · {appointment.slot}
              </h2>
              <div className="mt-4">
                {assigned ? (
                  <DemoNotice tone="success">
                    Đã phân công nhân viên phụ trách: Nguyễn Thành Được (FS).
                  </DemoNotice>
                ) : (
                  <DemoNotice tone="pending">Đang chờ FM phân công nhân viên phụ trách.</DemoNotice>
                )}
              </div>
              <div className="mt-5 flex flex-wrap items-center gap-2.5">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={rescheduleCount >= MAX_RESCHEDULES}
                  onClick={() => {
                    setRescheduleCount((count) => count + 1);
                    setRescheduling(true);
                    setDate(appointment.date);
                    setSlot(appointment.slot);
                  }}
                >
                  Dời lịch ({MAX_RESCHEDULES - rescheduleCount} lượt còn lại)
                </Button>
                <Button variant="secondary" size="sm" onClick={() => navigate("/my-storage")}>
                  Về Kho của tôi
                </Button>
              </div>
              {rescheduleCount >= MAX_RESCHEDULES && (
                <p className="mt-3 text-label text-danger">
                  Bạn đã dời lịch tối đa {MAX_RESCHEDULES} lần. Vui lòng liên hệ hỗ trợ nếu cần đổi
                  thêm.
                </p>
              )}
            </div>
          ) : (
            <div className="bg-surface p-5">
              {rescheduling && (
                <div className="mb-4">
                  <DemoNotice tone="info">
                    Dời lịch cần báo trước ít nhất 24 giờ so với khung giờ đã chọn.
                  </DemoNotice>
                </div>
              )}
              <label className="grid gap-1.5">
                <span className="flex items-center gap-1.5 text-label text-muted">
                  <Calendar size={14} /> Chọn ngày hẹn
                </span>
                <input
                  type="date"
                  className="w-fit rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
                  min={todayIso()}
                  max={addDaysIso(14)}
                  value={date}
                  onChange={(event) => setDate(event.target.value)}
                />
              </label>

              <div className="mt-5">
                <span className="flex items-center gap-1.5 text-label text-muted">
                  <Clock3 size={14} /> Chọn khung giờ
                </span>
                <div className="mt-2.5 flex flex-wrap gap-2">
                  {TIME_SLOTS.map((item) => (
                    <button
                      key={item}
                      className={`min-h-[42px] border-2 px-4 text-small font-semibold transition-colors ${
                        slot === item
                          ? "border-brand bg-brand text-signal-ink"
                          : "border-rule-strong bg-surface text-muted hover:border-ink hover:text-ink"
                      }`}
                      onClick={() => setSlot(item)}
                    >
                      {item}
                    </button>
                  ))}
                </div>
              </div>

              <Button className="mt-6" disabled={!date || !slot} onClick={confirm}>
                <Check size={16} /> Xác nhận lịch hẹn
              </Button>
            </div>
          )}
        </div>

        <aside className="grid gap-3">
          <div className="bg-surface p-5">
            <span className="text-label text-muted">Địa điểm</span>
            <h3 className="mt-1 text-section text-ink">{facility?.name}</h3>
            <p className="mt-2 text-small leading-relaxed text-muted">{facility?.address}</p>
            <p className="mt-1 text-small leading-relaxed text-muted">{facility?.hours}</p>
          </div>
          <div className="bg-surface p-5">
            <span className="flex items-center gap-1.5 text-label text-muted">
              <IdCard size={14} /> Cần chuẩn bị
            </span>
            <ul className="mt-2.5 grid gap-2 pl-0 text-small text-muted">
              <li className="flex items-start gap-2">
                <Check size={14} className="mt-0.5 shrink-0 text-success" /> CCCD hoặc hộ chiếu bản
                gốc
              </li>
              <li className="flex items-start gap-2">
                <UserRound size={14} className="mt-0.5 shrink-0 text-success" /> Có mặt đúng người
                đứng tên hợp đồng
              </li>
            </ul>
          </div>
        </aside>
      </div>
    </div>
  );
}
