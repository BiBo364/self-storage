import type { ReactNode } from "react";

type FieldProps = {
  label: string;
  name: string;
  type?: string;
  required?: boolean;
  placeholder?: string;
  icon?: ReactNode;
  suffix?: string;
  defaultValue?: string;
};

export const FIELD_GROUP = "mb-4";
export const FIELD_LABEL = "mb-1.5 block text-label text-muted";
export const INPUT_WRAP =
  "relative flex min-h-[42px] items-center gap-2.5 rounded-[2px] border border-rule-strong bg-surface px-3 text-muted focus-within:border-brand focus-within:shadow-[0_0_0_3px_var(--brand-soft)] [&>svg:last-child]:pointer-events-none [&>svg:last-child]:ml-auto";
export const INPUT_ELEMENT =
  "min-h-[40px] w-full min-w-0 appearance-none border-0 bg-transparent text-small text-ink outline-0 placeholder:text-muted";

export function FormField({
  label,
  name,
  type = "text",
  required,
  placeholder,
  icon,
  suffix,
  defaultValue,
}: FieldProps) {
  return (
    <div className={FIELD_GROUP}>
      <label className={FIELD_LABEL} htmlFor={name}>
        {label}
        {required && (
          <span className="text-danger" aria-hidden="true">
            {" *"}
          </span>
        )}
      </label>
      <div className={INPUT_WRAP}>
        {icon}
        <input
          id={name}
          name={name}
          type={type}
          required={required}
          placeholder={placeholder}
          defaultValue={defaultValue}
          autoComplete={name === "email" ? "email" : undefined}
          className={INPUT_ELEMENT}
        />
        {suffix && <span className="shrink-0 text-label text-muted">{suffix}</span>}
      </div>
    </div>
  );
}

export function SelectField({
  label,
  name,
  options,
  required,
  value,
  onChange,
  placeholder = "Chọn một lựa chọn",
}: {
  label: string;
  name: string;
  options: { value: string; label: string }[];
  required?: boolean;
  value?: string;
  onChange?: (value: string) => void;
  placeholder?: string;
}) {
  return (
    <div className={FIELD_GROUP}>
      <label className={FIELD_LABEL} htmlFor={name}>
        {label}
        {required && (
          <span className="text-danger" aria-hidden="true">
            {" *"}
          </span>
        )}
      </label>
      <div className={INPUT_WRAP}>
        <select
          id={name}
          name={name}
          required={required}
          value={value}
          onChange={(event) => onChange?.(event.target.value)}
          className={`${INPUT_ELEMENT} invalid:text-muted`}
        >
          <option value="">{placeholder}</option>
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <ChevronDownIcon />
      </div>
    </div>
  );
}

function ChevronDownIcon() {
  return (
    <svg
      aria-hidden="true"
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}
