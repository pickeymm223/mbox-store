// MBOX Store API — serves the store catalog and APK/icon files from R2.
//   GET  /catalog.json   -> catalog document (from R2 key "catalog.json")
//   GET  /files/<key>    -> file bytes from R2 (APKs, icons)
//   PUT  /admin/<key>    -> seed/update an R2 object (X-Admin-Secret header)
// The admin secret is injected at deploy time by deploy.py.
const ADMIN_SECRET = "__ADMIN_SECRET__";

const CT = {
  apk: "application/vnd.android.package-archive",
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  webp: "image/webp",
  json: "application/json",
};

function ctFor(key) {
  const ext = (key.split(".").pop() || "").toLowerCase();
  return CT[ext] || "application/octet-stream";
}

function badKey(key) {
  return !key || key.includes("..") || key.startsWith("/");
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname;

    if (req.method === "PUT" && path.startsWith("/admin/")) {
      if (req.headers.get("X-Admin-Secret") !== ADMIN_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      const key = decodeURIComponent(path.slice("/admin/".length));
      if (badKey(key)) return new Response("bad key", { status: 400 });
      await env.FILES.put(key, req.body, {
        httpMetadata: { contentType: ctFor(key) },
      });
      return Response.json({ ok: true, key });
    }

    if (req.method === "GET" && path === "/catalog.json") {
      const obj = await env.FILES.get("catalog.json");
      if (!obj) {
        return Response.json(
          { store: { name: "MBOX Store" }, apps: [] },
          { headers: { "Cache-Control": "public, max-age=60" } }
        );
      }
      return new Response(obj.body, {
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=60",
        },
      });
    }

    if (req.method === "GET" && path.startsWith("/files/")) {
      const key = decodeURIComponent(path.slice("/files/".length));
      if (badKey(key)) return new Response("bad key", { status: 400 });
      const obj = await env.FILES.get(key);
      if (!obj) return new Response("not found", { status: 404 });
      const h = new Headers();
      h.set("Content-Type", ctFor(key));
      h.set("Content-Length", String(obj.size));
      h.set("Cache-Control", "public, max-age=86400");
      return new Response(obj.body, { headers: h });
    }

    if (req.method === "GET" && path === "/") {
      return new Response("MBOX Store API — OK", {
        headers: { "Content-Type": "text/plain; charset=utf-8" },
      });
    }

    return new Response("not found", { status: 404 });
  },
};
