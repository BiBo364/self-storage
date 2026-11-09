import { Mail, Phone, UserRound } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import { fetchCurrentFmFacility, type FmFacility } from "../../app/fmFacility";
import { fetchFacilityStaff, staffDaySummary, type FmStaff } from "../../app/fmStaff";
import { errorMessage } from "../../app/http";
import { fetchFmStaffProfile, type StaffProfileSummary } from "../../app/services/hr";
import { StaffProfileDetails } from "../../components/domain/StaffProfileSection";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { SurfaceState } from "../../components/ui/SurfaceState";

function formatDate(iso: string) {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()}`;
}

function staffName(staff: FmStaff) {
  return staff.name ?? staff.email;
}

export function StaffListPage() {
  const [facility, setFacility] = useState<FmFacility | null>(null);
  const [staff, setStaff] = useState<FmStaff[]>([]);
  const [openId, setOpenId] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;

    (async () => {
      try {
        const current = await fetchCurrentFmFacility();
        const staffList = await fetchFacilityStaff(current.id);
        if (!active) return;
        setFacility(current);
        setStaff(staffList);
      } catch (caught) {
        if (!active) return;
        setError(
          caught instanceof ApiError
            ? { status: caught.status, message: caught.message }
            : { status: 0, message: "Không thể kết nối tới máy chủ." },
        );
      } finally {
        if (active) setIsLoading(false);
      }
    })();

    return () => {
      active = false;
    };
  }, [reloadKey]);

  const openStaff = staff.find((member) => member.id === openId) ?? null;

  function retry() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  if (isLoading) {
    return <SurfaceState variant="loading" title="Đang tải nhân viên…" />;
  }

  if (error) {
    if (error.status === 403 || error.status === 404) {
      return (
        <SurfaceState
          variant="forbidden"
          title="Bạn không có quyền xem nhân viên cơ sở"
          description={error.message}
        />
      );
    }
    return (
      <SurfaceState
        variant="error"
        title="Không tải được danh sách nhân viên"
        description={error.message}
        action={{ label: "Thử lại", onClick: retry }}
      />
    );
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Danh sách nhân viên FS</h1>
        <p className="mt-1.5 text-small text-muted">
          Nhân viên hiện trường được gán vào cơ sở {facility?.name ?? ""} — chỉ xem, việc gán/xoá
          thuộc quyền Admin.
        </p>
      </div>

      {staff.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có FS nào được gán vào cơ sở này"
          description="Nếu cần thêm nhân sự, gửi yêu cầu qua Business Operation Manager."
        />
      ) : (
        <div className="grid gap-2">
          {staff.map((member) => (
            <button
              key={member.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => setOpenId(member.id)}
            >
              <div>
                <strong className="flex items-center gap-2 text-small font-semibold text-ink">
                  <UserRound size={15} /> {staffName(member)}
                </strong>
                <span className="mt-1 flex items-center gap-1.5 text-label text-muted">
                  <Mail size={13} /> {member.email}
                </span>
                {member.phone && (
                  <span className="mt-1 flex items-center gap-1.5 text-label text-muted">
                    <Phone size={13} /> {member.phone}
                  </span>
                )}
                <span className="mt-1 block text-label text-muted">
                  Gán vào cơ sở từ {formatDate(member.assignedAt)}
                </span>
                <span className="mt-1 block text-label text-muted">
                  Hôm nay: {staffDaySummary(member)}
                </span>
              </div>
            </button>
          ))}
        </div>
      )}

      {openStaff && <StaffDetailPanel staff={openStaff} onClose={() => setOpenId(null)} />}
    </div>
  );
}

function StaffDetailPanel({ staff, onClose }: { staff: FmStaff; onClose: () => void }) {
  const [profile, setProfile] = useState<StaffProfileSummary | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchFmStaffProfile(staff.id).then(
      (result) => active && setProfile(result),
      (caught: unknown) => active && setProfileError(errorMessage(caught)),
    );
    return () => {
      active = false;
    };
  }, [staff.id]);

  return (
    <DetailPanel title={staffName(staff)} eyebrow="Nhân viên FS" onClose={onClose}>
      <div className="grid gap-2 text-small text-muted">
        <span className="flex items-center gap-2">
          <Mail size={14} /> {staff.email}
        </span>
        <span className="flex items-center gap-2">
          <Phone size={14} /> {staff.phone ?? "—"}
        </span>
        <span>Gán vào cơ sở từ {formatDate(staff.assignedAt)}</span>
        <span>Hôm nay: {staffDaySummary(staff)}</span>
      </div>
      <div className="mt-6 grid gap-3">
        <SectionTitle>Hồ sơ</SectionTitle>
        {profileError ? (
          <DemoNotice tone="error">Không tải được hồ sơ: {profileError}</DemoNotice>
        ) : profile ? (
          <StaffProfileDetails profile={profile} />
        ) : (
          <p className="m-0 text-label text-muted">Đang tải hồ sơ…</p>
        )}
      </div>
    </DetailPanel>
  );
}
