// Chạy toàn bộ migrations/*.sql theo thứ tự tên file: npm run db:migrate
// (Backend cũng tự chạy các file này lúc khởi động — script này để chạy tay khi cần.)
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
  const dir = path.join(__dirname, '..', 'migrations');
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();
  try {
    for (const file of files) {
      await pool.query(fs.readFileSync(path.join(dir, file), 'utf8'));
      console.log(`✔ ${file}`);
    }
    console.log('✔ Migrate xong.');
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error('✘ Migrate lỗi:', err.message);
  process.exit(1);
});
