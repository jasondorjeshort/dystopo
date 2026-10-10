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
 *   git log -p --all | java tools/PrivacyCheck.java scan [--pairs FILE]   # whole history (or any text on stdin)
 *   git grep -I -n -e "" | java tools/PrivacyCheck.java scan              # every tracked text file
 *
 * scan prints counts only (never the matched text, so the private terms stay private): lines read,
 * private-term hits, single coordinate values in a local bbox's lat or lon range (the hook's lenient
 * rule), lat/lon pairs inside a local bbox, and how many commits ("commit <sha>" lines) had any hit.
 * It also counts coordinate-like pairs outside every public area of areas.json (1 degree margin);
 * --pairs FILE writes those, deduplicated, for a person to review (pairs in a local bbox are left out).
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
        // In a linked worktree the personal file lives only in the main checkout, next to the common .git dir.
        Path mainRoot = Path.of(run("git", "rev-parse", "--git-common-dir").trim()).toAbsolutePath().normalize().getParent();
        Path local = root.resolve("devtool.local.json");
        if (!Files.exists(local) && mainRoot != null) local = mainRoot.resolve("devtool.local.json");
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

        if (args[0].equals("scan")) {
            scan(args, terms, boxes, root);
            return;
        }
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

    private static final Pattern PAIR = Pattern.compile("(-?\\d{1,3}\\.\\d{2,})[^\\d\\n]{1,24}?(-?\\d{1,3}\\.\\d{2,})");

    /** Counts-only scan of stdin; see the class comment. Exit 1 when any private hit is found. */
    private static void scan(String[] args, Set<String> terms, List<double[]> boxes, Path root) throws IOException {
        Path pairsOut = null;
        for (int i = 1; i < args.length; i++) if (args[i].equals("--pairs") && i + 1 < args.length) pairsOut = Path.of(args[++i]);
        List<double[]> publicBoxes = new ArrayList<>();
        Path areas = root.resolve("areas.json");
        if (Files.exists(areas)) {
            Matcher m = BBOX.matcher(Files.readString(areas, StandardCharsets.UTF_8));
            while (m.find()) {
                String[] parts = m.group(1).split(",");
                if (parts.length != 4) continue;
                double[] b = new double[4];
                for (int i = 0; i < 4; i++) b[i] = Double.parseDouble(parts[i].trim());
                publicBoxes.add(b);
            }
        }
        long lineCount = 0, termHits = 0, valueHits = 0, pairHits = 0, outsidePairs = 0;
        Set<String> hitCommits = new LinkedHashSet<>();
        Set<String> outside = new java.util.TreeSet<>();
        String commit = "-";
        var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            lineCount++;
            if (line.startsWith("commit ") && line.length() >= 47) commit = line.substring(7, 19);
            String norm = normalize(line);
            boolean hit = false;
            for (String t : terms) if (norm.contains(t)) { termHits++; hit = true; break; }
            if (coordinateHit(line, boxes) != null) { valueHits++; hit = true; }
            Matcher p = PAIR.matcher(line);
            while (p.find()) {
                double a = Double.parseDouble(p.group(1)), b = Double.parseDouble(p.group(2));
                if (inAny(a, b, boxes, MARGIN_DEG) || inAny(b, a, boxes, MARGIN_DEG)) { pairHits++; hit = true; continue; }
                boolean plausible = (Math.abs(a) <= 90 && Math.abs(b) <= 180) || (Math.abs(b) <= 90 && Math.abs(a) <= 180);
                if (!plausible || (Math.abs(a) < 1 && Math.abs(b) < 1)) continue;
                if (inAny(a, b, publicBoxes, 1.0) || inAny(b, a, publicBoxes, 1.0)) continue;
                outsidePairs++;
                outside.add(p.group(1) + " " + p.group(2));
            }
            if (hit) hitCommits.add(commit);
        }
        if (pairsOut != null) Files.write(pairsOut, outside, StandardCharsets.UTF_8);
        System.out.println("{\"lines\": " + lineCount + ", \"privateTerms\": " + terms.size() + ", \"privateBoxes\": " + boxes.size()
            + ", \"termHits\": " + termHits + ", \"coordinateValueHits\": " + valueHits + ", \"coordinatePairHits\": " + pairHits
            + ", \"commitsWithHits\": " + hitCommits.size() + ", \"publicAreas\": " + publicBoxes.size()
            + ", \"pairsOutsidePublicAreas\": " + outsidePairs + ", \"distinctPairsOutsidePublicAreas\": " + outside.size() + "}");
        if (termHits + pairHits + valueHits > 0) System.exit(1);
    }

    /** True when (lat, lon) lies in one of the [west, south, east, north] boxes grown by margin degrees. */
    private static boolean inAny(double lat, double lon, List<double[]> boxes, double margin) {
        for (double[] b : boxes) {
            if (lon >= b[0] - margin && lon <= b[2] + margin && lat >= b[1] - margin && lat <= b[3] + margin) return true;
        }
        return false;
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
        System.err.println("usage: PrivacyCheck staged | PrivacyCheck message FILE | PrivacyCheck scan [--pairs FILE] < text");
        System.exit(2);
    }
}
