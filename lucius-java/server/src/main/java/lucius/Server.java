package lucius;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import org.mindrot.jbcrypt.BCrypt;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lucius server: REST + WebSocket chat, SQLite storage, bcrypt passwords,
 * bearer-token sessions.
 */
public class Server {
  static final ObjectMapper J = new ObjectMapper();
  static final SecureRandom RNG = new SecureRandom();
  static final Map<Integer, Map<String, WsContext>> online = new ConcurrentHashMap<>();
  static final Map<String, Integer> who = new ConcurrentHashMap<>();
  static final Map<String, Deque<Long>> fails = new ConcurrentHashMap<>();
  static Connection db;

  // ---- database helpers ----
  static synchronized List<Map<String, Object>> q(String sql, Object... a) throws SQLException {
    try (var p = db.prepareStatement(sql)) {
      for (int i = 0; i < a.length; i++)
        p.setObject(i + 1, a[i]);
      try (var r = p.executeQuery()) {
        var md = r.getMetaData();
        var out = new ArrayList<Map<String, Object>>();
        while (r.next()) {
          var m = new LinkedHashMap<String, Object>();
          for (int i = 1; i <= md.getColumnCount(); i++)
            m.put(md.getColumnLabel(i), r.getObject(i));
          out.add(m);
        }
        return out;
      }
    }
  }

  static synchronized long x(String sql, Object... a) throws SQLException {
    try (var p = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      for (int i = 0; i < a.length; i++)
        p.setObject(i + 1, a[i]);
      int n = p.executeUpdate();
      try (var k = p.getGeneratedKeys()) {
        return k.next() ? k.getLong(1) : n;
      }
    }
  }

  // ---- small utilities ----
  static int id(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).intValue();
  }

  static String str(Map<String, Object> m, String k, int max) {
    Object o = m.get(k);
    String s = o instanceof String ? ((String) o).trim() : "";
    return s.length() > max ? s.substring(0, max) : s;
  }

  static void err(Context c, int code, String msg) {
    c.status(code).json(Map.of("error", msg));
  }

  static String js(Object o) {
    try {
      return J.writeValueAsString(o);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static void push(int uid, Object o) {
    var s = online.get(uid);
    if (s == null)
      return;
    final String j = js(o);
    s.values().forEach(w -> {
      try {
        w.send(j);
      } catch (Exception ignored) {
      }
    });
  }

  static boolean blocked(String ip) {
    var d = fails.computeIfAbsent(ip, k -> new ArrayDeque<>());
    synchronized (d) {
      long n = System.currentTimeMillis();
      while (!d.isEmpty() && n - d.peek() > 900_000)
        d.poll();
      return d.size() >= 10;
    }
  }

  static void failed(String ip) {
    var d = fails.computeIfAbsent(ip, k -> new ArrayDeque<>());
    synchronized (d) {
      d.add(System.currentTimeMillis());
    }
  }

  // ---- auth / data helpers ----
  static String session(int uid) throws SQLException {
    byte[] b = new byte[32];
    RNG.nextBytes(b);
    String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    x("INSERT INTO sessions(token,user_id) VALUES(?,?)", t, uid);
    return t;
  }

  static Integer uid(String token) throws SQLException {
    if (token == null)
      return null;
    var r = q("SELECT user_id FROM sessions WHERE token=?", token);
    return r.isEmpty() ? null : id(r.get(0), "user_id");
  }

  static Integer auth(Context c) throws SQLException {
    String h = c.header("Authorization");
    Integer u = h != null && h.startsWith("Bearer ") ? uid(h.substring(7)) : null;
    if (u == null)
      err(c, 401, "Log in to continue.");
    return u;
  }

  static Map<String, Object> user(int id) throws SQLException {
    return q("SELECT id,name,email FROM users WHERE id=?", id).get(0);
  }

  static boolean friends(int a, int b) throws SQLException {
    return !q(
        "SELECT 1 FROM friends WHERE status='accepted' AND ((requester=? AND addressee=?) OR (requester=? AND addressee=?))",
        a, b, b, a).isEmpty();
  }

  public static void main(String[] args) throws Exception {
    db = DriverManager.getConnection("jdbc:sqlite:" + System.getenv().getOrDefault("DB_PATH", "lucius.db"));
    for (String s : new String[] {
        "PRAGMA journal_mode=WAL",
        "CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT UNIQUE NOT NULL, password TEXT NOT NULL, created INTEGER DEFAULT (strftime('%s','now')))",
        "CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user_id INTEGER NOT NULL, created INTEGER DEFAULT (strftime('%s','now')))",
        "CREATE TABLE IF NOT EXISTS friends(requester INTEGER NOT NULL, addressee INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending', PRIMARY KEY(requester,addressee))",
        "CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, sender INTEGER NOT NULL, receiver INTEGER NOT NULL, body TEXT NOT NULL, created INTEGER DEFAULT (strftime('%s','now')))",
        "CREATE INDEX IF NOT EXISTS ix_msg ON messages(sender,receiver)" }) {
      try (var st = db.createStatement()) {
        st.execute(s);
      }
    }

    Javalin app = Javalin.create();
    app.get("/health", c -> c.result("ok"));

    app.post("/api/register", c -> {
      if (blocked(c.ip())) {
        err(c, 429, "Too many attempts. Try again in 15 minutes.");
        return;
      }
      Map<String, Object> b = c.bodyAsClass(Map.class);
      String n = str(b, "name", 40), e = str(b, "email", 120).toLowerCase(), pw = str(b, "password", 200);
      if (n.isEmpty() || !e.matches("^\\S+@\\S+\\.\\S+$") || pw.length() < 8) {
        err(c, 400, "Enter your name, a valid email and a password of 8+ characters.");
        return;
      }
      if (!q("SELECT 1 FROM users WHERE email=?", e).isEmpty()) {
        err(c, 409, "That email already has an account. Log in instead.");
        return;
      }
      failed(c.ip());
      int id = (int) x("INSERT INTO users(name,email,password) VALUES(?,?,?)", n, e,
          BCrypt.hashpw(pw, BCrypt.gensalt(12)));
      c.json(Map.of("token", session(id), "user", user(id)));
    });

    app.post("/api/login", c -> {
      if (blocked(c.ip())) {
        err(c, 429, "Too many attempts. Try again in 15 minutes.");
        return;
      }
      Map<String, Object> b = c.bodyAsClass(Map.class);
      var r = q("SELECT * FROM users WHERE email=?", str(b, "email", 120).toLowerCase());
      if (r.isEmpty() || !BCrypt.checkpw(str(b, "password", 200), (String) r.get(0).get("password"))) {
        failed(c.ip());
        err(c, 401, "Wrong email or password.");
        return;
      }
      int id = id(r.get(0), "id");
      c.json(Map.of("token", session(id), "user", user(id)));
    });

    app.get("/api/me", c -> {
      Integer me = auth(c);
      if (me != null)
        c.json(user(me));
    });

    app.get("/api/search", c -> {
      Integer me = auth(c);
      if (me == null)
        return;
      String s = c.queryParam("q") == null ? "" : c.queryParam("q").trim().toLowerCase();
      if (s.length() < 2) {
        c.json(List.of());
        return;
      }
      c.json(q(
          "SELECT u.id,u.name,COALESCE((SELECT status FROM friends f WHERE (f.requester=? AND f.addressee=u.id) OR (f.requester=u.id AND f.addressee=?)),'') AS rel "
              + "FROM users u WHERE u.id!=? AND (lower(u.name) LIKE ? ESCAPE '\\' OR u.email=?) LIMIT 20",
          me, me, me, "%" + s.replaceAll("([%_\\\\])", "\\\\$1") + "%", s));
    });

    app.get("/api/friends", c -> {
      Integer me = auth(c);
      if (me == null)
        return;
      c.json(Map.of(
          "friends",
          q("SELECT u.id,u.name FROM friends f JOIN users u ON u.id=(CASE WHEN f.requester=? THEN f.addressee ELSE f.requester END) "
              + "WHERE f.status='accepted' AND ? IN (f.requester,f.addressee) ORDER BY u.name", me, me),
          "incoming",
          q("SELECT u.id,u.name FROM friends f JOIN users u ON u.id=f.requester WHERE f.addressee=? AND f.status='pending'",
              me)));
    });

    app.post("/api/friends/request", c -> {
      Integer me = auth(c);
      if (me == null)
        return;
      Map<String, Object> b = c.bodyAsClass(Map.class);
      int to = ((Number) b.getOrDefault("userId", 0)).intValue();
      if (to == 0 || to == me || q("SELECT 1 FROM users WHERE id=?", to).isEmpty()) {
        err(c, 400, "That student does not exist.");
        return;
      }
      var ex = q("SELECT * FROM friends WHERE (requester=? AND addressee=?) OR (requester=? AND addressee=?)", me, to,
          to, me);
      if (ex.isEmpty())
        x("INSERT INTO friends(requester,addressee) VALUES(?,?)", me, to);
      else if ("pending".equals(ex.get(0).get("status")) && id(ex.get(0), "requester") == to)
        x("UPDATE friends SET status='accepted' WHERE requester=? AND addressee=?", to, me);
      else {
        err(c, 409, "Already friends or request pending.");
        return;
      }
      push(me, Map.of("type", "friends"));
      push(to, Map.of("type", "friends"));
      c.json(Map.of("ok", true));
    });

    app.post("/api/friends/respond", c -> {
      Integer me = auth(c);
      if (me == null)
        return;
      Map<String, Object> b = c.bodyAsClass(Map.class);
      int from = ((Number) b.getOrDefault("userId", 0)).intValue();
      if (Boolean.TRUE.equals(b.get("accept")))
        x("UPDATE friends SET status='accepted' WHERE requester=? AND addressee=? AND status='pending'", from, me);
      else
        x("DELETE FROM friends WHERE requester=? AND addressee=? AND status='pending'", from, me);
      push(me, Map.of("type", "friends"));
      push(from, Map.of("type", "friends"));
      c.json(Map.of("ok", true));
    });

    app.get("/api/messages/{id}", c -> {
      Integer me = auth(c);
      if (me == null)
        return;
      int o = Integer.parseInt(c.pathParam("id"));
      if (!friends(me, o)) {
        err(c, 403, "You can only chat with friends.");
        return;
      }
      c.json(q(
          "SELECT * FROM (SELECT id,sender,receiver,body,created FROM messages WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?) "
              + "ORDER BY id DESC LIMIT 100) ORDER BY id",
          me, o, o, me));
    });

    app.ws("/ws", ws -> {
      ws.onConnect(c -> {
        Integer u = uid(c.queryParam("token"));
        if (u == null) {
          c.closeSession(4401, "auth");
          return;
        }
        who.put(c.sessionId(), u);
        online.computeIfAbsent(u, k -> new ConcurrentHashMap<>()).put(c.sessionId(), c);
      });
      ws.onMessage(c -> {
        Integer me = who.get(c.sessionId());
        if (me == null)
          return;
        Map<String, Object> m = J.readValue(c.message(), Map.class);
        if (!(m.get("body") instanceof String))
          return;
        String t = ((String) m.get("body")).trim();
        if (t.length() > 2000)
          t = t.substring(0, 2000);
        int to = ((Number) m.getOrDefault("to", 0)).intValue();
        if (t.isEmpty() || !friends(me, to))
          return;
        long id = x("INSERT INTO messages(sender,receiver,body) VALUES(?,?,?)", me, to, t);
        var out = Map.of("type", "msg", "id", id, "sender", me, "receiver", to, "body", t, "created",
            System.currentTimeMillis() / 1000);
        push(me, out);
        push(to, out);
      });
      ws.onClose(c -> {
        Integer me = who.remove(c.sessionId());
        if (me != null && online.get(me) != null)
          online.get(me).remove(c.sessionId());
      });
    });

    app.start(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")));
  }
}
