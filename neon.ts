import { defineConfig } from "@neon/config/v1";

// Chỉ dùng Lakebase Postgres (có sẵn ở mọi project): backend Spring Boot tự xác thực bằng JWT, ảnh lưu Cloudinary,
// nên không khai báo Neon Auth / Functions / buckets / realtime.
export default defineConfig({
  // Branch policy: per-branch tuning
  branch: (branch) => {
    if (branch.isDefault) {
      // Default branch: no overrides, uses project defaults
      return {};
    }
    if (!branch.exists) {
      // New non-default branches: auto-expire
      // Run `neon checkout <name>` to create a new branch with these settings
      return { ttl: "7d" };
    }
    // Existing branch: no changes
    return {};
  },
});
