// Chạy schema khởi tạo DB: npm run db:migrate
// Đọc DATABASE_URL từ file .env (cùng cấp thư mục backend/).
require('dotenv').config();
const fs = require('fs');
const path = require('path');
const { Pool } = require('pg');

async function main() {
  if (!process.env.DATABASE_URL) {
    console.error('Thiếu DATABASE_URL — kiểm tra file backend/.env (copy từ .env.example).');
    process.exit(1);
  }
  const pool = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: process.env.DATABASE_SSL === 'false' ? false : { rejectUnauthorized: false },
  });
  const sqlPath = path.join(__dirname, '..', 'migrations', '001_init.sql');
  const sql = fs.readFileSync(sqlPath, 'utf8');
  try {
    await pool.query(sql);
    console.log('✔ Migrate xong: bảng users, documents đã sẵn sàng.');
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error('✘ Migrate lỗi:', err.message);
  process.exit(1);
});
