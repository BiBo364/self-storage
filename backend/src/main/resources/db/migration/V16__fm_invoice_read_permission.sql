-- FM xem hoá đơn của cơ sở mình phụ trách. Quyền ownership của khách vẫn kiểm ở service,
-- không seed thành role_permissions.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'FACILITY'
FROM roles r
JOIN permissions p ON p.code = 'invoice.read'
WHERE r.name = 'FM'
ON CONFLICT (role_id, permission_id) DO UPDATE SET scope = EXCLUDED.scope;
