package lucius;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.prefs.Preferences;


public class App extends Application {

    static final String DEFAULT_SERVER = "http://localhost:8080";
    static final ObjectMapper J = new ObjectMapper();

    final Preferences prefs = Preferences.userNodeForPackage(App.class);
    final HttpClient http = HttpClient.newHttpClient();
    final StackPane root = new StackPane();
    final Pane bg = new Pane();
    final Set<Integer> unread = new HashSet<>();
    String server = prefs.get("server", DEFAULT_SERVER), token, tab = "friends";
    volatile boolean reconnect, retrying;
    volatile WebSocket ws;
    JsonNode me, active, friends = J.createArrayNode(), incoming = J.createArrayNode();
    VBox side, log, results;
    HBox head, bar;
    ScrollPane scroll;
    TextField input, findBox;

    public static void main(String[] a) {
        launch(a);
    }

    @Override
    public void start(Stage st) {
        Canvas c = new Canvas();
        bg.getChildren().add(c);
        bg.setMouseTransparent(true);
        c.widthProperty().bind(bg.widthProperty());
        c.heightProperty().bind(bg.heightProperty());
        stars(c);
        var sc = new Scene(root, 1100, 720, Color.BLACK);
        sc.getStylesheets().add(getClass().getResource("/lucius.css").toExternalForm());
        st.setTitle("Lucius");
        st.setScene(sc);
        st.setMinWidth(820);
        st.setMinHeight(560);
        st.show();
        Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                })
                .scheduleAtFixedRate(() -> {
                    try {
                        var w = ws;
                        if (w != null) w.sendPing(ByteBuffer.allocate(0));
                    } catch (Exception ignored) {
                    }
                }, 20, 20, TimeUnit.SECONDS);
        token = prefs.get("token", null);
        if (token == null) login(false);
        else {
            view(new Region());
            call("GET", "/api/me", null, r -> {
                me = r;
                home();
            }, e -> {
                token = null;
                login(false);
            });
        }
    }


    void stars(Canvas c) {
        Random r = new Random(7);
        double[][] s = new double[260][4];
        for (var a : s) {
            a[0] = r.nextDouble();
            a[1] = r.nextDouble();
            a[2] = .00002 + r.nextDouble() * .00012;
            a[3] = .6 + r.nextDouble() * 1.7;
        }
        new AnimationTimer() {
            @Override
            public void handle(long t) {
                double w = c.getWidth(), h = c.getHeight();
                var g = c.getGraphicsContext2D();
                g.setFill(Color.BLACK);
                g.fillRect(0, 0, w, h);
                g.setFill(new RadialGradient(0, 0, w * .2, h * .1, w * .6, false, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#5b2bd9", .30)), new Stop(1, Color.TRANSPARENT)));
                g.fillRect(0, 0, w, h);
                g.setFill(new RadialGradient(0, 0, w * .85, h * .9, w * .55, false, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#1d6bff", .24)), new Stop(1, Color.TRANSPARENT)));
                g.fillRect(0, 0, w, h);
                for (var a : s) {
                    a[0] -= a[2];
                    if (a[0] < 0) a[0] += 1;
                    g.setFill(Color.color(1, 1, 1, .55 + .45 * Math.sin(t / 4e8 + a[1] * 50)));
                    g.fillOval(a[0] * w, a[1] * h, a[3], a[3]);
                }
            }
        }.start();
    }


    void view(Node n) {
        root.getChildren().setAll(bg, n);
    }

    static String wide(String s) {
        return String.join("\u2009\u2009", s.split(""));
    }

    static <T extends Region> T grow(T n) {
        HBox.setHgrow(n, Priority.ALWAYS);
        n.setMaxWidth(Double.MAX_VALUE);
        return n;
    }

    Label lbl(String t, String... cls) {
        var l = new Label(t);
        l.getStyleClass().addAll(cls);
        return l;
    }

    Button btn(String t, String cls, Runnable r) {
        var b = new Button(t);
        b.getStyleClass().add(cls);
        b.setOnAction(e -> r.run());
        return b;
    }

    Label avatar(String n) {
        return lbl(n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(), "av");
    }

    Region dot() {
        var d = new Region();
        d.getStyleClass().add("dot");
        return d;
    }

    String js(Object o) {
        try {
            return J.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void call(String m, String p, Object b, Consumer<JsonNode> ok, Consumer<String> err) {
        try {
            var rb = HttpRequest.newBuilder(URI.create(server + p)).header("Content-Type", "application/json").timeout(java.time.Duration.ofSeconds(15));
            if (token != null) rb.header("Authorization", "Bearer " + token);
            rb.method(m, b == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(js(b)));
            http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> Platform.runLater(() -> {
                if (e != null) {
                    err.accept("Can't reach the server at " + server + ". Check the address and your connection.");
                    return;
                }
                try {
                    JsonNode j = r.body().isBlank() ? J.createObjectNode() : J.readTree(r.body());
                    if (r.statusCode() == 401 && token != null) {
                        logout();
                        return;
                    }
                    if (r.statusCode() >= 400) err.accept(j.path("error").asText("Something went wrong."));
                    else ok.accept(j);
                } catch (Exception x) {
                    err.accept("Unexpected server response.");
                }
            }));
        } catch (Exception x) {
            err.accept("Invalid server address.");
        }
    }


    void login(boolean register) {
        token = null;
        var name = new TextField();
        name.setPromptText("Name");
        var email = new TextField();
        email.setPromptText("Email");
        var pw = new PasswordField();
        pw.setPromptText("Password (8+ characters)");
        var srv = new TextField(server);
        srv.setPromptText("Server address");
        var err = lbl("", "err");
        err.setWrapText(true);
        var go = btn(register ? "Create account" : "Log in", "primary", () -> {
        });
        go.setMaxWidth(Double.MAX_VALUE);
        go.setDefaultButton(true);
        go.setOnAction(e -> {
            server = srv.getText().trim().replaceAll("/+$", "");
            prefs.put("server", server);
            err.setText("");
            Map<String, String> body = register
                    ? Map.of("name", name.getText(), "email", email.getText(), "password", pw.getText())
                    : Map.of("email", email.getText(), "password", pw.getText());
            call("POST", register ? "/api/register" : "/api/login", body,
                    r -> {
                        token = r.get("token").asText();
                        prefs.put("token", token);
                        me = r.get("user");
                        home();
                    }, err::setText);
        });
        var sw = new Hyperlink(register ? "I already have an account" : "Create a new account");
        sw.setOnAction(e -> login(!register));
        var card = new VBox(12, lbl(wide("LUCIUS"), "logo"), lbl("Chat with students across the galaxy.", "tagline"));
        if (register) card.getChildren().add(name);
        card.getChildren().addAll(email, pw, err, go, sw, lbl("Server", "dim"), srv);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxSize(400, Region.USE_PREF_SIZE);
        view(card);
    }

    void logout() {
        reconnect = false;
        var w = ws;
        if (w != null) w.abort();
        ws = null;
        token = null;
        prefs.remove("token");
        active = null;
        unread.clear();
        friends = J.createArrayNode();
        incoming = J.createArrayNode();
        login(false);
    }


    void home() {
        side = new VBox(12);
        side.getStyleClass().add("panel");
        side.setPrefWidth(320);
        side.setMinWidth(320);
        side.setPadding(new Insets(16));
        head = new HBox(10);
        head.getStyleClass().add("chead");
        log = new VBox(8);
        log.setPadding(new Insets(18));
        scroll = new ScrollPane(log);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        input = new TextField();
        input.setPromptText("Write a message");
        HBox.setHgrow(input, Priority.ALWAYS);
        Runnable send = () -> {
            String t = input.getText().trim();
            var w = ws;
            if (t.isEmpty() || active == null || w == null) return;
            w.sendText(js(Map.of("to", active.get("id").asInt(), "body", t)), true);
            input.clear();
        };
        input.setOnAction(e -> send.run());
        bar = new HBox(10, input, btn("Send", "primary", send));
        bar.setPadding(new Insets(14));
        bar.getStyleClass().add("bar");
        var chat = new VBox(head, scroll, bar);
        chat.getStyleClass().add("panel");
        HBox.setHgrow(chat, Priority.ALWAYS);
        findBox = new TextField();
        findBox.setPromptText("Search by name or exact email");
        results = new VBox(6);
        var deb = new PauseTransition(Duration.millis(300));
        deb.setOnFinished(e -> search());
        findBox.textProperty().addListener((o, a, b) -> deb.playFromStart());
        var shell = new HBox(14, side, chat);
        shell.setPadding(new Insets(14));
        view(shell);
        connect();
        loadFriends();
        drawChat();
    }

    void connect() {
        reconnect = true;
        http.newWebSocketBuilder().buildAsync(URI.create(server.replaceFirst("^http", "ws") + "/ws?token=" + token), new WebSocket.Listener() {
            final StringBuilder sb = new StringBuilder();

            @Override
            public void onOpen(WebSocket w) {
                ws = w;
                w.request(1);
                Platform.runLater(() -> {
                    loadFriends();
                    if (active != null) open(active);
                });
            }

            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean last) {
                sb.append(d);
                if (last) {
                    String t = sb.toString();
                    sb.setLength(0);
                    Platform.runLater(() -> push(t));
                }
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket w, int code, String why) {
                lost();
                return null;
            }

            @Override
            public void onError(WebSocket w, Throwable e) {
                lost();
            }
        }).exceptionally(e -> {
            lost();
            return null;
        });
    }

    void lost() {
        ws = null;
        if (!reconnect || token == null || retrying) return;
        retrying = true;
        Platform.runLater(() -> {
            var p = new PauseTransition(Duration.seconds(3));
            p.setOnFinished(e -> {
                retrying = false;
                if (reconnect && token != null) connect();
            });
            p.play();
        });
    }

    void push(String t) {
        try {
            JsonNode m = J.readTree(t);
            if ("friends".equals(m.path("type").asText())) {
                loadFriends();
                return;
            }
            int s = m.get("sender").asInt(), r = m.get("receiver").asInt(), o = s == me.get("id").asInt() ? r : s;
            if (active != null && active.get("id").asInt() == o) bubble(m);
            else {
                unread.add(o);
                drawSide();
            }
        } catch (Exception ignored) {
        }
    }

    void loadFriends() {
        call("GET", "/api/friends", null, r -> {
            friends = r.get("friends");
            incoming = r.get("incoming");
            drawSide();
        }, e -> {
        });
    }

    void respond(JsonNode u, boolean ok) {
        call("POST", "/api/friends/respond", Map.of("userId", u.get("id").asInt(), "accept", ok), r -> loadFriends(), e -> {
        });
    }

    void search() {
        String q = findBox.getText().trim();
        if (q.length() < 2) {
            results.getChildren().clear();
            return;
        }
        call("GET", "/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), null, r -> {
            results.getChildren().clear();
            if (r.size() == 0) results.getChildren().add(lbl("No students found. Try their full email.", "dim"));
            for (var u : r) {
                String rel = u.path("rel").asText("");
                Node act = rel.equals("accepted") ? lbl("Friends", "dim") : rel.equals("pending") ? lbl("Pending", "dim")
                        : btn("Add", "mini", () -> call("POST", "/api/friends/request", Map.of("userId", u.get("id").asInt()), x -> search(), e -> {
                }));
                results.getChildren().add(row(u, act));
            }
        }, e -> {
        });
    }

    void open(JsonNode u) {
        active = u;
        unread.remove(u.get("id").asInt());
        drawSide();
        drawChat();
        call("GET", "/api/messages/" + u.get("id").asInt(), null, r -> {
            if (active == u) {
                log.getChildren().clear();
                r.forEach(this::bubble);
            }
        }, e -> {
        });
    }

    HBox row(JsonNode u, Node... acts) {
        int id = u.get("id").asInt();
        var r = new HBox(10, avatar(u.get("name").asText()), grow(lbl(u.get("name").asText())));
        if (unread.contains(id)) r.getChildren().add(dot());
        r.getChildren().addAll(acts);
        r.setAlignment(Pos.CENTER_LEFT);
        r.getStyleClass().add("row");
        if (active != null && active.get("id").asInt() == id) r.getStyleClass().add("row-on");
        return r;
    }

    void drawSide() {
        var tabs = new HBox(6);
        for (String t : new String[]{"friends", "requests", "find"}) {
            String txt = t.equals("friends") ? "Friends" : t.equals("find") ? "Find" : "Requests" + (incoming.size() > 0 ? " (" + incoming.size() + ")" : "");
            var b = btn(txt, "seg", () -> {
                tab = t;
                drawSide();
            });
            if (t.equals(tab)) b.getStyleClass().add("seg-on");
            tabs.getChildren().add(grow(b));
        }
        var list = new VBox(6);
        if (tab.equals("friends")) {
            if (friends.size() == 0) list.getChildren().add(lbl("No friends yet. Open Find to send a request.", "dim"));
            for (var u : friends) {
                var r = row(u);
                r.setOnMouseClicked(e -> open(u));
                list.getChildren().add(r);
            }
        } else if (tab.equals("requests")) {
            if (incoming.size() == 0) list.getChildren().add(lbl("No pending requests.", "dim"));
            for (var u : incoming)
                list.getChildren().add(row(u, btn("Accept", "mini", () -> respond(u, true)), btn("Decline", "mini", () -> respond(u, false))));
        } else list.getChildren().addAll(findBox, results);
        var sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        VBox.setVgrow(sp, Priority.ALWAYS);
        var mine = new HBox(10, avatar(me.get("name").asText()), grow(lbl(me.get("name").asText())), btn("Log out", "ghost", this::logout));
        mine.setAlignment(Pos.CENTER_LEFT);
        side.getChildren().setAll(lbl(wide("LUCIUS"), "brand"), mine, tabs, sp);
    }

    void drawChat() {
        bar.setVisible(active != null);
        bar.setManaged(active != null);
        if (active == null) {
            log.getChildren().clear();
            head.getChildren().setAll(lbl("Select a friend to start chatting.", "dim"));
            return;
        }
        head.getChildren().setAll(avatar(active.get("name").asText()), lbl(active.get("name").asText(), "brand"));
        input.requestFocus();
    }

    void bubble(JsonNode m) {
        boolean mine = m.get("sender").asInt() == me.get("id").asInt();
        var t = lbl(m.get("body").asText());
        t.setWrapText(true);
        t.setMaxWidth(460);
        t.setMinHeight(Region.USE_PREF_SIZE);
        var time = lbl(DateTimeFormatter.ofPattern("HH:mm").format(LocalDateTime.ofInstant(Instant.ofEpochSecond(m.get("created").asLong()), ZoneId.systemDefault())), "time");
        time.setMaxWidth(Double.MAX_VALUE);
        time.setAlignment(Pos.CENTER_RIGHT);
        var b = new VBox(2, t, time);
        b.getStyleClass().addAll("msg", mine ? "mine" : "theirs");
        b.setMaxWidth(Region.USE_PREF_SIZE);
        var line = new HBox(b);
        line.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        log.getChildren().add(line);
        Platform.runLater(() -> scroll.setVvalue(1));
    }
}
