import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocks commits that would reveal personal locations listed in the gitignored devtool.local.json.
 * Run by the git hooks in tools/git-hooks (enable with: git config core.hooksPath tools/git-hooks).
 *
 *   java tools/PrivacyCheck.java staged          # check added lines of the staged diff (pre-commit)
 *   java tools/PrivacyCheck.java message FILE    # check a commit message (commit-msg)
 *
 * Blocked: area names, "privateTerms" from the local file, decimal coordinates inside (or within
 * 0.1 degrees of) any local bbox, and 1-degree DEM cell names (USGS n47w122, Copernicus N46_00_W122_00)
 * that overlap a local bbox. Matching ignores case, spaces, hyphens and underscores.
 *
 * devtool.local.json may include  "privateTerms": ["Some Peak", "Some Range", ...]  at top level.
 */
public class PrivacyCheck {
    private static final Pattern AREA_KEY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{");
    private static final Pattern BBOX = Pattern.compile("\"bbox\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern PRIVATE_TERMS = Pattern.compile("\"privateTerms\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d{1,3}\\.\\d{2,}");
    private static final double MARGIN_DEG = 0.1;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        Path root = Path.of(run("git", "rev-parse", "--show-toplevel").trim());
        Path local = root.resolve("devtool.local.json");
        if (!Files.exists(local)) return;
        String json = Files.readString(local, StandardCharsets.UTF_8);

        Set<String> terms = new LinkedHashSet<>();
        List<double[]> boxes = new ArrayList<>();
        Matcher m = AREA_KEY.matcher(json);
        while (m.find()) if (!m.group(1).equals("areas")) terms.add(normalize(m.group(1)));
        m = PRIVATE_TERMS.matcher(json);
        if (m.find()) {
            Matcher s = STRING.matcher(m.group(1));
            while (s.find()) terms.add(normalize(s.group(1)));
        }
        m = BBOX.matcher(json);
        while (m.find()) {
            String[] parts = m.group(1).split(",");
            if (parts.length != 4) continue;
            double[] b = new double[4];
            for (int i = 0; i < 4; i++) b[i] = Double.parseDouble(parts[i].trim());
            boxes.add(b);
            terms.addAll(cellNames(b));
        }
        terms.removeIf(t -> t.length() < 4);

        List<String[]> lines = new ArrayList<>(); // {location, text}
        switch (args[0]) {
            case "staged" -> {
                String file = "?";
                int lineNo = 0;
                for (String line : run("git", "diff", "--cached", "-U0", "--no-color", "--no-ext-diff").split("\n")) {
                    if (line.startsWith("+++ ")) {
                        file = line.substring(4).replaceFirst("^b/", "");
                        lines.add(new String[]{"path", file});
                    } else if (line.startsWith("@@")) {
                        Matcher h = Pattern.compile("\\+(\\d+)").matcher(line);
                        lineNo = h.find() ? Integer.parseInt(h.group(1)) : 0;
                    } else if (line.startsWith("+")) {
                        lines.add(new String[]{file + ":" + lineNo, line.substring(1)});
                        lineNo++;
                    }
                }
            }
            case "message" -> {
                if (args.length < 2) usage();
                int n = 0;
                for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
                    n++;
                    if (!line.startsWith("#")) lines.add(new String[]{"commit message:" + n, line});
                }
            }
            default -> usage();
        }

        List<String> hits = new ArrayList<>();
        for (String[] l : lines) {
            String norm = normalize(l[1]);
            String reason = null;
            for (String t : terms) if (norm.contains(t)) { reason = "private term"; break; }
            if (reason == null) reason = coordinateHit(l[1], boxes);
            if (reason != null) {
                String text = l[1].strip();
                hits.add("  " + l[0] + " (" + reason + "): " + (text.length() > 120 ? text.substring(0, 120) + "..." : text));
            }
        }
        if (!hits.isEmpty()) {
            System.err.println("PRIVACY CHECK FAILED: this would commit a personal location from devtool.local.json.");
            hits.forEach(System.err::println);
            System.err.println("Remove it or describe the area generically (e.g. \"a Colorado mountain area\").");
            System.err.println("Do not bypass this check with --no-verify.");
            System.exit(1);
        }
    }

    private static String coordinateHit(String text, List<double[]> boxes) {
        Matcher d = DECIMAL.matcher(text);
        while (d.find()) {
            double v = Double.parseDouble(d.group());
            for (double[] b : boxes) {
                for (double c : new double[]{v, -v}) {
                    boolean lon = c >= b[0] - MARGIN_DEG && c <= b[2] + MARGIN_DEG;
                    boolean lat = c >= b[1] - MARGIN_DEG && c <= b[3] + MARGIN_DEG;
                    if (lon || lat) return "coordinate " + d.group();
                }
            }
        }
        return null;
    }

    /** 1-degree cell names overlapping a bbox, normalized: USGS "n47w122" and Copernicus "N46_00_W122_00". */
    private static List<String> cellNames(double[] b) {
        List<String> names = new ArrayList<>();
        for (int lat = (int) Math.floor(b[1]); lat <= (int) Math.floor(b[3]); lat++) {
            for (int lon = (int) Math.floor(b[0]); lon <= (int) Math.floor(b[2]); lon++) {
                String usgsLat = (lat + 1 >= 0 ? "n" : "s") + Math.abs(lat + 1);
                String usgsLon = (lon < 0 ? "w" + String.format("%03d", -lon) : "e" + String.format("%03d", lon));
                names.add(normalize(usgsLat + usgsLon));
                String copLat = (lat >= 0 ? "n" : "s") + String.format("%02d", Math.abs(lat)) + "00";
                String copLon = (lon < 0 ? "w" : "e") + String.format("%03d", Math.abs(lon)) + "00";
                names.add(normalize(copLat + copLon));
            }
        }
        return names;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("command failed: " + String.join(" ", cmd));
        return out;
    }

    private static void usage() {
        System.err.println("usage: PrivacyCheck staged | PrivacyCheck message FILE");
        System.exit(2);
    }
}
