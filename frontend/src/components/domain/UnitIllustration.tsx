type UnitIllustrationProps = {
  unitCode: string;
  unitTypeName: string;
  area?: number;
  width?: number;
  depth?: number;
  height?: number;
};

/**
 * Hình minh hoạ theo loại khoang — dùng khi chưa có ảnh thật.
 * Không phải ảnh khoang cụ thể; panel nên ghi rõ "Hình minh hoạ".
 */
export function UnitIllustration({
  unitCode,
  unitTypeName,
  area,
  width,
  depth,
  height,
}: UnitIllustrationProps) {
  const dimensions =
    width !== undefined && depth !== undefined && height !== undefined
      ? `${width} × ${depth} × ${height} m`
      : "Kích thước đang cập nhật";

  return (
    <svg
      className="block h-auto w-full"
      viewBox="0 0 640 300"
      role="img"
      aria-label={`Hình minh hoạ khoang ${unitCode} loại ${unitTypeName}`}
    >
      <rect width="640" height="300" fill="var(--surface-subtle)" />
      <path d="M0 232H640V300H0Z" fill="var(--rule-strong)" opacity="0.35" />

      <text
        x="36"
        y="34"
        fill="var(--muted)"
        fontFamily="var(--font-display, sans-serif)"
        fontSize="10"
        fontWeight="700"
        letterSpacing="2"
      >
        HÌNH MINH HOẠ
      </text>

      <rect
        x="36"
        y="58"
        width="568"
        height="174"
        fill="var(--surface)"
        stroke="var(--rule-strong)"
        strokeWidth="2"
      />

      {[70, 245, 420].map((x, index) => (
        <g key={x}>
          <rect
            x={x}
            y="92"
            width="150"
            height="140"
            fill={index === 1 ? "var(--brand)" : "var(--surface-subtle)"}
            stroke="var(--ink)"
            strokeWidth={index === 1 ? 3 : 2}
          />
          {index === 1 ? (
            <>
              <rect
                x={x + 24}
                y="112"
                width="102"
                height="120"
                fill="none"
                stroke="var(--signal-ink)"
                strokeWidth="2"
              />
              <text
                x={x + 75}
                y="166"
                textAnchor="middle"
                fill="var(--signal-ink)"
                fontFamily="var(--font-display, sans-serif)"
                fontSize="22"
                fontWeight="700"
              >
                {unitCode}
              </text>
              <text
                x={x + 75}
                y="188"
                textAnchor="middle"
                fill="var(--signal-ink)"
                fontFamily="var(--font-sans, sans-serif)"
                fontSize="11"
              >
                {unitTypeName}
                {area !== undefined ? ` · ${area} m²` : ""}
              </text>
              <rect x={x + 58} y="202" width="34" height="4" fill="var(--signal-ink)" />
            </>
          ) : (
            <rect
              x={x + 24}
              y="112"
              width="102"
              height="120"
              fill="none"
              stroke="var(--rule-strong)"
              strokeWidth="1"
              strokeDasharray="6 6"
            />
          )}
        </g>
      ))}

      <line
        x1="70"
        y1="268"
        x2="570"
        y2="268"
        stroke="var(--ink)"
        strokeWidth="1"
        strokeDasharray="4 4"
      />
      <line x1="70" y1="262" x2="70" y2="274" stroke="var(--ink)" strokeWidth="1" />
      <line x1="570" y1="262" x2="570" y2="274" stroke="var(--ink)" strokeWidth="1" />
      <text
        x="320"
        y="288"
        textAnchor="middle"
        fill="var(--muted)"
        fontFamily="var(--font-sans, sans-serif)"
        fontSize="11"
      >
        {dimensions}
      </text>
    </svg>
  );
}
