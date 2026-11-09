import { Users } from "lucide-react";
import { useEffect, useState } from "react";
import { errorMessage, errorStatus } from "../../app/http";
import {
  fetchBomFacilities,
  fetchBomStaff,
  setBomStaffFacilities,
  type FacilityRow,
  type StaffRow,
} from "../../app/services/bom";
import { fetchBomStaffProfile, updateBomStaffProfile } from "../../app/services/hr";
import { StaffProfileEditor } from "../../components/domain/StaffProfileSection";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

/** BOM điều phối FS giữa các cơ sở. Tài khoản giữ nguyên — chỉ đổi danh sách cơ sở được gán. */
export function BomStaffPage(_props: { navigate: Navigate }) {
  const [staff, setStaff] = useState<StaffRow[]>([]);
  const [facilities, setFacilities] = useState<FacilityRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [staffList, facilityList] = await Promise.all([
          fetchBomStaff(),
          fetchBomFacilities(),
        ]);
        if (!active) return;
        setStaff(staffList);
        setFacilities(facilityList);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [reloadKey]);

  function reload() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  const facilityLabel = (id: number) =>
    facilities.find((facility) => facility.id === id)?.code ?? `#${id}`;
  const openStaff = staff.find((member) => member.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Nhân viên FS</h1>
        <p className="mt-1.5 text-small text-muted">
          Gán hoặc chuyển FS giữa các cơ sở. FS còn việc chưa đóng ở cơ sở bị bỏ thì không chuyển
          được — xử lý các việc đó trước.
        </p>
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải nhân viên FS…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền điều phối FS"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được danh sách FS"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : staff.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có tài khoản FS"
          description="ADMIN tạo tài khoản FS trước, sau đó BOM gán cơ sở."
        />
      ) : (
        <div className="grid gap-2">
          {staff.map((member) => (
            <button
              key={member.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => {
                setNotice(null);
                setOpenId(member.id);
              }}
            >
              <div className="min-w-0">
                <strong className="flex items-center gap-2 text-section text-ink">
                  <Users size={16} /> {member.name ?? member.email}
                </strong>
                <span className="mt-1 block text-small text-muted">
                  {member.email}
                  {member.phone ? ` · ${member.phone}` : ""}
                </span>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {member.status !== "ACTIVE" && <StatusTag tone="danger">Đã khoá</StatusTag>}
                <span className="text-small text-ink">
                  {member.facilityIds.length > 0
                    ? member.facilityIds.map(facilityLabel).join(", ")
                    : "Chưa gán cơ sở"}
                </span>
              </div>
            </button>
          ))}
        </div>
      )}

      {openStaff && (
        <AssignPanel
          key={openStaff.id}
          member={openStaff}
          facilities={facilities}
          onClose={() => setOpenId(null)}
          onSaved={(row) => {
            setStaff((list) => list.map((item) => (item.id === row.id ? row : item)));
            setOpenId(null);
            setNotice(`Đã cập nhật cơ sở cho ${row.name ?? row.email}.`);
          }}
        />
      )}
    </div>
  );
}

function AssignPanel({
  member,
  facilities,
  onClose,
  onSaved,
}: {
  member: StaffRow;
  facilities: FacilityRow[];
  onClose: () => void;
  onSaved: (row: StaffRow) => void;
}) {
  const [facilityIds, setFacilityIds] = useState<number[]>(member.facilityIds);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      onSaved(await setBomStaffFacilities(member.id, facilityIds));
    } catch (caught) {
      // Lưu bị từ chối thì không có gì đổi — ô chọn quay về cơ sở đang gán thật.
      setFacilityIds(member.facilityIds);
      setError(`Chưa lưu: ${errorMessage(caught)}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <DetailPanel
      title={member.name ?? member.email}
      eyebrow={`FS #${member.id}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Huỷ
          </Button>
          <Button pending={busy} onClick={save}>
            Lưu cơ sở
          </Button>
        </>
      }
    >
      <div className="grid gap-3">
        <SectionTitle>Cơ sở được gán</SectionTitle>
        {facilities.map((facility) => (
          <label key={facility.id} className="flex items-center gap-2.5 text-small text-ink">
            <input
              type="checkbox"
              className="h-4 w-4 accent-[var(--brand)]"
              checked={facilityIds.includes(facility.id)}
              onChange={(event) =>
                setFacilityIds((ids) =>
                  event.target.checked
                    ? [...ids, facility.id]
                    : ids.filter((id) => id !== facility.id),
                )
              }
            />
            {facility.name} <span className="text-label text-muted">({facility.code})</span>
            {facility.status !== "Active" && (
              <span className="text-label text-muted">· ngừng hoạt động</span>
            )}
          </label>
        ))}
        <p className="m-0 text-label text-muted">
          Bỏ một cơ sở mà FS còn lịch hẹn, phiên bàn giao hoặc yêu cầu hỗ trợ chưa đóng ở đó sẽ bị
          từ chối kèm danh sách việc và cách xử lý từng việc.
        </p>
      </div>
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
      <div className="mt-6">
        <StaffProfileEditor
          load={() => fetchBomStaffProfile(member.id)}
          save={(input) => updateBomStaffProfile(member.id, input)}
        />
      </div>
    </DetailPanel>
  );
}
