@echo off
rem Nạp dữ liệu demo (backend\sql\dev\demo-seed.sql) vào Postgres của docker compose.
rem Chạy từ đâu cũng được: backend\sql\dev\seed-demo.cmd [tên DB, mặc định self_storage] (cmd hoặc PowerShell).
rem Cần: docker compose up -d và backend đã khởi động trên DB này (Flyway dựng schema) — lệnh tự đợi tối đa 3 phút.
setlocal
chcp 65001 >nul
cd /d "%~dp0..\..\.."
set DB=%~1
if "%DB%"=="" set DB=self_storage

set tries=0
:wait_flyway
docker compose exec -T postgres psql -U self_storage -d %DB% -tAc "select 'flyway-ready' from flyway_schema_history where version = '34' and success" 2>nul | findstr "flyway-ready" >nul
if not errorlevel 1 goto seed
set /a tries+=1
if %tries% geq 60 (
  echo Chua thay migration V34 sau 3 phut. Kiem tra: docker compose up -d, roi bat backend cho Flyway chay xong.
  exit /b 1
)
if %tries% equ 1 echo Dang doi backend chay Flyway...
ping -n 4 127.0.0.1 >nul
goto wait_flyway

:seed
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U self_storage -d %DB% < backend\sql\dev\demo-seed.sql
if errorlevel 1 (
  echo.
  echo Seed that bai - xem loi o tren. Khong co gi duoc ghi vao DB.
  exit /b 1
)
echo.
echo Da nap du lieu demo. Mat khau moi tai khoan: Test@1234
endlocal
