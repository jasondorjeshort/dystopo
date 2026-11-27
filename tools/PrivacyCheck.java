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
 *   java tools/PrivacyCheck.java scan --dump < dystopo-diagnostics-....txt # a diagnostic dump (T271)
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
 * Without devtool.local.json the hooks pass and scan reports a clean result ("localFile": false,
 * "0 private terms configured"); the public-area pair counts are still made.
 *
 * scan --dump reads a diagnostic dump (DumpFormat, `dystopo-dump 1`: header lines, then `== <name>` sections each
 * ended by an empty line) and scans its header, samples, starts, diagnostics and any unknown section with the full
 * rules, but the `logcat` section with the private terms, the pair rule and the stricter lone-value rule of the app's
 * scrubber (Scrub.LONE: a decimal with five or more fraction digits, a metre's precision) in place of the lenient
 * DECIMAL rule, which reads log timestamps (13:36:46.123) and timings (avg=78.48ms) as coordinates. Input whose first
 * line is not a dump's exits 2. scan --local FILE reads FILE in place of devtool.local.json (tests on fixtures).
 *
 * T388: positions in the forms the app prints (shared/.../geo/CoordinateFormat.kt) are read too, as pairs: decimal degrees
 * with N, S, E or W before or after the number (the degree sign optional), degrees and decimal minutes, degrees minutes
 * seconds (the prime and double prime, or a plain apostrophe and quote), and UTM ("10T 596555 5182160": zone, band,
 * easting, northing, turned into latitude and longitude by the same series as shared/.../geo/Utm.kt). Each is checked
 * like a signed pair: inside (or within 0.1 degrees of) a local bbox blocks the hooks and counts in scan's
 * coordinatePairHits, with formPairHits beside it saying how many of those were in one of these forms; outside every
 * public area it counts in pairsOutsidePublicAreas. Every Unicode space (the no-break space CoordinateFormat.line puts
 * inside each part, thin and figure spaces) reads as a space in every coordinate rule; hemisphere letters and the UTM
 * band may be lower case; "N 12° 20.740' W 123° 27.407'", letters first with no comma, is a pair. An angle needs its hemisphere letter and a partner on the other axis
 * within 24 characters, a decimal needs two fraction digits, minutes and seconds need their marks, and a UTM easting six
 * digits, so a time (12:47' 09.6"), a version ("10T") or a bare number pair is no hit.
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
        // T271: scan only; the hooks' staged and message modes always read the real file.
        if (args[0].equals("scan")) for (int i = 1; i + 1 < args.length; i++) if (args[i].equals("--local")) local = Path.of(args[i + 1]);
        boolean haveLocal = Files.exists(local);
        // T87: without the private file there is nothing private to check. The hooks pass; scan still reads its
        // input and reports a clean result with "0 private terms configured" (an empty output read as a failure).
        if (!haveLocal && !args[0].equals("scan")) return;
        String json = haveLocal ? Files.readString(local, StandardCharsets.UTF_8) : "";

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
            scan(args, terms, boxes, root, haveLocal);
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
            if (reason == null) reason = coordinateHit(spaces(l[1]), boxes);
            if (reason == null) for (double[] q : formPairs(l[1])) if (inAny(q[0], q[1], boxes, MARGIN_DEG)) { reason = "coordinate pair"; break; } // T388
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
    /** The app scrubber's lone-value rule (Scrub.LONE): five or more fraction digits, not inside a word, number or placeholder. */
    private static final Pattern LONE = Pattern.compile("(?:^|[^\\w.\\-<])(-?\\d{1,3}\\.\\d{5,})(?![\\w.])");
    private static final String DUMP_PREFIX = "dystopo-dump ", DUMP_SECTION = "== ", DUMP_LOGCAT = "logcat";

    /** Counts-only scan of stdin; see the class comment. Exit 1 when any private hit is found. */
    private static void scan(String[] args, Set<String> terms, List<double[]> boxes, Path root, boolean haveLocal) throws IOException {
        Path pairsOut = null;
        boolean dump = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--pairs") && i + 1 < args.length) pairsOut = Path.of(args[++i]);
            else if (args[i].equals("--local") && i + 1 < args.length) i++; // read in main
            else if (args[i].equals("--dump")) dump = true;
        }
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
        long lineCount = 0, termHits = 0, valueHits = 0, pairHits = 0, outsidePairs = 0, logcatLines = 0, formHits = 0;
        String section = null; // --dump: the section being read; null in the header and between sections
        Set<String> hitCommits = new LinkedHashSet<>();
        Set<String> outside = new java.util.TreeSet<>();
        String commit = "-";
        var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            lineCount++;
            boolean logcat = false;
            if (dump) {
                if (lineCount == 1 && !line.startsWith(DUMP_PREFIX)) {
                    System.err.println("scan --dump: the first line is not a diagnostic dump's (\"" + DUMP_PREFIX + "1\")");
                    System.exit(2);
                }
                if (line.isEmpty()) section = null;
                else if (section == null && lineCount > 1 && line.startsWith(DUMP_SECTION)) section = line.substring(DUMP_SECTION.length()).strip();
                else logcat = DUMP_LOGCAT.equals(section);
                if (logcat) logcatLines++;
            }
            if (line.startsWith("commit ") && line.length() >= 47) commit = line.substring(7, 19);
            String norm = normalize(line);
            boolean hit = false;
            for (String t : terms) if (norm.contains(t)) { termHits++; hit = true; break; }
            line = spaces(line); // T388: a no-break or thin space reads as a space in every coordinate rule
            if ((logcat ? loneHit(line, boxes) : coordinateHit(line, boxes)) != null) { valueHits++; hit = true; }
            Matcher p = PAIR.matcher(line);
            while (p.find()) {
                if (HEMI_AFTER.matcher(line).region(p.end(1), line.length()).lookingAt()) continue; // T388: a lettered pair, formPairs' to read
                double a = Double.parseDouble(p.group(1)), b = Double.parseDouble(p.group(2));
                if (inAny(a, b, boxes, MARGIN_DEG) || inAny(b, a, boxes, MARGIN_DEG)) { pairHits++; hit = true; continue; }
                boolean plausible = (Math.abs(a) <= 90 && Math.abs(b) <= 180) || (Math.abs(b) <= 90 && Math.abs(a) <= 180);
                if (!plausible || (Math.abs(a) < 1 && Math.abs(b) < 1)) continue;
                if (inAny(a, b, publicBoxes, 1.0) || inAny(b, a, publicBoxes, 1.0)) continue;
                outsidePairs++;
                outside.add(p.group(1) + " " + p.group(2));
            }
            // T388: the forms the app prints (hemisphere letters, minutes, seconds, UTM), as pairs.
            for (double[] q : formPairs(line)) {
                if (inAny(q[0], q[1], boxes, MARGIN_DEG)) { pairHits++; formHits++; hit = true; continue; }
                if (inAny(q[0], q[1], publicBoxes, 1.0)) continue;
                outsidePairs++;
                outside.add(String.format(Locale.ROOT, "%.5f %.5f", q[0], q[1]));
            }
            if (hit) hitCommits.add(commit);
        }
        if (pairsOut != null) Files.write(pairsOut, outside, StandardCharsets.UTF_8);
        System.out.println("{\"lines\": " + lineCount + ", \"privateTerms\": " + terms.size() + ", \"privateBoxes\": " + boxes.size()
            + ", \"termHits\": " + termHits + ", \"coordinateValueHits\": " + valueHits + ", \"coordinatePairHits\": " + pairHits + ", \"formPairHits\": " + formHits
            + ", \"commitsWithHits\": " + hitCommits.size() + ", \"publicAreas\": " + publicBoxes.size()
            + ", \"pairsOutsidePublicAreas\": " + outsidePairs + ", \"distinctPairsOutsidePublicAreas\": " + outside.size()
            + (dump ? ", \"dump\": true, \"logcatLines\": " + logcatLines + ", \"logcatValueRule\": \"lone\"" : "")
            + ", \"localFile\": " + haveLocal + ", \"note\": \"" + (haveLocal ? terms.size() + " private terms configured" : "0 private terms configured: no devtool.local.json") + "\"}");
        if (termHits + pairHits + valueHits > 0) System.exit(1);
    }

    // ---- T388: positions as the app prints them ----

    private static final String D = "(\\d{1,3}\\.\\d{2,})\\s*°?";
    private static final String DM = "(\\d{1,3})(?:\\s*°\\s*|\\s+)(\\d{1,2}(?:\\.\\d+)?)\\s*['′]";
    private static final String DMS = "(\\d{1,3})(?:\\s*°\\s*|\\s+)(\\d{1,2})\\s*['′]\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*[\"″]";
    /** One angle and its hemisphere letter, before (group 1) or after it (group 8); groups 2-4 DMS, 5-6 DM, 7 D. */
    private static final Pattern ANGLE = Pattern.compile("(?:(?<![A-Za-z])([NSEWnsew])\\s*)?(?<![\\d.])(?:" + DMS + "|" + DM + "|" + D + ")"
        + "(?:\\s*([NSEWnsew])(?![A-Za-z]))?");
    /** Every Unicode space that is not a plain one: no-break (U+00A0, the app's CoordinateFormat.line), figure, thin, narrow no-break and the rest. */
    private static final Pattern SPACES = Pattern.compile("[\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{202F}\\x{205F}\\x{3000}]");

    /** T388: [text] with every Unicode space a plain space, so the coordinate rules' \\s and ' ' read it. */
    static String spaces(String text) {
        return SPACES.matcher(text).replaceAll(" ");
    }

    /** A hemisphere letter right after a number: PAIR leaves that pair to formPairs, so it is counted once, with its sign. */
    private static final Pattern HEMI_AFTER = Pattern.compile("\\s*°?\\s*[NSEWnsew](?![A-Za-z])");
    private static final Pattern UTM = Pattern.compile("(?<![\\w.])([1-9]|[1-5]\\d|60)([C-HJ-NP-Xc-hj-np-x])\\s+(\\d{6})(?:\\.\\d+)?\\s+(\\d{6,7})(?:\\.\\d+)?(?![\\w.])");

    /** T388: every (lat, lon) the line writes in a form CoordinateFormat prints (not the signed pairs PAIR reads). */
    static List<double[]> formPairs(String line) {
        line = spaces(line);
        List<double[]> out = new ArrayList<>();
        List<double[]> angles = new ArrayList<>(); // {signed degrees, axis 0 lat / 1 lon, start, end}
        Matcher m = ANGLE.matcher(line);
        for (int from = 0; from < line.length() && m.find(from); ) {
            String before = m.group(1), after = m.group(8);
            from = m.end();
            int end = m.end();
            if (before == null && after == null) continue; // no hemisphere letter
            if (before != null && after != null) { // "N 12° 20.740' W 123° 27.407'": the letter after is the next angle's
                after = null;
                from = end = m.start(8);
            }
            char h = Character.toUpperCase((before != null ? before : after).charAt(0));
            double v;
            if (m.group(2) != null) {
                double min = Double.parseDouble(m.group(3)), sec = Double.parseDouble(m.group(4));
                if (min >= 60 || sec >= 60) continue;
                v = Integer.parseInt(m.group(2)) + min / 60 + sec / 3600;
            } else if (m.group(5) != null) {
                double min = Double.parseDouble(m.group(6));
                if (min >= 60) continue;
                v = Integer.parseInt(m.group(5)) + min / 60;
            } else v = Double.parseDouble(m.group(7));
            boolean lat = h == 'N' || h == 'S';
            if (v > (lat ? 90 : 180)) continue;
            angles.add(new double[]{(h == 'S' || h == 'W') ? -v : v, lat ? 0 : 1, m.start(), end});
        }
        for (int i = 0; i + 1 < angles.size(); i++) {
            double[] a = angles.get(i), b = angles.get(i + 1);
            if (a[1] == b[1]) continue;
            String gap = line.substring((int) a[3], (int) b[2]);
            if (gap.length() > 24 || gap.chars().anyMatch(Character::isDigit)) continue;
            out.add(a[1] == 0 ? new double[]{a[0], b[0]} : new double[]{b[0], a[0]});
            i++;
        }
        m = UTM.matcher(line);
        while (m.find()) {
            int zone = Integer.parseInt(m.group(1));
            char band = Character.toUpperCase(m.group(2).charAt(0));
            double e = Double.parseDouble(m.group(3)), n = Double.parseDouble(m.group(4));
            if (e < 100_000 || e > 900_000 || n > 9_400_000) continue;
            out.add(utmInverse(e, n, zone, band >= 'N'));
        }
        return out;
    }

    /** T388: UTM to (lat, lon) in degrees: the Kruger series of shared/.../geo/Utm.kt (GRS80/WGS 84), re-written here. */
    static double[] utmInverse(double easting, double northing, int zone, boolean north) {
        double f = 1 / 298.257222101, n = f / (2 - f), k0 = 0.9996;
        double radius = 6_378_137.0 / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64);
        double[] beta = {n / 2 - 2 * n * n / 3 + 37 * n * n * n / 96, n * n / 48 + n * n * n / 15, 17 * n * n * n / 480};
        double[] delta = {2 * n - 2 * n * n / 3 - 2 * n * n * n, 7 * n * n / 3 - 8 * n * n * n / 5, 56 * n * n * n / 15};
        double xi = (northing - (north ? 0 : 10_000_000.0)) / (k0 * radius), eta = (easting - 500_000.0) / (k0 * radius);
        double xiP = xi, etaP = eta;
        for (int j = 1; j <= 3; j++) {
            xiP -= beta[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
            etaP -= beta[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
        }
        double chi = Math.asin(Math.sin(xiP) / Math.cosh(etaP)), phi = chi;
        for (int j = 1; j <= 3; j++) phi += delta[j - 1] * Math.sin(2 * j * chi);
        double lon = Math.toDegrees((zone * 6 - 183) * Math.PI / 180 + Math.atan(Math.sinh(etaP) / Math.cos(xiP)));
        if (lon >= 180) lon -= 360;
        if (lon < -180) lon += 360;
        return new double[]{Math.toDegrees(phi), lon};
    }

    /** True when (lat, lon) lies in one of the [west, south, east, north] boxes grown by margin degrees. */
    private static boolean inAny(double lat, double lon, List<double[]> boxes, double margin) {
        for (double[] b : boxes) {
            if (lon >= b[0] - margin && lon <= b[2] + margin && lat >= b[1] - margin && lat <= b[3] + margin) return true;
        }
        return false;
    }

    private static String coordinateHit(String text, List<double[]> boxes) {
        return valueHit(DECIMAL.matcher(text), 0, boxes);
    }

    /** The dump's logcat value rule (T271): only a lone decimal of a metre's precision is checked against the boxes. */
    private static String loneHit(String text, List<double[]> boxes) {
        return valueHit(LONE.matcher(text), 1, boxes);
    }

    private static String valueHit(Matcher d, int group, List<double[]> boxes) {
        while (d.find()) {
            double v = Double.parseDouble(d.group(group));
            for (double[] b : boxes) {
                for (double c : new double[]{v, -v}) {
                    boolean lon = c >= b[0] - MARGIN_DEG && c <= b[2] + MARGIN_DEG;
                    boolean lat = c >= b[1] - MARGIN_DEG && c <= b[3] + MARGIN_DEG;
                    if (lon || lat) return "coordinate " + d.group(group);
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
        System.err.println("usage: PrivacyCheck staged | PrivacyCheck message FILE | PrivacyCheck scan [--pairs FILE] [--dump] [--local FILE] < text");
        System.exit(2);
    }
}
