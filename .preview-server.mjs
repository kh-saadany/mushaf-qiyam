import http from 'http';

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(`<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="utf-8">
  <title>مصحف القيام — بيئة التنسيق المعماري</title>
  <style>
    body {
      background-color: #0f172a;
      color: #e2e8f0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100vh;
      margin: 0;
      text-align: center;
    }
    .card {
      background: #1e293b;
      padding: 2.5rem;
      border-radius: 1rem;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3);
      border: 1px solid #334155;
      max-width: 500px;
    }
    h1 {
      color: #38bdf8;
      font-size: 1.5rem;
      margin-bottom: 0.75rem;
    }
    p {
      color: #94a3b8;
      font-size: 0.95rem;
      line-height: 1.6;
      margin: 0;
    }
    .badge {
      display: inline-block;
      margin-top: 1.25rem;
      padding: 0.35rem 0.75rem;
      background: #064e3b;
      color: #34d399;
      border-radius: 9999px;
      font-size: 0.8rem;
      font-weight: 600;
    }
  </style>
</head>
<body>
  <div class="card">
    <h1>مصحف القيام — بيئة التنسيق المعماري</h1>
    <p>هذا المشروع هو تطبيق Android Native أصلي (Kotlin + C++ NDK). يجري التنسيق المعماري بين Google AI Studio و Google Antigravity عبر مجلد <code>.collab/</code>.</p>
    <div class="badge">البيئة نشطة وجاهزة للتنسيق ✓</div>
  </div>
</body>
</html>`);
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`[Preview Server] Live on port ${PORT}`);
});
