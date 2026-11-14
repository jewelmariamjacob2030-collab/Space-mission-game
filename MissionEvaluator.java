import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mission evaluator service: the authoritative mission physics (no external libraries).
 *  GET /evaluate?code=1121-013-12&df=1&mode=0&fund=0  ->  JSON result
 *  Mirrors the maths in the JavaScript game; the backend uses maxPct to reject cheated scores. */
public class MissionEvaluator {
    record Scn(String name, int budget, int massLimit, double solar, int dist, int goal) {}
    record Pw(int watts, boolean rtg, int mass, int cost) {}
    record Cm(int rate, int power, int mass, int cost) {}
    record Pl(int sci, int power, int data, int mass, int cost) {}
    record Ob(double mult, int mass, int cost) {}

    static final Scn[] SCN = {new Scn("Lunar Ice Hunter", 650, 900, 1, 1, 8),
        new Scn("Mars Climate Orbiter", 800, 1000, .43, 2, 10),
        new Scn("Europa Ocean Scout", 1000, 1100, .04, 3, 12)};
    static final Pw[] PW = {new Pw(200, false, 60, 40), new Pw(600, false, 150, 90), new Pw(300, true, 90, 160)};
    static final Cm[] CM = {new Cm(1, 20, 10, 20), new Cm(4, 60, 40, 60), new Cm(12, 120, 90, 120)};
    static final Pl[] PL = {new Pl(3, 40, 3, 30, 50), new Pl(4, 80, 2, 50, 80),
        new Pl(6, 150, 5, 120, 150), new Pl(2, 20, 1, 10, 25)};
    static final Ob[] OB = {new Ob(1.3, 150, 80), new Ob(1.0, 80, 50), new Ob(0.6, 40, 30)};
    static final double[] BUDGET_MULT = {1.2, 1.0, 0.85};
    static final Pattern CODE = Pattern.compile("^(\\d)(\\d)(\\d)(\\d)-(\\d{1,4})-(\\d{1,2})$");

    static String evaluate(String code, int df, int mode, int fund) {
        if (code == null) throw new IllegalArgumentException("code is required");
        Matcher m = CODE.matcher(code);
        if (!m.matches()) throw new IllegalArgumentException("bad design code");
        int sc = Integer.parseInt(m.group(1)), pw = Integer.parseInt(m.group(2));
        int cm = Integer.parseInt(m.group(3)), ob = Integer.parseInt(m.group(4));
        int months = Integer.parseInt(m.group(6));
        if (sc > 2 || pw > 2 || cm > 2 || ob > 2 || df < 0 || df > 2 || fund < 0 || fund > 500
            || (months != 6 && months != 12 && months != 24))
            throw new IllegalArgumentException("design out of range");
        boolean[] used = new boolean[PL.length];
        for (char ch : m.group(5).toCharArray()) {
            int i = ch - '0';
            if (i >= PL.length) throw new IllegalArgumentException("unknown instrument");
            used[i] = true;
        }
        Scn s = SCN[sc]; Pw p = PW[pw]; Cm c = CM[cm]; Ob o = OB[ob];
        int mass = 150 + p.mass() + c.mass() + o.mass();
        int cost = 100 + p.cost() + c.cost() + o.cost() + months * 4;
        int power = 50 + c.power(), data = 0, sci = 0;
        for (int i = 0; i < PL.length; i++) if (used[i]) {
            mass += PL[i].mass(); cost += PL[i].cost(); power += PL[i].power();
            data += PL[i].data(); sci += PL[i].sci();
        }
        double budget = s.budget() * BUDGET_MULT[df] + fund;
        double gen = p.rtg() ? p.watts() : p.watts() * s.solar();
        double ret = data == 0 ? 0 : Math.min(1.0, (double) c.rate() / s.dist() / data);
        double science = sci * o.mult() * Math.pow(months / 12.0, 0.8) * ret;
        boolean valid = mode == 1 || (mass <= s.massLimit() && cost <= budget && gen >= power);
        long maxPct = valid ? Math.min(100, Math.round(science / s.goal() * 100)) : 0;
        return String.format(Locale.ROOT,
            "{\"scenario\":\"%s\",\"valid\":%b,\"maxPct\":%d,\"mass\":%d,\"massLimit\":%d,\"cost\":%d,"
          + "\"budget\":%.0f,\"powerGenerated\":%.1f,\"powerUsed\":%d,\"dataReturnedPct\":%.0f,"
          + "\"science\":%.2f,\"goal\":%d}",
            s.name(), valid, maxPct, mass, s.massLimit(), cost, budget, gen, power, ret * 100, science, s.goal());
    }

    static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            String[] a = kv.split("=", 2);
            q.put(URLDecoder.decode(a[0], StandardCharsets.UTF_8),
                  a.length > 1 ? URLDecoder.decode(a[1], StandardCharsets.UTF_8) : "");
        }
        return q;
    }

    static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8081"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", x -> send(x, 200, "{\"status\":\"ok\"}"));
        server.createContext("/evaluate", x -> {
            try {
                Map<String, String> q = query(x.getRequestURI().getRawQuery());
                send(x, 200, evaluate(q.get("code"), Integer.parseInt(q.getOrDefault("df", "1")),
                    Integer.parseInt(q.getOrDefault("mode", "0")), Integer.parseInt(q.getOrDefault("fund", "0"))));
            } catch (IllegalArgumentException e) {
                send(x, 400, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
        server.start();
        System.out.println("Evaluator listening on :" + port);
    }
}
