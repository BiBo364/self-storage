import { ArrowRight, Calendar, Check, Clock3, IdCard, UserRound } from "lucide-react";
import { useCallback, useState, type ReactNode } from "react";
import type { Navigate } from "../app/types";
import { queryParam } from "../app/routes";
import { formatDate, formatTime } from "../app/format";
import { errorMessage } from "../app/http";
import {
  bookAppointment,
  fetchAppointmentSlots,
  fetchMyOrder,
  fetchMyOrders,
  useResource,
  type BookedAppointment,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { SurfaceState } from "../components/ui/SurfaceState";

const WEEKDAY = ["CN", "T2", "T3", "T4", "T5", "T6", "T7"];

function weekday(isoDay: string): string {
  const [y, m, d] = isoDay.split("-").map(Number);
  return WEEKDAY[new Date(y, m - 1, d).getDay()];
}

function initialOrderId(): number | null {
  const value = Number(queryParam("orderId"));
  return Number.isFinite(value) && value > 0 ? value : null;
}

export function AppointmentBookingPage({ navigate }: { navigate: Navigate }) {
  const [orderId, setOrderId] = useState(initialOrderId);

  if (orderId === null) {
    return (
      <OrderPicker
        navigate={navigate}
        onPick={(id) => {
          navigate(`/appointments/new?orderId=${id}`);
          setOrderId(id);
        }}
      />
    );
  }
  return <SlotBooking key={orderId} orderId={orderId} navigate={navigate} />;
}

function OrderPicker({ navigate, onPick }: { navigate: Navigate; onPick: (id: number) => void }) {
  const orders = useResource(fetchMyOrders);
  const bookable = (orders.data ?? []).filter((item) =>
    item.availableActions.includes("BOOK_CHECKIN"),
  );

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Chọn lịch hẹn check-in</h1>
        <p className="mt-1.5 text-small text-muted">
          Chọn đơn thuê đã đặt cọc để đặt lịch đến cơ sở nhận bàn giao.
        </p>
      </div>
      {orders.loading && !orders.data ? (
        <div className="grid gap-2" role="status" aria-label="Đang tải">
          {[0, 1].map((index) => (
            <div key={index} className="h-[76px] animate-pulse bg-surface" />
          ))}
        </div>
      ) : orders.error && !orders.data ? (
        <SurfaceState
          variant="error"
          title="Không tải được đơn thuê"
          description={orders.error}
          action={{ label: "Thử lại", onClick: orders.reload }}
        />
      ) : bookable.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có đơn nào cần đặt lịch"
          description="Đơn thuê cần được đặt cọc trước khi chọn lịch hẹn check-in."
          action={{ label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }}
        />
      ) : (
        <div className="grid gap-2">
          {bookable.map(({ order }) => (
            <div
              key={order.id}
              className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4"
            >
              <div>
                <span className="text-label text-muted">
                  Đơn #{order.id}
                  {order.unitCode ? ` · Khoang ${order.unitCode}` : ""}
                  {order.unitTypeName ? ` · ${order.unitTypeName}` : ""}
                </span>
                <h2 className="mt-1 text-section text-ink">{order.facilityName}</h2>
                <p className="mt-0.5 text-label text-muted">
                  Bắt đầu thuê {formatDate(order.startDate)} · {order.period} tháng
                </p>
              </div>
              <Button size="sm" onClick={() => onPick(order.id)}>
                Chọn lịch <ArrowRight size={15} />
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function SlotBooking({ orderId, navigate }: { orderId: number; navigate: Navigate }) {
  const loadSlots = useCallback(() => fetchAppointmentSlots(orderId), [orderId]);
  const loadOrder = useCallback(() => fetchMyOrder(orderId), [orderId]);
  const slots = useResource(loadSlots);
  const order = useResource(loadOrder);

  const [date, setDate] = useState<string | null>(null);
  const [startAt, setStartAt] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [booked, setBooked] = useState<BookedAppointment | null>(null);

  const days = slots.data?.days ?? [];
  const selectedDay = days.find((day) => day.date === date) ?? null;
  const orderRow = order.data?.order ?? null;

  async function confirm() {
    if (!date || !startAt) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      setBooked(await bookAppointment(orderId, date, startAt));
    } catch (caught) {
      setSubmitError(errorMessage(caught));
      setStartAt(null);
      slots.reload();
    } finally {
      setSubmitting(false);
    }
  }

  let main: ReactNode;
  if (booked) {
    main = (
      <div className="bg-surface p-5">
        <span className="text-label text-muted">Lịch hẹn của bạn</span>
        <h2 className="mt-1 text-section text-ink">
          {formatDate(booked.date)} · {formatTime(booked.startAt)} – {formatTime(booked.endAt)}
        </h2>
        <p className="mt-1.5 text-small text-muted">
          {booked.facilityName}
          {booked.facilityAddress ? ` · ${booked.facilityAddress}` : ""}
        </p>
        <DemoNotice tone="success">
          Đã đặt lịch hẹn check-in. Vui lòng đến đúng giờ hẹn — nhân viên cơ sở sẽ tiếp đón và bàn
          giao kho cho bạn.
        </DemoNotice>
        <div className="mt-5">
          <Button variant="secondary" size="sm" onClick={() => navigate("/my-storage")}>
            Về Kho của tôi
          </Button>
        </div>
      </div>
    );
  } else if (slots.loading && !slots.data) {
    main = <SurfaceState variant="loading" title="Đang tải khung giờ…" />;
  } else if (slots.error && !slots.data) {
    main = (
      <SurfaceState
        variant="error"
        title="Chưa thể đặt lịch cho đơn này"
        description={slots.error}
        action={{ label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }}
      />
    );
  } else if (days.length === 0) {
    main = (
      <SurfaceState
        variant="empty"
        title="Không còn khung giờ trống"
        description="Không có ngày nào trong cửa sổ đặt lịch còn khung trống. Vui lòng liên hệ cơ sở."
        action={{ label: "Thử lại", onClick: slots.reload }}
      />
    );
  } else {
    main = (
      <div className="bg-surface p-5">
        <span className="flex items-center gap-1.5 text-label text-muted">
          <Calendar size={14} /> Chọn ngày hẹn ({formatDate(slots.data?.minDate)} –{" "}
          {formatDate(slots.data?.maxDate)})
        </span>
        <div className="mt-2.5 flex flex-wrap gap-2">
          {days.map((day) => {
            const open = day.slots.some((slot) => slot.available);
            const active = day.date === date;
            return (
              <button
                key={day.date}
                type="button"
                disabled={!open}
                aria-pressed={active}
                className={`grid min-h-[52px] min-w-[72px] place-items-center border-2 px-3 text-small font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
                  active
                    ? "border-brand bg-brand text-signal-ink"
                    : "border-rule-strong bg-surface text-muted hover:border-ink hover:text-ink"
                }`}
                onClick={() => {
                  setDate(day.date);
                  setStartAt(null);
                }}
              >
                <span className="text-label">{weekday(day.date)}</span>
                {formatDate(day.date).slice(0, 5)}
              </button>
            );
          })}
        </div>

        <div className="mt-5">
          <span className="flex items-center gap-1.5 text-label text-muted">
            <Clock3 size={14} /> Chọn khung giờ
          </span>
          {selectedDay ? (
            <div className="mt-2.5 flex flex-wrap gap-2">
              {selectedDay.slots.map((slot) => (
                <button
                  key={slot.startAt}
                  type="button"
                  disabled={!slot.available}
                  aria-pressed={startAt === slot.startAt}
                  className={`min-h-[42px] border-2 px-4 text-small font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
                    startAt === slot.startAt
                      ? "border-brand bg-brand text-signal-ink"
                      : "border-rule-strong bg-surface text-muted hover:border-ink hover:text-ink"
                  }`}
                  onClick={() => setStartAt(slot.startAt)}
                >
                  {formatTime(slot.startAt)} – {formatTime(slot.endAt)}
                  {!slot.available && " · Hết chỗ"}
                </button>
              ))}
            </div>
          ) : (
            <p className="mt-2 text-small text-muted">Chọn một ngày để xem khung giờ.</p>
          )}
        </div>

        {submitError && <DemoNotice tone="error">{submitError}</DemoNotice>}

        <Button
          className="mt-6"
          disabled={!date || !startAt}
          pending={submitting}
          onClick={confirm}
        >
          {!submitting && <Check size={16} />} Xác nhận lịch hẹn
        </Button>
      </div>
    );
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Chọn lịch hẹn check-in</h1>
        <p className="mt-1.5 text-small text-muted">
          Đặt lịch đến cơ sở để kiểm tra khoang, ký hợp đồng và nhận bàn giao cho đơn #{orderId}
          {orderRow?.unitCode ? ` · khoang ${orderRow.unitCode}` : ""}.
        </p>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_320px] items-start gap-6 max-[980px]:grid-cols-1">
        <div>{main}</div>

        <aside className="grid gap-3">
          <div className="bg-surface p-5">
            <span className="text-label text-muted">Địa điểm</span>
            {orderRow ? (
              <>
                <h3 className="mt-1 text-section text-ink">{orderRow.facilityName}</h3>
                {orderRow.facilityAddress && (
                  <p className="mt-2 text-small leading-relaxed text-muted">
                    {orderRow.facilityAddress}
                  </p>
                )}
              </>
            ) : order.error ? (
              <p className="mt-1 text-small text-danger">{order.error}</p>
            ) : (
              <div className="mt-2 h-10 animate-pulse bg-surface-subtle" />
            )}
          </div>
          <div className="bg-surface p-5">
            <span className="flex items-center gap-1.5 text-label text-muted">
              <IdCard size={14} /> Cần chuẩn bị
            </span>
            <ul className="mt-2.5 grid gap-2 pl-0 text-small text-muted">
              <li className="flex items-start gap-2">
                <Check size={14} className="mt-0.5 shrink-0 text-success" /> Giấy tờ tuỳ thân bản
                gốc để nhân viên xác minh
              </li>
              <li className="flex items-start gap-2">
                <UserRound size={14} className="mt-0.5 shrink-0 text-success" /> Có mặt đúng người
                đứng tên đơn thuê
              </li>
            </ul>
          </div>
        </aside>
      </div>
    </div>
  );
}
